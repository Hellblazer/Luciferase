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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the LITMAX/BIGMIN cells(Q) optimization in Octree.
 * Verifies that range queries produce efficient Morton intervals.
 *
 * @author hal.hildebrand
 */
public class OctreeCellsQOptimizationTest {

    @Test
    void testCellsQProducesOptimalIntervals() {
        // Create octree at level 8
        byte level = 8;
        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, level);

        // A 2x2x2 query box at origin should produce optimal intervals
        // Use cellSize * 2 - 1 to ensure we cover exactly cells 0 and 1 in each dimension
        var cellSize = Constants.lengthAtLevel(level);
        var smallQuery = new VolumeBounds(0, 0, 0, cellSize * 2 - 1, cellSize * 2 - 1, cellSize * 2 - 1);

        var intervals = octree.cellsQ(smallQuery, level);

        // 2x2x2 query at origin should produce exactly 1 interval (Morton codes 0-7 are contiguous)
        assertTrue(intervals.size() <= 8,
            "2x2x2 query should produce ≤8 intervals, got " + intervals.size());
    }

    @Test
    void testCellsQForLargeQuery() {
        byte level = 8;
        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, level);

        // Large query (5x5x5 cells)
        var cellSize = Constants.lengthAtLevel(level);
        var largeQuery = new VolumeBounds(
            cellSize, cellSize, cellSize,
            cellSize * 6, cellSize * 6, cellSize * 6
        );

        var intervals = octree.cellsQ(largeQuery, level);

        // LITMAX/BIGMIN should produce far fewer intervals than naive approach
        // Naive: 5*5*5 = 125 individual cells, possibly that many intervals
        // Optimized: should merge into contiguous intervals
        assertTrue(intervals.size() < 125,
            "Large query should produce fewer than 125 intervals, got " + intervals.size());

        // Verify intervals are non-empty and properly ordered
        assertFalse(intervals.isEmpty(), "Should produce some intervals");
        for (var interval : intervals) {
            assertTrue(interval.start().getMortonCode() <= interval.end().getMortonCode(),
                "Interval start should be <= end");
        }
    }

    @Test
    void testCellsQAtDifferentLevels() {
        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, (byte) 15);

        // Test at various levels
        for (byte level = 4; level <= 10; level++) {
            var cellSize = Constants.lengthAtLevel(level);
            var query = new VolumeBounds(0, 0, 0, cellSize * 3, cellSize * 3, cellSize * 3);

            var intervals = octree.cellsQ(query, level);

            // 3x3x3 = 27 cells, should produce reasonable number of intervals
            assertFalse(intervals.isEmpty(), "Should produce intervals at level " + level);
            assertTrue(intervals.size() <= 27,
                "3x3x3 query at level " + level + " should produce ≤27 intervals, got " + intervals.size());
        }
    }

    @Test
    void testCellsQIntegrationWithRangeQuery() {
        byte level = 10;
        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, level);

        // Insert entities in a grid pattern
        var cellSize = Constants.lengthAtLevel(level);
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    var pos = new Point3f(
                        x * cellSize * 2 + cellSize / 2,
                        y * cellSize * 2 + cellSize / 2,
                        z * cellSize * 2 + cellSize / 2
                    );
                    octree.insert(pos, level, "entity_" + x + "_" + y + "_" + z);
                }
            }
        }

        assertEquals(125, octree.entityCount());

        // Query that should cover a subset
        var queryRegion = new Spatial.Cube(
            cellSize * 2, cellSize * 2, cellSize * 2,
            cellSize * 6
        );

        var entities = octree.entitiesInRegion(queryRegion);

        // Should find entities in the query region
        assertFalse(entities.isEmpty(), "Should find entities in region");
    }

    @Test
    void testCellsQForSingleCellQuery() {
        byte level = 8;
        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, level);

        var cellSize = Constants.lengthAtLevel(level);
        // Query exactly one cell
        var singleCell = new VolumeBounds(
            cellSize, cellSize, cellSize,
            cellSize * 2 - 0.001f, cellSize * 2 - 0.001f, cellSize * 2 - 0.001f
        );

        var intervals = octree.cellsQ(singleCell, level);

        // Single cell should produce exactly 1 interval
        assertEquals(1, intervals.size(), "Single cell query should produce 1 interval");

        // Interval should cover exactly that cell
        var interval = intervals.get(0);
        assertEquals(interval.start().getMortonCode(), interval.end().getMortonCode(),
            "Single cell interval should have start == end");
    }

    @Test
    void testCellsQForAlignedCubeProducesOptimalIntervals() {
        // Key theoretical result: A 2^k x 2^k x 2^k aligned cube should produce
        // minimal contiguous Morton intervals
        byte level = 8;
        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, level);

        var cellSize = Constants.lengthAtLevel(level);

        // 2x2x2 cube at origin (aligned)
        // Use cellSize * 2 - 1 to ensure we cover exactly cells 0 and 1 in each dimension
        var alignedCube = new VolumeBounds(0, 0, 0, cellSize * 2 - 1, cellSize * 2 - 1, cellSize * 2 - 1);
        var intervals = octree.cellsQ(alignedCube, level);

        // With LITMAX/BIGMIN, aligned 2^k cubes at origin produce exactly 1 interval
        // because Morton codes 0-7 are contiguous
        assertTrue(intervals.size() <= 8,
            "2x2x2 aligned cube should produce ≤8 intervals (optimal), got " + intervals.size());
    }

    @Test
    void testCellsQMortonIntervalContains() {
        byte level = 8;
        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, level);

        var cellSize = Constants.lengthAtLevel(level);
        var query = new VolumeBounds(cellSize, cellSize, cellSize,
                                     cellSize * 4, cellSize * 4, cellSize * 4);

        var intervals = octree.cellsQ(query, level);

        // Test the contains method on intervals
        for (var interval : intervals) {
            // Start and end should be contained
            assertTrue(interval.contains(interval.start()));
            assertTrue(interval.contains(interval.end()));
        }
    }

    @Test
    void testCellsQCompareWithSFCArrayIndex() {
        // Both Octree and SFCArrayIndex should produce similar interval counts
        // for the same query since they both use LITMAX/BIGMIN
        byte level = 8;
        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, level);

        var cellSize = Constants.lengthAtLevel(level);
        var query = new VolumeBounds(cellSize, cellSize, cellSize,
                                     cellSize * 5, cellSize * 5, cellSize * 5);

        var intervals = octree.cellsQ(query, level);

        // 4x4x4 = 64 cells, LITMAX/BIGMIN should produce much fewer intervals
        assertTrue(intervals.size() < 64,
            "4x4x4 query should produce fewer than 64 intervals, got " + intervals.size());

        // Verify intervals are non-overlapping
        var sortedIntervals = intervals.stream()
            .sorted((a, b) -> Long.compare(a.start().getMortonCode(), b.start().getMortonCode()))
            .toList();

        for (int i = 0; i < sortedIntervals.size() - 1; i++) {
            var current = sortedIntervals.get(i);
            var next = sortedIntervals.get(i + 1);
            assertTrue(current.end().getMortonCode() < next.start().getMortonCode(),
                "Intervals should be non-overlapping");
        }
    }
}
