package com.hellblazer.luciferase.esvo.traversal;

import javax.vecmath.Vector3f;

/**
 * Enhanced Ray class that matches the C++ ESVO implementation.
 * Includes size parameters for proper termination conditions.
 * 
 * Based on C++ struct Ray from Raycast.inl:
 * struct Ray {
 *     float3 orig;
 *     float orig_sz;
 *     float3 dir;
 *     float dir_sz;
 * };
 */
public class EnhancedRay {
    
    /**
     * Ray origin in octree coordinate space [1,2]
     */
    public final Vector3f origin;
    
    /**
     * Origin size parameter for termination condition.
     * Used in: tc_max * dir_sz + orig_sz >= scale_exp2
     */
    public final float originSize;
    
    /**
     * Ray direction (normalized)
     */
    public final Vector3f direction;
    
    /**
     * Direction size parameter for termination condition.
     * Controls when traversal should stop based on voxel size.
     */
    public final float directionSize;
    
    /**
     * Epsilon value for small direction components (2^-23)
     */
    private static final float EPSILON = (float)Math.pow(2, -23);
    
    /**
     * Create a ray with full parameters matching C++ implementation
     */
    public EnhancedRay(Vector3f origin, float originSize, Vector3f direction, float directionSize) {
        this.origin = new Vector3f(origin);
        this.originSize = originSize;
        this.direction = new Vector3f(direction);
        this.directionSize = directionSize;
        
        // Normalize direction
        this.direction.normalize();
        
        // Handle small direction components to avoid division by zero
        // C++ lines 96-98 from Raycast.inl
        if (Math.abs(this.direction.x) < EPSILON) {
            this.direction.x = Math.copySign(EPSILON, this.direction.x);
        }
        if (Math.abs(this.direction.y) < EPSILON) {
            this.direction.y = Math.copySign(EPSILON, this.direction.y);
        }
        if (Math.abs(this.direction.z) < EPSILON) {
            this.direction.z = Math.copySign(EPSILON, this.direction.z);
        }
    }
    
    /**
     * Convenience constructor for rays without size parameters (defaults to 0)
     */
    public EnhancedRay(Vector3f origin, Vector3f direction) {
        this(origin, 0.0f, direction, 0.0f);
    }
    
    /**
     * Calculate point along ray at parameter t
     */
    public Vector3f pointAt(float t) {
        var point = new Vector3f(direction);
        point.scale(t);
        point.add(origin);
        return point;
    }
    
    /**
     * Check if voxel is small enough to terminate traversal.
     * Based on C++ line 181: if (tc_max * ray.dir_sz + ray_orig_sz >= scale_exp2)
     */
    public boolean shouldTerminate(float tcMax, float scaleExp2) {
        return (tcMax * directionSize + originSize) >= scaleExp2;
    }
    
    /**
     * Transform ray to octree coordinate space [0,1].
     * Now that ESVO uses unified [0,1] space, this is an identity operation.
     * Kept for API compatibility.
     */
    public static EnhancedRay transformToOctreeSpace(EnhancedRay ray) {
        // No transformation needed - unified [0,1] space
        return ray;
    }
    
    /**
     * Apply octant mirroring for traversal optimization.
     * Mirrors the coordinate system so ray direction is negative along each axis.
     */
    public EnhancedRay applyOctantMirroring(int octantMask) {
        var mirroredOrigin = new Vector3f(origin);
        var mirroredDirection = new Vector3f(direction);
        
        // Apply mirroring based on octant mask
        // For [0,1] space, mirroring is around center 0.5: x' = 1.0 - x
        if ((octantMask & 1) != 0) {
            mirroredOrigin.x = 1.0f - mirroredOrigin.x;
            mirroredDirection.x = -mirroredDirection.x;
        }
        if ((octantMask & 2) != 0) {
            mirroredOrigin.y = 1.0f - mirroredOrigin.y;
            mirroredDirection.y = -mirroredDirection.y;
        }
        if ((octantMask & 4) != 0) {
            mirroredOrigin.z = 1.0f - mirroredOrigin.z;
            mirroredDirection.z = -mirroredDirection.z;
        }
        
        return new EnhancedRay(mirroredOrigin, originSize, 
                              mirroredDirection, directionSize);
    }
    
    /**
     * Calculate octant mask for this ray.
     * Based on C++ lines 114-117.
     */
    public int calculateOctantMask() {
        int octantMask = 7;
        if (direction.x > 0.0f) octantMask ^= 1;
        if (direction.y > 0.0f) octantMask ^= 2;
        if (direction.z > 0.0f) octantMask ^= 4;
        return octantMask;
    }
    
    @Override
    public String toString() {
        return String.format("EnhancedRay[origin=%s, origSize=%.3f, dir=%s, dirSize=%.3f]",
                           origin, originSize, direction, directionSize);
    }
}