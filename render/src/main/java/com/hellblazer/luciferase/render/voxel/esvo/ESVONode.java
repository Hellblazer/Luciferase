package com.hellblazer.luciferase.render.voxel.esvo;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ESVO-compatible octree node with exact 8-byte binary format.
 * 
 * Memory layout (little-endian):
 * Word 0 (32 bits):
 *   Bits 0-7:   Valid mask (which children exist)
 *   Bits 8-15:  Non-leaf mask (which children are internal nodes)
 *   Bit 16:     Far pointer flag
 *   Bits 17-31: Child pointer (15 bits)
 * 
 * Word 1 (32 bits):
 *   Bits 0-7:   Contour mask (which children have contours)
 *   Bits 8-31:  Contour pointer (24 bits)
 * 
 * This format matches NVIDIA's ESVO implementation exactly for binary compatibility.
 */
public class ESVONode {
    
    // Constants for bit manipulation
    private static final int VALID_MASK_SHIFT = 0;
    private static final int VALID_MASK_BITS = 0xFF;
    
    private static final int NON_LEAF_MASK_SHIFT = 8;
    private static final int NON_LEAF_MASK_BITS = 0xFF;
    
    private static final int FAR_FLAG_SHIFT = 16;
    private static final int FAR_FLAG_BIT = 1;
    
    private static final int CHILD_PTR_SHIFT = 17;
    private static final int CHILD_PTR_BITS = 0x7FFF; // 15 bits
    
    private static final int CONTOUR_MASK_SHIFT = 0;
    private static final int CONTOUR_MASK_BITS = 0xFF;
    
    private static final int CONTOUR_PTR_SHIFT = 8;
    private static final int CONTOUR_PTR_BITS = 0xFFFFFF; // 24 bits
    
    // Storage - either direct memory or heap
    private final MemorySegment memory;
    private final boolean ownedMemory;
    private int word0;
    private int word1;
    
    /**
     * Creates a new ESVO node with heap storage.
     */
    public ESVONode() {
        this.memory = null;
        this.ownedMemory = false;
        this.word0 = 0;
        this.word1 = 0;
    }
    
    /**
     * Creates a new ESVO node backed by native memory.
     * 
     * @param memory Memory segment of at least 8 bytes
     */
    public ESVONode(MemorySegment memory) {
        if (memory.byteSize() < 8) {
            throw new IllegalArgumentException("Memory segment must be at least 8 bytes");
        }
        this.memory = memory;
        this.ownedMemory = false;
        loadFromMemory();
    }
    
    /**
     * Creates a node from a ByteBuffer.
     * 
     * @param buffer Buffer positioned at node data
     * @return New ESVONode instance
     */
    public static ESVONode readFrom(ByteBuffer buffer) {
        var node = new ESVONode();
        node.word0 = buffer.getInt();
        node.word1 = buffer.getInt();
        return node;
    }
    
    /**
     * Writes this node to a ByteBuffer.
     * 
     * @param buffer Buffer to write to (must have at least 8 bytes remaining)
     */
    public void writeTo(ByteBuffer buffer) {
        syncToMemory();
        buffer.putInt(word0);
        buffer.putInt(word1);
    }
    
    /**
     * Gets the size of this node in bytes.
     * 
     * @return Always returns 8
     */
    public int getSizeInBytes() {
        return 8;
    }
    
    // Valid mask operations
    
    public byte getValidMask() {
        return (byte)((word0 >> VALID_MASK_SHIFT) & VALID_MASK_BITS);
    }
    
    public void setValidMask(byte mask) {
        word0 = (word0 & ~(VALID_MASK_BITS << VALID_MASK_SHIFT)) | 
                ((mask & VALID_MASK_BITS) << VALID_MASK_SHIFT);
        syncToMemory();
    }
    
    public boolean hasChild(int index) {
        if (index < 0 || index >= 8) return false;
        return (getValidMask() & (1 << index)) != 0;
    }
    
    public void setChildValid(int index, boolean valid) {
        byte mask = getValidMask();
        if (valid) {
            mask |= (1 << index);
        } else {
            mask &= ~(1 << index);
        }
        setValidMask(mask);
    }
    
    // Non-leaf mask operations
    
    public byte getNonLeafMask() {
        return (byte)((word0 >> NON_LEAF_MASK_SHIFT) & NON_LEAF_MASK_BITS);
    }
    
    public void setNonLeafMask(byte mask) {
        word0 = (word0 & ~(NON_LEAF_MASK_BITS << NON_LEAF_MASK_SHIFT)) | 
                ((mask & NON_LEAF_MASK_BITS) << NON_LEAF_MASK_SHIFT);
        syncToMemory();
    }
    
    public boolean isChildInternal(int index) {
        if (index < 0 || index >= 8) return false;
        return hasChild(index) && (getNonLeafMask() & (1 << index)) != 0;
    }
    
    public boolean isChildLeaf(int index) {
        if (index < 0 || index >= 8) return false;
        return hasChild(index) && (getNonLeafMask() & (1 << index)) == 0;
    }
    
    // Child pointer operations
    
    public int getChildPointer() {
        return (word0 >> CHILD_PTR_SHIFT) & CHILD_PTR_BITS;
    }
    
    public void setChildPointer(int pointer, boolean far) {
        // Clear existing pointer and far flag
        word0 &= ~((CHILD_PTR_BITS << CHILD_PTR_SHIFT) | (FAR_FLAG_BIT << FAR_FLAG_SHIFT));
        
        // Set new pointer
        word0 |= (pointer & CHILD_PTR_BITS) << CHILD_PTR_SHIFT;
        
        // Set far flag
        if (far) {
            word0 |= FAR_FLAG_BIT << FAR_FLAG_SHIFT;
        }
        
        syncToMemory();
    }
    
    public boolean isFarPointer() {
        return ((word0 >> FAR_FLAG_SHIFT) & FAR_FLAG_BIT) != 0;
    }
    
    /**
     * Computes the address of a specific child.
     * 
     * @param baseAddr Base address of the node storage
     * @param childIndex Index of the child (0-7)
     * @return Address of the child node
     */
    public int getChildAddress(int baseAddr, int childIndex) {
        int pointer = getChildPointer();
        
        if (isFarPointer()) {
            // Far pointers are indices into far pointer table
            // The actual resolution happens at runtime
            return pointer;
        } else {
            // Near pointers are qword (8-byte) offsets
            return baseAddr + (pointer + childIndex) * 8;
        }
    }
    
    // Contour mask operations
    
    public byte getContourMask() {
        return (byte)((word1 >> CONTOUR_MASK_SHIFT) & CONTOUR_MASK_BITS);
    }
    
    public void setContourMask(byte mask) {
        word1 = (word1 & ~(CONTOUR_MASK_BITS << CONTOUR_MASK_SHIFT)) | 
                ((mask & CONTOUR_MASK_BITS) << CONTOUR_MASK_SHIFT);
        syncToMemory();
    }
    
    public boolean hasContour(int index) {
        if (index < 0 || index >= 8) return false;
        return (getContourMask() & (1 << index)) != 0;
    }
    
    public void setContourPresent(int index, boolean present) {
        byte mask = getContourMask();
        if (present) {
            mask |= (1 << index);
        } else {
            mask &= ~(1 << index);
        }
        setContourMask(mask);
    }
    
    // Contour pointer operations
    
    public int getContourPointer() {
        return (word1 >> CONTOUR_PTR_SHIFT) & CONTOUR_PTR_BITS;
    }
    
    public void setContourPointer(int pointer) {
        word1 = (word1 & ~(CONTOUR_PTR_BITS << CONTOUR_PTR_SHIFT)) | 
                ((pointer & CONTOUR_PTR_BITS) << CONTOUR_PTR_SHIFT);
        syncToMemory();
    }
    
    /**
     * Gets the address of contour data for a specific child.
     * 
     * @param baseAddr Base address of contour storage
     * @param childIndex Index of the child (0-7)
     * @return Address of the contour data, or -1 if no contour
     */
    public int getContourAddress(int baseAddr, int childIndex) {
        if (!hasContour(childIndex)) {
            return -1;
        }
        
        // Count how many contours come before this child
        int contourIndex = 0;
        byte mask = getContourMask();
        for (int i = 0; i < childIndex; i++) {
            if ((mask & (1 << i)) != 0) {
                contourIndex++;
            }
        }
        
        // Contour pointers are dword (4-byte) offsets
        return baseAddr + (getContourPointer() + contourIndex) * 4;
    }
    
    // Memory synchronization
    
    private void loadFromMemory() {
        if (memory != null) {
            word0 = memory.get(ValueLayout.JAVA_INT, 0);
            word1 = memory.get(ValueLayout.JAVA_INT, 4);
        }
    }
    
    private void syncToMemory() {
        if (memory != null) {
            memory.set(ValueLayout.JAVA_INT, 0, word0);
            memory.set(ValueLayout.JAVA_INT, 4, word1);
        }
    }
    
    // Utility methods
    
    public boolean isEmpty() {
        return getValidMask() == 0;
    }
    
    public int getChildCount() {
        return Integer.bitCount(getValidMask() & 0xFF);
    }
    
    public int getInternalChildCount() {
        return Integer.bitCount(getNonLeafMask() & getValidMask() & 0xFF);
    }
    
    public int getLeafChildCount() {
        return Integer.bitCount(getValidMask() & ~getNonLeafMask() & 0xFF);
    }
    
    public int getContourCount() {
        return Integer.bitCount(getContourMask() & 0xFF);
    }
    
    /**
     * Serialize node to bytes for GPU upload
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(word0);
        buffer.putInt(word1);
        return buffer.array();
    }
    
    @Override
    public String toString() {
        return String.format("ESVONode[valid=%02X, nonLeaf=%02X, ptr=%04X%s, contour=%02X, cPtr=%06X]",
                getValidMask() & 0xFF,
                getNonLeafMask() & 0xFF,
                getChildPointer(),
                isFarPointer() ? "F" : "",
                getContourMask() & 0xFF,
                getContourPointer());
    }
}