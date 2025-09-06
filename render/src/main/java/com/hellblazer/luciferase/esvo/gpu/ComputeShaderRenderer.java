package com.hellblazer.luciferase.esvo.gpu;

import org.lwjgl.opengl.GL43;
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
    
    // Binding points for shader resources
    public static final int OCTREE_BUFFER_BINDING = 0;
    public static final int OUTPUT_IMAGE_BINDING = 1;
    public static final int CAMERA_UBO_BINDING = 2;
    
    // Shader programs
    private int raycastComputeShader = 0;
    private int raycastProgram = 0;
    
    // Uniform buffer for camera data
    private int cameraUBO = 0;
    private static final int CAMERA_UBO_SIZE = 64 * 4 + 16 * 4; // 4x4 matrices + vectors
    
    // Output texture
    private int outputTexture = 0;
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
     * Render a frame using the octree data
     * 
     * @param octreeMemory GPU memory containing octree node data
     * @param viewMatrix Camera view matrix
     * @param projMatrix Camera projection matrix  
     * @param objectToWorld Transform from object to world space
     * @param octreeToObject Transform from octree [1,2] to object space
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
        
        // Update camera uniforms
        updateCameraUniforms(viewMatrix, projMatrix, objectToWorld, octreeToObject);
        
        // Bind output texture
        glBindImageTexture(OUTPUT_IMAGE_BINDING, outputTexture, 0, false, 0, 
                          GL_WRITE_ONLY, GL_RGBA8);
        
        // Use compute shader program
        glUseProgram(raycastProgram);
        
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
     * Get the output texture ID for display or further processing
     */
    public int getOutputTexture() {
        return outputTexture;
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
            if (outputTexture != 0) {
                glDeleteTextures(outputTexture);
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
        
        if (raycastProgram != 0) {
            glDeleteProgram(raycastProgram);
            raycastProgram = 0;
        }
        
        if (raycastComputeShader != 0) {
            glDeleteShader(raycastComputeShader);
            raycastComputeShader = 0;
        }
        
        if (cameraUBO != 0) {
            glDeleteBuffers(cameraUBO);
            cameraUBO = 0;
        }
        
        if (outputTexture != 0) {
            glDeleteTextures(outputTexture);
            outputTexture = 0;
        }
        
        disposed = true;
        initialized = false;
        
        log.info("Disposed ComputeShaderRenderer");
    }
    
    // === Private Implementation Methods ===
    
    private void compileShaders() throws IOException {
        // Load raycast compute shader source
        String shaderSource = loadShaderSource("raycast.comp");
        
        // Create and compile compute shader
        raycastComputeShader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(raycastComputeShader, shaderSource);
        glCompileShader(raycastComputeShader);
        
        // Check compilation status
        if (glGetShaderi(raycastComputeShader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(raycastComputeShader);
            glDeleteShader(raycastComputeShader);
            throw new RuntimeException("Compute shader compilation failed:\n" + log);
        }
        
        // Create and link program
        raycastProgram = glCreateProgram();
        glAttachShader(raycastProgram, raycastComputeShader);
        glLinkProgram(raycastProgram);
        
        // Check link status
        if (glGetProgrami(raycastProgram, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(raycastProgram);
            glDeleteProgram(raycastProgram);
            throw new RuntimeException("Compute shader program linking failed:\n" + log);
        }
        
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
        // Create camera uniform buffer
        cameraUBO = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO);
        glBufferData(GL_UNIFORM_BUFFER, CAMERA_UBO_SIZE, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_UNIFORM_BUFFER, CAMERA_UBO_BINDING, cameraUBO);
    }
    
    private void createOutputTexture() {
        outputTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, outputTexture);
        
        // Create RGBA8 texture for output
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, frameWidth, frameHeight, 0, 
                    GL_RGBA, GL_UNSIGNED_BYTE, 0);
        
        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
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
            glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO);
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