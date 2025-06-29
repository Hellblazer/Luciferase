/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.List;

/**
 * Utility class for selecting optimal starting levels for bulk operations. Analyzes data distribution and suggests
 * appropriate level to minimize unnecessary subdivisions.
 *
 * @author hal.hildebrand
 */
public class LevelSelector {

    // Target entities per node for efficient operations
    private static final int TARGET_ENTITIES_PER_NODE = 100;
    private static final int MIN_ENTITIES_PER_NODE    = 10;
    private static final int MAX_ENTITIES_PER_NODE    = 1000;

    /**
     * Calculate adaptive subdivision threshold based on level
     */
    public static int getAdaptiveSubdivisionThreshold(byte level, int baseThreshold) {
        // Increase threshold exponentially for deeper levels
        // This prevents wasteful subdivision when cells are very small
        if (level <= 10) {
            return baseThreshold;
        }

        // Double threshold for each level beyond 10
        int multiplier = 1 << (level - 10);
        return Math.min(baseThreshold * multiplier, MAX_ENTITIES_PER_NODE);
    }

    /**
     * Select optimal level for bulk insertion based on data characteristics
     */
    public static byte selectOptimalLevel(List<Point3f> positions, int maxEntitiesPerNode) {
        if (positions.isEmpty()) {
            return 10; // Default middle level
        }

        // Calculate spatial extent
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (Point3f p : positions) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }

        // Calculate volume and density
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        float volume = dx * dy * dz;

        if (volume == 0) {
            return 15; // All points at same location
        }

        // Estimate number of cells needed
        int targetCells = positions.size() / TARGET_ENTITIES_PER_NODE;
        targetCells = Math.max(1, Math.min(targetCells, positions.size() / MIN_ENTITIES_PER_NODE));

        // Calculate level based on cell count
        // At each level, we have 8^level cells
        int level = (int) Math.ceil(Math.log(targetCells) / Math.log(8));

        // Adjust based on spatial distribution
        float avgDimension = (dx + dy + dz) / 3.0f;
        if (avgDimension > 10000) {
            level = Math.max(5, level - 2); // Coarser for very spread data
        } else if (avgDimension > 1000) {
            level = Math.max(6, level - 1); // Slightly coarser for spread data
        }

        // Clamp to reasonable range
        return (byte) Math.max(5, Math.min(15, level));
    }

    /**
     * Analyze if data would benefit from Morton sorting at given level
     */
    public static boolean shouldUseMortonSort(List<Point3f> positions, byte level) {
        if (positions.size() < 1000) {
            return false; // Not worth the overhead for small datasets
        }

        // Morton sorting is most beneficial when:
        // 1. Data is spatially clustered
        // 2. Level is not too deep (cells are not too small)
        // 3. Dataset is large enough to benefit from cache locality

        if (level > 12) {
            return false; // Cells too small, sorting won't help
        }

        // Simple clustering check - sample positions
        int sampleSize = Math.min(100, positions.size());
        float avgDistance = 0;
        for (int i = 0; i < sampleSize; i++) {
            int idx1 = (int) (Math.random() * positions.size());
            int idx2 = (int) (Math.random() * positions.size());
            if (idx1 != idx2) {
                Point3f p1 = positions.get(idx1);
                Point3f p2 = positions.get(idx2);
                float dist = (float) Math.sqrt(
                (p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y) + (p1.z - p2.z) * (p1.z - p2.z));
                avgDistance += dist;
            }
        }
        avgDistance /= sampleSize;

        // If average distance is small relative to space, data is clustered
        return avgDistance < 1000; // Threshold based on typical data
    }
}
