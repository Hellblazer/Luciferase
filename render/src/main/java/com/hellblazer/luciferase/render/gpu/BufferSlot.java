package com.hellblazer.luciferase.render.gpu;

/**
 * Enum representing standard buffer binding slots for ESVO compute operations.
 * Based on OpenGL SSBO binding analysis from existing ESVO implementation.
 */
public enum BufferSlot {
    
    // ESVO Node and Page Buffers (read-only)
    NODE_BUFFER(0, "ESVO octree nodes for GPU traversal"),
    PAGE_BUFFER(1, "ESVO page data (8KB pages)"),
    
    // Work Queue Management (read-write, atomic)
    WORK_QUEUE(2, "Work distribution for persistent threads"),
    COUNTER_BUFFER(3, "Atomic counters for work queue management"),
    
    // Optional Statistics and Debug (write-only)
    STATISTICS_BUFFER(4, "Performance and debug statistics"),
    DEBUG_BUFFER(5, "Debug output and error reporting"),
    
    // Uniform Buffer Slots (separate namespace)
    TRAVERSAL_UNIFORMS(6, "Scene transformation and bounds data"),
    MATERIAL_UNIFORMS(7, "Material properties and lighting"),
    
    // Texture Image Slots (for compute shader image access)
    RAY_ORIGIN_IMAGE(8, "Input ray origins for traversal"),
    RAY_DIRECTION_IMAGE(9, "Input ray directions for traversal"),
    HIT_RESULT_IMAGE(10, "Output intersection results"),
    DEPTH_BUFFER_IMAGE(11, "Z-buffer for occlusion culling");
    
    private final int slotIndex;
    private final String description;
    
    BufferSlot(int slotIndex, String description) {
        this.slotIndex = slotIndex;
        this.description = description;
    }
    
    /**
     * Get the binding slot index for this buffer type.
     */
    public int getSlotIndex() {
        return slotIndex;
    }
    
    /**
     * Get a description of what this buffer slot is used for.
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Check if this slot is used for compute storage buffers (SSBOs).
     */
    public boolean isStorageBuffer() {
        return slotIndex >= 0 && slotIndex <= 5;
    }
    
    /**
     * Check if this slot is used for uniform buffers.
     */
    public boolean isUniformBuffer() {
        return slotIndex >= 6 && slotIndex <= 7;
    }
    
    /**
     * Check if this slot is used for image/texture access.
     */
    public boolean isImageSlot() {
        return slotIndex >= 8 && slotIndex <= 11;
    }
    
    /**
     * Check if this buffer requires atomic operation support.
     */
    public boolean requiresAtomicOps() {
        return this == WORK_QUEUE || this == COUNTER_BUFFER;
    }
    
    /**
     * Get the typical access pattern for this buffer type.
     */
    public AccessType getTypicalAccess() {
        return switch (this) {
            case NODE_BUFFER, PAGE_BUFFER -> AccessType.READ_ONLY;
            case WORK_QUEUE, COUNTER_BUFFER -> AccessType.READ_WRITE;
            case STATISTICS_BUFFER, DEBUG_BUFFER -> AccessType.WRITE_ONLY;
            case TRAVERSAL_UNIFORMS, MATERIAL_UNIFORMS -> AccessType.READ_ONLY;
            case RAY_ORIGIN_IMAGE, RAY_DIRECTION_IMAGE -> AccessType.READ_ONLY;
            case HIT_RESULT_IMAGE, DEPTH_BUFFER_IMAGE -> AccessType.WRITE_ONLY;
        };
    }
    
    /**
     * Get expected buffer size in bytes for typical ESVO workloads.
     * Returns -1 for variable-sized buffers.
     */
    public int getTypicalSize() {
        return switch (this) {
            case NODE_BUFFER -> -1; // Variable: ~8MB for 1M nodes
            case PAGE_BUFFER -> -1; // Variable: ~64MB for 8K pages  
            case WORK_QUEUE -> 32 * 32768; // 32K work items × 32 bytes
            case COUNTER_BUFFER -> 16; // 4 integers
            case STATISTICS_BUFFER -> 1024; // 1KB stats
            case DEBUG_BUFFER -> 4096; // 4KB debug
            case TRAVERSAL_UNIFORMS -> 84; // Matrix + bounds
            case MATERIAL_UNIFORMS -> 256; // Material properties
            case RAY_ORIGIN_IMAGE, RAY_DIRECTION_IMAGE, HIT_RESULT_IMAGE -> -1; // Depends on resolution
            case DEPTH_BUFFER_IMAGE -> -1; // Depends on Z-buffer size
        };
    }
}