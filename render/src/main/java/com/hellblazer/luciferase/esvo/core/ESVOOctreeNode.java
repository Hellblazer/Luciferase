package com.hellblazer.luciferase.esvo.core;

/**
 * ESVO Octree Node for file I/O
 * Simplified version of OctreeNode for serialization
 */
public class ESVOOctreeNode {
    public byte childMask;
    public int contour;
    public int farPointer;
    
    public ESVOOctreeNode() {
        this.childMask = 0;
        this.contour = 0;
        this.farPointer = 0;
    }
    
    public ESVOOctreeNode(byte childMask, int contour, int farPointer) {
        this.childMask = childMask;
        this.contour = contour;
        this.farPointer = farPointer;
    }
    
    /**
     * Create a copy of this node
     */
    public ESVOOctreeNode copy() {
        return new ESVOOctreeNode(this.childMask, this.contour, this.farPointer);
    }
}