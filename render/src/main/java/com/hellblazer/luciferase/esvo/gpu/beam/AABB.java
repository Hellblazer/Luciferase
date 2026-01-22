package com.hellblazer.luciferase.esvo.gpu.beam;

import javax.vecmath.Point3f;

/**
 * Axis-Aligned Bounding Box utility for ray grouping in BeamTree.
 *
 * Used to compute spatial bounds of ray origins and directions.
 */
public record AABB(Point3f min, Point3f max) {

    public AABB {
        if (min == null || max == null) {
            throw new IllegalArgumentException("AABB min/max cannot be null");
        }
    }

    /**
     * Check if a point is contained within this AABB.
     */
    public boolean contains(Point3f point) {
        return point.x >= min.x && point.x <= max.x
                && point.y >= min.y && point.y <= max.y
                && point.z >= min.z && point.z <= max.z;
    }

    /**
     * Expand AABB to contain the given point.
     */
    public AABB expand(Point3f point) {
        var newMin = new Point3f(
                Math.min(min.x, point.x),
                Math.min(min.y, point.y),
                Math.min(min.z, point.z)
        );
        var newMax = new Point3f(
                Math.max(max.x, point.x),
                Math.max(max.y, point.y),
                Math.max(max.z, point.z)
        );
        return new AABB(newMin, newMax);
    }

    /**
     * Get volume of this AABB.
     */
    public float volume() {
        var dx = max.x - min.x;
        var dy = max.y - min.y;
        var dz = max.z - min.z;
        return dx * dy * dz;
    }

    /**
     * Compute AABB from ray origins and directions.
     *
     * @param rays all rays in the scene
     * @param indices ray indices to compute bounds for
     * @return AABB encompassing all ray origins
     */
    public static AABB fromRays(Ray[] rays, int[] indices) {
        if (indices.length == 0) {
            throw new IllegalArgumentException("Cannot compute AABB from empty ray set");
        }

        var firstRay = rays[indices[0]];
        var origin = new Point3f(firstRay.origin());
        var min = new Point3f(origin);
        var max = new Point3f(origin);

        for (int i = 1; i < indices.length; i++) {
            origin = new Point3f(rays[indices[i]].origin());
            if (origin.x < min.x) min.x = origin.x;
            if (origin.y < min.y) min.y = origin.y;
            if (origin.z < min.z) min.z = origin.z;
            if (origin.x > max.x) max.x = origin.x;
            if (origin.y > max.y) max.y = origin.y;
            if (origin.z > max.z) max.z = origin.z;
        }

        return new AABB(min, max);
    }
}
