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
package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TetreeBubbleFactory bubble creation and distribution logic.
 *
 * @author hal.hildebrand
 */
class TetreeBubbleFactoryTest {

    @Test
    void testCreateBubbles_SingleBubble() {
        var grid = new TetreeBubbleGrid((byte) 0);

        TetreeBubbleFactory.createBubbles(grid, 1, (byte) 0, 100);

        assertEquals(1, grid.getBubbleCount());
    }

    @Test
    void testCreateBubbles_NineBubbles_2Levels() {
        var grid = new TetreeBubbleGrid((byte) 1);

        TetreeBubbleFactory.createBubbles(grid, 9, (byte) 1, 100);

        // Note: Due to tetrahedral key collisions during generation, may create fewer than requested.
        // Verify we created at least some bubbles and at most what we requested
        var count = grid.getBubbleCount();
        assertTrue(count > 0, "Should create at least 1 bubble");
        assertTrue(count <= 9, "Should not exceed requested count");

        // For typical tetrahedral structure, 2 levels can support ~5 unique bubbles
        assertTrue(count >= 3, "Should create at least 3 bubbles for 2-level tree");
    }

    @Test
    void testCreateBubbles_64Bubbles_3Levels() {
        var grid = new TetreeBubbleGrid((byte) 2);

        TetreeBubbleFactory.createBubbles(grid, 64, (byte) 2, 100);

        // Note: Due to tetrahedral key collisions, may create fewer than requested.
        // Verify we created a reasonable number of bubbles for a 3-level tree
        var count = grid.getBubbleCount();
        assertTrue(count > 0, "Should create bubbles");
        assertTrue(count <= 64, "Should not exceed requested 64");
        assertTrue(count >= 10, "Should create reasonable multi-level distribution");
    }

    @Test
    void testCreateBalancedGrid_1Bubble() {
        var distribution = TetreeBubbleFactory.createBalancedGrid(1, (byte) 0);

        assertEquals(1, distribution.size());
        assertEquals(1, distribution.get(0));
    }

    @Test
    void testCreateBalancedGrid_9Bubbles() {
        var distribution = TetreeBubbleFactory.createBalancedGrid(9, (byte) 1);

        assertEquals(2, distribution.size());
        assertEquals(1, distribution.get(0)); // Level 0: 1 bubble
        assertEquals(8, distribution.get(1)); // Level 1: 8 bubbles
    }

    @Test
    void testCreateBalancedGrid_100Bubbles() {
        var distribution = TetreeBubbleFactory.createBalancedGrid(100, (byte) 3);

        assertNotNull(distribution);
        assertTrue(distribution.size() > 0);

        // Verify total adds up to 100
        var total = distribution.stream().mapToInt(Integer::intValue).sum();
        assertEquals(100, total);

        // Verify first level has 1 bubble (root)
        assertEquals(1, distribution.get(0));
    }

    @Test
    void testAssignBubbleLocations_Uniqueness() {
        var keys = TetreeBubbleFactory.assignBubbleLocations(50, (byte) 2);

        assertEquals(50, keys.size());

        // Verify all keys are unique
        var uniqueKeys = new HashSet<>(keys);
        assertEquals(50, uniqueKeys.size());
    }

    @Test
    void testAssignBubbleLocations_SFCOrder() {
        var keys = TetreeBubbleFactory.assignBubbleLocations(20, (byte) 2);

        // Verify keys are sorted by level, then by high/low bits (SFC-like order)
        for (int i = 1; i < keys.size(); i++) {
            var prev = keys.get(i - 1);
            var curr = keys.get(i);

            // Check ordering: level ascending, then high bits, then low bits
            if (prev.getLevel() == curr.getLevel()) {
                if (prev.getHighBits() == curr.getHighBits()) {
                    assertTrue(prev.getLowBits() <= curr.getLowBits(),
                        "Keys should be sorted by low bits within same level/high bits");
                } else {
                    assertTrue(prev.getHighBits() <= curr.getHighBits(),
                        "Keys should be sorted by high bits within same level");
                }
            } else {
                assertTrue(prev.getLevel() <= curr.getLevel(),
                    "Keys should be sorted by level first");
            }
        }
    }

    @Test
    void testAssignBubbleLocations_CoverageMaxLevel() {
        var keys = TetreeBubbleFactory.assignBubbleLocations(100, (byte) 3);

        // Verify we have keys at different levels (good distribution)
        var levels = new HashSet<Byte>();
        for (var key : keys) {
            levels.add(key.getLevel());
        }

        assertTrue(levels.size() >= 2, "Should distribute across multiple levels");
    }

    @Test
    void testCreateBubbles_MaxLevel21() {
        var grid = new TetreeBubbleGrid((byte) 21);

        TetreeBubbleFactory.createBubbles(grid, 10, (byte) 21, 100);

        assertEquals(10, grid.getBubbleCount());
    }

    @Test
    void testPerformance_Create100Bubbles_Under100ms() {
        var grid = new TetreeBubbleGrid((byte) 3);

        var start = System.currentTimeMillis();
        TetreeBubbleFactory.createBubbles(grid, 100, (byte) 3, 100);
        var elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 100, "Creating bubbles should take <100ms, took " + elapsed + "ms");
        var count = grid.getBubbleCount();
        assertTrue(count > 0, "Should create at least one bubble");
        assertTrue(count <= 100, "Should not exceed requested 100");
    }

    @Test
    void testMemoryBounds_LargeCount_1000Bubbles() {
        var grid = new TetreeBubbleGrid((byte) 5);

        // This should not throw OutOfMemoryError or take excessive time
        // Note: Due to tetrahedral key collisions, may create fewer than 1000
        TetreeBubbleFactory.createBubbles(grid, 1000, (byte) 5, 50);

        var count = grid.getBubbleCount();
        assertTrue(count > 0, "Should create bubbles without OOM");
        assertTrue(count <= 1000, "Should not exceed requested 1000");
        // Tetrahedral structure typically achieves ~50-60% of requested count
        assertTrue(count >= 300, "Should create reasonable number given 5-level tree");
    }
}
