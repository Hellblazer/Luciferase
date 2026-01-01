package com.hellblazer.luciferase.esvo.core;

import java.nio.ByteBuffer;

/**
 * Ray structure for ESVO ray traversal, validated against CUDA reference.
 * Coordinates are in [0, 1] normalized voxel space.
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
     * Create ray with origin and direction
     */
    public ESVORay(float originX, float originY, float originZ,
                   float directionX, float directionY, float directionZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.directionX = directionX;
        this.directionY = directionY;
        this.directionZ = directionZ;
        this.originSize = 0.0f;
        this.directionSize = 0.0f;
    }
    
    /**
     * Create ray with origin, direction, and size parameters
     */
    public ESVORay(float originX, float originY, float originZ,
                   float directionX, float directionY, float directionZ,
                   float originSize, float directionSize) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.directionX = directionX;
        this.directionY = directionY;
        this.directionZ = directionZ;
        this.originSize = originSize;
        this.directionSize = directionSize;
    }
    
    /**
     * Prepare ray for traversal by handling small direction components.
     * This prevents division by zero issues during traversal.
     */
    public void prepareForTraversal() {
        // Get rid of small ray direction components to avoid division by zero
        if (Math.abs(directionX) < EPSILON) {
            directionX = directionX < 0 ? -EPSILON : EPSILON;
        }
        if (Math.abs(directionY) < EPSILON) {
            directionY = directionY < 0 ? -EPSILON : EPSILON;
        }
        if (Math.abs(directionZ) < EPSILON) {
            directionZ = directionZ < 0 ? -EPSILON : EPSILON;
        }
    }
    
    /**
     * Normalize ray direction
     */
    public void normalize() {
        float length = (float)Math.sqrt(directionX * directionX + 
                                       directionY * directionY + 
                                       directionZ * directionZ);
        if (length > EPSILON) {
            directionX /= length;
            directionY /= length;
            directionZ /= length;
        }
    }
    
    /**
     * Get point along ray at parameter t
     */
    public void getPoint(float t, float[] result) {
        result[0] = originX + t * directionX;
        result[1] = originY + t * directionY;
        result[2] = originZ + t * directionZ;
    }
    
    /**
     * Check if ray intersects axis-aligned bounding box
     */
    public boolean intersectsAABB(float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        float tMin = (minX - originX) / directionX;
        float tMax = (maxX - originX) / directionX;
        
        if (tMin > tMax) {
            float temp = tMin;
            tMin = tMax;
            tMax = temp;
        }
        
        float tMinY = (minY - originY) / directionY;
        float tMaxY = (maxY - originY) / directionY;
        
        if (tMinY > tMaxY) {
            float temp = tMinY;
            tMinY = tMaxY;
            tMaxY = temp;
        }
        
        if (tMin > tMaxY || tMinY > tMax) {
            return false;
        }
        
        tMin = Math.max(tMin, tMinY);
        tMax = Math.min(tMax, tMaxY);
        
        float tMinZ = (minZ - originZ) / directionZ;
        float tMaxZ = (maxZ - originZ) / directionZ;
        
        if (tMinZ > tMaxZ) {
            float temp = tMinZ;
            tMinZ = tMaxZ;
            tMaxZ = temp;
        }
        
        return !(tMin > tMaxZ || tMinZ > tMax);
    }
    
    /**
     * Serialize to ByteBuffer
     */
    public void toBuffer(ByteBuffer buffer) {
        buffer.putFloat(originX);
        buffer.putFloat(originY);
        buffer.putFloat(originZ);
        buffer.putFloat(directionX);
        buffer.putFloat(directionY);
        buffer.putFloat(directionZ);
        buffer.putFloat(originSize);
        buffer.putFloat(directionSize);
    }
    
    /**
     * Deserialize from ByteBuffer
     */
    public static ESVORay fromBuffer(ByteBuffer buffer) {
        return new ESVORay(
            buffer.getFloat(), buffer.getFloat(), buffer.getFloat(),
            buffer.getFloat(), buffer.getFloat(), buffer.getFloat(),
            buffer.getFloat(), buffer.getFloat()
        );
    }
    
    /**
     * Size in bytes for serialization
     */
    public static final int SIZE_BYTES = 8 * 4; // 8 floats
    
    @Override
    public String toString() {
        return String.format("ESVORay[origin=(%.3f,%.3f,%.3f), direction=(%.3f,%.3f,%.3f), size=(%.3f,%.3f)]",
            originX, originY, originZ, directionX, directionY, directionZ, originSize, directionSize);
    }
}