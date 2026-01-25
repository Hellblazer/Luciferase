/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;

import javax.vecmath.Point3f;
import java.util.List;

/**
 * Geometric plane for splitting bubbles.
 * <p>
 * Defined by normal vector and distance from origin.
 * Used to partition entities into two groups during split.
 *
 * @param normal   plane normal vector (unit length)
 * @param distance signed distance from origin
 * @param axis     split axis (X, Y, Z, LONGEST, or DENSITY_WEIGHTED)
 */
public record SplitPlane(Point3f normal, float distance, SplitAxis axis) {

    /**
     * Axis along which the split plane is aligned.
     */
    public enum SplitAxis {
        /** X-axis aligned plane (normal = [1, 0, 0]) */
        X,
        /** Y-axis aligned plane (normal = [0, 1, 0]) */
        Y,
        /** Z-axis aligned plane (normal = [0, 0, 1]) */
        Z,
        /** Plane along longest dimension of bounds */
        LONGEST,
        /** Plane optimized by entity density distribution */
        DENSITY_WEIGHTED
    }

    /**
     * Backward-compatible constructor (2-arg).
     * Infers axis from normal vector for compatibility with existing code.
     * <p>
     * CRITICAL: Preserves compatibility with 25 existing call sites.
     *
     * @param normal   plane normal vector
     * @param distance signed distance from origin
     */
    public SplitPlane(Point3f normal, float distance) {
        this(normal, distance, inferAxis(normal));
    }

    /**
     * Create X-axis aligned split plane.
     *
     * @param distance distance along X axis
     * @return split plane with normal [1, 0, 0]
     */
    public static SplitPlane xAxis(float distance) {
        return new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), distance, SplitAxis.X);
    }

    /**
     * Create Y-axis aligned split plane.
     *
     * @param distance distance along Y axis
     * @return split plane with normal [0, 1, 0]
     */
    public static SplitPlane yAxis(float distance) {
        return new SplitPlane(new Point3f(0.0f, 1.0f, 0.0f), distance, SplitAxis.Y);
    }

    /**
     * Create Z-axis aligned split plane.
     *
     * @param distance distance along Z axis
     * @return split plane with normal [0, 0, 1]
     */
    public static SplitPlane zAxis(float distance) {
        return new SplitPlane(new Point3f(0.0f, 0.0f, 1.0f), distance, SplitAxis.Z);
    }

    /**
     * Create split plane along the longest axis of the given bounds.
     * <p>
     * Computes the dimensions of the bounds in Cartesian space,
     * identifies the longest dimension, and creates a plane
     * perpendicular to that axis passing through the centroid.
     *
     * @param bounds bubble bounds to analyze
     * @return split plane along longest axis through centroid
     */
    public static SplitPlane alongLongestAxis(com.hellblazer.luciferase.simulation.bubble.BubbleBounds bounds) {
        // Get RDGCS bounds
        var rdgMin = bounds.rdgMin();
        var rdgMax = bounds.rdgMax();

        // Convert to Cartesian to compute actual dimensions
        var cartMin = bounds.toCartesian(rdgMin);
        var cartMax = bounds.toCartesian(rdgMax);

        // Compute dimensions
        float dx = (float) Math.abs(cartMax.getX() - cartMin.getX());
        float dy = (float) Math.abs(cartMax.getY() - cartMin.getY());
        float dz = (float) Math.abs(cartMax.getZ() - cartMin.getZ());

        // Get centroid
        var centroid = bounds.centroid();

        // Choose longest axis and create plane through centroid
        if (dx > dy && dx > dz) {
            // X is longest - plane normal is X-axis
            return xAxis((float) centroid.getX());
        } else if (dy > dx && dy > dz) {
            // Y is longest - plane normal is Y-axis
            return yAxis((float) centroid.getY());
        } else {
            // Z is longest (or tie) - plane normal is Z-axis
            return zAxis((float) centroid.getZ());
        }
    }

    /**
     * Infer axis from normal vector.
     * <p>
     * Used for backward compatibility with 2-arg constructor.
     * Identifies dominant component of normal vector.
     *
     * @param normal normal vector to analyze
     * @return inferred axis (X, Y, or Z)
     */
    private static SplitAxis inferAxis(Point3f normal) {
        var absX = Math.abs(normal.x);
        var absY = Math.abs(normal.y);
        var absZ = Math.abs(normal.z);

        if (absX > absY && absX > absZ) {
            return SplitAxis.X;
        } else if (absY > absX && absY > absZ) {
            return SplitAxis.Y;
        } else {
            return SplitAxis.Z;
        }
    }

    /**
     * Check if split plane intersects bounds (simple RDGCS-based test for unit testing).
     * <p>
     * NOTE: This is kept for unit tests. Production validation should use intersectsEntityBounds().
     *
     * @param bounds bubble bounds to test
     * @return true if plane intersects bounds
     */
    public boolean intersects(com.hellblazer.luciferase.simulation.bubble.BubbleBounds bounds) {
        // Convert RDGCS bounds to Cartesian
        var cartMin = bounds.toCartesian(bounds.rdgMin());
        var cartMax = bounds.toCartesian(bounds.rdgMax());

        float dx = (float) (cartMax.getX() - cartMin.getX());
        float dy = (float) (cartMax.getY() - cartMin.getY());
        float dz = (float) (cartMax.getZ() - cartMin.getZ());
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float radius = diagonal / 2.0f;

        var centroid = bounds.centroid();
        float centerDist = normal.x * (float) centroid.getX() + normal.y * (float) centroid.getY() + normal.z * (float) centroid.getZ() - distance;

        return Math.abs(centerDist) < radius;
    }

    /**
     * Check if split plane intersects entity bounds (Byzantine-resistant validation).
     * <p>
     * Computes tight AABB from actual entity positions, not conservative RDGCS bounds.
     * This prevents Byzantine proposals with planes far outside actual entity distribution.
     *
     * @param entityRecords entities to compute bounds from
     * @return true if plane intersects the tight entity AABB
     */
    public boolean intersectsEntityBounds(List<EnhancedBubble.EntityRecord> entityRecords) {
        if (entityRecords.isEmpty()) {
            return false;
        }

        // Compute tight AABB from entity positions (all Cartesian)
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (var record : entityRecords) {
            var pos = record.position();
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }

        // Centroid of entity AABB
        float centroidX = (minX + maxX) / 2.0f;
        float centroidY = (minY + maxY) / 2.0f;
        float centroidZ = (minZ + maxZ) / 2.0f;

        // Radius = half of diagonal
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float radius = diagonal / 2.0f;

        // Distance from plane to centroid
        float centerDist = normal.x * centroidX + normal.y * centroidY + normal.z * centroidZ - distance;

        // Check if plane within radius of centroid
        return Math.abs(centerDist) < radius;
    }
}
