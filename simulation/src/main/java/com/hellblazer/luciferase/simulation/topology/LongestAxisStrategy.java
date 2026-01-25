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

import java.util.List;

/**
 * Split plane strategy that selects the longest axis of entity distribution.
 * <p>
 * This is the default strategy for bubble splitting. It analyzes the
 * actual entity positions, computes the AABB, identifies the longest dimension
 * (X, Y, or Z), and creates a plane perpendicular to that axis passing
 * through the entity centroid.
 * <p>
 * Benefits:
 * - Maximizes split effectiveness (largest dimension divided)
 * - Balanced subdivision (plane through entity centroid)
 * - Adaptive to actual entity distribution
 * <p>
 * Example:
 * - Entity spread: 100m (X) × 20m (Y) × 20m (Z)
 * - Selected plane: X-axis aligned through entity centroid
 */
public class LongestAxisStrategy implements SplitPlaneStrategy {

    @Override
    public SplitPlane calculate(com.hellblazer.luciferase.simulation.bubble.BubbleBounds bubbleBounds,
                                List<EnhancedBubble.EntityRecord> entities) {
        if (entities.isEmpty()) {
            // Fallback to bounds-based calculation if no entities
            return SplitPlane.alongLongestAxis(bubbleBounds);
        }

        // Compute AABB from actual entity positions
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (var entity : entities) {
            var pos = entity.position();
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }

        // Compute dimensions
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;

        // Compute entity centroid
        float cx = (minX + maxX) / 2.0f;
        float cy = (minY + maxY) / 2.0f;
        float cz = (minZ + maxZ) / 2.0f;

        // Choose longest axis and create plane through entity centroid
        if (dx > dy && dx > dz) {
            return SplitPlane.xAxis(cx);
        } else if (dy > dx && dy > dz) {
            return SplitPlane.yAxis(cy);
        } else {
            return SplitPlane.zAxis(cz);
        }
    }
}
