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
 */
public record SplitPlane(Point3f normal, float distance) {

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
