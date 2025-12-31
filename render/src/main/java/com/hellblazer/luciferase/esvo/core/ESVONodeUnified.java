package com.hellblazer.luciferase.esvo.core;

import java.nio.ByteBuffer;

/**
 * UNIFIED ESVO Node Structure - CUDA Reference Implementation
 * 
 * This is THE SINGLE node structure that must be used throughout the entire ESVO implementation.
 * It exactly matches the Laine & Karras 2010 CUDA reference implementation.
 * 
 * CRITICAL: This replaces all other node structures (ESVONode, OctreeNode, etc.)
 * 
 * Structure: 8 bytes total (int2 in CUDA)
 * - childDescriptor (32 bits):
 *   Bits 0-7:   leafmask (8 bits) - which children are leaves
 *   Bits 8-15:  childmask (8 bits) - which children exist
 *   Bit 16:     far flag (1 bit) - far pointer flag
 *   Bits 17-30: childptr (14 bits) - child pointer offset
 *   Bit 31:     valid flag (1 bit) - node validity
 * 
 * - contourDescriptor (32 bits):
 *   Bits 0-7:   contour_mask (8 bits) - which children have contours
 *   Bits 8-31:  contour_ptr (24 bits) - contour data pointer
 * 
 * IMPORTANT: All other node classes must be DELETED and replaced with this one.
 */
public final class ESVONodeUnified {

    /** Node size in bytes (int2 in CUDA = 8 bytes) */
    public static final int SIZE_BYTES = 8;

    // Node is exactly 8 bytes (int2 in CUDA)
    private int childDescriptor;    // First 32 bits
    private int contourDescriptor;  // Second 32 bits
    
    // === Bit masks for childDescriptor (CUDA REFERENCE) ===
    private static final int LEAF_MASK_BITS = 0xFF;             // Bits 0-7: leafmask
    private static final int CHILD_MASK_BITS = 0xFF00;          // Bits 8-15: childmask
    private static final int CHILD_MASK_SHIFT = 8;
    private static final int FAR_FLAG_BIT = 0x10000;            // Bit 16: far flag
    private static final int CHILD_PTR_MASK = 0x7FFE0000;       // Bits 17-30: childptr (14 bits)
    private static final int CHILD_PTR_SHIFT = 17;
    private static final int VALID_FLAG_BIT = 0x80000000;       // Bit 31: valid flag
    
    // === Bit masks for contourDescriptor ===
    private static final int CONTOUR_MASK_BITS = 0xFF;          // Bits 0-7: contour_mask
    private static final int CONTOUR_PTR_MASK = 0xFFFFFF00;     // Bits 8-31: contour_ptr (24 bits)
    private static final int CONTOUR_PTR_SHIFT = 8;
    
    // === Constructors ===
    
    /**
     * Create empty node
     */
    public ESVONodeUnified() {
        this.childDescriptor = 0;
        this.contourDescriptor = 0;
    }
    
    /**
     * Create node from raw descriptors (for loading from buffer)
     */
    public ESVONodeUnified(int childDescriptor, int contourDescriptor) {
        this.childDescriptor = childDescriptor;
        this.contourDescriptor = contourDescriptor;
    }
    
    /**
     * Create node from components
     */
    public ESVONodeUnified(byte leafMask, byte childMask, boolean isFar, 
                          int childPtr, byte contourMask, int contourPtr) {
        setLeafMask(leafMask);
        setChildMask(childMask);
        setFar(isFar);
        setChildPtr(childPtr);
        setContourMask(contourMask);
        setContourPtr(contourPtr);
        setValid(true); // New nodes are valid by default
    }
    
    // === Child Mask Operations (bits 8-15) ===
    
    /**
     * Get the child mask - which children exist.
     * This is THE mask used for sparse indexing in CUDA reference.
     */
    public int getChildMask() {
        return (childDescriptor & CHILD_MASK_BITS) >> CHILD_MASK_SHIFT;
    }
    
    public void setChildMask(int mask) {
        childDescriptor = (childDescriptor & ~CHILD_MASK_BITS) | 
                         ((mask & 0xFF) << CHILD_MASK_SHIFT);
    }
    
    /**
     * Check if a child exists at given index (0-7).
     * CRITICAL: This drives the sparse indexing algorithm.
     */
    public boolean hasChild(int childIdx) {
        if (childIdx < 0 || childIdx > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getChildMask() & (1 << childIdx)) != 0;
    }
    
    // === Leaf Mask Operations (bits 0-7) ===
    
    /**
     * Get the leaf mask - which children are leaf nodes.
     * CUDA reference: 1 = leaf, 0 = internal
     */
    public int getLeafMask() {
        return childDescriptor & LEAF_MASK_BITS;
    }
    
    public void setLeafMask(int mask) {
        childDescriptor = (childDescriptor & ~LEAF_MASK_BITS) | (mask & 0xFF);
    }
    
    /**
     * Check if a child is a leaf node.
     * CUDA reference: leafmask bit set = leaf node
     */
    public boolean isChildLeaf(int childIdx) {
        if (childIdx < 0 || childIdx > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getLeafMask() & (1 << childIdx)) != 0;
    }
    
    // === Child Pointer Operations (bits 17-30) ===
    
    /**
     * Get child pointer offset (14 bits).
     * This is the relative offset to the first child.
     */
    public int getChildPtr() {
        return (childDescriptor & CHILD_PTR_MASK) >> CHILD_PTR_SHIFT;
    }
    
    public void setChildPtr(int ptr) {
        if (ptr < 0 || ptr >= (1 << 14)) {
            throw new IllegalArgumentException("Child pointer must fit in 14 bits");
        }
        childDescriptor = (childDescriptor & ~CHILD_PTR_MASK) | 
                         (ptr << CHILD_PTR_SHIFT);
    }
    
    // === Far Flag Operations (bit 16) ===
    
    public boolean isFar() {
        return (childDescriptor & FAR_FLAG_BIT) != 0;
    }
    
    public void setFar(boolean far) {
        if (far) {
            childDescriptor |= FAR_FLAG_BIT;
        } else {
            childDescriptor &= ~FAR_FLAG_BIT;
        }
    }
    
    // === Valid Flag Operations (bit 31) ===
    
    public boolean isValid() {
        return (childDescriptor & VALID_FLAG_BIT) != 0;
    }
    
    public void setValid(boolean valid) {
        if (valid) {
            childDescriptor |= VALID_FLAG_BIT;
        } else {
            childDescriptor &= ~VALID_FLAG_BIT;
        }
    }
    
    // === Contour Operations ===
    
    public int getContourMask() {
        return contourDescriptor & CONTOUR_MASK_BITS;
    }
    
    public void setContourMask(int mask) {
        contourDescriptor = (contourDescriptor & ~CONTOUR_MASK_BITS) | (mask & 0xFF);
    }
    
    public int getContourPtr() {
        return (contourDescriptor & CONTOUR_PTR_MASK) >>> CONTOUR_PTR_SHIFT;
    }
    
    public void setContourPtr(int ptr) {
        if (ptr < 0 || ptr >= (1 << 24)) {
            throw new IllegalArgumentException("Contour pointer must fit in 24 bits");
        }
        contourDescriptor = (contourDescriptor & ~CONTOUR_PTR_MASK) | 
                           (ptr << CONTOUR_PTR_SHIFT);
    }
    
    public boolean hasContour(int childIdx) {
        if (childIdx < 0 || childIdx > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getContourMask() & (1 << childIdx)) != 0;
    }
    
    // === Sparse Indexing Algorithm (CUDA Reference) ===
    
    /**
     * Calculate the relative offset of a child within this node's children.
     * This is the popcount-based sparse indexing algorithm.
     * CUDA reference: popc8(child_masks & ((1 << child_idx) - 1))
     * 
     * @param childIdx The child index (0-7)
     * @return The relative offset (0-based) within this node's children
     */
    public int getChildOffset(int childIdx) {
        if (!hasChild(childIdx)) {
            throw new IllegalArgumentException("Child " + childIdx + " does not exist");
        }
        
        // Count how many children exist before this one
        int mask = getChildMask();
        int bitsBeforeChild = mask & ((1 << childIdx) - 1);
        return Integer.bitCount(bitsBeforeChild);
    }
    
    /**
     * Calculate the actual index of a child in the sparse array.
     * Uses relative child pointer: currentNodeIdx + relativeOffset + sparseOffset
     * NOTE: This method ignores far pointers. Use the overload with farPointers array
     * for octrees that may have far pointers.
     *
     * @param childIdx The child index (0-7)
     * @param currentNodeIdx The index of the current node in the array
     * @return The actual index in the node array
     */
    public int getChildIndex(int childIdx, int currentNodeIdx) {
        // Child is at: current node + relative offset + sparse offset
        return currentNodeIdx + getChildPtr() + getChildOffset(childIdx);
    }

    /**
     * Calculate the actual index of a child in the sparse array with far pointer support.
     * When isFar() is true, childPtr is an index into the farPointers array,
     * and farPointers[childPtr] contains the actual relative offset.
     *
     * @param childIdx The child index (0-7)
     * @param currentNodeIdx The index of the current node in the array
     * @param farPointers Array of far pointer offsets (can be null if no far pointers)
     * @return The actual index in the node array
     */
    public int getChildIndex(int childIdx, int currentNodeIdx, int[] farPointers) {
        int relativeOffset = getChildPtr();

        // If this is a far pointer, dereference to get the actual offset
        if (isFar() && farPointers != null && relativeOffset < farPointers.length) {
            relativeOffset = farPointers[relativeOffset];
        }

        // Child is at: current node + relative offset + sparse offset
        return currentNodeIdx + relativeOffset + getChildOffset(childIdx);
    }
    
    /**
     * Get the total number of children this node has.
     */
    public int getChildCount() {
        return Integer.bitCount(getChildMask());
    }
    
    // === Raw Access (for GPU transfer) ===
    
    public int getChildDescriptor() {
        return childDescriptor;
    }
    
    public int getContourDescriptor() {
        return contourDescriptor;
    }
    
    /**
     * Write this node to a ByteBuffer (for GPU transfer).
     * Writes exactly 8 bytes.
     */
    public void writeTo(ByteBuffer buffer) {
        buffer.putInt(childDescriptor);
        buffer.putInt(contourDescriptor);
    }
    
    /**
     * Read a node from a ByteBuffer.
     * Reads exactly 8 bytes.
     */
    public static ESVONodeUnified readFrom(ByteBuffer buffer) {
        int child = buffer.getInt();
        int contour = buffer.getInt();
        return new ESVONodeUnified(child, contour);
    }
    
    @Override
    public String toString() {
        return String.format("ESVONode[child=0x%08X, contour=0x%08X, " +
                           "childMask=%02X, leafMask=%02X, ptr=%d, far=%b]",
            childDescriptor, contourDescriptor,
            getChildMask(), getLeafMask(), getChildPtr(), isFar());
    }
}