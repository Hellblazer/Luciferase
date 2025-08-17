package com.hellblazer.luciferase.render.gpu;

import java.util.Map;

/**
 * Abstract GPU context interface supporting both OpenGL and BGFX backends.
 * Provides compute shader execution, buffer management, and synchronization.
 */
public interface IGPUContext {
    
    /**
     * Initialize the GPU context with the given configuration.
     * @param config Configuration parameters for initialization
     * @return true if initialization successful, false otherwise
     */
    boolean initialize(GPUConfig config);
    
    /**
     * Create a GPU buffer for compute operations.
     * @param type Buffer type (storage, uniform, etc.)
     * @param size Buffer size in bytes
     * @param usage Buffer usage pattern
     * @return GPU buffer handle
     */
    IGPUBuffer createBuffer(BufferType type, int size, BufferUsage usage);
    
    /**
     * Create a GPU buffer for compute operations with access type.
     * @param size Buffer size in bytes
     * @param usage Buffer usage pattern
     * @param accessType Memory access pattern
     * @return GPU buffer handle
     */
    IGPUBuffer createBuffer(int size, BufferUsage usage, AccessType accessType);
    
    /**
     * Create a compute shader from GLSL source code.
     * @param shaderSource GLSL compute shader source
     * @param defines Preprocessor defines for compilation
     * @return Compiled compute shader
     */
    IGPUShader createComputeShader(String shaderSource, Map<String, String> defines);
    
    /**
     * Create a shader from source code with defines.
     * @param shaderSource Shader source code
     * @param defines Preprocessor defines for compilation
     * @return Compiled shader
     */
    IGPUShader createShader(String shaderSource, Map<String, String> defines);
    
    /**
     * Get the shader factory for this GPU context.
     * @return Shader factory instance
     */
    IShaderFactory getShaderFactory();
    
    /**
     * Dispatch compute shader execution.
     * @param shader Compute shader to execute
     * @param groupsX Number of work groups in X dimension
     * @param groupsY Number of work groups in Y dimension
     * @param groupsZ Number of work groups in Z dimension
     */
    void dispatch(IGPUShader shader, int groupsX, int groupsY, int groupsZ);
    
    /**
     * Insert a memory barrier to synchronize GPU operations.
     * @param type Type of memory barrier
     */
    void memoryBarrier(BarrierType type);
    
    /**
     * Cleanup and release all GPU resources.
     */
    void cleanup();
    
    /**
     * Check if the context is valid and ready for use.
     * @return true if context is valid
     */
    boolean isValid();
}