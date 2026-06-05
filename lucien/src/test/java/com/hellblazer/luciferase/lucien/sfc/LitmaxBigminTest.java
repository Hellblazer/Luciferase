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

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
        // Canonical BIGMIN (Luciferase-lield): returns the smallest IN-BOX Morton code strictly greater than
        // current — NOT a blind current+1 (current+1 may be outside the box). It must advance and stay in-box.
        int minX = 2, minY = 2, minZ = 2;
        int maxX = 5, maxY = 5, maxZ = 5;

        long current = MortonCurve.encode(3, 3, 3);
        long next = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);

        assertTrue(next > current, "BIGMIN must advance past current");
        var c = MortonCurve.decode(next);
        assertTrue(c[0] >= minX && c[0] <= maxX && c[1] >= minY && c[1] <= maxY && c[2] >= minZ && c[2] <= maxZ,
                   "BIGMIN result must be inside the query box (Luciferase-lield)");
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
        // Single-cell box; current IS that cell (the only in-box code). There is no in-box code greater than current,
        // so canonical BIGMIN reports "no further" rather than scanning forward (Luciferase-lield).
        int minX = 5, minY = 5, minZ = 5;
        int maxX = 5, maxY = 5, maxZ = 5;  // single cell

        long current = MortonCurve.encode(5, 5, 5);
        long next = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);

        assertEquals(Long.MAX_VALUE, next, "no in-box code exists above the single cell -> BIGMIN terminates");
    }

    // ================================
    // bigmin() edge cases (Luciferase-7wzml.141)
    // ================================

    /**
     * Edge case (a): current is already above the entire query box in all dimensions.
     * bigmin() must return Long.MAX_VALUE (the guard at the end normalises this),
     * and findNextInRange must terminate and return -1 (not loop forever).
     */
    @Test
    void testBigminCurrentAboveBox_findNextInRangeTerminates() {
        int minX = 2, minY = 2, minZ = 2;
        int maxX = 4, maxY = 4, maxZ = 4;

        // current is well above the box
        long current = MortonCurve.encode(10, 10, 10);
        long maxMorton = MortonCurve.encode(5, 5, 5);  // also above box, but below current

        // bigmin must return MAX_VALUE (no in-box code above current)
        long bm = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);
        assertEquals(Long.MAX_VALUE, bm, "bigmin should return MAX_VALUE when current is above the box");

        // findNextInRange with maxMorton < current: must return -1, not hang
        long next = assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> LitmaxBigmin.findNextInRange(current, minX, minY, minZ, maxX, maxY, maxZ, maxMorton),
            "findNextInRange must terminate when current > maxMorton");
        assertEquals(-1, next, "findNextInRange returns -1 when start is above maxMorton");
    }

    /**
     * Edge case (b): single-cell box (min==max). findNextInRange must return the cell
     * if current equals it, and return -1 if current is already past it.
     */
    @Test
    void testBigminSingleCellBox_findNextInRangeTerminates() {
        int cx = 5, cy = 5, cz = 5;
        long cell = MortonCurve.encode(cx, cy, cz);
        long maxMorton = MortonCurve.encode(cx + 2, cy + 2, cz + 2);

        // Start at the cell itself — must be found
        long found = LitmaxBigmin.findNextInRange(cell, cx, cy, cz, cx, cy, cz, maxMorton);
        assertEquals(cell, found, "findNextInRange on single-cell box returns the cell when start==cell");

        // Start past the cell — must return -1 (not hang via MAX_VALUE loop)
        long past = MortonCurve.encode(cx + 1, cy + 1, cz + 1);
        long notFound = assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> LitmaxBigmin.findNextInRange(past, cx, cy, cz, cx, cy, cz, maxMorton),
            "findNextInRange must terminate for single-cell box when start > cell");
        assertEquals(-1, notFound, "findNextInRange returns -1 when past the only cell in box");
    }

    /**
     * Edge case (c): current == maxMorton.
     * findNextInRange must check the single remaining code and return -1 or the code,
     * then terminate.  It must not advance past maxMorton.
     */
    @Test
    void testBigminCurrentEqualsMaxMorton_findNextInRangeTerminates() {
        int minX = 2, minY = 2, minZ = 2;
        int maxX = 5, maxY = 5, maxZ = 5;

        // maxMorton is inside the box
        long maxMorton = MortonCurve.encode(3, 3, 3);

        long result = assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> LitmaxBigmin.findNextInRange(maxMorton, minX, minY, minZ, maxX, maxY, maxZ, maxMorton),
            "findNextInRange must terminate when current == maxMorton");
        assertEquals(maxMorton, result, "findNextInRange returns maxMorton when it is inside the box");

        // maxMorton is outside the box (above): must return -1
        long outsideMax = MortonCurve.encode(10, 10, 10);
        long result2 = assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> LitmaxBigmin.findNextInRange(outsideMax, minX, minY, minZ, maxX, maxY, maxZ, outsideMax),
            "findNextInRange must terminate when current == maxMorton and both outside box");
        assertEquals(-1, result2, "findNextInRange returns -1 when current == maxMorton and outside box");
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

    // ================================================================
    // findIntervalEnd() O(bits) optimization — PARITY & PERFORMANCE
    // ================================================================

    /**
     * Linear reference implementation — kept ONLY in the test class as the oracle.
     * Never use in production; it is O(range) and exists solely to verify parity
     * with the optimised O(bits) implementation.
     */
    private static long findIntervalEndLinear(long intervalStart, int minX, int minY, int minZ,
                                               int maxX, int maxY, int maxZ, long maxMorton) {
        long current = intervalStart;
        while (current < maxMorton) {
            long next = current + 1;
            int[] coords = MortonCurve.decode(next);
            if (coords[0] >= minX && coords[0] <= maxX &&
                coords[1] >= minY && coords[1] <= maxY &&
                coords[2] >= minZ && coords[2] <= maxZ) {
                current = next;
            } else {
                break;
            }
        }
        return current;
    }

    @Test
    void testFindIntervalEndParitySmallBoxes() {
        // PARITY: optimised O(bits) must return the SAME result as the linear reference
        // for a variety of representative small boxes and starting points.
        int[][][] boxes = {
            // { minX, minY, minZ, maxX, maxY, maxZ }
            { { 0, 0, 0 }, { 1, 1, 1 } },     // origin 2x2x2
            { { 2, 2, 2 }, { 5, 5, 5 } },     // interior 4x4x4
            { { 0, 0, 0 }, { 7, 7, 7 } },     // perfect cube 8x8x8
            { { 3, 4, 5 }, { 7, 8, 9 } },     // asymmetric box
            { { 0, 0, 0 }, { 3, 3, 3 } },     // 4x4x4 aligned
            { { 1, 0, 0 }, { 4, 0, 0 } },     // thin slab along X
            { { 5, 5, 5 }, { 5, 5, 5 } },     // single cell
        };

        for (int[][] box : boxes) {
            int minX = box[0][0], minY = box[0][1], minZ = box[0][2];
            int maxX = box[1][0], maxY = box[1][1], maxZ = box[1][2];
            long maxMorton = MortonCurve.encode(maxX + 4, maxY + 4, maxZ + 4);

            // Try multiple starting points for each box
            long[] starts = {
                MortonCurve.encode(minX, minY, minZ),
                MortonCurve.encode((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
            };

            for (long start : starts) {
                // Make sure start is actually in the box
                int[] sc = MortonCurve.decode(start);
                if (sc[0] < minX || sc[0] > maxX || sc[1] < minY || sc[1] > maxY || sc[2] < minZ || sc[2] > maxZ) {
                    continue;
                }

                long expected = findIntervalEndLinear(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);
                long actual   = LitmaxBigmin.findIntervalEnd(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);

                assertEquals(expected, actual,
                    String.format("findIntervalEnd parity failed for box [%d,%d,%d]-[%d,%d,%d] start=%d",
                                  minX, minY, minZ, maxX, maxY, maxZ, start));
            }
        }
    }

    @Test
    void testFindIntervalEndParitySystematic() {
        // PARITY: exhaustive comparison over a 6x6x6 grid of boxes, every cell as start
        int GRID = 6;
        long maxMorton = MortonCurve.encode(GRID + 2, GRID + 2, GRID + 2);

        for (int x1 = 0; x1 < GRID; x1++) {
            for (int x2 = x1; x2 < GRID; x2++) {
                for (int y1 = 0; y1 < GRID; y1++) {
                    int y2 = Math.min(y1 + 2, GRID - 1);
                    for (int z1 = 0; z1 < GRID; z1++) {
                        int z2 = Math.min(z1 + 2, GRID - 1);
                        // Try every possible intervalStart within the box
                        for (int sx = x1; sx <= x2; sx++) {
                            for (int sy = y1; sy <= y2; sy++) {
                                for (int sz = z1; sz <= z2; sz++) {
                                    long start = MortonCurve.encode(sx, sy, sz);
                                    long expected = findIntervalEndLinear(
                                        start, x1, y1, z1, x2, y2, z2, maxMorton);
                                    long actual = LitmaxBigmin.findIntervalEnd(
                                        start, x1, y1, z1, x2, y2, z2, maxMorton);
                                    assertEquals(expected, actual,
                                        String.format("Systematic parity failed: box [%d,%d,%d]-[%d,%d,%d] start=(%d,%d,%d)",
                                                      x1, y1, z1, x2, y2, z2, sx, sy, sz));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void testFindIntervalEndParityWideBoxes() {
        // PARITY: geometries deliberately excluded from testFindIntervalEndParitySystematic
        // (which clamps y2/z2 to y1+2 / z1+2 and therefore never tests wide-Y, wide-Z,
        // full-domain, or single-cell boxes).  Each case asserts optimised == linear-oracle
        // for several starting points to confirm the O(bits²) rewrite is correct on these shapes.
        int GRID = 5; // coordinates 0..5; matches systematic test grid size
        long maxMorton = MortonCurve.encode(GRID + 2, GRID + 2, GRID + 2);

        // --- full-domain box: {0,0,0}-{GRID,GRID,GRID} ---
        checkParityForBox(0, 0, 0, GRID, GRID, GRID, maxMorton,
                          "full-domain {0,0,0}-{" + GRID + "," + GRID + "," + GRID + "}");

        // --- wide-Y slab: {0,0,0}-{1,GRID,0} ---
        checkParityForBox(0, 0, 0, 1, GRID, 0, maxMorton,
                          "wide-Y slab {0,0,0}-{1," + GRID + ",0}");

        // --- wide-Z slab: {0,0,0}-{0,0,GRID} ---
        checkParityForBox(0, 0, 0, 0, 0, GRID, maxMorton,
                          "wide-Z slab {0,0,0}-{0,0," + GRID + "}");

        // --- single-cell box: {2,2,2}-{2,2,2} ---
        checkParityForBox(2, 2, 2, 2, 2, 2, maxMorton,
                          "single-cell {2,2,2}");
    }

    /** Assert optimised findIntervalEnd == linear oracle for all in-box starting points. */
    private void checkParityForBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                   long maxMorton, String label) {
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sy = minY; sy <= maxY; sy++) {
                for (int sz = minZ; sz <= maxZ; sz++) {
                    long start = MortonCurve.encode(sx, sy, sz);
                    long expected = findIntervalEndLinear(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);
                    long actual   = LitmaxBigmin.findIntervalEnd(start, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);
                    assertEquals(expected, actual,
                        String.format("findIntervalEnd parity failed [%s] start=(%d,%d,%d)", label, sx, sy, sz));
                }
            }
        }
    }

    @Test
    void testFindIntervalEndLargeBoxCompletesFast() {
        // PERFORMANCE: a box spanning 2^9 = 512 codes per dimension (10x10x10 octree levels)
        // has ~134 million Morton codes in its bounding range.  The LINEAR walk would iterate
        // through every single one; the O(bits) implementation must complete in < 1 second.
        int maxCoord = 1023;  // 10-bit coordinates: 2^10 = 1024 values per dim
        long startMorton = MortonCurve.encode(0, 0, 0);
        long maxMorton   = MortonCurve.encode(maxCoord, maxCoord, maxCoord);

        // The entire [0,1023]^3 box is aligned (starts at 0 which is 2^30-aligned).
        // The O(bits) implementation should jump to maxMorton in ~30 doubling steps.
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            long end = LitmaxBigmin.findIntervalEnd(
                startMorton, 0, 0, 0, maxCoord, maxCoord, maxCoord, maxMorton);
            // Every code in [0, encode(1023,1023,1023)] is inside [0,1023]^3
            // so the contiguous interval should extend to maxMorton.
            assertEquals(maxMorton, end,
                "Large aligned box: interval end should reach maxMorton = " + maxMorton);
        }, "findIntervalEnd must complete in O(bits) time, not O(range)");
    }

    @Test
    void testFindIntervalEndLargeBoxInteriorStart() {
        // PERFORMANCE: start from an interior point of a large box.
        // The contiguous run should still be computed in O(bits) time.
        int maxCoord = 511;  // 9-bit: 2^9 = 512 per dim
        long startMorton = MortonCurve.encode(4, 4, 4);
        long maxMorton   = MortonCurve.encode(maxCoord, maxCoord, maxCoord);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            long end = LitmaxBigmin.findIntervalEnd(
                startMorton, 0, 0, 0, maxCoord, maxCoord, maxCoord, maxMorton);
            assertTrue(end >= startMorton, "End must be >= start");
            // Verify parity on the SMALLER sub-problem to avoid slow linear reference
            // (linear would take too long for large range — just check correctness via
            //  the fact that the returned end IS in-box)
            int[] endCoords = MortonCurve.decode(end);
            assertTrue(endCoords[0] <= maxCoord && endCoords[1] <= maxCoord && endCoords[2] <= maxCoord,
                "Returned end must be inside the query box");
        }, "findIntervalEnd must complete in O(bits) time for large boxes");
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
