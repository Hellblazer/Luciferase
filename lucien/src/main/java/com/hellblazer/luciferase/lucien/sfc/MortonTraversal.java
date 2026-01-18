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

import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.octree.MortonKey;

import java.util.ArrayList;
import java.util.List;

/**
 * SFC traversal implementation for Morton-encoded spatial indices.
 *
 * This class provides range query functionality for Octree and SFCArrayIndex,
 * using the unified LITMAX/BIGMIN algorithm from {@link LitmaxBigmin}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var traversal = new MortonTraversal();
 * var intervals = traversal.rangeQuery(bounds, level);
 *
 * for (var interval : intervals) {
 *     MortonKey start = interval.startKey();
 *     MortonKey end = interval.endKey();
 *     // Use for range queries on ConcurrentSkipListMap, etc.
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 * @see SFCTraversal
 * @see LitmaxBigmin
 * @see MortonKey
 */
public class MortonTraversal implements SFCTraversal<MortonKey> {

    /**
     * Singleton instance for convenience.
     */
    public static final MortonTraversal INSTANCE = new MortonTraversal();

    /**
     * Creates a new MortonTraversal instance.
     */
    public MortonTraversal() {
        // Default constructor
    }

    @Override
    public List<KeyInterval<MortonKey>> rangeQuery(VolumeBounds queryBounds, byte level) {
        // Compute grid cell bounds from world coordinates
        var gridBounds = computeGridCellBounds(queryBounds, level);
        var minX = gridBounds[0];
        var minY = gridBounds[1];
        var minZ = gridBounds[2];
        var maxX = gridBounds[3];
        var maxY = gridBounds[4];
        var maxZ = gridBounds[5];

        // Use LITMAX/BIGMIN to compute optimal intervals
        var sfcIntervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        // Convert SFCIntervals to KeyIntervals with MortonKey
        var result = new ArrayList<KeyInterval<MortonKey>>(sfcIntervals.size());
        for (var interval : sfcIntervals) {
            var startKey = new MortonKey(interval.start(), level);
            var endKey = new MortonKey(interval.end(), level);
            result.add(new KeyInterval<>(startKey, endKey));
        }

        return result;
    }

    @Override
    public MortonKey mortonCodeToKey(long mortonCode, byte level) {
        return new MortonKey(mortonCode, level);
    }

    /**
     * Compute intervals directly from grid cell coordinates.
     *
     * This is a convenience method for cases where grid cell bounds
     * are already computed.
     *
     * @param minX  minimum X grid cell index
     * @param minY  minimum Y grid cell index
     * @param minZ  minimum Z grid cell index
     * @param maxX  maximum X grid cell index
     * @param maxY  maximum Y grid cell index
     * @param maxZ  maximum Z grid cell index
     * @param level the refinement level
     * @return list of MortonKey intervals
     */
    public List<KeyInterval<MortonKey>> rangeQueryFromGridCells(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            byte level) {

        var sfcIntervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        var result = new ArrayList<KeyInterval<MortonKey>>(sfcIntervals.size());
        for (var interval : sfcIntervals) {
            var startKey = new MortonKey(interval.start(), level);
            var endKey = new MortonKey(interval.end(), level);
            result.add(new KeyInterval<>(startKey, endKey));
        }

        return result;
    }
}
