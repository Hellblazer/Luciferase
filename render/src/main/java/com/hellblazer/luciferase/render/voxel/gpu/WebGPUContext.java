package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.webgpu.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * WebGPU context management for ESVO rendering.
 * Provides a high-level interface for voxel rendering operations using WebGPU backend abstraction.
 */
public class WebGPUContext {
    private static final Logger log = LoggerFactory.getLogger(WebGPUContext.class);
    
    private final WebGPUBackend backend;
    
    /**
     * Create WebGPU context with automatic backend selection.
     */
    public WebGPUContext() {
        this(WebGPUBackendFactory.getDefaultBackend());
    }
    
    /**
     * Create WebGPU context with specific backend.
     */
    public WebGPUContext(WebGPUBackend backend) {
        this.backend = backend;
    }
    
    /**
     * Initialize WebGPU context.
     */
    public CompletableFuture<Void> initialize() {
        log.info("Initializing WebGPU context with backend: {}", backend.getBackendName());
        return backend.initialize().thenAccept(success -> {
            if (success) {
                log.info("WebGPU context initialized successfully");
            } else {
                throw new RuntimeException("Failed to initialize WebGPU backend");
            }
        });
    }
    
    /**
     * Check if WebGPU is available.
     */
    public boolean isAvailable() {
        return backend.isAvailable();
    }
    
    /**
     * Check if the context is initialized.
     */
    public boolean isInitialized() {
        return backend.isInitialized();
    }
    
    /**
     * Create a buffer for GPU storage.
     */
    public BufferHandle createBuffer(long size, int usage) {
        return backend.createBuffer(size, usage);
    }
    
    /**
     * Create a compute shader from WGSL source.
     */
    public ShaderHandle createComputeShader(String wgslSource) {
        return backend.createComputeShader(wgslSource);
    }
    
    /**
     * Write data to a buffer.
     */
    public void writeBuffer(BufferHandle buffer, byte[] data, long offset) {
        backend.writeBuffer(buffer, data, offset);
    }
    
    /**
     * Read data from a buffer.
     */
    public byte[] readBuffer(BufferHandle buffer, long size, long offset) {
        return backend.readBuffer(buffer, size, offset);
    }
    
    /**
     * Dispatch compute work.
     */
    public void dispatchCompute(ShaderHandle shader, int workGroupCountX, int workGroupCountY, int workGroupCountZ) {
        backend.dispatchCompute(shader, workGroupCountX, workGroupCountY, workGroupCountZ);
    }
    
    /**
     * Wait for all GPU operations to complete.
     */
    public CompletableFuture<Void> waitIdle() {
        return CompletableFuture.runAsync(() -> backend.waitIdle());
    }
    
    /**
     * Shutdown the context and release resources.
     */
    public void shutdown() {
        backend.shutdown();
        log.info("WebGPU context shutdown complete");
    }
    
    /**
     * Get the backend implementation name.
     */
    public String getBackendName() {
        return backend.getBackendName();
    }
    
    // Legacy compatibility methods for VoxelRenderingPipeline
    
    /**
     * Write to buffer (legacy compatibility).
     */
    public void writeBuffer(Object buffer, long offset, byte[] data) {
        if (buffer instanceof BufferHandle bufferHandle) {
            backend.writeBuffer(bufferHandle, data, offset);
        } else {
            throw new IllegalArgumentException("Invalid buffer type: " + buffer.getClass());
        }
    }
    
    /**
     * Read from buffer (legacy compatibility).
     */
    public java.nio.ByteBuffer readBuffer(Object buffer) {
        if (buffer instanceof BufferHandle bufferHandle) {
            var data = backend.readBuffer(bufferHandle, bufferHandle.getSize(), 0);
            return java.nio.ByteBuffer.wrap(data);
        } else {
            throw new IllegalArgumentException("Invalid buffer type: " + buffer.getClass());
        }
    }
    
    /**
     * Dispatch compute (legacy compatibility).
     */
    public void dispatchCompute(Object pipeline, int x, int y, int z) {
        if (pipeline instanceof ShaderHandle shaderHandle) {
            backend.dispatchCompute(shaderHandle, x, y, z);
        } else {
            throw new IllegalArgumentException("Invalid shader type: " + pipeline.getClass());
        }
    }
}