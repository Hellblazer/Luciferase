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
package com.hellblazer.luciferase.esvo.gpu.beam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Tests for BeamOptimizationGate - Phase 3: Decision Logic
 *
 * Tests the decision gate for conditionally enabling beam optimization.
 * Logic: Enable if (latency > target) AND (coherence > threshold).
 *
 * @author hal.hildebrand
 */
@DisplayName("Stream C: Beam Optimization Gate")
class BeamOptimizationGateTest {

    private BeamOptimizationGate gate;

    // Target: 100K rays in <100µs (10x improvement from 500-1000µs baseline)
    private static final double TARGET_LATENCY_MICROSECONDS = 100.0;
    private static final double COHERENCE_THRESHOLD = 0.5;

    @BeforeEach
    void setUp() {
        gate = new BeamOptimizationGate();
    }

    /**
     * Test 1: Both conditions met - enable optimization
     */
    @Test
    @DisplayName("Enable beam optimization when latency high and coherence high")
    void testEnableBeam_BothConditionsMet() {
        var latency = 500.0;  // Above target
        var coherence = 0.7;  // Above threshold

        var shouldEnable = gate.shouldEnableBeam(latency, coherence);

        assertTrue(shouldEnable,
                   "Should enable beam when latency=" + latency + "µs and coherence=" + coherence);
    }

    /**
     * Test 2: Latency low - don't enable (already fast enough)
     */
    @Test
    @DisplayName("Disable beam optimization when latency already meets target")
    void testDisableBeam_LatencyMeetsTarget() {
        var latency = 50.0;   // Below target (already fast)
        var coherence = 0.7;  // Above threshold

        var shouldEnable = gate.shouldEnableBeam(latency, coherence);

        assertFalse(shouldEnable,
                    "Should not enable beam when latency already meets target");
    }

    /**
     * Test 3: Coherence low - don't enable (won't benefit)
     */
    @Test
    @DisplayName("Disable beam optimization when coherence too low")
    void testDisableBeam_LowCoherence() {
        var latency = 500.0;  // Above target
        var coherence = 0.3;  // Below threshold

        var shouldEnable = gate.shouldEnableBeam(latency, coherence);

        assertFalse(shouldEnable,
                    "Should not enable beam when coherence too low to benefit");
    }

    /**
     * Test 4: Both conditions fail - don't enable
     */
    @Test
    @DisplayName("Disable beam optimization when both conditions fail")
    void testDisableBeam_BothConditionsFail() {
        var latency = 50.0;   // Below target
        var coherence = 0.3;  // Below threshold

        var shouldEnable = gate.shouldEnableBeam(latency, coherence);

        assertFalse(shouldEnable,
                    "Should not enable beam when neither condition is met");
    }

    /**
     * Test 5: Edge case - latency exactly at target
     */
    @Test
    @DisplayName("Disable beam at exact target latency")
    void testEdgeCase_ExactTargetLatency() {
        var latency = TARGET_LATENCY_MICROSECONDS;
        var coherence = 0.7;

        var shouldEnable = gate.shouldEnableBeam(latency, coherence);

        assertFalse(shouldEnable,
                    "Should not enable beam when latency exactly meets target");
    }

    /**
     * Test 6: Edge case - coherence exactly at threshold
     */
    @Test
    @DisplayName("Enable beam at exact coherence threshold")
    void testEdgeCase_ExactCoherenceThreshold() {
        var latency = 500.0;
        var coherence = COHERENCE_THRESHOLD;

        var shouldEnable = gate.shouldEnableBeam(latency, coherence);

        assertTrue(shouldEnable,
                   "Should enable beam when coherence exactly meets threshold");
    }

    /**
     * Test 7: Very high latency - definitely enable
     */
    @Test
    @DisplayName("Enable beam for very high latency")
    void testEnableBeam_VeryHighLatency() {
        var latency = 5000.0;  // 10x above target
        var coherence = 0.6;

        var shouldEnable = gate.shouldEnableBeam(latency, coherence);

        assertTrue(shouldEnable,
                   "Should definitely enable beam when latency very high");
    }

    /**
     * Test 8: Perfect coherence - enable if latency needs it
     */
    @Test
    @DisplayName("Enable beam with perfect coherence if latency high")
    void testEnableBeam_PerfectCoherence() {
        var latency = 300.0;
        var coherence = 1.0;  // Perfect coherence

        var shouldEnable = gate.shouldEnableBeam(latency, coherence);

        assertTrue(shouldEnable,
                   "Should enable beam with perfect coherence and high latency");
    }

    /**
     * Test 9: Invalid inputs - negative latency
     */
    @Test
    @DisplayName("Reject negative latency")
    void testInvalidInput_NegativeLatency() {
        var latency = -100.0;
        var coherence = 0.7;

        assertThrows(IllegalArgumentException.class,
                     () -> gate.shouldEnableBeam(latency, coherence),
                     "Should reject negative latency");
    }

    /**
     * Test 10: Invalid inputs - coherence out of range
     */
    @Test
    @DisplayName("Reject coherence outside [0, 1]")
    void testInvalidInput_InvalidCoherence() {
        var latency = 500.0;

        assertThrows(IllegalArgumentException.class,
                     () -> gate.shouldEnableBeam(latency, -0.1),
                     "Should reject coherence < 0");

        assertThrows(IllegalArgumentException.class,
                     () -> gate.shouldEnableBeam(latency, 1.5),
                     "Should reject coherence > 1");
    }

    /**
     * Test 11: Configuration - get current thresholds
     */
    @Test
    @DisplayName("Provide access to configuration thresholds")
    void testConfiguration_GetThresholds() {
        var latencyThreshold = gate.getLatencyThreshold();
        var coherenceThreshold = gate.getCoherenceThreshold();

        assertTrue(latencyThreshold > 0, "Latency threshold should be positive");
        assertTrue(coherenceThreshold >= 0.0 && coherenceThreshold <= 1.0,
                   "Coherence threshold should be in [0, 1]");
    }

    /**
     * Test 12: Logging - decision rationale
     */
    @Test
    @DisplayName("Log decision rationale for debugging")
    void testLogging_DecisionRationale() {
        var latency = 500.0;
        var coherence = 0.7;

        // Should not throw when logging is enabled
        assertDoesNotThrow(() -> {
            gate.shouldEnableBeam(latency, coherence);
            var reason = gate.getLastDecisionReason();
            assertNotNull(reason, "Decision reason should be available");
        });
    }
}
