/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link SpatialLocalityCache} behaviour after the Luciferase-7wzml.134 fix:
 * {@code preCacheNeighborhood} now returns the count of warmed entries instead of being void,
 * eliminating the dead-variable defect.
 *
 * <p>Cell sizes: {@code Constants.lengthAtLevel(level) = 1 << (21 - level)}.
 * All Tet coordinates must be aligned to the cell size at the chosen level.
 *
 * @author hal.hildebrand
 */
class SpatialLocalityCacheTest {

    // Cell size at level 3: 1 << (21-3) = 262144
    private static final int CELL_L3 = Constants.lengthAtLevel((byte) 3);
    // Cell size at level 4: 1 << (21-4) = 131072
    private static final int CELL_L4 = Constants.lengthAtLevel((byte) 4);

    @Test
    void preCacheNeighborhoodReturnsPositiveCount() {
        // radius=1 => (2*1+1)^3 = 27 positions; center far from origin so no negatives are skipped.
        // All 27 positions * 6 types = 162 entries.
        var center = new Tet(4 * CELL_L3, 4 * CELL_L3, 4 * CELL_L3, (byte) 3, (byte) 0);
        var cache = new SpatialLocalityCache(1);

        int count = cache.preCacheNeighborhood(center);

        assertEquals(162, count, "radius=1 with no boundary skips: 27 positions * 6 types = 162");
    }

    @Test
    void preCacheNeighborhoodSkipsNegativeCoords() {
        // Center at (0,0,0) level 3: neighbors at dx=-1 have x = -CELL_L3 < 0 and are skipped.
        // Valid positions: dx in {0,1}, dy in {0,1}, dz in {0,1} -> 2^3 = 8 positions * 6 types = 48.
        var center = new Tet(0, 0, 0, (byte) 3, (byte) 0);
        var cache = new SpatialLocalityCache(1);

        int count = cache.preCacheNeighborhood(center);

        assertEquals(48, count, "corner origin: only non-negative positions: 8 positions * 6 types = 48");
    }

    @Test
    void preCacheNeighborhoodRadiusZeroWarmsSinglePosition() {
        // radius=0: only the center position's 6 types
        var center = new Tet(CELL_L3, CELL_L3, CELL_L3, (byte) 3, (byte) 0);
        var cache = new SpatialLocalityCache(0);

        int count = cache.preCacheNeighborhood(center);

        assertEquals(6, count, "radius=0 warms only the center position (6 types)");
    }

    @Test
    void preCacheMultipleNeighborhoodsReturnsTotalCount() {
        var cache = new SpatialLocalityCache(0);
        // Two non-overlapping centers at different level-3 positions (MAX_COORD=2097151; max grid index=7)
        var center1 = new Tet(CELL_L3, CELL_L3, CELL_L3, (byte) 3, (byte) 0);
        var center2 = new Tet(5 * CELL_L3, 5 * CELL_L3, 5 * CELL_L3, (byte) 3, (byte) 0);

        int total = cache.preCacheMultipleNeighborhoods(new Tet[]{center1, center2});

        // Each radius=0 center warms 6 entries -> 12 total
        assertEquals(12, total, "two radius=0 centers -> 2 * 6 = 12 warmed entries");
    }

    @Test
    void preCacheMultipleNeighborhoodsEmptyArrayReturnsZero() {
        var cache = new SpatialLocalityCache(1);
        int total = cache.preCacheMultipleNeighborhoods(new Tet[0]);
        assertEquals(0, total);
    }
}
