package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import com.hellblazer.luciferase.resource.opengl.BufferResource;
import com.hellblazer.luciferase.resource.opengl.ShaderProgramResource;
import com.hellblazer.luciferase.resource.opengl.ShaderResource;
import com.hellblazer.luciferase.resource.opengl.TextureResource;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * GPU Compute Shader Renderer for ESVO
 * 
 * This class manages GLSL compute shaders for octree ray traversal,
 * integrating with the LWJGL GPU framework and implementing the critical
 * shader bug fixes identified in the memory bank documentation.
 * 
 * Key Features:
 * - GLSL compute shader compilation and management
 * - Uniform buffer management for camera and octree transforms
 * - Integration with OctreeGPUMemory for node data
 * - Thread-safe shader dispatch
 */
public final class ComputeShaderRenderer {
    private static final Logger log = LoggerFactory.getLogger(ComputeShaderRenderer.class);
    
    // Compute shader workgroup size (must match shader)
    public static final int WORKGROUP_SIZE_X = 8;
    public static final int WORKGROUP_SIZE_Y = 8;
    public static final int WORKGROUP_SIZE_Z = 1;
    
    // Binding points for shader resources (must match raycast.comp layout declarations)
    public static final int OCTREE_BUFFER_BINDING   = 0;
    public static final int OUTPUT_IMAGE_BINDING    = 1;
    public static final int CAMERA_UBO_BINDING      = 2;
    // FAR_POINTER_BUFFER_BINDING must not conflict with bindings 0-2 above.
    // Matches the layout(std430, binding = 3) declaration in raycast.comp.
    public static final int FAR_POINTER_BUFFER_BINDING = 3;
    
    // Resource manager for GPU resources
    private final UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();
    
    // Managed resources
    private ShaderResource raycastComputeShader;
    private ShaderProgramResource raycastProgram;
    private BufferResource cameraUBO;
    private TextureResource outputTexture;

    // Far-pointer SSBO (binding 3). Always bound — contains a 4-byte placeholder
    // when no far pointers are present so the shader binding slot is always valid.
    // Mirrors ESVTComputeRenderer.farPointerSSBO (PR #232).
    private BufferResource farPointerSSBO;

    private static final int CAMERA_UBO_SIZE = 64 * 4 + 16 * 4; // 4x4 matrices + vectors

    private int frameWidth;
    private int frameHeight;

    private boolean initialized = false;
    private boolean disposed = false;
    
    /**
     * Create compute shader renderer with specified output resolution
     */
    public ComputeShaderRenderer(int frameWidth, int frameHeight) {
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
            log.info("Initialized ComputeShaderRenderer: {}x{}", frameWidth, frameHeight);
            
        } catch (Exception e) {
            dispose();
            throw new RuntimeException("Failed to initialize ComputeShaderRenderer", e);
        }
    }
    
    /**
     * Upload far-pointer data from an {@link ESVOOctreeData} to the GPU SSBO at binding 3.
     *
     * <p>When the data has no far pointers (empty or null array) a minimal 4-byte
     * placeholder buffer is uploaded so the shader binding slot is always valid —
     * matching the convention in ESVTComputeRenderer (PR #232).
     *
     * <p>Must be called from the OpenGL context thread, after {@link #initialize()}.
     *
     * @param data the octree data whose far-pointer table to upload
     */
    public void uploadData(ESVOOctreeData data) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }
        if (disposed) {
            throw new IllegalStateException("Renderer has been disposed");
        }

        int[] fps = data.getFarPointers();
        int byteCount = (fps != null && fps.length > 0) ? fps.length * Integer.BYTES : Integer.BYTES;

        if (farPointerSSBO == null) {
            farPointerSSBO = resourceManager.createStorageBuffer(byteCount, "ESVOFarPointerSSBO");
        }

        ByteBuffer buf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
        if (fps != null && fps.length > 0) {
            for (int fp : fps) {
                buf.putInt(fp);
            }
        } else {
            buf.putInt(0);  // 4-byte placeholder so binding 3 is always valid
        }
        buf.flip();

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, farPointerSSBO.getOpenGLId());
        glBufferData(GL_SHADER_STORAGE_BUFFER, buf, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, FAR_POINTER_BUFFER_BINDING, farPointerSSBO.getOpenGLId());

        log.debug("Uploaded {} far-pointer entries ({} bytes) to SSBO binding {}",
                  fps != null ? fps.length : 0, byteCount, FAR_POINTER_BUFFER_BINDING);
    }

    /**
     * Render a frame using the octree data
     *
     * @param octreeMemory GPU memory containing octree node data
     * @param viewMatrix Camera view matrix
     * @param projMatrix Camera projection matrix
     * @param objectToWorld Transform from object to world space
     * @param octreeToObject Transform from octree [0,1] to object space
     */
    public void renderFrame(OctreeGPUMemory octreeMemory,
                          Matrix4f viewMatrix, Matrix4f projMatrix,
                          Matrix4f objectToWorld, Matrix4f octreeToObject) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }

        if (disposed) {
            throw new IllegalStateException("Renderer has been disposed");
        }

        // Bind octree data
        octreeMemory.bindToShader(OCTREE_BUFFER_BINDING);

        // Bind far-pointer SSBO (binding 3) — always required by the shader.
        // If uploadData() was not called, bind a minimal placeholder so the binding slot is valid.
        if (farPointerSSBO == null) {
            farPointerSSBO = resourceManager.createStorageBuffer(Integer.BYTES, "ESVOFarPointerSSBOPlaceholder");
            ByteBuffer placeholder = ByteBuffer.allocateDirect(Integer.BYTES).order(ByteOrder.nativeOrder());
            placeholder.putInt(0);
            placeholder.flip();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, farPointerSSBO.getOpenGLId());
            glBufferData(GL_SHADER_STORAGE_BUFFER, placeholder, GL_STATIC_DRAW);
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, FAR_POINTER_BUFFER_BINDING, farPointerSSBO.getOpenGLId());

        // Update camera uniforms
        updateCameraUniforms(viewMatrix, projMatrix, objectToWorld, octreeToObject);

        // Bind output texture
        glBindImageTexture(OUTPUT_IMAGE_BINDING, outputTexture.getOpenGLId(), 0, false, 0,
                          GL_WRITE_ONLY, GL_RGBA8);

        // Use compute shader program
        glUseProgram(raycastProgram.getOpenGLId());

        // Ensure any prior writes to the octree SSBO (e.g. async upload) are
        // visible to the compute shader before it reads them. Without this
        // barrier the dispatch can read stale GPU memory and produce empty
        // frames or wrong hits.
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        // Dispatch compute shader
        int groupsX = (frameWidth + WORKGROUP_SIZE_X - 1) / WORKGROUP_SIZE_X;
        int groupsY = (frameHeight + WORKGROUP_SIZE_Y - 1) / WORKGROUP_SIZE_Y;

        glDispatchCompute(groupsX, groupsY, WORKGROUP_SIZE_Z);

        // Ensure all image writes are visible to subsequent samplers / readback.
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Check for errors
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new RuntimeException(String.format("OpenGL error during rendering: 0x%X", error));
        }
    }
    
    /**
     * Get the output texture ID for display or further processing
     */
    public int getOutputTexture() {
        return outputTexture != null ? outputTexture.getOpenGLId() : 0;
    }
    
    /**
     * Get frame dimensions
     */
    public int getFrameWidth() { return frameWidth; }
    public int getFrameHeight() { return frameHeight; }
    
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
            // Recreate output texture with new size
            if (outputTexture != null) {
                outputTexture.close();
            }
            createOutputTexture();
            
            log.info("Resized ComputeShaderRenderer: {}x{}", frameWidth, frameHeight);
        }
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

            if (farPointerSSBO != null) {
                farPointerSSBO.close();
                farPointerSSBO = null;
            }
        } catch (Exception e) {
            log.error("Error disposing GPU resources", e);
        }

        disposed = true;
        initialized = false;

        log.info("Disposed ComputeShaderRenderer");
    }
    
    // === Private Implementation Methods ===
    
    private void compileShaders() throws IOException {
        // Load raycast compute shader source
        String shaderSource = loadShaderSource("raycast.comp");
        
        // Create and compile compute shader using resource manager
        raycastComputeShader = resourceManager.createComputeShader(shaderSource, "ESVORaycastCompute");
        
        // Create shader program using resource manager
        raycastProgram = resourceManager.createShaderProgram(
            "ESVORaycastProgram",
            raycastComputeShader
        );
        
        log.info("Compiled ESVO compute shaders successfully");
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
        // Create camera uniform buffer using resource manager
        cameraUBO = resourceManager.createUniformBuffer(CAMERA_UBO_SIZE, "ESVOCameraUBO");
        glBindBufferBase(GL_UNIFORM_BUFFER, CAMERA_UBO_BINDING, cameraUBO.getOpenGLId());
    }
    
    private void createOutputTexture() {
        // Create RGBA8 texture using resource manager
        outputTexture = resourceManager.createTexture2D(frameWidth, frameHeight, GL_RGBA8, "ESVOOutputTexture");
    }
    
    private void updateCameraUniforms(Matrix4f viewMatrix, Matrix4f projMatrix,
                                    Matrix4f objectToWorld, Matrix4f octreeToObject) {
        try (MemoryStack stack = stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(CAMERA_UBO_SIZE / 4);
            
            // Pack matrices into buffer (row-major order)
            putMatrixInBuffer(viewMatrix, buffer);
            putMatrixInBuffer(projMatrix, buffer);
            putMatrixInBuffer(objectToWorld, buffer);
            putMatrixInBuffer(octreeToObject, buffer);
            
            // Add camera position and direction (placeholder - derive from view matrix)
            Vector3f cameraPos = extractCameraPosition(viewMatrix);
            Vector3f cameraDir = extractCameraDirection(viewMatrix);
            
            buffer.put(cameraPos.x).put(cameraPos.y).put(cameraPos.z).put(1.0f); // nearPlane placeholder
            buffer.put(cameraDir.x).put(cameraDir.y).put(cameraDir.z).put(100.0f); // farPlane placeholder
            
            buffer.flip();
            
            // Update uniform buffer
            glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO.getOpenGLId());
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer);
        }
    }
    
    private Vector3f extractCameraPosition(Matrix4f viewMatrix) {
        // Extract camera position from inverse view matrix
        Matrix4f invView = new Matrix4f();
        invView.invert(viewMatrix);
        return new Vector3f(invView.m03, invView.m13, invView.m23);
    }
    
    private Vector3f extractCameraDirection(Matrix4f viewMatrix) {
        // Extract camera forward direction from view matrix
        return new Vector3f(-viewMatrix.m02, -viewMatrix.m12, -viewMatrix.m22);
    }
    
    private void putMatrixInBuffer(Matrix4f matrix, FloatBuffer buffer) {
        // Put matrix values in row-major order (OpenGL convention)
        buffer.put(matrix.m00).put(matrix.m01).put(matrix.m02).put(matrix.m03);
        buffer.put(matrix.m10).put(matrix.m11).put(matrix.m12).put(matrix.m13);
        buffer.put(matrix.m20).put(matrix.m21).put(matrix.m22).put(matrix.m23);
        buffer.put(matrix.m30).put(matrix.m31).put(matrix.m32).put(matrix.m33);
    }
}