package com.hellblazer.luciferase.webgpu.wrapper;

/**
 * Type-safe wrapper for WebGPU Command Buffer.
 * Represents a pre-recorded list of GPU commands.
 */
public class CommandBuffer {
    private final long id;
    
    protected CommandBuffer(long id) {
        this.id = id;
    }
    
    public long getId() {
        return id;
    }
}