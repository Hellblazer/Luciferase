/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.gpu;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import com.hellblazer.luciferase.resource.opengl.BufferResource;
import com.hellblazer.luciferase.resource.opengl.ShaderProgramResource;
import com.hellblazer.luciferase.resource.opengl.ShaderResource;
import com.hellblazer.luciferase.resource.opengl.TextureResource;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * GPU Compute Shader Renderer for ESVT (Efficient Sparse Voxel Tetrahedra)
 *
 * Manages GLSL compute shaders for tetrahedral ray traversal, using the
 * Moller-Trumbore intersection algorithm and entry-face-based child ordering.
 *
 * Key differences from ESVO ComputeShaderRenderer:
 * - Uses raycast_esvt.comp shader instead of raycast.comp
 * - Uses [0,1] coordinate space instead of [1,2]
 * - Tetrahedral subdivision with type propagation
 *
 * @author hal.hildebrand
 */
public final class ESVTComputeRenderer {
    private static final Logger log = LoggerFactory.getLogger(ESVTComputeRenderer.class);

    // Compute shader workgroup size (must match shader)
    public static final int WORKGROUP_SIZE_X = 8;
    public static final int WORKGROUP_SIZE_Y = 8;
    public static final int WORKGROUP_SIZE_Z = 1;

    // Binding points for shader resources (matches raycast_esvt.comp)
    public static final int ESVT_BUFFER_BINDING = 0;
    public static final int OUTPUT_IMAGE_BINDING = 1;
    public static final int CAMERA_UBO_BINDING = 2;
    public static final int RENDER_FLAGS_UBO_BINDING = 3;
    public static final int COARSE_OUTPUT_BINDING = 4;
    public static final int COARSE_INPUT_BINDING = 5;

    // Beam optimization constants
    public static final int DEFAULT_COARSE_SIZE = 4;

    // Render flag bits (must match shader)
    private static final int FLAG_COARSE_PASS = 1;
    private static final int FLAG_USE_COARSE_DATA = 2;

    // Resource manager for GPU resources
    private final UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();

    // Managed resources
    private ShaderResource raycastComputeShader;
    private ShaderProgramResource raycastProgram;
    private BufferResource cameraUBO;
    private BufferResource renderFlagsUBO;
    private TextureResource outputTexture;
    private TextureResource coarseTexture;
    private int coarseSampler;

    // Camera UBO size: 4 matrices (4x4) + 2 vec4 (position+nearPlane, direction+farPlane)
    private static final int CAMERA_UBO_SIZE = 64 * 4 + 16 * 2;

    // RenderFlags UBO size: uint flags + int coarseSize + int frameWidth + int frameHeight (16 bytes aligned)
    private static final int RENDER_FLAGS_UBO_SIZE = 16;

    // Beam optimization state
    private int coarseSize = DEFAULT_COARSE_SIZE;
    private int coarseWidth;
    private int coarseHeight;
    private boolean beamOptimizationEnabled = false;

    private int frameWidth;
    private int frameHeight;

    private boolean initialized = false;
    private boolean disposed = false;

    /**
     * Create ESVT compute shader renderer with specified output resolution
     *
     * @param frameWidth Output width in pixels
     * @param frameHeight Output height in pixels
     */
    public ESVTComputeRenderer(int frameWidth, int frameHeight) {
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }

    /**
     * Initialize the compute shader renderer
     * Must be called from OpenGL context thread
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            // Load and compile compute shaders
            compileShaders();

            // Create uniform buffers
            createUniformBuffers();

            // Create output texture
            createOutputTexture();

            initialized = true;
            log.info("Initialized ESVTComputeRenderer: {}x{}", frameWidth, frameHeight);

        } catch (Exception e) {
            dispose();
            throw new RuntimeException("Failed to initialize ESVTComputeRenderer", e);
        }
    }

    /**
     * Render a frame using the ESVT data
     *
     * @param esvtMemory GPU memory containing ESVT node data
     * @param viewMatrix Camera view matrix
     * @param projMatrix Camera projection matrix
     * @param objectToWorld Transform from object to world space
     * @param tetreeToObject Transform from tetree [0,1] to object space
     */
    public void renderFrame(ESVTGPUMemory esvtMemory,
                           Matrix4f viewMatrix, Matrix4f projMatrix,
                           Matrix4f objectToWorld, Matrix4f tetreeToObject) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }

        if (disposed) {
            throw new IllegalStateException("Renderer has been disposed");
        }

        // Bind ESVT data
        esvtMemory.bindToShader(ESVT_BUFFER_BINDING);

        // Update camera uniforms
        updateCameraUniforms(viewMatrix, projMatrix, objectToWorld, tetreeToObject);

        // Set render flags to 0 (no beam optimization)
        updateRenderFlags(0);

        // Bind output texture
        glBindImageTexture(OUTPUT_IMAGE_BINDING, outputTexture.getOpenGLId(), 0, false, 0,
                          GL_WRITE_ONLY, GL_RGBA8);

        // Use compute shader program
        glUseProgram(raycastProgram.getOpenGLId());

        // Dispatch compute shader
        int groupsX = (frameWidth + WORKGROUP_SIZE_X - 1) / WORKGROUP_SIZE_X;
        int groupsY = (frameHeight + WORKGROUP_SIZE_Y - 1) / WORKGROUP_SIZE_Y;

        glDispatchCompute(groupsX, groupsY, WORKGROUP_SIZE_Z);

        // Ensure all writes are complete
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Check for errors
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new RuntimeException(String.format("OpenGL error during rendering: 0x%X", error));
        }
    }

    /**
     * Render a frame with default identity transforms (for testing)
     *
     * @param esvtMemory GPU memory containing ESVT node data
     * @param cameraPosition Camera position in world space
     * @param lookAt Point the camera is looking at
     * @param fovDegrees Field of view in degrees
     */
    public void renderFrame(ESVTGPUMemory esvtMemory,
                           Vector3f cameraPosition, Vector3f lookAt, float fovDegrees) {
        // Create view matrix (look-at)
        var viewMatrix = createLookAtMatrix(cameraPosition, lookAt, new Vector3f(0, 1, 0));

        // Create projection matrix
        float aspect = (float) frameWidth / frameHeight;
        var projMatrix = createPerspectiveMatrix(fovDegrees, aspect, 0.1f, 100.0f);

        // Identity transforms for object and tetree
        var objectToWorld = new Matrix4f();
        objectToWorld.setIdentity();

        var tetreeToObject = new Matrix4f();
        tetreeToObject.setIdentity();

        renderFrame(esvtMemory, viewMatrix, projMatrix, objectToWorld, tetreeToObject);
    }

    /**
     * Render a frame using beam optimization (two-pass rendering).
     * First pass renders at coarse resolution to gather t-min values,
     * second pass uses coarse data to skip empty space.
     *
     * @param esvtMemory GPU memory containing ESVT node data
     * @param viewMatrix Camera view matrix
     * @param projMatrix Camera projection matrix
     * @param objectToWorld Transform from object to world space
     * @param tetreeToObject Transform from tetree [0,1] to object space
     */
    public void renderFrameWithBeamOptimization(ESVTGPUMemory esvtMemory,
                                                 Matrix4f viewMatrix, Matrix4f projMatrix,
                                                 Matrix4f objectToWorld, Matrix4f tetreeToObject) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }

        if (disposed) {
            throw new IllegalStateException("Renderer has been disposed");
        }

        // Bind ESVT data
        esvtMemory.bindToShader(ESVT_BUFFER_BINDING);

        // Update camera uniforms
        updateCameraUniforms(viewMatrix, projMatrix, objectToWorld, tetreeToObject);

        // Use compute shader program
        glUseProgram(raycastProgram.getOpenGLId());

        // =====================================================================
        // PASS 1: Coarse pass - render at reduced resolution, store t-min
        // =====================================================================
        updateRenderFlags(FLAG_COARSE_PASS);

        // Bind coarse texture for output
        glBindImageTexture(COARSE_OUTPUT_BINDING, coarseTexture.getOpenGLId(), 0, false, 0,
                          GL_WRITE_ONLY, GL_R32F);

        // Dispatch at coarse resolution
        int coarseGroupsX = (coarseWidth + WORKGROUP_SIZE_X - 1) / WORKGROUP_SIZE_X;
        int coarseGroupsY = (coarseHeight + WORKGROUP_SIZE_Y - 1) / WORKGROUP_SIZE_Y;

        glDispatchCompute(coarseGroupsX, coarseGroupsY, WORKGROUP_SIZE_Z);

        // Ensure coarse pass writes are complete
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // =====================================================================
        // PASS 2: Fine pass - full resolution with beam optimization
        // =====================================================================
        updateRenderFlags(FLAG_USE_COARSE_DATA);

        // Bind coarse texture for reading (as sampler)
        glActiveTexture(GL_TEXTURE0 + COARSE_INPUT_BINDING);
        glBindTexture(GL_TEXTURE_2D, coarseTexture.getOpenGLId());
        glBindSampler(COARSE_INPUT_BINDING, coarseSampler);

        // Bind output texture for writing
        glBindImageTexture(OUTPUT_IMAGE_BINDING, outputTexture.getOpenGLId(), 0, false, 0,
                          GL_WRITE_ONLY, GL_RGBA8);

        // Dispatch at full resolution
        int fineGroupsX = (frameWidth + WORKGROUP_SIZE_X - 1) / WORKGROUP_SIZE_X;
        int fineGroupsY = (frameHeight + WORKGROUP_SIZE_Y - 1) / WORKGROUP_SIZE_Y;

        glDispatchCompute(fineGroupsX, fineGroupsY, WORKGROUP_SIZE_Z);

        // Ensure fine pass writes are complete
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Reset render flags
        updateRenderFlags(0);

        // Check for errors
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new RuntimeException(String.format("OpenGL error during beam-optimized rendering: 0x%X", error));
        }
    }

    /**
     * Enable or disable beam optimization for subsequent render calls
     *
     * @param enabled true to enable beam optimization
     */
    public void setBeamOptimizationEnabled(boolean enabled) {
        this.beamOptimizationEnabled = enabled;
    }

    /**
     * Check if beam optimization is enabled
     */
    public boolean isBeamOptimizationEnabled() {
        return beamOptimizationEnabled;
    }

    /**
     * Set the coarse size for beam optimization
     *
     * @param size Resolution divisor (e.g., 4 means 1/4 resolution for coarse pass)
     */
    public void setCoarseSize(int size) {
        if (size < 1 || size > 16) {
            throw new IllegalArgumentException("Coarse size must be between 1 and 16");
        }
        if (this.coarseSize != size) {
            this.coarseSize = size;
            if (initialized) {
                // Recreate coarse texture with new size
                if (coarseTexture != null) {
                    coarseTexture.close();
                }
                if (coarseSampler != 0) {
                    glDeleteSamplers(coarseSampler);
                }
                createCoarseTexture();
            }
        }
    }

    /**
     * Get the current coarse size
     */
    public int getCoarseSize() {
        return coarseSize;
    }

    /**
     * Get the output texture ID for display or further processing
     */
    public int getOutputTexture() {
        return outputTexture != null ? outputTexture.getOpenGLId() : 0;
    }

    /**
     * Get frame width
     */
    public int getFrameWidth() {
        return frameWidth;
    }

    /**
     * Get frame height
     */
    public int getFrameHeight() {
        return frameHeight;
    }

    /**
     * Resize the output resolution
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == frameWidth && newHeight == frameHeight) {
            return;
        }

        this.frameWidth = newWidth;
        this.frameHeight = newHeight;

        if (initialized) {
            if (outputTexture != null) {
                outputTexture.close();
            }
            if (coarseTexture != null) {
                coarseTexture.close();
            }
            if (coarseSampler != 0) {
                glDeleteSamplers(coarseSampler);
                coarseSampler = 0;
            }
            createOutputTexture();

            log.info("Resized ESVTComputeRenderer: {}x{}", frameWidth, frameHeight);
        }
    }

    /**
     * Check if renderer is initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Check if renderer is disposed
     */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Dispose all GPU resources
     */
    public void dispose() {
        if (disposed) {
            return;
        }

        try {
            if (raycastProgram != null) {
                raycastProgram.close();
                raycastProgram = null;
            }

            if (raycastComputeShader != null) {
                raycastComputeShader.close();
                raycastComputeShader = null;
            }

            if (cameraUBO != null) {
                cameraUBO.close();
                cameraUBO = null;
            }

            if (renderFlagsUBO != null) {
                renderFlagsUBO.close();
                renderFlagsUBO = null;
            }

            if (outputTexture != null) {
                outputTexture.close();
                outputTexture = null;
            }

            if (coarseTexture != null) {
                coarseTexture.close();
                coarseTexture = null;
            }

            if (coarseSampler != 0) {
                glDeleteSamplers(coarseSampler);
                coarseSampler = 0;
            }
        } catch (Exception e) {
            log.error("Error disposing GPU resources", e);
        }

        disposed = true;
        initialized = false;

        log.info("Disposed ESVTComputeRenderer");
    }

    // === Private Implementation Methods ===

    private void compileShaders() throws IOException {
        // Load ESVT raycast compute shader source
        String shaderSource = loadShaderSource("raycast_esvt.comp");

        // Create and compile compute shader
        raycastComputeShader = resourceManager.createComputeShader(shaderSource, "ESVTRaycastCompute");

        // Create shader program
        raycastProgram = resourceManager.createShaderProgram(
            "ESVTRaycastProgram",
            raycastComputeShader
        );

        log.info("Compiled ESVT compute shaders successfully");
    }

    private String loadShaderSource(String filename) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/shaders/" + filename)) {
            if (is == null) {
                throw new IOException("Shader file not found: " + filename);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void createUniformBuffers() {
        cameraUBO = resourceManager.createUniformBuffer(CAMERA_UBO_SIZE, "ESVTCameraUBO");
        glBindBufferBase(GL_UNIFORM_BUFFER, CAMERA_UBO_BINDING, cameraUBO.getOpenGLId());

        renderFlagsUBO = resourceManager.createUniformBuffer(RENDER_FLAGS_UBO_SIZE, "ESVTRenderFlagsUBO");
        glBindBufferBase(GL_UNIFORM_BUFFER, RENDER_FLAGS_UBO_BINDING, renderFlagsUBO.getOpenGLId());
    }

    private void createOutputTexture() {
        outputTexture = resourceManager.createTexture2D(frameWidth, frameHeight, GL_RGBA8, "ESVTOutputTexture");

        // Create coarse texture for beam optimization
        createCoarseTexture();
    }

    private void createCoarseTexture() {
        // Calculate coarse dimensions (add 1 to ensure coverage at boundaries)
        coarseWidth = (frameWidth + coarseSize - 1) / coarseSize + 1;
        coarseHeight = (frameHeight + coarseSize - 1) / coarseSize + 1;

        // Create R32F texture for storing t-min values
        coarseTexture = resourceManager.createTexture2D(coarseWidth, coarseHeight, GL_R32F, "ESVTCoarseTexture");

        // Create sampler for coarse texture (nearest filtering for exact pixel access)
        coarseSampler = glGenSamplers();
        glSamplerParameteri(coarseSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(coarseSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(coarseSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(coarseSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        log.debug("Created coarse texture: {}x{} (from {}x{} with coarseSize={})",
                 coarseWidth, coarseHeight, frameWidth, frameHeight, coarseSize);
    }

    private void updateRenderFlags(int flags) {
        try (MemoryStack stack = stackPush()) {
            var buffer = stack.mallocInt(4);
            buffer.put(flags);
            buffer.put(coarseSize);
            buffer.put(frameWidth);
            buffer.put(frameHeight);
            buffer.flip();

            glBindBuffer(GL_UNIFORM_BUFFER, renderFlagsUBO.getOpenGLId());
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer);
        }
    }

    private void updateCameraUniforms(Matrix4f viewMatrix, Matrix4f projMatrix,
                                      Matrix4f objectToWorld, Matrix4f tetreeToObject) {
        try (MemoryStack stack = stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(CAMERA_UBO_SIZE / 4);

            // Pack matrices into buffer (row-major order)
            putMatrixInBuffer(viewMatrix, buffer);
            putMatrixInBuffer(projMatrix, buffer);
            putMatrixInBuffer(objectToWorld, buffer);
            putMatrixInBuffer(tetreeToObject, buffer);

            // Add camera position and direction
            var cameraPos = extractCameraPosition(viewMatrix);
            var cameraDir = extractCameraDirection(viewMatrix);

            buffer.put(cameraPos.x).put(cameraPos.y).put(cameraPos.z).put(0.1f);  // nearPlane
            buffer.put(cameraDir.x).put(cameraDir.y).put(cameraDir.z).put(100.0f); // farPlane

            buffer.flip();

            // Update uniform buffer
            glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO.getOpenGLId());
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer);
        }
    }

    private Vector3f extractCameraPosition(Matrix4f viewMatrix) {
        var invView = new Matrix4f();
        invView.invert(viewMatrix);
        return new Vector3f(invView.m03, invView.m13, invView.m23);
    }

    private Vector3f extractCameraDirection(Matrix4f viewMatrix) {
        return new Vector3f(-viewMatrix.m02, -viewMatrix.m12, -viewMatrix.m22);
    }

    private void putMatrixInBuffer(Matrix4f matrix, FloatBuffer buffer) {
        buffer.put(matrix.m00).put(matrix.m01).put(matrix.m02).put(matrix.m03);
        buffer.put(matrix.m10).put(matrix.m11).put(matrix.m12).put(matrix.m13);
        buffer.put(matrix.m20).put(matrix.m21).put(matrix.m22).put(matrix.m23);
        buffer.put(matrix.m30).put(matrix.m31).put(matrix.m32).put(matrix.m33);
    }

    private Matrix4f createLookAtMatrix(Vector3f eye, Vector3f target, Vector3f up) {
        var forward = new Vector3f();
        forward.sub(target, eye);
        forward.normalize();

        var right = new Vector3f();
        right.cross(forward, up);
        right.normalize();

        var newUp = new Vector3f();
        newUp.cross(right, forward);

        var matrix = new Matrix4f();
        matrix.m00 = right.x;
        matrix.m01 = right.y;
        matrix.m02 = right.z;
        matrix.m03 = -right.dot(eye);
        matrix.m10 = newUp.x;
        matrix.m11 = newUp.y;
        matrix.m12 = newUp.z;
        matrix.m13 = -newUp.dot(eye);
        matrix.m20 = -forward.x;
        matrix.m21 = -forward.y;
        matrix.m22 = -forward.z;
        matrix.m23 = forward.dot(eye);
        matrix.m30 = 0;
        matrix.m31 = 0;
        matrix.m32 = 0;
        matrix.m33 = 1;

        return matrix;
    }

    private Matrix4f createPerspectiveMatrix(float fovDegrees, float aspect, float near, float far) {
        float fovRad = (float) Math.toRadians(fovDegrees);
        float f = 1.0f / (float) Math.tan(fovRad / 2.0f);

        var matrix = new Matrix4f();
        matrix.m00 = f / aspect;
        matrix.m11 = f;
        matrix.m22 = (far + near) / (near - far);
        matrix.m23 = (2 * far * near) / (near - far);
        matrix.m32 = -1;
        matrix.m33 = 0;

        return matrix;
    }
}
