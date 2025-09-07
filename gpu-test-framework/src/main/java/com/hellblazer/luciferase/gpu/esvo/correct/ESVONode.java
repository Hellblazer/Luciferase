package com.hellblazer.luciferase.gpu.esvo.correct;

import java.nio.ByteBuffer;

/**
 * Correct ESVO node structure matching the reference CUDA implementation.
 * 
 * From Laine & Karras 2010 "Efficient Sparse Voxel Octrees"
 * Reference: src/octree/cuda/Raycast.inl
 * 
 * Node is exactly 8 bytes (int2 in CUDA):
 * - childDescriptor (4 bytes): packed child information
 * - contourDescriptor (4 bytes): packed contour information
 */
public class ESVONode {
    // First 4 bytes - child descriptor
    // Bit layout: [valid(1)|far(1)|dummy(1)|childptr(14)|childmask(8)|leafmask(8)]
    private int childDescriptor;
    
    // Second 4 bytes - contour descriptor  
    // Bit layout: [contour_ptr(24)|contour_mask(8)]
    private int contourDescriptor;
    
    // Constants for bit manipulation
    private static final int CHILD_MASK_BITS = 8;
    private static final int LEAF_MASK_BITS = 8;
    private static final int CHILD_PTR_BITS = 14;
    private static final int CONTOUR_PTR_BITS = 24;
    private static final int CONTOUR_MASK_BITS = 8;
    
    // Bit masks
    private static final int CHILD_MASK_MASK = 0xFF00;
    private static final int LEAF_MASK_MASK = 0xFF;
    private static final int CHILD_PTR_MASK = 0x7FFF << 17;
    private static final int FAR_BIT = 1 << 16;
    private static final int VALID_BIT = 1 << 31;
    private static final int CONTOUR_MASK_MASK = 0xFF;
    private static final int CONTOUR_PTR_MASK = 0xFFFFFF00;
    
    /**
     * Create an empty node
     */
    public ESVONode() {
        this.childDescriptor = 0;
        this.contourDescriptor = 0;
    }
    
    /**
     * Create a node from raw descriptors
     */
    public ESVONode(int childDescriptor, int contourDescriptor) {
        this.childDescriptor = childDescriptor;
        this.contourDescriptor = contourDescriptor;
    }
    
    // Child descriptor accessors
    
    public int getChildMask() {
        return (childDescriptor & CHILD_MASK_MASK) >> 8;
    }
    
    public void setChildMask(int mask) {
        childDescriptor = (childDescriptor & ~CHILD_MASK_MASK) | ((mask & 0xFF) << 8);
    }
    
    public int getLeafMask() {
        return childDescriptor & LEAF_MASK_MASK;
    }
    
    public void setLeafMask(int mask) {
        childDescriptor = (childDescriptor & ~LEAF_MASK_MASK) | (mask & 0xFF);
    }
    
    public int getChildPointer() {
        return (childDescriptor >> 17) & 0x3FFF;
    }
    
    public void setChildPointer(int ptr) {
        childDescriptor = (childDescriptor & ~CHILD_PTR_MASK) | ((ptr & 0x3FFF) << 17);
    }
    
    public boolean isFar() {
        return (childDescriptor & FAR_BIT) != 0;
    }
    
    public void setFar(boolean far) {
        if (far) {
            childDescriptor |= FAR_BIT;
        } else {
            childDescriptor &= ~FAR_BIT;
        }
    }
    
    public boolean isValid() {
        return (childDescriptor & VALID_BIT) != 0;
    }
    
    public void setValid(boolean valid) {
        if (valid) {
            childDescriptor |= VALID_BIT;
        } else {
            childDescriptor &= ~VALID_BIT;
        }
    }
    
    public boolean hasChild(int idx) {
        return (getChildMask() & (1 << idx)) != 0;
    }
    
    public boolean isLeaf(int idx) {
        return (getLeafMask() & (1 << idx)) == 0;
    }
    
    // Contour descriptor accessors
    
    public int getContourMask() {
        return contourDescriptor & CONTOUR_MASK_MASK;
    }
    
    public void setContourMask(int mask) {
        contourDescriptor = (contourDescriptor & ~CONTOUR_MASK_MASK) | (mask & 0xFF);
    }
    
    public int getContourPointer() {
        return (contourDescriptor >> 8) & 0xFFFFFF;
    }
    
    public void setContourPointer(int ptr) {
        contourDescriptor = (contourDescriptor & ~CONTOUR_PTR_MASK) | ((ptr & 0xFFFFFF) << 8);
    }
    
    public boolean hasContour(int idx) {
        return (getContourMask() & (1 << idx)) != 0;
    }
    
    // Sparse child indexing - THE CRITICAL ALGORITHM
    
    /**
     * Calculate the child node index using sparse indexing.
     * This is the CORRECT algorithm from the reference implementation.
     * 
     * @param childIdx The child slot (0-7)
     * @return The actual index in the node array, or -1 if child doesn't exist
     */
    public int getChildNodeIndex(int childIdx) {
        if (!hasChild(childIdx)) {
            return -1;
        }
        
        // Count bits set before this child (popcount)
        int mask = getChildMask();
        int bitsBeforeChild = Integer.bitCount(mask & ((1 << childIdx) - 1));
        
        // Child index = base pointer + popcount
        return getChildPointer() + bitsBeforeChild;
    }
    
    /**
     * Get the number of children this node has
     */
    public int getChildCount() {
        return Integer.bitCount(getChildMask());
    }
    
    // Serialization
    
    /**
     * Write this node to a ByteBuffer (8 bytes)
     */
    public void toBuffer(ByteBuffer buffer) {
        buffer.putInt(childDescriptor);
        buffer.putInt(contourDescriptor);
    }
    
    /**
     * Read a node from a ByteBuffer
     */
    public static ESVONode fromBuffer(ByteBuffer buffer) {
        int childDesc = buffer.getInt();
        int contourDesc = buffer.getInt();
        return new ESVONode(childDesc, contourDesc);
    }
    
    /**
     * Size of a node in bytes
     */
    public static final int SIZE_BYTES = 8;
    
    @Override
    public String toString() {
        return String.format("ESVONode[childMask=%02X, leafMask=%02X, childPtr=%d, far=%b, valid=%b, children=%d]",
            getChildMask(), getLeafMask(), getChildPointer(), isFar(), isValid(), getChildCount());
    }
}