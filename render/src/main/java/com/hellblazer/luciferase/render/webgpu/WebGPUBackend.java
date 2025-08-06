package com.hellblazer.luciferase.render.webgpu;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for WebGPU backend implementations.
 * Allows switching between FFM native implementation and stub implementation.
 */
public interface WebGPUBackend {
    
    /**
     * Check if this backend is available on the current system.
     */
    boolean isAvailable();
    
    /**
     * Initialize the WebGPU backend.
     * @return Future that completes with true if initialization succeeded
     */
    CompletableFuture<Boolean> initialize();
    
    /**
     * Shutdown the WebGPU backend and release resources.
     */
    void shutdown();
    
    /**
     * Check if the backend is currently initialized.
     */
    boolean isInitialized();
    
    /**
     * Create a compute buffer with the specified size and usage.
     * @param size Buffer size in bytes
     * @param usage Buffer usage flags
     * @return Handle to the created buffer
     */
    BufferHandle createBuffer(long size, int usage);
    
    /**
     * Create a compute shader from WGSL source.
     * @param wgslSource The WGSL shader source code
     * @return Handle to the compiled shader
     */
    ShaderHandle createComputeShader(String wgslSource);
    
    /**
     * Write data to a buffer.
     * @param buffer Target buffer
     * @param data Data to write
     * @param offset Offset in buffer
     */
    void writeBuffer(BufferHandle buffer, byte[] data, long offset);
    
    /**
     * Read data from a buffer.
     * @param buffer Source buffer
     * @param size Number of bytes to read
     * @param offset Offset in buffer
     * @return Buffer contents
     */
    byte[] readBuffer(BufferHandle buffer, long size, long offset);
    
    /**
     * Dispatch compute shader with specified work groups.
     * @param shader Compute shader to execute
     * @param workGroupCountX Number of work groups in X dimension
     * @param workGroupCountY Number of work groups in Y dimension
     * @param workGroupCountZ Number of work groups in Z dimension
     */
    void dispatchCompute(ShaderHandle shader, int workGroupCountX, int workGroupCountY, int workGroupCountZ);
    
    /**
     * Wait for all pending GPU operations to complete.
     */
    void waitIdle();
    
    /**
     * Get the backend implementation name.
     */
    String getBackendName();
}