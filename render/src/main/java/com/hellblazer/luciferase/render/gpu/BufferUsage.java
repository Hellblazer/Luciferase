package com.hellblazer.luciferase.render.gpu;

/**
 * GPU buffer usage patterns that hint at optimal memory placement and access.
 */
public enum BufferUsage {
    /**
     * Buffer data is set once and used many times.
     * Optimal for static geometry, precomputed data.
     */
    STATIC_READ,
    
    /**
     * Buffer data is set once and modified infrequently from GPU.
     * Used for data that's mostly static but occasionally updated by compute shaders.
     */
    STATIC_WRITE,
    
    /**
     * Buffer data is updated frequently from CPU and read by GPU.
     * Optimal for per-frame uniform data, dynamic vertex buffers.
     */
    DYNAMIC_READ,
    
    /**
     * Buffer data is updated frequently by GPU compute shaders.
     * Used for work queues, accumulation buffers, GPU-modified data structures.
     */
    DYNAMIC_WRITE,
    
    /**
     * Buffer data is both read and written frequently.
     * Used for ping-pong buffers, persistent data structures modified by compute.
     */
    READ_WRITE,
    
    /**
     * Buffer is used for streaming data from CPU to GPU.
     * Optimized for frequent uploads with sequential access patterns.
     */
    STREAM_TO_GPU,
    
    /**
     * Buffer is used for reading back data from GPU to CPU.
     * Optimized for GPU compute results that need CPU processing.
     */
    STREAM_FROM_GPU
}