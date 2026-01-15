/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DensityMonitor hysteresis state machine.
 *
 * @author hal.hildebrand
 */
class DensityMonitorTest {

    private DensityMonitor monitor;
    private UUID bubbleId;

    @BeforeEach
    void setUp() {
        monitor = new DensityMonitor(5000, 500); // Split at 5000, merge at 500
        bubbleId = UUID.randomUUID();
    }

    @Test
    void testSplitThresholdDetection() {
        // Start with normal density
        var distribution = Map.of(bubbleId, 2000);
        monitor.update(distribution);

        var state = monitor.getState(bubbleId);
        assertEquals(DensityState.NORMAL, state, "Should start in NORMAL state");

        // Approach split threshold (90% of 5000 = 4500)
        distribution = Map.of(bubbleId, 4600);
        monitor.update(distribution);

        state = monitor.getState(bubbleId);
        assertEquals(DensityState.APPROACHING_SPLIT, state, "Should transition to APPROACHING_SPLIT");

        // Exceed split threshold
        distribution = Map.of(bubbleId, 5100);
        monitor.update(distribution);

        state = monitor.getState(bubbleId);
        assertEquals(DensityState.NEEDS_SPLIT, state, "Should transition to NEEDS_SPLIT");
        assertTrue(monitor.needsSplit(bubbleId), "Should report needs split");
    }

    @Test
    void testMergeThresholdDetection() {
        // Start with normal density
        var distribution = Map.of(bubbleId, 2000);
        monitor.update(distribution);

        var state = monitor.getState(bubbleId);
        assertEquals(DensityState.NORMAL, state, "Should start in NORMAL state");

        // Approach merge threshold (110% of 500 = 550)
        distribution = Map.of(bubbleId, 540);
        monitor.update(distribution);

        state = monitor.getState(bubbleId);
        assertEquals(DensityState.APPROACHING_MERGE, state, "Should transition to APPROACHING_MERGE");

        // Drop below merge threshold
        distribution = Map.of(bubbleId, 450);
        monitor.update(distribution);

        state = monitor.getState(bubbleId);
        assertEquals(DensityState.NEEDS_MERGE, state, "Should transition to NEEDS_MERGE");
        assertTrue(monitor.needsMerge(bubbleId), "Should report needs merge");
    }

    @Test
    void testHysteresisPreventsOscillation() {
        // Go into NEEDS_SPLIT state
        var distribution = Map.of(bubbleId, 5100);
        monitor.update(distribution);
        assertEquals(DensityState.NEEDS_SPLIT, monitor.getState(bubbleId));

        // Drop slightly below threshold - should require 10% drop to clear
        // 5000 * 0.9 = 4500 is the hysteresis boundary
        distribution = Map.of(bubbleId, 4900);
        monitor.update(distribution);
        assertEquals(DensityState.NEEDS_SPLIT, monitor.getState(bubbleId), "Should stay in NEEDS_SPLIT (hysteresis)");

        // Drop to 90% of threshold - should clear
        distribution = Map.of(bubbleId, 4400);
        monitor.update(distribution);
        assertEquals(DensityState.APPROACHING_SPLIT, monitor.getState(bubbleId),
                     "Should clear to APPROACHING_SPLIT after 10% drop");

        // Similarly for merge: go into NEEDS_MERGE state
        distribution = Map.of(bubbleId, 450);
        monitor.update(distribution);
        assertEquals(DensityState.NEEDS_MERGE, monitor.getState(bubbleId));

        // Rise slightly above threshold - should require 10% rise to clear
        // 500 * 1.1 = 550 is the hysteresis boundary
        distribution = Map.of(bubbleId, 520);
        monitor.update(distribution);
        assertEquals(DensityState.NEEDS_MERGE, monitor.getState(bubbleId),
                     "Should stay in NEEDS_MERGE (hysteresis)");

        // Rise to 110% of threshold - should clear
        distribution = Map.of(bubbleId, 560);
        monitor.update(distribution);
        assertEquals(DensityState.APPROACHING_MERGE, monitor.getState(bubbleId),
                     "Should clear to APPROACHING_MERGE after 10% rise");
    }

    @Test
    void testDensityRatioCalculation() {
        // Test split ratio calculation
        var distribution = Map.of(bubbleId, 5000);
        monitor.update(distribution);

        var ratio = monitor.getSplitRatio(bubbleId);
        assertEquals(1.0f, ratio, 0.01f, "Split ratio should be 1.0 at threshold");

        distribution = Map.of(bubbleId, 6000);
        monitor.update(distribution);

        ratio = monitor.getSplitRatio(bubbleId);
        assertEquals(1.2f, ratio, 0.01f, "Split ratio should be 1.2 at 120%");

        // Test merge ratio calculation
        distribution = Map.of(bubbleId, 500);
        monitor.update(distribution);

        ratio = monitor.getMergeRatio(bubbleId);
        assertEquals(1.0f, ratio, 0.01f, "Merge ratio should be 1.0 at threshold");

        distribution = Map.of(bubbleId, 250);
        monitor.update(distribution);

        ratio = monitor.getMergeRatio(bubbleId);
        assertEquals(0.5f, ratio, 0.01f, "Merge ratio should be 0.5 at 50%");
    }

    @Test
    void testMultipleBubbles() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var bubble3 = UUID.randomUUID();

        var distribution = Map.of(
            bubble1, 5100,  // Needs split
            bubble2, 2000,  // Normal
            bubble3, 450    // Needs merge
        );

        monitor.update(distribution);

        assertEquals(DensityState.NEEDS_SPLIT, monitor.getState(bubble1));
        assertEquals(DensityState.NORMAL, monitor.getState(bubble2));
        assertEquals(DensityState.NEEDS_MERGE, monitor.getState(bubble3));

        assertTrue(monitor.needsSplit(bubble1));
        assertFalse(monitor.needsSplit(bubble2));
        assertFalse(monitor.needsSplit(bubble3));

        assertFalse(monitor.needsMerge(bubble1));
        assertFalse(monitor.needsMerge(bubble2));
        assertTrue(monitor.needsMerge(bubble3));
    }
}
