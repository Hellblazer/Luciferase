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

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LITMAX/BIGMIN range query optimization.
 *
 * LITMAX/BIGMIN is an algorithm for computing optimal Morton code intervals
 * that cover an axis-aligned query box. This test validates:
 * - bigmin() skips invalid Morton codes efficiently
 * - computeIntervals() produces correct intervals
 * - Interval coverage is complete (no gaps)
 * - Interval count is optimal (at most 8 for 2x2x2)
 *
 * @author hal.hildebrand
 */
class LitmaxBigminTest {

    // ================================
    // bigmin() Tests
    // ================================

    @Test
    void testBigminInsideQuery() {
        // Current is inside the query box - should return current + 1
        int minX = 2, minY = 2, minZ = 2;
        int maxX = 5, maxY = 5, maxZ = 5;

        long current = MortonCurve.encode(3, 3, 3);
        long next = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);

        assertEquals(current + 1, next, "Should return current + 1 when inside query");
    }

    @Test
    void testBigminBeforeQueryJumpsToCorner() {
        // Current is before query in X - should jump to query corner
        int minX = 5, minY = 5, minZ = 5;
        int maxX = 10, maxY = 10, maxZ = 10;

        long current = MortonCurve.encode(0, 5, 5);  // X is before minX
        long next = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);

        // Should jump to at least (5, 5, 5)
        var coords = MortonCurve.decode(next);
        assertTrue(coords[0] >= minX, "X should be >= minX after jump");
        assertTrue(next > current, "Should advance past current");
    }

    @Test
    void testBigminPastQueryIncrementsToKeepSearching() {
        // Current is past query in X - should increment (not -1)
        // due to Morton curve structure, valid codes can appear later
        int minX = 2, minY = 2, minZ = 2;
        int maxX = 5, maxY = 5, maxZ = 5;

        long current = MortonCurve.encode(10, 3, 3);  // X is past maxX
        long next = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);

        assertTrue(next > current, "Should increment past current when past query");
    }

    @Test
    void testBigminAtBoundary() {
        // Current is exactly at query boundary
        int minX = 5, minY = 5, minZ = 5;
        int maxX = 5, maxY = 5, maxZ = 5;  // Single cell query

        long current = MortonCurve.encode(5, 5, 5);
        long next = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);

        assertEquals(current + 1, next, "Should return current + 1 at boundary");
    }

    // ================================
    // findNextInRange() Tests
    // ================================

    @Test
    void testFindNextInRangeStartsInQuery() {
        int minX = 2, minY = 2, minZ = 2;
        int maxX = 5, maxY = 5, maxZ = 5;

        long start = MortonCurve.encode(2, 2, 2);
        long maxMorton = MortonCurve.encode(5, 5, 5);

        long next = LitmaxBigmin.findNextInRange(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);

        assertEquals(start, next, "Should return start when start is in query");
    }

    @Test
    void testFindNextInRangeSkipsToQuery() {
        int minX = 5, minY = 5, minZ = 5;
        int maxX = 10, maxY = 10, maxZ = 10;

        long start = MortonCurve.encode(0, 0, 0);  // Before query
        long maxMorton = MortonCurve.encode(10, 10, 10);

        long next = LitmaxBigmin.findNextInRange(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);

        // Should find first point in query
        assertTrue(next >= 0, "Should find a point in query");
        var coords = MortonCurve.decode(next);
        assertTrue(coords[0] >= minX && coords[0] <= maxX, "X should be in range");
        assertTrue(coords[1] >= minY && coords[1] <= maxY, "Y should be in range");
        assertTrue(coords[2] >= minZ && coords[2] <= maxZ, "Z should be in range");
    }

    @Test
    void testFindNextInRangeNoMatch() {
        int minX = 100, minY = 100, minZ = 100;
        int maxX = 110, maxY = 110, maxZ = 110;

        long start = MortonCurve.encode(0, 0, 0);
        long maxMorton = MortonCurve.encode(50, 50, 50);  // Max is before query

        long next = LitmaxBigmin.findNextInRange(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);

        assertEquals(-1, next, "Should return -1 when no match in range");
    }

    // ================================
    // findIntervalEnd() Tests
    // ================================

    @Test
    void testFindIntervalEndSingleCell() {
        int minX = 5, minY = 5, minZ = 5;
        int maxX = 5, maxY = 5, maxZ = 5;  // Single cell

        long start = MortonCurve.encode(5, 5, 5);
        long maxMorton = MortonCurve.encode(10, 10, 10);

        long end = LitmaxBigmin.findIntervalEnd(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);

        assertEquals(start, end, "Single cell interval should have start == end");
    }

    @Test
    void testFindIntervalEndContiguousRange() {
        // Query a range where Morton codes are contiguous
        int minX = 0, minY = 0, minZ = 0;
        int maxX = 1, maxY = 0, maxZ = 0;  // Two cells along X

        long start = MortonCurve.encode(0, 0, 0);
        long maxMorton = MortonCurve.encode(10, 10, 10);

        long end = LitmaxBigmin.findIntervalEnd(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);

        // End should be at least start
        assertTrue(end >= start, "End should be >= start");
    }

    // ================================
    // computeIntervals() Tests
    // ================================

    @Test
    void testComputeIntervalsSingleCell() {
        int minX = 5, minY = 5, minZ = 5;
        int maxX = 5, maxY = 5, maxZ = 5;

        List<SFCInterval> intervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        assertEquals(1, intervals.size(), "Single cell should produce 1 interval");

        var interval = intervals.get(0);
        assertEquals(interval.start(), interval.end(), "Single cell interval has start == end");
    }

    @Test
    void testComputeIntervals2x2x2ProducesAtMost8Intervals() {
        // A 2x2x2 query box should produce at most 8 intervals (worst case)
        int minX = 4, minY = 4, minZ = 4;
        int maxX = 5, maxY = 5, maxZ = 5;

        List<SFCInterval> intervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        assertTrue(intervals.size() <= 8,
            "2x2x2 query should produce at most 8 intervals, got: " + intervals.size());
        assertFalse(intervals.isEmpty(), "Should produce at least one interval");
    }

    @Test
    void testComputeIntervalsCompleteCoverage() {
        // Verify that intervals cover all cells in query box
        int minX = 2, minY = 2, minZ = 2;
        int maxX = 4, maxY = 4, maxZ = 4;  // 3x3x3 = 27 cells

        List<SFCInterval> intervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        // Count total cells covered by intervals
        long totalCells = 0;
        for (var interval : intervals) {
            totalCells += interval.end() - interval.start() + 1;
        }

        // Should cover exactly 27 cells
        int expectedCells = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        assertEquals(expectedCells, totalCells,
            "Intervals should cover exactly " + expectedCells + " cells");
    }

    @Test
    void testComputeIntervalsNoOverlap() {
        int minX = 1, minY = 1, minZ = 1;
        int maxX = 5, maxY = 5, maxZ = 5;

        List<SFCInterval> intervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        // Verify no overlapping intervals
        Set<Long> coveredCodes = new HashSet<>();
        for (var interval : intervals) {
            for (long code = interval.start(); code <= interval.end(); code++) {
                assertTrue(coveredCodes.add(code),
                    "Morton code " + code + " covered by multiple intervals");
            }
        }
    }

    @Test
    void testComputeIntervalsAllCodesInQuery() {
        int minX = 2, minY = 2, minZ = 2;
        int maxX = 3, maxY = 3, maxZ = 3;

        List<SFCInterval> intervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        // Collect all Morton codes in intervals
        Set<Long> coveredCodes = new HashSet<>();
        for (var interval : intervals) {
            for (long code = interval.start(); code <= interval.end(); code++) {
                coveredCodes.add(code);
            }
        }

        // Verify each covered code is actually in the query box
        for (long code : coveredCodes) {
            var coords = MortonCurve.decode(code);
            assertTrue(coords[0] >= minX && coords[0] <= maxX,
                "X coordinate " + coords[0] + " should be in [" + minX + ", " + maxX + "]");
            assertTrue(coords[1] >= minY && coords[1] <= maxY,
                "Y coordinate " + coords[1] + " should be in [" + minY + ", " + maxY + "]");
            assertTrue(coords[2] >= minZ && coords[2] <= maxZ,
                "Z coordinate " + coords[2] + " should be in [" + minZ + ", " + maxZ + "]");
        }
    }

    @Test
    void testComputeIntervalsOrigin() {
        // Query at origin
        int minX = 0, minY = 0, minZ = 0;
        int maxX = 1, maxY = 1, maxZ = 1;

        List<SFCInterval> intervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        assertFalse(intervals.isEmpty(), "Origin query should produce intervals");
        assertEquals(0, intervals.get(0).start(), "First interval should start at 0");
    }

    @Test
    void testComputeIntervalsLargeQuery() {
        // Larger query box
        int minX = 0, minY = 0, minZ = 0;
        int maxX = 7, maxY = 7, maxZ = 7;  // 8x8x8 = 512 cells

        List<SFCInterval> intervals = LitmaxBigmin.computeIntervals(minX, minY, minZ, maxX, maxY, maxZ);

        // Count total cells
        long totalCells = 0;
        for (var interval : intervals) {
            totalCells += interval.end() - interval.start() + 1;
        }

        assertEquals(512, totalCells, "8x8x8 query should cover 512 cells");
    }

    // ================================
    // SFCInterval Record Tests
    // ================================

    @Test
    void testSFCIntervalCellCount() {
        var interval = new SFCInterval(10, 20);

        assertEquals(11, interval.cellCount(), "Interval [10, 20] has 11 cells");
    }

    @Test
    void testSFCIntervalContains() {
        var interval = new SFCInterval(10, 20);

        assertTrue(interval.contains(10), "Should contain start");
        assertTrue(interval.contains(15), "Should contain middle");
        assertTrue(interval.contains(20), "Should contain end");
        assertFalse(interval.contains(9), "Should not contain before start");
        assertFalse(interval.contains(21), "Should not contain after end");
    }

    @Test
    void testSFCIntervalSingleCell() {
        var interval = new SFCInterval(42, 42);

        assertEquals(1, interval.cellCount(), "Single cell interval has count 1");
        assertTrue(interval.contains(42), "Should contain the single cell");
        assertFalse(interval.contains(41), "Should not contain adjacent cells");
        assertFalse(interval.contains(43), "Should not contain adjacent cells");
    }
}
