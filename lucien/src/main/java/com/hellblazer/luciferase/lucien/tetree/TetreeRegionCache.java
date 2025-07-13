/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Region-based caching for bulk Tetree operations. Pre-computes ExtendedTetreeKey values for all tetrahedra in a
 * spatial region to amortize the cost of tmIndex() calls across multiple operations.
 *
 * @author hal.hildebrand
 */
public class TetreeRegionCache {

    // Statistics for monitoring
    private static final AtomicInteger totalRegionsPrecomputed    = new AtomicInteger(0);
    private static final AtomicInteger totalTetrahedraPrecomputed = new AtomicInteger(0);

    private final ConcurrentHashMap<Long, TetreeKey<? extends TetreeKey>> regionCache = new ConcurrentHashMap<>();

    /**
     * Get global statistics for monitoring.
     *
     * @return string with cache statistics
     */
    public static String getStatistics() {
        return String.format("Regions pre-computed: %d, Total tetrahedra: %d", totalRegionsPrecomputed.get(),
                             totalTetrahedraPrecomputed.get());
    }

    /**
     * Pack coordinates and type into a cache key. Uses bit packing for efficient storage.
     */
    private static long packCacheKey(int x, int y, int z, byte level, byte type) {
        // Use 12 bits each for x,y,z (4096 cells), 5 bits for level, 3 bits for type
        // This gives us 44 bits total, well within a long
        return ((long) (x & 0xFFF) << 32) | ((long) (y & 0xFFF) << 20) | ((long) (z & 0xFFF) << 8) | (
        (long) (level & 0x1F) << 3) | (type & 0x7);
    }

    /**
     * Reset global statistics.
     */
    public static void resetStatistics() {
        totalRegionsPrecomputed.set(0);
        totalTetrahedraPrecomputed.set(0);
    }

    /**
     * Clear the region cache to free memory.
     */
    public void clear() {
        regionCache.clear();
    }

    /**
     * Get a cached ExtendedTetreeKey from the region cache.
     *
     * @param x     x coordinate
     * @param y     y coordinate
     * @param z     z coordinate
     * @param level subdivision level
     * @param type  tetrahedron type
     * @return the cached ExtendedTetreeKey or null if not in cache
     */
    public TetreeKey<? extends TetreeKey> getCachedKey(int x, int y, int z, byte level, byte type) {
        var cacheKey = packCacheKey(x, y, z, level, type);
        return regionCache.get(cacheKey);
    }

    /**
     * Pre-compute regions for multiple levels. Useful when entities might be inserted at different levels based on
     * density.
     *
     * @param bounds   the spatial region
     * @param minLevel minimum level to pre-compute
     * @param maxLevel maximum level to pre-compute
     */
    public void precomputeMultiLevel(VolumeBounds bounds, byte minLevel, byte maxLevel) {
        for (byte level = minLevel; level <= maxLevel; level++) {
            precomputeRegion(bounds, level);
        }
    }

    /**
     * Pre-compute all tetrahedra in a spatial region at the specified level. This dramatically improves bulk insertion
     * performance by ensuring all tmIndex() calls hit the cache.
     *
     * @param bounds the spatial region to pre-compute
     * @param level  the tetrahedral subdivision level
     */
    public void precomputeRegion(VolumeBounds bounds, byte level) {
        var cellSize = Constants.lengthAtLevel(level);

        // Calculate grid-aligned bounds
        var minX = (int) (bounds.minX() / cellSize) * cellSize;
        var maxX = ((int) (bounds.maxX() / cellSize) + 1) * cellSize;
        var minY = (int) (bounds.minY() / cellSize) * cellSize;
        var maxY = ((int) (bounds.maxY() / cellSize) + 1) * cellSize;
        var minZ = (int) (bounds.minZ() / cellSize) * cellSize;
        var maxZ = ((int) (bounds.maxZ() / cellSize) + 1) * cellSize;

        var count = 0;

        // Pre-compute all tetrahedra in the region
        for (int x = minX; x <= maxX; x += cellSize) {
            for (int y = minY; y <= maxY; y += cellSize) {
                for (int z = minZ; z <= maxZ; z += cellSize) {
                    // Skip if outside valid bounds
                    if (x < 0 || y < 0 || z < 0) {
                        continue;
                    }

                    // Pre-compute all 6 tetrahedron types at this position
                    for (byte type = 0; type < 6; type++) {
                        var tet = new Tet(x, y, z, level, type);

                        // This will compute and cache the TetreeKey<? extends TetreeKey>
                        var key = tet.tmIndex();

                        // Also store in our local cache for quick lookup
                        var cacheKey = packCacheKey(x, y, z, level, type);
                        regionCache.put(cacheKey, key);

                        count++;
                    }
                }
            }
        }

        // Update statistics
        totalRegionsPrecomputed.incrementAndGet();
        totalTetrahedraPrecomputed.addAndGet(count);
    }

    /**
     * Get the number of pre-computed entries.
     *
     * @return the cache size
     */
    public int size() {
        return regionCache.size();
    }
}
