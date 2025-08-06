package com.hellblazer.luciferase.render.webgpu;

/**
 * Abstract handle to a WebGPU buffer resource.
 * Implementations may wrap native handles or provide stub functionality.
 */
public interface BufferHandle {
    
    /**
     * Get the size of the buffer in bytes.
     */
    long getSize();
    
    /**
     * Get the usage flags for this buffer.
     */
    int getUsage();
    
    /**
     * Check if this buffer handle is valid/alive.
     */
    boolean isValid();
    
    /**
     * Release the buffer resource. 
     * Handle becomes invalid after this call.
     */
    void release();
    
    /**
     * Get the backend-specific native handle.
     * May return null for stub implementations.
     */
    Object getNativeHandle();
}