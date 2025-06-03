package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * 3D Ray representation for ray tracing queries
 * Ray is defined by an origin point and a direction vector
 * 
 * @author hal.hildebrand
 */
public record Ray3D(Point3f origin, Vector3f direction) {
    
    /**
     * Create a ray with validation
     * @param origin the starting point of the ray (must have positive coordinates)
     * @param direction the direction vector (will be normalized)
     */
    public Ray3D {
        if (origin.x < 0 || origin.y < 0 || origin.z < 0) {
            throw new IllegalArgumentException("Ray origin must have positive coordinates");
        }
        
        // Normalize the direction vector
        direction = new Vector3f(direction);
        direction.normalize();
    }
    
    /**
     * Get a point along the ray at parameter t
     * @param t the parameter (t >= 0 for points along the ray from origin)
     * @return the point at origin + t * direction
     */
    public Point3f getPointAt(float t) {
        return new Point3f(
            origin.x + t * direction.x,
            origin.y + t * direction.y,
            origin.z + t * direction.z
        );
    }
    
    /**
     * Create a ray from origin to target point
     * @param origin the starting point
     * @param target the target point
     * @return ray pointing from origin to target
     */
    public static Ray3D fromPoints(Point3f origin, Point3f target) {
        Vector3f direction = new Vector3f(
            target.x - origin.x,
            target.y - origin.y,
            target.z - origin.z
        );
        return new Ray3D(origin, direction);
    }
}