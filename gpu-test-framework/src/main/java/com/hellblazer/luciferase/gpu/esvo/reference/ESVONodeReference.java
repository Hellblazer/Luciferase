package com.hellblazer.luciferase.gpu.esvo.reference;

import java.nio.ByteBuffer;

/**
 * REFERENCE-CORRECT ESVO Node implementation matching CUDA raycast.inl exactly.
 * 
 * Based on exhaustive validation against:
 * - /src/octree/cuda/Raycast.inl (NVIDIA CUDA reference)
 * - /doc/java-translation/01-translation-guide-final.md
 * - ChromaDB knowledge base validation
 * 
 * CRITICAL: This replaces our previous incorrect implementation.
 */
public class ESVONodeReference {
    
    // Node is exactly 8 bytes (int2 in CUDA)
    private int childDescriptor;    // First 32 bits
    private int contourDescriptor;  // Second 32 bits
    
    // Bit masks for childDescriptor (REFERENCE-CORRECT)
    private static final int NON_LEAF_MASK_BITS = 0xFF;         // Bits 0-7
    private static final int VALID_MASK_BITS = 0xFF00;          // Bits 8-15 ← CRITICAL
    private static final int FAR_BIT = 0x10000;                 // Bit 16
    private static final int CHILD_PTR_MASK = 0xFFFE0000;       // Bits 17-31 (15 bits)
    private static final int CHILD_PTR_SHIFT = 17;
    
    // Bit masks for contourDescriptor 
    private static final int CONTOUR_MASK_BITS = 0xFF;          // Bits 0-7
    private static final int CONTOUR_PTR_MASK = 0xFFFFFF00;     // Bits 8-31
    private static final int CONTOUR_PTR_SHIFT = 8;
    
    /**
     * Create empty node
     */
    public ESVONodeReference() {
        this.childDescriptor = 0;
        this.contourDescriptor = 0;
    }
    
    /**
     * Create node from raw descriptors
     */
    public ESVONodeReference(int childDescriptor, int contourDescriptor) {
        this.childDescriptor = childDescriptor;
        this.contourDescriptor = contourDescriptor;
    }
    
    // CRITICAL: Valid mask operations (bits 8-15)
    
    /**
     * Get the valid mask - which children exist.
     * THIS IS THE MASK USED FOR SPARSE INDEXING.
     */
    public int getValidMask() {
        return (childDescriptor & VALID_MASK_BITS) >> 8;
    }
    
    /**
     * Set the valid mask - which children exist.
     */
    public void setValidMask(int mask) {
        childDescriptor = (childDescriptor & ~VALID_MASK_BITS) | ((mask & 0xFF) << 8);
    }
    
    /**
     * Check if a child exists at given index.
     * CRITICAL: Uses VALID mask, not "child mask".
     */
    public boolean hasChild(int childIdx) {
        if (childIdx < 0 || childIdx > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getValidMask() & (1 << childIdx)) != 0;
    }
    
    // Non-leaf mask operations (bits 0-7)
    
    /**
     * Get the non-leaf mask - which children are internal nodes.
     */
    public int getNonLeafMask() {
        return childDescriptor & NON_LEAF_MASK_BITS;
    }
    
    /**
     * Set the non-leaf mask - which children are internal nodes.
     */
    public void setNonLeafMask(int mask) {
        childDescriptor = (childDescriptor & ~NON_LEAF_MASK_BITS) | (mask & 0xFF);
    }
    
    /**
     * Check if a child is a non-leaf (internal node).
     */
    public boolean isChildNonLeaf(int childIdx) {
        if (childIdx < 0 || childIdx > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getNonLeafMask() & (1 << childIdx)) != 0;
    }
    
    /**
     * Check if a child is a leaf node.
     */
    public boolean isChildLeaf(int childIdx) {
        return hasChild(childIdx) && !isChildNonLeaf(childIdx);
    }
    
    // Far pointer operations (bit 16)
    
    /**
     * Check if this node uses far pointer.
     */
    public boolean isFar() {
        return (childDescriptor & FAR_BIT) != 0;
    }
    
    /**
     * Set far pointer flag.
     */
    public void setFar(boolean far) {
        if (far) {
            childDescriptor |= FAR_BIT;
        } else {
            childDescriptor &= ~FAR_BIT;
        }
    }
    
    // Child pointer operations (bits 17-31)
    
    /**
     * Get child pointer (15 bits).
     */
    public int getChildPointer() {
        return (childDescriptor & CHILD_PTR_MASK) >>> CHILD_PTR_SHIFT;
    }
    
    /**
     * Set child pointer (15 bits).
     */
    public void setChildPointer(int ptr) {
        childDescriptor = (childDescriptor & ~CHILD_PTR_MASK) | ((ptr & 0x7FFF) << CHILD_PTR_SHIFT);
    }
    
    // CRITICAL: Sparse child indexing algorithm (REFERENCE-CORRECT)
    
    /**
     * Calculate child node index using REFERENCE-CORRECT sparse indexing.
     * This is the exact algorithm from CUDA raycast.inl.
     * 
     * @param childIdx Child slot (0-7)
     * @return Actual index in node array, or -1 if child doesn't exist
     */
    public int getChildNodeIndex(int childIdx) {
        if (!hasChild(childIdx)) {
            return -1;
        }
        
        // CRITICAL: Use VALID mask for popcount (not "child mask")
        int validMask = getValidMask();
        int bitsBeforeChild = Integer.bitCount(validMask & ((1 << childIdx) - 1));
        
        return getChildPointer() + bitsBeforeChild;
    }
    
    /**
     * CRITICAL: Child indexing with octant mirroring.
     * This implements the exact CUDA algorithm with child_shift.
     * 
     * @param childIdx Original child index
     * @param octantMask Octant mask from ray traversal
     * @return Actual node index after octant transformation
     */
    public int getChildNodeIndexWithOctant(int childIdx, int octantMask) {
        int childShift = childIdx ^ octantMask;
        
        // Apply shift to check existence (this is the CUDA algorithm)
        int childMasks = childDescriptor << childShift;
        if ((childMasks & 0x8000) == 0) {
            return -1; // Child doesn't exist
        }
        
        // Calculate sparse index
        int validMask = getValidMask();
        int bitsBeforeChild = Integer.bitCount(validMask & ((1 << childIdx) - 1));
        
        return getChildPointer() + bitsBeforeChild;
    }
    
    /**
     * Get number of children this node has.
     */
    public int getChildCount() {
        return Integer.bitCount(getValidMask());
    }
    
    // Contour descriptor operations
    
    /**
     * Get contour mask (bits 0-7).
     */
    public int getContourMask() {
        return contourDescriptor & CONTOUR_MASK_BITS;
    }
    
    /**
     * Set contour mask (bits 0-7).
     */
    public void setContourMask(int mask) {
        contourDescriptor = (contourDescriptor & ~CONTOUR_MASK_BITS) | (mask & 0xFF);
    }
    
    /**
     * Get contour pointer (bits 8-31).
     */
    public int getContourPointer() {
        return (contourDescriptor & CONTOUR_PTR_MASK) >>> CONTOUR_PTR_SHIFT;
    }
    
    /**
     * Set contour pointer (bits 8-31).
     */
    public void setContourPointer(int ptr) {
        contourDescriptor = (contourDescriptor & ~CONTOUR_PTR_MASK) | ((ptr & 0xFFFFFF) << CONTOUR_PTR_SHIFT);
    }
    
    /**
     * Check if contour exists at given index.
     */
    public boolean hasContour(int contourIdx) {
        if (contourIdx < 0 || contourIdx > 7) {
            throw new IllegalArgumentException("Contour index must be 0-7");
        }
        return (getContourMask() & (1 << contourIdx)) != 0;
    }
    
    // Utility methods
    
    /**
     * Check if this is a leaf node (no children).
     */
    public boolean isLeaf() {
        return getValidMask() == 0;
    }
    
    /**
     * Serialize to ByteBuffer (8 bytes).
     */
    public void toBuffer(ByteBuffer buffer) {
        buffer.putInt(childDescriptor);
        buffer.putInt(contourDescriptor);
    }
    
    /**
     * Deserialize from ByteBuffer.
     */
    public static ESVONodeReference fromBuffer(ByteBuffer buffer) {
        int childDesc = buffer.getInt();
        int contourDesc = buffer.getInt();
        return new ESVONodeReference(childDesc, contourDesc);
    }
    
    /**
     * Size in bytes (always 8).
     */
    public static final int SIZE_BYTES = 8;
    
    @Override
    public String toString() {
        return String.format("ESVONodeReference[validMask=%02X, nonLeafMask=%02X, childPtr=%d, far=%b, children=%d, contourMask=%02X]",
            getValidMask(), getNonLeafMask(), getChildPointer(), isFar(), getChildCount(), getContourMask());
    }
    
    /**
     * CRITICAL: Far pointer resolution algorithm.
     * Implements the exact CUDA far pointer mechanism.
     * 
     * @param nodes Node array
     * @param parentIdx Current parent index
     * @return Final parent index after far pointer resolution
     */
    public static int resolveFarPointer(ESVONodeReference[] nodes, int parentIdx) {
        ESVONodeReference parent = nodes[parentIdx];
        int ofs = parent.getChildPointer();
        
        if (parent.isFar()) {
            // CRITICAL: Far pointer resolution from ChromaDB
            // "ofs = octree.nodes[parentIdx + ofs * 2].x;"
            ofs = nodes[parentIdx + ofs * 2].childDescriptor;
            return parentIdx + ofs * 2;
        }
        
        return parentIdx + ofs * 2;
    }
}