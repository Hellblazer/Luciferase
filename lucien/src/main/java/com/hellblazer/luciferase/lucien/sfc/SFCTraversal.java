/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.sfc;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import java.util.List;

/**
 * Interface for Space-Filling Curve (SFC) based spatial traversal.
 *
 * This interface provides a unified API for range queries across different
 * spatial index implementations (Octree, Tetree, SFCArrayIndex). It abstracts
 * the LITMAX/BIGMIN algorithm to produce optimal Morton code intervals for
 * axis-aligned query boxes.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SFCTraversal<MortonKey> traversal = new MortonTraversal();
 * List<KeyInterval<MortonKey>> intervals = traversal.rangeQuery(bounds, level);
 *
 * for (KeyInterval<MortonKey> interval : intervals) {
 *     // Process keys from interval.startKey() to interval.endKey()
 * }
 * }</pre>
 *
 * @param <K> the spatial key type (e.g., MortonKey, TetreeKey)
 *
 * @author hal.hildebrand
 * @see LitmaxBigmin
 * @see SFCInterval
 */
public interface SFCTraversal<K extends SpatialKey<K>> {

    /**
     * Represents a contiguous interval of spatial keys.
     *
     * @param <K> the spatial key type
     * @param startKey the first key in the interval (inclusive)
     * @param endKey   the last key in the interval (inclusive)
     */
    record KeyInterval<K extends SpatialKey<K>>(K startKey, K endKey) {

        /**
         * Checks if this interval contains the given key.
         *
         * @param key the key to check
         * @return true if the key is within [startKey, endKey]
         */
        public boolean contains(K key) {
            return key.compareTo(startKey) >= 0 && key.compareTo(endKey) <= 0;
        }
    }

    /**
     * Compute optimal SFC intervals covering the query region.
     *
     * Uses LITMAX/BIGMIN to produce a minimal set of contiguous key intervals
     * that together cover exactly the cells in the specified query bounds.
     *
     * @param queryBounds the axis-aligned query region
     * @param level       the refinement level for the query
     * @return list of non-overlapping KeyIntervals covering the query region
     */
    List<KeyInterval<K>> rangeQuery(VolumeBounds queryBounds, byte level);

    /**
     * Convert a Morton code to the corresponding spatial key at the given level.
     *
     * @param mortonCode the Morton code
     * @param level      the refinement level
     * @return the spatial key
     */
    K mortonCodeToKey(long mortonCode, byte level);

    /**
     * Get the cell size at a given level.
     *
     * @param level the refinement level
     * @return the cell size in world coordinates
     */
    default int cellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    /**
     * Compute grid cell bounds from world coordinate bounds.
     *
     * @param queryBounds the world coordinate bounds
     * @param level       the refinement level
     * @return array of [minX, minY, minZ, maxX, maxY, maxZ] grid cell indices
     */
    default int[] computeGridCellBounds(VolumeBounds queryBounds, byte level) {
        var cellSize = cellSizeAtLevel(level);

        var minX = (int) Math.floor(queryBounds.minX() / cellSize);
        var minY = (int) Math.floor(queryBounds.minY() / cellSize);
        var minZ = (int) Math.floor(queryBounds.minZ() / cellSize);
        var maxX = (int) Math.floor(queryBounds.maxX() / cellSize);
        var maxY = (int) Math.floor(queryBounds.maxY() / cellSize);
        var maxZ = (int) Math.floor(queryBounds.maxZ() / cellSize);

        // Clamp to valid ranges
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        minZ = Math.max(0, minZ);
        maxX = Math.max(0, maxX);
        maxY = Math.max(0, maxY);
        maxZ = Math.max(0, maxZ);

        return new int[] { minX, minY, minZ, maxX, maxY, maxZ };
    }
}
