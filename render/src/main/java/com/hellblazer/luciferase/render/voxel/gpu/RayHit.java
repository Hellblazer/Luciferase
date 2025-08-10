package com.hellblazer.luciferase.render.voxel.gpu;

import javax.vecmath.Vector3f;

/**
 * Represents a ray-voxel intersection result.
 */
public class RayHit {
    public final boolean hit;           // Whether ray hit something
    public final float distance;         // Distance to hit point
    public final Vector3f position;      // Hit position in world space
    public final Vector3f normal;        // Surface normal at hit point
    public final int materialId;         // ID of the material hit
    
    public RayHit(boolean hit, float distance, Vector3f position, Vector3f normal, int materialId) {
        this.hit = hit;
        this.distance = distance;
        this.position = position;
        this.normal = normal;
        this.materialId = materialId;
    }
    
    /**
     * Create a miss result.
     */
    public static RayHit miss() {
        return new RayHit(
            false,
            Float.MAX_VALUE,
            new Vector3f(0, 0, 0),
            new Vector3f(0, 0, 0),
            -1
        );
    }
}