/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.113: empty-case getStats() must satisfy min &lt;= max.
 *
 * @author hal.hildebrand
 */
class LatencyTrackerEmptyStatsTest {

    @Test
    void emptyStatsSatisfyMinLessThanOrEqualMax() {
        var tracker = new LatencyTracker();
        var stats = tracker.getStats();

        assertEquals(0L, stats.sampleCount(), "no samples recorded");
        // Pre-fix: minLatencyNs was Long.MAX_VALUE, violating min <= max.
        assertTrue(stats.minLatencyNs() <= stats.maxLatencyNs(),
                   "empty stats must satisfy min <= max (min=" + stats.minLatencyNs()
                   + ", max=" + stats.maxLatencyNs() + ")");
        assertEquals(0L, stats.minLatencyNs(), "empty min is 0, not Long.MAX_VALUE");
        assertEquals(0L, stats.maxLatencyNs());
    }

    @Test
    void populatedStatsStillSatisfyMinLessThanOrEqualMax() {
        var tracker = new LatencyTracker();
        tracker.record(100L);
        tracker.record(50L);
        tracker.record(200L);
        var stats = tracker.getStats();

        assertEquals(3L, stats.sampleCount());
        assertTrue(stats.minLatencyNs() <= stats.maxLatencyNs());
        assertEquals(50L, stats.minLatencyNs());
        assertEquals(200L, stats.maxLatencyNs());
    }
}
