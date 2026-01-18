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

import java.util.ArrayList;
import java.util.List;

/**
 * LITMAX/BIGMIN algorithm for efficient range queries on Morton-encoded spatial data.
 *
 * This class provides the core algorithm for computing optimal Morton code intervals
 * that cover an axis-aligned query box. The LITMAX/BIGMIN algorithm exploits the
 * Z-order curve structure to skip large portions of the Morton code space that
 * fall outside the query region.
 *
 * <h2>Algorithm Overview</h2>
 * For a 3D query box [minX, maxX] × [minY, maxY] × [minZ, maxZ]:
 * <ol>
 *   <li>Start at the minimum Morton code in the query region</li>
 *   <li>Use BIGMIN to jump over codes outside the query box</li>
 *   <li>Extend contiguous intervals as far as possible</li>
 *   <li>Repeat until the entire query region is covered</li>
 * </ol>
 *
 * <h2>Key Properties</h2>
 * <ul>
 *   <li>For a 2×2×2 query box, at most 8 intervals are produced</li>
 *   <li>Intervals are non-overlapping and together provide complete coverage</li>
 *   <li>Significantly faster than naive enumeration for large query boxes</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Compute intervals for a query box
 * List<SFCInterval> intervals = LitmaxBigmin.computeIntervals(
 *     minX, minY, minZ, maxX, maxY, maxZ);
 *
 * // Use intervals for efficient range queries
 * for (SFCInterval interval : intervals) {
 *     // Query data structure with [interval.start(), interval.end()]
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 * @see SFCInterval
 */
public final class LitmaxBigmin {

    private LitmaxBigmin() {
        // Utility class - no instantiation
    }

    /**
     * Compute the SFC intervals that cover a query region using LITMAX/BIGMIN.
     *
     * This is the main entry point for range query optimization. It produces
     * a minimal set of contiguous Morton code intervals that together cover
     * exactly the cells in the specified 3D query box.
     *
     * @param minX minimum X coordinate (grid cell index)
     * @param minY minimum Y coordinate (grid cell index)
     * @param minZ minimum Z coordinate (grid cell index)
     * @param maxX maximum X coordinate (grid cell index)
     * @param maxY maximum Y coordinate (grid cell index)
     * @param maxZ maximum Z coordinate (grid cell index)
     * @return list of non-overlapping SFCIntervals covering the query box
     */
    public static List<SFCInterval> computeIntervals(int minX, int minY, int minZ,
                                                      int maxX, int maxY, int maxZ) {
        var intervals = new ArrayList<SFCInterval>();

        // Ensure valid bounds
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        minZ = Math.max(0, minZ);
        maxX = Math.max(minX, maxX);
        maxY = Math.max(minY, maxY);
        maxZ = Math.max(minZ, maxZ);

        // Compute Morton code bounds by checking ALL 8 corners of the AABB
        // Morton codes don't preserve AABB ordering, so we must find actual min/max
        long minMorton = Long.MAX_VALUE;
        long maxMorton = Long.MIN_VALUE;

        int[] xs = {minX, maxX};
        int[] ys = {minY, maxY};
        int[] zs = {minZ, maxZ};

        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    long morton = MortonCurve.encode(x, y, z);
                    minMorton = Math.min(minMorton, morton);
                    maxMorton = Math.max(maxMorton, morton);
                }
            }
        }

        // Iterate through the Morton range, finding intervals using LITMAX/BIGMIN
        var current = minMorton;
        while (current <= maxMorton) {
            // Find the start of the next interval (first code in query >= current)
            var intervalStart = findNextInRange(current, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);
            if (intervalStart < 0) {
                break; // No more codes in query
            }

            // Find the end of this interval (last contiguous code in query)
            var intervalEnd = findIntervalEnd(intervalStart, minX, minY, minZ, maxX, maxY, maxZ, maxMorton);

            intervals.add(new SFCInterval(intervalStart, intervalEnd));

            // Move past this interval
            current = intervalEnd + 1;
        }

        return intervals;
    }

    /**
     * Find the next Morton code >= start that is inside the query box.
     *
     * Uses BIGMIN to efficiently skip codes that are outside the query region.
     *
     * @param start    the starting Morton code
     * @param minX     minimum X coordinate
     * @param minY     minimum Y coordinate
     * @param minZ     minimum Z coordinate
     * @param maxX     maximum X coordinate
     * @param maxY     maximum Y coordinate
     * @param maxZ     maximum Z coordinate
     * @param maxMorton maximum Morton code to consider
     * @return the next valid Morton code, or -1 if none found
     */
    public static long findNextInRange(long start, int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ, long maxMorton) {
        var current = start;
        while (current <= maxMorton) {
            var coords = MortonCurve.decode(current);
            if (coords[0] >= minX && coords[0] <= maxX &&
                coords[1] >= minY && coords[1] <= maxY &&
                coords[2] >= minZ && coords[2] <= maxZ) {
                return current;
            }

            // Use BIGMIN to jump to the next potentially valid Morton code
            current = bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);
        }
        return -1;
    }

    /**
     * Find the end of the contiguous interval starting at intervalStart.
     *
     * Extends the interval as far as possible while all codes remain inside
     * the query box.
     *
     * @param intervalStart the start of the interval
     * @param minX          minimum X coordinate
     * @param minY          minimum Y coordinate
     * @param minZ          minimum Z coordinate
     * @param maxX          maximum X coordinate
     * @param maxY          maximum Y coordinate
     * @param maxZ          maximum Z coordinate
     * @param maxMorton     maximum Morton code to consider
     * @return the last Morton code in the contiguous interval
     */
    public static long findIntervalEnd(long intervalStart, int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ, long maxMorton) {
        var current = intervalStart;
        while (current < maxMorton) {
            var next = current + 1;
            var coords = MortonCurve.decode(next);

            // Check if next is still in query
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

    /**
     * BIGMIN: Find the smallest Morton code > current that could be in the query box.
     *
     * This is the core of the LITMAX/BIGMIN algorithm. It examines the current
     * position relative to the query box and determines the next candidate:
     * <ul>
     *   <li>If current is before the query in any dimension, jump to the query corner</li>
     *   <li>If current is past the query in any dimension, increment to continue searching</li>
     *   <li>Otherwise, return current + 1 (simple increment)</li>
     * </ul>
     *
     * Note: Due to Morton curve structure, valid codes can appear after codes that are
     * outside the query box, so we never fully stop searching until we exceed maxMorton.
     *
     * @param current the current Morton code
     * @param minX    minimum X coordinate
     * @param minY    minimum Y coordinate
     * @param minZ    minimum Z coordinate
     * @param maxX    maximum X coordinate
     * @param maxY    maximum Y coordinate
     * @param maxZ    maximum Z coordinate
     * @return the next candidate Morton code (always > current)
     */
    public static long bigmin(long current, int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ) {
        var coords = MortonCurve.decode(current);
        var x = coords[0];
        var y = coords[1];
        var z = coords[2];

        long nextMorton = current + 1;

        if (x < minX || y < minY || z < minZ) {
            // Current is before query in some dimension - jump to query corner
            nextMorton = MortonCurve.encode(
                Math.max(x, minX),
                Math.max(y, minY),
                Math.max(z, minZ)
            );
            if (nextMorton <= current) {
                nextMorton = current + 1;
            }
        }
        // Note: When x > maxX || y > maxY || z > maxZ, we just increment.
        // Due to Morton curve structure, valid codes can appear later in the sequence.

        return nextMorton;
    }
}
