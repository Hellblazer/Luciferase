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

    // Resource manager for GPU resources
    private final UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();

    // Managed resources
    private ShaderResource raycastComputeShader;
    private ShaderProgramResource raycastProgram;
    private BufferResource cameraUBO;
    private TextureResource outputTexture;

    // Camera UBO size: 4 matrices (4x4) + 2 vec4 (position+nearPlane, direction+farPlane)
    private static final int CAMERA_UBO_SIZE = 64 * 4 + 16 * 2;

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

            if (outputTexture != null) {
                outputTexture.close();
                outputTexture = null;
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
    }

    private void createOutputTexture() {
        outputTexture = resourceManager.createTexture2D(frameWidth, frameHeight, GL_RGBA8, "ESVTOutputTexture");
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
