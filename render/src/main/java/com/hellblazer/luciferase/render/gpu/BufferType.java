package com.hellblazer.luciferase.render.gpu;

/**
 * GPU buffer types for different usage patterns.
 */
public enum BufferType {
    /**
     * Shader Storage Buffer Object (SSBO) - read/write access from compute shaders.
     * Used for large data structures like octree nodes, ray data, work queues.
     */
    STORAGE,
    
    /**
     * Uniform Buffer Object (UBO) - read-only access from shaders.
     * Used for small, frequently updated data like transformation matrices, constants.
     */
    UNIFORM,
    
    /**
     * Vertex buffer - contains vertex attribute data.
     * Not typically used in compute shaders but included for completeness.
     */
    VERTEX,
    
    /**
     * Index buffer - contains vertex indices for indexed rendering.
     * Not typically used in compute shaders but included for completeness.
     */
    INDEX,
    
    /**
     * Indirect buffer - contains draw/dispatch parameters.
     * Used for GPU-driven rendering and compute dispatch.
     */
    INDIRECT,
    
    /**
     * Atomic counter buffer - contains atomic counters for synchronization.
     * Used in persistent work queue implementations.
     */
    ATOMIC_COUNTER,
    
    /**
     * Texture/Image buffer - used for image access in compute shaders.
     * Represents textures that can be read from or written to in shaders.
     */
    TEXTURE
}