package com.hellblazer.luciferase.render.webgpu;

/**
 * Abstract handle to a WebGPU shader resource.
 * Implementations may wrap native handles or provide stub functionality.
 */
public interface ShaderHandle {
    
    /**
     * Get the original WGSL source code for this shader.
     */
    String getWgslSource();
    
    /**
     * Check if this shader handle is valid/alive.
     */
    boolean isValid();
    
    /**
     * Release the shader resource.
     * Handle becomes invalid after this call.
     */
    void release();
    
    /**
     * Get the backend-specific native handle.
     * May return null for stub implementations.
     */
    Object getNativeHandle();
}