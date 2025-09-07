package com.hellblazer.luciferase.gpu.esvo.correct;

import java.nio.ByteBuffer;

/**
 * Ray structure matching the reference CUDA implementation.
 * Coordinates are in [1, 2] space as per reference.
 */
public class ESVORay {
    // Ray origin and direction
    public float originX, originY, originZ;
    public float directionX, directionY, directionZ;
    
    // Ray size parameters (for level-of-detail)
    public float originSize;    // Size at origin
    public float directionSize;  // Size per unit distance
    
    // Constants from reference
    public static final int CAST_STACK_DEPTH = 23;
    public static final float EPSILON = (float)Math.pow(2, -CAST_STACK_DEPTH);
    
    /**
     * Create a ray with normalized direction
     */
    public ESVORay(float ox, float oy, float oz, float dx, float dy, float dz) {
        this.originX = ox;
        this.originY = oy;
        this.originZ = oz;
        
        // Normalize direction
        float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        this.directionX = dx / len;
        this.directionY = dy / len;
        this.directionZ = dz / len;
        
        // Default LOD parameters
        this.originSize = 0.0f;
        this.directionSize = 1.0f;
    }
    
    /**
     * Prepare ray for traversal by handling epsilon cases.
     * This prevents division by zero as per reference implementation.
     */
    public void prepareForTraversal() {
        // Get rid of small ray direction components to avoid division by zero
        if (Math.abs(directionX) < EPSILON) {
            directionX = Math.copySign(EPSILON, directionX);
        }
        if (Math.abs(directionY) < EPSILON) {
            directionY = Math.copySign(EPSILON, directionY);
        }
        if (Math.abs(directionZ) < EPSILON) {
            directionZ = Math.copySign(EPSILON, directionZ);
        }
    }
    
    /**
     * Calculate octant mask for mirroring optimization
     */
    public int getOctantMask() {
        int mask = 7;
        if (directionX > 0.0f) mask ^= 1;
        if (directionY > 0.0f) mask ^= 2;
        if (directionZ > 0.0f) mask ^= 4;
        return mask;
    }
    
    /**
     * Write ray to buffer (32 bytes)
     */
    public void toBuffer(ByteBuffer buffer) {
        buffer.putFloat(originX);
        buffer.putFloat(originY);
        buffer.putFloat(originZ);
        buffer.putFloat(originSize);
        buffer.putFloat(directionX);
        buffer.putFloat(directionY);
        buffer.putFloat(directionZ);
        buffer.putFloat(directionSize);
    }
    
    public static final int SIZE_BYTES = 32;
}

/**
 * Traversal stack matching reference implementation.
 * Uses dual arrays for node pointers and t_max values.
 */
class ESVOStack {
    private final int[] nodeIndices;
    private final float[] tmaxValues;
    
    public ESVOStack() {
        // Stack depth from reference
        int depth = ESVORay.CAST_STACK_DEPTH + 1;
        this.nodeIndices = new int[depth];
        this.tmaxValues = new float[depth];
    }
    
    public void write(int level, int nodeIdx, float tmax) {
        nodeIndices[level] = nodeIdx;
        tmaxValues[level] = tmax;
    }
    
    public int readNode(int level) {
        return nodeIndices[level];
    }
    
    public float readTmax(int level) {
        return tmaxValues[level];
    }
}

/**
 * Traversal result structure
 */
class ESVOResult {
    public float t;           // Ray parameter at hit
    public float x, y, z;     // Hit position
    public int iterations;    // Debug: iteration count
    public int nodeIndex;     // Node containing hit
    public int childIndex;    // Child within node
    public int stackPtr;      // Stack depth at termination
    public boolean hit;       // Whether ray hit a voxel
    
    public ESVOResult() {
        this.t = 2.0f;  // Miss value from reference
        this.hit = false;
    }
}