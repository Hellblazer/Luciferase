package com.hellblazer.luciferase.esvo.core;

/**
 * Result structure for ESVO ray traversal operations.
 * Contains hit information and traversal statistics.
 */
public class ESVOResult {
    // Hit information
    public boolean hit;          // Whether ray hit something
    public float t;             // Hit distance along ray
    public float x, y, z;       // Hit position in world coordinates
    
    // Node information
    public int nodeIndex;       // Index of hit node
    public int childIndex;      // Child index within node (0-7)
    
    // Traversal statistics
    public int iterations;      // Number of traversal iterations
    public int stackPtr;        // Final stack pointer depth
    
    /**
     * Create empty result (miss)
     */
    public ESVOResult() {
        this.hit = false;
        this.t = Float.POSITIVE_INFINITY;
        this.x = 0.0f;
        this.y = 0.0f;
        this.z = 0.0f;
        this.nodeIndex = -1;
        this.childIndex = -1;
        this.iterations = 0;
        this.stackPtr = -1;
    }
    
    /**
     * Create hit result
     */
    public ESVOResult(float t, float x, float y, float z, int nodeIndex, int childIndex) {
        this.hit = true;
        this.t = t;
        this.x = x;
        this.y = y;
        this.z = z;
        this.nodeIndex = nodeIndex;
        this.childIndex = childIndex;
        this.iterations = 0;
        this.stackPtr = -1;
    }
    
    /**
     * Reset to miss state
     */
    public void reset() {
        this.hit = false;
        this.t = Float.POSITIVE_INFINITY;
        this.x = 0.0f;
        this.y = 0.0f;
        this.z = 0.0f;
        this.nodeIndex = -1;
        this.childIndex = -1;
        this.iterations = 0;
        this.stackPtr = -1;
    }
    
    /**
     * Check if this is a valid hit
     */
    public boolean isValidHit() {
        return hit && t >= 0.0f && t < Float.POSITIVE_INFINITY;
    }
    
    /**
     * Get hit distance
     */
    public float getHitDistance() {
        return isValidHit() ? t : Float.POSITIVE_INFINITY;
    }
    
    /**
     * Get hit position as array
     */
    public float[] getHitPosition() {
        return new float[]{x, y, z};
    }
    
    /**
     * Copy from another result
     */
    public void copyFrom(ESVOResult other) {
        this.hit = other.hit;
        this.t = other.t;
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        this.nodeIndex = other.nodeIndex;
        this.childIndex = other.childIndex;
        this.iterations = other.iterations;
        this.stackPtr = other.stackPtr;
    }
    
    @Override
    public String toString() {
        if (hit) {
            return String.format("ESVOResult[HIT: t=%.6f, pos=(%.3f,%.3f,%.3f), node=%d, child=%d, iter=%d]",
                t, x, y, z, nodeIndex, childIndex, iterations);
        } else {
            return String.format("ESVOResult[MISS: iter=%d]", iterations);
        }
    }
}