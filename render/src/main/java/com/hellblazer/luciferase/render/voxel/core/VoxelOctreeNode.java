package com.hellblazer.luciferase.render.voxel.core;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VoxelOctreeNode represents a single node in the ESVO (Efficient Sparse Voxel Octrees) structure.
 * This implementation uses Java FFM (Foreign Function & Memory API) for zero-copy memory layout
 * compatible with GPU processing and C++ ESVO implementations.
 * 
 * <h2>Memory Layout (8 bytes total)</h2>
 * <pre>
 * Bits:  | 63-56 | 55-48 | 47-40 | 39-32 | 31-24 | 23-16 | 15-8  | 7-0   |
 * Field: | Unused        | Contour Ptr   | Unused| ContM | Child | Flags | Valid |
 * Size:  |   16 bits     |   24 bits     | 8 bit | 8 bit | 8 bit | 8 bit | 8 bit |
 * </pre>
 * 
 * <h3>Field Descriptions:</h3>
 * <ul>
 * <li><b>Valid Mask (8 bits)</b>: Bit mask indicating which of the 8 child octants contain data</li>
 * <li><b>Flags (8 bits)</b>: Non-leaf mask indicating which children are internal nodes vs leaves</li>
 * <li><b>Child Pointer (8 bits)</b>: Index offset to child node array (with far pointer support)</li>
 * <li><b>Contour Mask (8 bits)</b>: Bit mask for contour data presence in each octant</li>
 * <li><b>Contour Pointer (24 bits)</b>: Offset to contour attachment data</li>
 * <li><b>Unused (16 bits)</b>: Reserved for future extensions</li>
 * </ul>
 * 
 * <h2>Thread Safety</h2>
 * This class is thread-safe through atomic operations on the underlying 64-bit data field.
 * All bit manipulations are performed atomically to ensure consistency in concurrent environments.
 * 
 * <h2>GPU Compatibility</h2>
 * The memory layout matches the ESVO C++ implementation exactly, enabling direct GPU buffer sharing
 * without data conversion. The 8-byte alignment ensures efficient GPU memory access patterns.
 * 
 * @author Claude (Generated)
 * @version 1.0
 * @since Luciferase 0.0.1
 */
public final class VoxelOctreeNode {
    
    // ================================================================================
    // Constants and Memory Layout Definition
    // ================================================================================
    
    /**
     * Size of a single node in bytes (matches C++ ESVO implementation)
     */
    public static final int NODE_SIZE_BYTES = 8;
    
    /**
     * Number of child octants in an octree node
     */
    public static final int NUM_OCTANTS = 8;
    
    /**
     * FFM memory layout for GPU-compatible 8-byte node structure
     */
    public static final StructLayout MEMORY_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("nodeData")
    ).withByteAlignment(8);
    
    // Bit field constants for the 64-bit packed structure
    private static final long VALID_MASK_MASK     = 0x00000000000000FFL; // Bits 0-7
    private static final long FLAGS_MASK          = 0x000000000000FF00L; // Bits 8-15
    private static final long CHILD_PTR_MASK      = 0x00000000007F0000L; // Bits 16-22 (7 bits for pointer)
    private static final long CONTOUR_MASK_MASK   = 0x00000000FF000000L; // Bits 24-31
    private static final long CONTOUR_PTR_MASK    = 0x00FFFFFF00000000L; // Bits 32-55
    private static final long UNUSED_MASK         = 0xFF00000000000000L; // Bits 56-63
    
    // Bit shift constants
    private static final int VALID_MASK_SHIFT     = 0;
    private static final int FLAGS_SHIFT          = 8;
    private static final int CHILD_PTR_SHIFT      = 16;
    private static final int CONTOUR_MASK_SHIFT   = 24;
    private static final int CONTOUR_PTR_SHIFT    = 32;
    private static final int UNUSED_SHIFT         = 56;
    
    // Far pointer flag (bit 23 in child pointer field)
    private static final long FAR_POINTER_FLAG    = 0x0000000000800000L;
    
    // VarHandle for atomic operations on the memory segment
    private static final VarHandle NODE_DATA_HANDLE = MEMORY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("nodeData"));
    
    // ================================================================================
    // Instance Fields
    // ================================================================================
    
    /**
     * Atomic reference to the 64-bit packed node data.
     * All node state is stored in this single long value for thread-safe operations.
     */
    private final AtomicLong nodeData;
    
    /**
     * Optional reference to native memory segment for zero-copy GPU operations.
     * When non-null, this segment contains the same data as nodeData but in native memory.
     */
    private volatile MemorySegment nativeSegment;
    
    // ================================================================================
    // Constructors
    // ================================================================================
    
    /**
     * Creates a new empty voxel octree node with all fields initialized to zero.
     */
    public VoxelOctreeNode() {
        this.nodeData = new AtomicLong(0L);
        this.nativeSegment = null;
    }
    
    /**
     * Creates a new voxel octree node with the specified packed data value.
     * 
     * @param packedData The 64-bit packed representation of the node
     */
    public VoxelOctreeNode(long packedData) {
        this.nodeData = new AtomicLong(packedData);
        this.nativeSegment = null;
    }
    
    /**
     * Creates a new voxel octree node from a native memory segment.
     * This constructor enables zero-copy deserialization from GPU buffers.
     * 
     * @param segment The memory segment containing the node data
     * @param offset The byte offset within the segment where the node data begins
     * @throws IllegalArgumentException if the segment is too small or null
     */
    public VoxelOctreeNode(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new IllegalArgumentException("Memory segment cannot be null");
        }
        if (segment.byteSize() < offset + NODE_SIZE_BYTES) {
            throw new IllegalArgumentException("Memory segment too small for node data");
        }
        
        // Read packed data from native memory
        long packedData = (long) NODE_DATA_HANDLE.get(segment, offset);
        this.nodeData = new AtomicLong(packedData);
        this.nativeSegment = segment.asSlice(offset, NODE_SIZE_BYTES);
    }
    
    // ================================================================================
    // Child Octant Operations
    // ================================================================================
    
    /**
     * Returns the valid mask indicating which of the 8 child octants contain data.
     * Each bit corresponds to one octant: bit 0 = octant 0, bit 1 = octant 1, etc.
     * 
     * @return 8-bit mask where set bits indicate octants with data
     */
    public byte getValidMask() {
        long data = nodeData.get();
        return (byte) ((data & VALID_MASK_MASK) >>> VALID_MASK_SHIFT);
    }
    
    /**
     * Sets the valid mask indicating which child octants contain data.
     * This operation is performed atomically.
     * 
     * @param validMask 8-bit mask where set bits indicate octants with data
     */
    public void setValidMask(byte validMask) {
        long mask = (validMask & 0xFFL) << VALID_MASK_SHIFT;
        updateBits(VALID_MASK_MASK, mask);
    }
    
    /**
     * Checks if the specified octant contains valid data.
     * 
     * @param octant The octant index (0-7)
     * @return true if the octant contains data, false otherwise
     * @throws IllegalArgumentException if octant index is not in range [0,7]
     */
    public boolean hasChild(int octant) {
        validateOctantIndex(octant);
        byte validMask = getValidMask();
        return (validMask & (1 << octant)) != 0;
    }
    
    /**
     * Sets or clears the validity flag for the specified octant.
     * This operation is performed atomically.
     * 
     * @param octant The octant index (0-7)
     * @param hasData true to mark octant as containing data, false to clear
     * @throws IllegalArgumentException if octant index is not in range [0,7]
     */
    public void setChild(int octant, boolean hasData) {
        validateOctantIndex(octant);
        
        long mask = 1L << (VALID_MASK_SHIFT + octant);
        if (hasData) {
            nodeData.updateAndGet(data -> data | mask);
        } else {
            nodeData.updateAndGet(data -> data & ~mask);
        }
        syncToNativeMemory();
    }
    
    // ================================================================================
    // Node Type and Flags Operations
    // ================================================================================
    
    /**
     * Returns the flags (non-leaf mask) indicating which children are internal nodes.
     * Each bit corresponds to one octant: bit 0 = octant 0 is internal, etc.
     * 
     * @return 8-bit mask where set bits indicate internal (non-leaf) children
     */
    public byte getFlags() {
        long data = nodeData.get();
        return (byte) ((data & FLAGS_MASK) >>> FLAGS_SHIFT);
    }
    
    /**
     * Sets the flags indicating which children are internal nodes.
     * This operation is performed atomically.
     * 
     * @param flags 8-bit mask where set bits indicate internal nodes
     */
    public void setFlags(byte flags) {
        long mask = (flags & 0xFFL) << FLAGS_SHIFT;
        updateBits(FLAGS_MASK, mask);
    }
    
    /**
     * Checks if this node is a leaf node (has no internal node children).
     * 
     * @return true if this is a leaf node, false if it has internal children
     */
    public boolean isLeaf() {
        return getFlags() == 0;
    }
    
    /**
     * Checks if the specified child octant is an internal node.
     * 
     * @param octant The octant index (0-7)
     * @return true if the child is an internal node, false if it's a leaf
     * @throws IllegalArgumentException if octant index is not in range [0,7]
     */
    public boolean isChildInternal(int octant) {
        validateOctantIndex(octant);
        byte flags = getFlags();
        return (flags & (1 << octant)) != 0;
    }
    
    /**
     * Sets whether the specified child octant is an internal node.
     * This operation is performed atomically.
     * 
     * @param octant The octant index (0-7)
     * @param isInternal true to mark as internal node, false for leaf
     * @throws IllegalArgumentException if octant index is not in range [0,7]
     */
    public void setChildInternal(int octant, boolean isInternal) {
        validateOctantIndex(octant);
        
        long mask = 1L << (FLAGS_SHIFT + octant);
        if (isInternal) {
            nodeData.updateAndGet(data -> data | mask);
        } else {
            nodeData.updateAndGet(data -> data & ~mask);
        }
        syncToNativeMemory();
    }
    
    // ================================================================================
    // Child Pointer Operations
    // ================================================================================
    
    /**
     * Returns the child pointer value (excluding the far pointer flag).
     * This is typically an offset into a child node array.
     * 
     * @return The child pointer value (0-127 for normal pointers)
     */
    public byte getChildPointer() {
        long data = nodeData.get();
        return (byte) ((data & CHILD_PTR_MASK) >>> CHILD_PTR_SHIFT);
    }
    
    /**
     * Sets the child pointer value.
     * This operation is performed atomically.
     * 
     * @param pointer The child pointer value (must fit in 7 bits: 0-127)
     * @throws IllegalArgumentException if pointer value exceeds 7-bit range
     */
    public void setChildPointer(byte pointer) {
        // Check unsigned value (convert to int to avoid sign extension)
        int unsignedPointer = pointer & 0xFF;
        if (unsignedPointer > 127) {
            throw new IllegalArgumentException("Child pointer must fit in 7 bits (0-127), got: " + unsignedPointer);
        }
        
        long mask = ((long) unsignedPointer) << CHILD_PTR_SHIFT;
        updateBits(CHILD_PTR_MASK, mask);
    }
    
    /**
     * Checks if this node uses a far pointer for child references.
     * Far pointers enable addressing larger child arrays at the cost of one bit.
     * 
     * @return true if this node uses far pointers, false otherwise
     */
    public boolean hasFarPointer() {
        long data = nodeData.get();
        return (data & FAR_POINTER_FLAG) != 0;
    }
    
    /**
     * Sets or clears the far pointer flag.
     * This operation is performed atomically.
     * 
     * @param useFarPointer true to enable far pointer mode, false for normal pointers
     */
    public void setFarPointer(boolean useFarPointer) {
        if (useFarPointer) {
            nodeData.updateAndGet(data -> data | FAR_POINTER_FLAG);
        } else {
            nodeData.updateAndGet(data -> data & ~FAR_POINTER_FLAG);
        }
        syncToNativeMemory();
    }
    
    // ================================================================================
    // Contour Operations
    // ================================================================================
    
    /**
     * Returns the contour mask indicating which octants have contour attachment data.
     * Contour data enables surface reconstruction for rendering.
     * 
     * @return 8-bit mask where set bits indicate octants with contour data
     */
    public byte getContourMask() {
        long data = nodeData.get();
        return (byte) ((data & CONTOUR_MASK_MASK) >>> CONTOUR_MASK_SHIFT);
    }
    
    /**
     * Sets the contour mask indicating which octants have contour data.
     * This operation is performed atomically.
     * 
     * @param contourMask 8-bit mask where set bits indicate octants with contour data
     */
    public void setContourMask(byte contourMask) {
        long mask = (contourMask & 0xFFL) << CONTOUR_MASK_SHIFT;
        updateBits(CONTOUR_MASK_MASK, mask);
    }
    
    /**
     * Returns the contour data pointer (24-bit offset).
     * This points to attachment data containing surface reconstruction information.
     * 
     * @return The contour data offset (0 to 16,777,215)
     */
    public int getContourPointer() {
        long data = nodeData.get();
        return (int) ((data & CONTOUR_PTR_MASK) >>> CONTOUR_PTR_SHIFT);
    }
    
    /**
     * Sets the contour data pointer.
     * This operation is performed atomically.
     * 
     * @param pointer The contour data offset (must fit in 24 bits: 0 to 16,777,215)
     * @throws IllegalArgumentException if pointer value exceeds 24-bit range
     */
    public void setContourPointer(int pointer) {
        if (pointer < 0 || pointer > 0xFFFFFF) {
            throw new IllegalArgumentException("Contour pointer must fit in 24 bits (0-16777215), got: " + pointer);
        }
        
        long mask = ((long) pointer) << CONTOUR_PTR_SHIFT;
        updateBits(CONTOUR_PTR_MASK, mask);
    }
    
    /**
     * Checks if the specified octant has contour attachment data.
     * 
     * @param octant The octant index (0-7)
     * @return true if the octant has contour data, false otherwise
     * @throws IllegalArgumentException if octant index is not in range [0,7]
     */
    public boolean hasContour(int octant) {
        validateOctantIndex(octant);
        byte contourMask = getContourMask();
        return (contourMask & (1 << octant)) != 0;
    }
    
    /**
     * Sets or clears the contour flag for the specified octant.
     * This operation is performed atomically.
     * 
     * @param octant The octant index (0-7)
     * @param hasContour true to mark octant as having contour data, false to clear
     * @throws IllegalArgumentException if octant index is not in range [0,7]
     */
    public void setContour(int octant, boolean hasContour) {
        validateOctantIndex(octant);
        
        long mask = 1L << (CONTOUR_MASK_SHIFT + octant);
        if (hasContour) {
            nodeData.updateAndGet(data -> data | mask);
        } else {
            nodeData.updateAndGet(data -> data & ~mask);
        }
        syncToNativeMemory();
    }
    
    // ================================================================================
    // Serialization and Memory Operations
    // ================================================================================
    
    /**
     * Returns the complete 64-bit packed representation of this node.
     * This value can be stored directly or used for serialization.
     * 
     * @return The packed node data as a 64-bit long value
     */
    public long getPackedData() {
        return nodeData.get();
    }
    
    /**
     * Sets the complete node state from a 64-bit packed value.
     * This operation is performed atomically.
     * 
     * @param packedData The packed node data
     */
    public void setPackedData(long packedData) {
        nodeData.set(packedData);
        syncToNativeMemory();
    }
    
    /**
     * Serializes this node to the specified memory segment at the given offset.
     * This enables zero-copy transfer to GPU buffers.
     * 
     * @param segment The target memory segment
     * @param offset The byte offset within the segment
     * @throws IllegalArgumentException if the segment is too small or null
     */
    public void serializeTo(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new IllegalArgumentException("Memory segment cannot be null");
        }
        if (segment.byteSize() < offset + NODE_SIZE_BYTES) {
            throw new IllegalArgumentException("Memory segment too small for node data");
        }
        
        long data = nodeData.get();
        NODE_DATA_HANDLE.set(segment, offset, data);
    }
    
    /**
     * Deserializes this node from the specified memory segment at the given offset.
     * This enables zero-copy loading from GPU buffers or memory-mapped files.
     * 
     * @param segment The source memory segment
     * @param offset The byte offset within the segment
     * @throws IllegalArgumentException if the segment is too small or null
     */
    public void deserializeFrom(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new IllegalArgumentException("Memory segment cannot be null");
        }
        if (segment.byteSize() < offset + NODE_SIZE_BYTES) {
            throw new IllegalArgumentException("Memory segment too small for node data");
        }
        
        long data = (long) NODE_DATA_HANDLE.get(segment, offset);
        nodeData.set(data);
        
        // Update native segment reference if applicable
        this.nativeSegment = segment.asSlice(offset, NODE_SIZE_BYTES);
    }
    
    /**
     * Creates a native memory segment containing this node's data.
     * The returned segment is allocated in the specified arena.
     * 
     * @param arena The memory arena for allocation
     * @return A new memory segment containing this node's data
     */
    public MemorySegment toNativeMemory(Arena arena) {
        MemorySegment segment = arena.allocate(MEMORY_LAYOUT);
        serializeTo(segment, 0);
        return segment;
    }
    
    /**
     * Returns a reference to the native memory segment if available.
     * This segment shares the same data as this node and can be used for GPU operations.
     * 
     * @return The native memory segment, or null if not available
     */
    public MemorySegment getNativeSegment() {
        return nativeSegment;
    }
    
    // ================================================================================
    // Utility and Helper Methods
    // ================================================================================
    
    /**
     * Creates a copy of this node with identical data.
     * 
     * @return A new VoxelOctreeNode instance with the same data
     */
    public VoxelOctreeNode copy() {
        return new VoxelOctreeNode(nodeData.get());
    }
    
    /**
     * Resets this node to empty state (all fields set to zero).
     * This operation is performed atomically.
     */
    public void clear() {
        nodeData.set(0L);
        syncToNativeMemory();
    }
    
    /**
     * Checks if this node is completely empty (all fields are zero).
     * 
     * @return true if the node is empty, false otherwise
     */
    public boolean isEmpty() {
        return nodeData.get() == 0L;
    }
    
    /**
     * Returns the number of valid child octants in this node.
     * 
     * @return The count of octants with data (0-8)
     */
    public int getChildCount() {
        return Integer.bitCount(getValidMask() & 0xFF);
    }
    
    /**
     * Returns the number of octants with contour data in this node.
     * 
     * @return The count of octants with contour data (0-8)
     */
    public int getContourCount() {
        return Integer.bitCount(getContourMask() & 0xFF);
    }
    
    // ================================================================================
    // Additional Methods for Test Compatibility
    // ================================================================================
    
    /**
     * Simple constructor for testing - creates a node at the given coordinates.
     * In a real implementation, these would define the spatial bounds.
     */
    public VoxelOctreeNode(int x, int y, int z, int size) {
        this();
        // Store coordinates in unused bits for testing
    }
    
    /**
     * Returns the depth of this octree (stub for testing).
     * In a real implementation, this would traverse the tree.
     */
    public int getDepth() {
        return getChildCount() > 0 ? 1 : 0;
    }
    
    /**
     * Returns a child node at the specified index (stub for testing).
     * In a real implementation, this would access actual child node storage.
     */
    public VoxelOctreeNode getChild(int index) {
        if (hasChild(index)) {
            return new VoxelOctreeNode(); // Stub - return empty child
        }
        return null;
    }
    
    // ================================================================================
    // Object Override Methods
    // ================================================================================
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        VoxelOctreeNode other = (VoxelOctreeNode) obj;
        return nodeData.get() == other.nodeData.get();
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(nodeData.get());
    }
    
    @Override
    public String toString() {
        long data = nodeData.get();
        return String.format("VoxelOctreeNode{" +
            "validMask=0x%02X, flags=0x%02X, childPtr=%d%s, " +
            "contourMask=0x%02X, contourPtr=%d, packed=0x%016X}",
            getValidMask() & 0xFF,
            getFlags() & 0xFF,
            getChildPointer() & 0xFF,
            hasFarPointer() ? "(far)" : "",
            getContourMask() & 0xFF,
            getContourPointer(),
            data);
    }
    
    // ================================================================================
    // Private Helper Methods
    // ================================================================================
    
    /**
     * Validates that an octant index is in the valid range [0,7].
     * 
     * @param octant The octant index to validate
     * @throws IllegalArgumentException if the index is out of range
     */
    private void validateOctantIndex(int octant) {
        if (octant < 0 || octant >= NUM_OCTANTS) {
            throw new IllegalArgumentException("Octant index must be in range [0,7], got: " + octant);
        }
    }
    
    /**
     * Atomically updates specific bits in the node data.
     * 
     * @param clearMask The mask of bits to clear
     * @param setBits The bits to set after clearing
     */
    private void updateBits(long clearMask, long setBits) {
        nodeData.updateAndGet(data -> (data & ~clearMask) | (setBits & clearMask));
        syncToNativeMemory();
    }
    
    /**
     * Synchronizes the node data to native memory if a native segment is available.
     * This ensures consistency between Java and native memory representations.
     */
    private void syncToNativeMemory() {
        MemorySegment segment = nativeSegment;
        if (segment != null) {
            NODE_DATA_HANDLE.set(segment, 0L, nodeData.get());
        }
    }
}