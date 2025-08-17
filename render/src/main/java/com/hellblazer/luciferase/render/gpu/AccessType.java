package com.hellblazer.luciferase.render.gpu;

/**
 * GPU memory access patterns for buffers and shader resources.
 */
public enum AccessType {
    /**
     * Read-only access from GPU shaders.
     * Data flows from buffer to shader.
     */
    READ_ONLY,
    
    /**
     * Write-only access from GPU shaders.
     * Data flows from shader to buffer.
     */
    WRITE_ONLY,
    
    /**
     * Read-write access from GPU shaders.
     * Data can flow in both directions.
     */
    READ_WRITE
}