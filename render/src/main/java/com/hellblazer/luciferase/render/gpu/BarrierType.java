package com.hellblazer.luciferase.render.gpu;

/**
 * Types of GPU memory barriers for synchronization.
 */
public enum BarrierType {
    /**
     * Synchronize shader storage buffer writes.
     * Ensures SSBO writes are visible to subsequent operations.
     */
    SHADER_STORAGE_BARRIER,
    
    /**
     * Synchronize uniform buffer updates.
     * Ensures uniform buffer data is available to shaders.
     */
    UNIFORM_BARRIER,
    
    /**
     * Synchronize texture/image access.
     * Ensures image writes are visible to subsequent reads.
     */
    TEXTURE_BARRIER,
    
    /**
     * Synchronize buffer updates.
     * General buffer synchronization for various buffer types.
     */
    BUFFER_UPDATE_BARRIER,
    
    /**
     * Synchronize atomic counter operations.
     * Ensures atomic operations are visible across work groups.
     */
    ATOMIC_COUNTER_BARRIER,
    
    /**
     * Full memory barrier.
     * Synchronizes all memory operations.
     */
    ALL_BARRIER
}