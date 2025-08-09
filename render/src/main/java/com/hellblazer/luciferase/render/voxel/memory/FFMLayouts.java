package com.hellblazer.luciferase.render.voxel.memory;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

/**
 * FFM memory layouts for voxel data structures compatible with GPU memory alignment.
 * 
 * These layouts define the structure of data in native memory for efficient
 * GPU transfer and processing. All layouts follow GPU alignment requirements
 * for optimal performance.
 */
public final class FFMLayouts {
    
    private FFMLayouts() {} // Prevent instantiation
    
    // ================================================================================
    // Voxel Node Layout - GPU-compatible structure for octree nodes
    // ================================================================================
    
    /**
     * Memory layout for a voxel octree node (16 bytes, GPU-aligned).
     * Structure:
     * - validMask (1 byte): Bitmask indicating which children exist
     * - leafMask (1 byte): Bitmask indicating which children are leaves
     * - padding (2 bytes): Alignment padding
     * - childPointer (4 bytes): Pointer/index to first child
     * - attachmentData (8 bytes): User data or material information
     */
    public static final StructLayout VOXEL_NODE_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_BYTE.withName("validMask"),
        ValueLayout.JAVA_BYTE.withName("leafMask"),
        ValueLayout.JAVA_SHORT.withName("padding"),
        ValueLayout.JAVA_INT.withName("childPointer"),
        ValueLayout.JAVA_LONG.withName("attachmentData")
    ).withByteAlignment(16);
    
    // VarHandles for efficient field access
    public static final VarHandle VOXEL_NODE_VALID_MASK = VOXEL_NODE_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("validMask"));
    public static final VarHandle VOXEL_NODE_LEAF_MASK = VOXEL_NODE_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("leafMask"));
    public static final VarHandle VOXEL_NODE_CHILD_POINTER = VOXEL_NODE_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("childPointer"));
    public static final VarHandle VOXEL_NODE_ATTACHMENT_DATA = VOXEL_NODE_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("attachmentData"));
    
    // ================================================================================
    // Ray Layout - GPU-compatible structure for ray data
    // ================================================================================
    
    /**
     * Memory layout for ray data (32 bytes, GPU-aligned).
     * Structure:
     * - origin (12 bytes): Ray origin as vec3
     * - tMin (4 bytes): Minimum distance along ray
     * - direction (12 bytes): Ray direction as vec3
     * - tMax (4 bytes): Maximum distance along ray
     */
    public static final StructLayout RAY_LAYOUT = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("origin"),
        ValueLayout.JAVA_FLOAT.withName("tMin"),
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("direction"),
        ValueLayout.JAVA_FLOAT.withName("tMax")
    ).withByteAlignment(16);
    
    // VarHandles for ray fields
    public static final VarHandle RAY_ORIGIN_X = RAY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("origin"),
        MemoryLayout.PathElement.sequenceElement(0));
    public static final VarHandle RAY_ORIGIN_Y = RAY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("origin"),
        MemoryLayout.PathElement.sequenceElement(1));
    public static final VarHandle RAY_ORIGIN_Z = RAY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("origin"),
        MemoryLayout.PathElement.sequenceElement(2));
    public static final VarHandle RAY_T_MIN = RAY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("tMin"));
    public static final VarHandle RAY_DIRECTION_X = RAY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("direction"),
        MemoryLayout.PathElement.sequenceElement(0));
    public static final VarHandle RAY_DIRECTION_Y = RAY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("direction"),
        MemoryLayout.PathElement.sequenceElement(1));
    public static final VarHandle RAY_DIRECTION_Z = RAY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("direction"),
        MemoryLayout.PathElement.sequenceElement(2));
    public static final VarHandle RAY_T_MAX = RAY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("tMax"));
    
    // ================================================================================
    // Hit Result Layout - GPU-compatible structure for intersection results
    // ================================================================================
    
    /**
     * Memory layout for ray-voxel intersection results (48 bytes, GPU-aligned).
     * Structure:
     * - hit (4 bytes): Boolean flag indicating hit
     * - distance (4 bytes): Distance to intersection
     * - materialId (4 bytes): Material identifier
     * - padding (4 bytes): Alignment padding
     * - normal (12 bytes): Surface normal at hit point as vec3
     * - padding2 (4 bytes): Alignment padding
     * - position (12 bytes): Hit position as vec3
     * - padding3 (4 bytes): Alignment padding
     */
    public static final StructLayout HIT_RESULT_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("hit"),
        ValueLayout.JAVA_FLOAT.withName("distance"),
        ValueLayout.JAVA_INT.withName("materialId"),
        ValueLayout.JAVA_INT.withName("padding"),
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("normal"),
        ValueLayout.JAVA_INT.withName("padding2"),
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("position"),
        ValueLayout.JAVA_INT.withName("padding3")
    ).withByteAlignment(16);
    
    // ================================================================================
    // Voxel Material Layout - GPU-compatible material properties
    // ================================================================================
    
    /**
     * Memory layout for voxel material properties (32 bytes, GPU-aligned).
     * Structure:
     * - albedo (16 bytes): RGBA color as vec4
     * - metallic (4 bytes): Metallic property
     * - roughness (4 bytes): Roughness property
     * - emission (4 bytes): Emission strength
     * - padding (4 bytes): Alignment padding
     */
    public static final StructLayout MATERIAL_LAYOUT = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("albedo"),
        ValueLayout.JAVA_FLOAT.withName("metallic"),
        ValueLayout.JAVA_FLOAT.withName("roughness"),
        ValueLayout.JAVA_FLOAT.withName("emission"),
        ValueLayout.JAVA_FLOAT.withName("padding")
    ).withByteAlignment(16);
    
    // ================================================================================
    // Compute Dispatch Layout - GPU compute parameters
    // ================================================================================
    
    /**
     * Memory layout for compute dispatch parameters (16 bytes, GPU-aligned).
     * Structure:
     * - workgroupsX (4 bytes): Number of workgroups in X dimension
     * - workgroupsY (4 bytes): Number of workgroups in Y dimension
     * - workgroupsZ (4 bytes): Number of workgroups in Z dimension
     * - flags (4 bytes): Dispatch flags and options
     */
    public static final StructLayout COMPUTE_DISPATCH_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("workgroupsX"),
        ValueLayout.JAVA_INT.withName("workgroupsY"),
        ValueLayout.JAVA_INT.withName("workgroupsZ"),
        ValueLayout.JAVA_INT.withName("flags")
    ).withByteAlignment(16);
    
    // ================================================================================
    // Utility Methods
    // ================================================================================
    
    /**
     * Calculates the byte size needed for an array of elements.
     */
    public static long calculateArraySize(MemoryLayout layout, long elementCount) {
        return layout.byteSize() * elementCount;
    }
    
    /**
     * Creates a sequence layout for an array of structures.
     */
    public static SequenceLayout createArrayLayout(StructLayout elementLayout, long elementCount) {
        return MemoryLayout.sequenceLayout(elementCount, elementLayout);
    }
    
    /**
     * Allocates memory for a single structure.
     */
    public static MemorySegment allocateStruct(Arena arena, StructLayout layout) {
        return arena.allocate(layout);
    }
    
    /**
     * Allocates memory for an array of structures.
     */
    public static MemorySegment allocateArray(Arena arena, StructLayout layout, long count) {
        return arena.allocate(layout, count);
    }
}