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

/**
 * Represents a contiguous interval of Morton codes on a space-filling curve.
 *
 * SFC intervals are the output of LITMAX/BIGMIN range query optimization.
 * For an axis-aligned query box, the algorithm produces a minimal set of
 * these intervals that together cover exactly the cells in the query region.
 *
 * Key properties:
 * - Intervals are contiguous: all codes from start to end (inclusive) are covered
 * - Intervals do not overlap
 * - Together they provide complete coverage of the query box
 * - For a 2x2x2 query, at most 8 intervals are produced
 *
 * @param start the first Morton code in the interval (inclusive)
 * @param end   the last Morton code in the interval (inclusive)
 *
 * @author hal.hildebrand
 */
public record SFCInterval(long start, long end) {

    /**
     * Compact constructor with validation.
     */
    public SFCInterval {
        if (start < 0) {
            throw new IllegalArgumentException("Start must be non-negative: " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException("End (" + end + ") must be >= start (" + start + ")");
        }
    }

    /**
     * Returns the number of cells (Morton codes) covered by this interval.
     *
     * @return the cell count (always >= 1)
     */
    public long cellCount() {
        return end - start + 1;
    }

    /**
     * Checks if this interval contains the given Morton code.
     *
     * @param mortonCode the Morton code to check
     * @return true if the code is within [start, end]
     */
    public boolean contains(long mortonCode) {
        return mortonCode >= start && mortonCode <= end;
    }

    /**
     * Checks if this interval overlaps with another interval.
     *
     * @param other the other interval
     * @return true if the intervals share at least one Morton code
     */
    public boolean overlaps(SFCInterval other) {
        return this.start <= other.end && other.start <= this.end;
    }

    /**
     * Returns a string representation showing the Morton code range.
     */
    @Override
    public String toString() {
        return "SFCInterval[" + start + ".." + end + " (" + cellCount() + " cells)]";
    }
}
