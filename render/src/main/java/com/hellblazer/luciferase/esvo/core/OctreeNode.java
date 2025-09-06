package com.hellblazer.luciferase.esvo.core;

/**
 * Octree Node - 8 bytes exactly matching C++ ESVO implementation
 * 
 * Critical bit layout (MUST match C++ exactly):
 * - First 32 bits (childDescriptor):
 *   - Bits 0-7:   non-leaf mask (which children are internal nodes)
 *   - Bits 8-15:  valid mask (which children exist)  
 *   - Bit 16:     far pointer flag
 *   - Bits 17-31: child pointer offset (15 bits)
 * 
 * - Second 32 bits (contourDescriptor):
 *   - Bits 0-7:   contour mask
 *   - Bits 8-30:  contour data pointer (23 bits)
 *   - Bit 31:     reserved
 * 
 * This layout is critical for GPU performance and must exactly match
 * the C++ implementation for compatibility.
 */
public final class OctreeNode {
    
    // 8 bytes total - exactly matching C++ struct
    private final int childDescriptor;    // First 32 bits
    private final int contourDescriptor;  // Second 32 bits
    
    /**
     * Construct an octree node with raw descriptor values
     */
    public OctreeNode(int childDescriptor, int contourDescriptor) {
        this.childDescriptor = childDescriptor;
        this.contourDescriptor = contourDescriptor;
    }
    
    /**
     * Construct an octree node from individual components
     */
    public OctreeNode(byte nonLeafMask, byte validMask, boolean isFar, 
                     int childPointer, byte contourMask, int contourPointer) {
        this.childDescriptor = buildChildDescriptor(nonLeafMask, validMask, isFar, childPointer);
        this.contourDescriptor = buildContourDescriptor(contourMask, contourPointer);
    }
    
    // === Child Descriptor Access Methods ===
    
    /**
     * Get non-leaf mask (bits 0-7) - which children are internal nodes
     */
    public byte getNonLeafMask() {
        return (byte)(childDescriptor & 0xFF);
    }
    
    /**
     * Get valid mask (bits 8-15) - which children exist
     */
    public byte getValidMask() {
        return (byte)((childDescriptor >> 8) & 0xFF);
    }
    
    /**
     * Get far pointer flag (bit 16)
     */
    public boolean isFar() {
        return (childDescriptor & 0x10000) != 0;
    }
    
    /**
     * Get child pointer (bits 17-31) - 15 bits
     */
    public int getChildPointer() {
        return (childDescriptor >>> 17) & 0x7FFF;
    }
    
    /**
     * Get raw child descriptor (first 32 bits)
     */
    public int getChildDescriptor() {
        return childDescriptor;
    }
    
    // === Contour Descriptor Access Methods ===
    
    /**
     * Get contour mask (bits 0-7)
     */
    public byte getContourMask() {
        return (byte)(contourDescriptor & 0xFF);
    }
    
    /**
     * Get contour pointer (bits 8-30) - 23 bits
     */
    public int getContourPointer() {
        return (contourDescriptor >>> 8) & 0x7FFFFF;
    }
    
    /**
     * Get raw contour descriptor (second 32 bits)
     */
    public int getContourDescriptor() {
        return contourDescriptor;
    }
    
    // === Utility Methods ===
    
    /**
     * Check if a specific child exists
     * @param childIndex Child index (0-7)
     */
    public boolean hasChild(int childIndex) {
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getValidMask() & (1 << childIndex)) != 0;
    }
    
    /**
     * Check if a specific child is a leaf
     * @param childIndex Child index (0-7)
     */
    public boolean isChildLeaf(int childIndex) {
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getNonLeafMask() & (1 << childIndex)) == 0;
    }
    
    /**
     * Calculate child offset using popc8 (population count of lower 8 bits)
     * This is critical for child indexing in the octree traversal
     * 
     * @param childIndex The child index to calculate offset for
     * @return Offset of the child in the children array
     */
    public int getChildOffset(int childIndex) {
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        
        int validMask = getValidMask() & 0xFF;
        int mask = validMask & ((1 << childIndex) - 1); // Mask for bits before childIndex
        return popc8(mask);
    }
    
    /**
     * popc8 - Population count for lower 8 bits only
     * CRITICAL: This must only count the lower 8 bits, ignoring upper bits
     */
    public static int popc8(int mask) {
        return Integer.bitCount(mask & 0xFF);
    }
    
    // === Builder Methods ===
    
    private static int buildChildDescriptor(byte nonLeafMask, byte validMask, 
                                          boolean isFar, int childPointer) {
        int descriptor = (nonLeafMask & 0xFF);
        descriptor |= (validMask & 0xFF) << 8;
        if (isFar) {
            descriptor |= 0x10000;
        }
        descriptor |= (childPointer & 0x7FFF) << 17;
        return descriptor;
    }
    
    private static int buildContourDescriptor(byte contourMask, int contourPointer) {
        int descriptor = (contourMask & 0xFF);
        descriptor |= (contourPointer & 0x7FFFFF) << 8;
        return descriptor;
    }
    
    // === Object Methods ===
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        OctreeNode that = (OctreeNode) obj;
        return childDescriptor == that.childDescriptor && 
               contourDescriptor == that.contourDescriptor;
    }
    
    @Override
    public int hashCode() {
        return childDescriptor ^ contourDescriptor;
    }
    
    @Override
    public String toString() {
        return String.format("OctreeNode{nonLeaf=%02X, valid=%02X, far=%s, childPtr=%d, contourMask=%02X, contourPtr=%d}",
                           getNonLeafMask() & 0xFF,
                           getValidMask() & 0xFF,
                           isFar(),
                           getChildPointer(),
                           getContourMask() & 0xFF,
                           getContourPointer());
    }
    
    // === Static Factory Methods ===
    
    /**
     * Create a leaf node (no children)
     */
    public static OctreeNode createLeaf() {
        return new OctreeNode((byte)0, (byte)0, false, 0, (byte)0, 0);
    }
    
    /**
     * Create a node with specific child configuration
     */
    public static OctreeNode createWithChildren(byte validMask, byte nonLeafMask, 
                                               int childPointer, boolean isFar) {
        return new OctreeNode(nonLeafMask, validMask, isFar, childPointer, (byte)0, 0);
    }
}