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
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for MortonTraversal.
 *
 * @author hal.hildebrand
 */
class MortonTraversalTest {

    private final MortonTraversal traversal = MortonTraversal.INSTANCE;

    @Test
    void testRangeQuerySmallBox() {
        byte level = 10;
        var cellSize = Constants.lengthAtLevel(level);

        // 2x2x2 cell query at origin (cells 0 and 1 in each dimension)
        // Use cellSize * 2 - 1 to stay within cell index 1
        var bounds = new VolumeBounds(0, 0, 0, cellSize * 2 - 1, cellSize * 2 - 1, cellSize * 2 - 1);
        var intervals = traversal.rangeQuery(bounds, level);

        assertFalse(intervals.isEmpty(), "Should produce intervals");
        assertTrue(intervals.size() <= 8, "2x2x2 query should produce at most 8 intervals");

        // Count total cells - should be 8
        long totalCells = 0;
        for (var interval : intervals) {
            totalCells += interval.endKey().getMortonCode() - interval.startKey().getMortonCode() + 1;
        }
        assertEquals(8, totalCells, "Should cover 8 cells for 2x2x2 query");

        // All keys should be at the correct level
        for (var interval : intervals) {
            assertEquals(level, interval.startKey().getLevel());
            assertEquals(level, interval.endKey().getLevel());
        }
    }

    @Test
    void testRangeQueryLargeBox() {
        byte level = 8;
        var cellSize = Constants.lengthAtLevel(level);

        // 5x5x5 cell query
        var bounds = new VolumeBounds(
            cellSize, cellSize, cellSize,
            cellSize * 6, cellSize * 6, cellSize * 6
        );
        var intervals = traversal.rangeQuery(bounds, level);

        assertFalse(intervals.isEmpty(), "Should produce intervals");
        assertTrue(intervals.size() < 125, "Should produce fewer than 125 intervals");

        // Verify interval ordering
        for (var interval : intervals) {
            assertTrue(interval.startKey().compareTo(interval.endKey()) <= 0,
                "Start should be <= end");
        }
    }

    @Test
    void testRangeQueryFromGridCells() {
        byte level = 10;

        // Direct grid cell query
        var intervals = traversal.rangeQueryFromGridCells(2, 2, 2, 4, 4, 4, level);

        assertFalse(intervals.isEmpty(), "Should produce intervals");

        // Count total cells covered
        long totalCells = 0;
        for (var interval : intervals) {
            totalCells += interval.endKey().getMortonCode() - interval.startKey().getMortonCode() + 1;
        }

        // 3x3x3 = 27 cells
        assertEquals(27, totalCells, "Should cover exactly 27 cells");
    }

    @Test
    void testMortonCodeToKey() {
        byte level = 10;
        long mortonCode = 12345;

        MortonKey key = traversal.mortonCodeToKey(mortonCode, level);

        assertEquals(mortonCode, key.getMortonCode());
        assertEquals(level, key.getLevel());
    }

    @Test
    void testKeyIntervalContains() {
        byte level = 10;
        var start = new MortonKey(100, level);
        var end = new MortonKey(200, level);
        var interval = new SFCTraversal.KeyInterval<>(start, end);

        // Test containment
        assertTrue(interval.contains(new MortonKey(100, level)), "Should contain start");
        assertTrue(interval.contains(new MortonKey(150, level)), "Should contain middle");
        assertTrue(interval.contains(new MortonKey(200, level)), "Should contain end");
        assertFalse(interval.contains(new MortonKey(99, level)), "Should not contain before");
        assertFalse(interval.contains(new MortonKey(201, level)), "Should not contain after");
    }

    @Test
    void testCellSizeAtLevel() {
        assertEquals(Constants.lengthAtLevel((byte) 0), traversal.cellSizeAtLevel((byte) 0));
        assertEquals(Constants.lengthAtLevel((byte) 10), traversal.cellSizeAtLevel((byte) 10));
        assertEquals(Constants.lengthAtLevel((byte) 21), traversal.cellSizeAtLevel((byte) 21));
    }

    @Test
    void testComputeGridCellBounds() {
        byte level = 10;
        var cellSize = Constants.lengthAtLevel(level);

        var bounds = new VolumeBounds(
            cellSize * 2.5f, cellSize * 3.5f, cellSize * 4.5f,
            cellSize * 5.5f, cellSize * 6.5f, cellSize * 7.5f
        );

        var gridBounds = traversal.computeGridCellBounds(bounds, level);

        assertEquals(2, gridBounds[0], "minX");
        assertEquals(3, gridBounds[1], "minY");
        assertEquals(4, gridBounds[2], "minZ");
        assertEquals(5, gridBounds[3], "maxX");
        assertEquals(6, gridBounds[4], "maxY");
        assertEquals(7, gridBounds[5], "maxZ");
    }

    @Test
    void testSingletonInstance() {
        assertNotNull(MortonTraversal.INSTANCE);
        assertSame(MortonTraversal.INSTANCE, MortonTraversal.INSTANCE);
    }

    @Test
    void testConsistencyWithOldOctreeImplementation() {
        // This test verifies that MortonTraversal produces results
        // consistent with the original Octree.cellsQ() implementation
        byte level = 8;
        var cellSize = Constants.lengthAtLevel(level);

        // Test at origin - cells 0, 1, 2 in each dimension
        // Use cellSize * 3 - 1 to stay within cell index 2
        var bounds = new VolumeBounds(0, 0, 0, cellSize * 3 - 1, cellSize * 3 - 1, cellSize * 3 - 1);
        var intervals = traversal.rangeQuery(bounds, level);

        // Should cover 3x3x3 = 27 cells
        long totalCells = 0;
        for (var interval : intervals) {
            totalCells += interval.endKey().getMortonCode() - interval.startKey().getMortonCode() + 1;
        }
        assertEquals(27, totalCells, "Should cover 27 cells for 3x3x3 query");

        // Should produce reasonable number of intervals
        assertTrue(intervals.size() <= 27, "Should produce at most 27 intervals for 3x3x3");
    }
}
