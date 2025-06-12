package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * 3D Ray representation for ray tracing queries. Ray is defined by an origin point, a normalized direction vector, and
 * an optional maximum distance.
 *
 * @author hal.hildebrand
 */
public record Ray3D(Point3f origin, Vector3f direction, float maxDistance) {

    /**
     * Default maximum distance for unbounded rays
     */
    public static final float UNBOUNDED = Float.POSITIVE_INFINITY;

    /**
     * Create a ray with validation
     *
     * @param origin      the starting point of the ray (must have positive coordinates)
     * @param direction   the direction vector (will be normalized)
     * @param maxDistance the maximum distance along the ray (must be positive, can be UNBOUNDED)
     */
    public Ray3D {
        if (origin.x < 0 || origin.y < 0 || origin.z < 0) {
            throw new IllegalArgumentException("Ray origin must have positive coordinates: " + origin);
        }
        if (maxDistance <= 0 && maxDistance != UNBOUNDED) {
            throw new IllegalArgumentException("Ray max distance must be positive or unbounded: " + maxDistance);
        }

        // Normalize the direction vector
        direction = new Vector3f(direction);
        if (direction.lengthSquared() == 0) {
            throw new IllegalArgumentException("Ray direction cannot be zero vector");
        }
        direction.normalize();
    }

    /**
     * Create an unbounded ray
     *
     * @param origin    the starting point of the ray
     * @param direction the direction vector
     */
    public Ray3D(Point3f origin, Vector3f direction) {
        this(origin, direction, UNBOUNDED);
    }

    /**
     * Create a ray from origin to target point
     *
     * @param origin the starting point
     * @param target the target point
     * @return ray pointing from origin to target with distance limited to target
     */
    public static Ray3D fromPoints(Point3f origin, Point3f target) {
        Vector3f direction = new Vector3f(target.x - origin.x, target.y - origin.y, target.z - origin.z);
        float distance = direction.length();
        return new Ray3D(origin, direction, distance);
    }

    /**
     * Create an unbounded ray from origin pointing towards target
     *
     * @param origin the starting point
     * @param target the target point
     * @return unbounded ray pointing from origin towards target
     */
    public static Ray3D fromPointsUnbounded(Point3f origin, Point3f target) {
        Vector3f direction = new Vector3f(target.x - origin.x, target.y - origin.y, target.z - origin.z);
        return new Ray3D(origin, direction, UNBOUNDED);
    }

    /**
     * Get a point along the ray at parameter t
     *
     * @param t the parameter (t >= 0 for points along the ray from origin)
     * @return the point at origin + t * direction
     */
    public Point3f getPointAt(float t) {
        return new Point3f(origin.x + t * direction.x, origin.y + t * direction.y, origin.z + t * direction.z);
    }

    /**
     * Check if this is an unbounded ray
     *
     * @return true if maxDistance is UNBOUNDED
     */
    public boolean isUnbounded() {
        return maxDistance == UNBOUNDED;
    }

    /**
     * Check if a distance is within the ray's maximum distance
     *
     * @param distance the distance to check
     * @return true if distance <= maxDistance
     */
    public boolean isWithinDistance(float distance) {
        return distance <= maxDistance;
    }

    /**
     * Alias for getPointAt for compatibility with TetrahedralGeometry
     *
     * @param t the parameter
     * @return the point at origin + t * direction
     */
    public Point3f pointAt(float t) {
        return getPointAt(t);
    }

    /**
     * Create an unbounded version of this ray
     *
     * @return new ray with same origin and direction but unbounded
     */
    public Ray3D unbounded() {
        return new Ray3D(origin, direction, UNBOUNDED);
    }

    /**
     * Create a new ray with a different maximum distance
     *
     * @param newMaxDistance the new maximum distance
     * @return new ray with same origin and direction but different max distance
     */
    public Ray3D withMaxDistance(float newMaxDistance) {
        return new Ray3D(origin, direction, newMaxDistance);
    }
}
