/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SpatialLevelHeuristic}.
 * <p>
 * Verifies the level-default algorithm targets r ~ 8*cell-edge per RDR-003 Phase 0 Step 0:
 * {@code L = clamp(24 - ceil(log2(r)), MIN_USEFUL_LEVEL, MAX_LEVEL)}.
 */
class SpatialLevelHeuristicTest {

    @ParameterizedTest
    @CsvSource({
        // r,  expectedLevel
        "10,   20",
        "20,   19",
        "30,   19",
        "32,   19",
        "33,   18",
        "50,   18",
        "64,   18",
        "65,   17",
        "100,  17",
        "128,  17",
        "129,  16",
        "200,  16"
    })
    void computeDefault_targetsApproxEightCellEdges(float aoiRadius, int expectedLevel) {
        var actual = SpatialLevelHeuristic.computeDefault(aoiRadius);
        assertEquals((byte) expectedLevel, actual,
            "For r=" + aoiRadius + " expected level " + expectedLevel + " but got " + actual);
    }

    @Test
    void defaultSpatialLevel_isEighteenForDefaultAoiOfFifty() {
        // The default Manager configuration uses aoiRadius=50, world=200. The default-level
        // constant must agree with computeDefault(50f) and equal 18 (cell-edge 8 units).
        assertEquals((byte) 18, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL);
        assertEquals(SpatialLevelHeuristic.computeDefault(50.0f),
                     SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL);
    }

    @Test
    void verySmallRadius_clampsToMaxLevel() {
        // r=1 → 24 - ceil(log2(1)) = 24 - 0 = 24 → clamp to MAX = 21.
        assertEquals(SpatialLevelHeuristic.MAX_LEVEL,
                     SpatialLevelHeuristic.computeDefault(1.0f));
        assertEquals(SpatialLevelHeuristic.MAX_LEVEL,
                     SpatialLevelHeuristic.computeDefault(0.001f));
    }

    @Test
    void veryLargeRadius_clampsToMinUsefulLevel() {
        // r=1e9 → 24 - ceil(log2(1e9)) = 24 - 30 = -6 → clamp to MIN_USEFUL=8.
        assertEquals(SpatialLevelHeuristic.MIN_USEFUL_LEVEL,
                     SpatialLevelHeuristic.computeDefault(1.0e9f));
    }

    @Test
    void minAndMaxLevelConstants_matchExpectations() {
        assertEquals(MortonCurve.MAX_REFINEMENT_LEVEL, SpatialLevelHeuristic.MAX_LEVEL);
        assertEquals((byte) 8, SpatialLevelHeuristic.MIN_USEFUL_LEVEL);
        assertTrue(SpatialLevelHeuristic.MIN_USEFUL_LEVEL < SpatialLevelHeuristic.MAX_LEVEL);
    }

    @Test
    void zeroRadius_throws() {
        assertThrows(IllegalArgumentException.class,
                     () -> SpatialLevelHeuristic.computeDefault(0.0f));
    }

    @Test
    void negativeRadius_throws() {
        assertThrows(IllegalArgumentException.class,
                     () -> SpatialLevelHeuristic.computeDefault(-1.0f));
    }

    @Test
    void nanRadius_throws() {
        assertThrows(IllegalArgumentException.class,
                     () -> SpatialLevelHeuristic.computeDefault(Float.NaN));
    }

    @Test
    void infiniteRadius_throws() {
        assertThrows(IllegalArgumentException.class,
                     () -> SpatialLevelHeuristic.computeDefault(Float.POSITIVE_INFINITY));
    }
}
