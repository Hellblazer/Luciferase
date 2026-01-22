package com.hellblazer.luciferase.esvo.gpu.beam;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Represents a single ray for tracing through the spatial structure.
 *
 * Contains ray origin and direction for coherence analysis and batch kernel processing.
 */
public record Ray(Point3f origin, Vector3f direction) {

    public Ray {
        if (origin == null) {
            throw new IllegalArgumentException("Ray origin cannot be null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("Ray direction cannot be null");
        }
    }

    /**
     * Compute squared distance between two ray origins.
     */
    public float distanceSquaredTo(Ray other) {
        var dx = origin.x - other.origin.x;
        var dy = origin.y - other.origin.y;
        var dz = origin.z - other.origin.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Compute angle between this ray's direction and another's.
     * Returns 0.0 for parallel directions, 1.0 for opposite directions.
     */
    public float directionDifference(Ray other) {
        var dot = this.direction.dot(other.direction);
        // Normalize to [0, 1]: dot = 1 (parallel) -> 0, dot = -1 (opposite) -> 1
        return Math.max(0f, 1f - dot);
    }
}
