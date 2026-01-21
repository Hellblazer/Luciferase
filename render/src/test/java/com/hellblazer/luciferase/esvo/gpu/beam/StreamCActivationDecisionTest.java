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

import com.hellblazer.luciferase.esvo.gpu.profiler.CoherenceMetrics;
import com.hellblazer.luciferase.esvo.gpu.profiler.PerformanceMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Tests for StreamCActivationDecision - Phase 4.2 P2
 *
 * Tests the 3-way decision logic for Stream C beam optimization:
 * - SKIP_BEAM: Target met (latency < 500µs)
 * - ENABLE_BEAM: High coherence (>= 0.5), latency still high
 * - INVESTIGATE_ALTERNATIVES: Low coherence, beam won't help
 *
 * @author hal.hildebrand
 */
@DisplayName("Stream C: Activation Decision Logic")
class StreamCActivationDecisionTest {

    private StreamCActivationDecision decision;

    // Decision thresholds (from handoff document)
    private static final double LATENCY_TARGET_MICROSECONDS = 500.0;
    private static final double COHERENCE_THRESHOLD = 0.5;

    @BeforeEach
    void setUp() {
        decision = new StreamCActivationDecision();
    }

    /**
     * Test 1: SKIP_BEAM - Target already met (latency < 500µs)
     */
    @Test
    @DisplayName("Decision: SKIP_BEAM when latency < 500µs (target met)")
    void testDecision_SkipBeam_TargetMet() {
        var optimized = createMetrics("optimized", 450.0, 2.22, 85.0f, 0.65f);
        var coherence = createCoherence(0.65, 0.70);

        var result = decision.evaluate(optimized, coherence);

        assertEquals(StreamCActivationDecision.Decision.SKIP_BEAM, result,
                     "Should SKIP_BEAM when latency 450µs < 500µs target");

        var rationale = decision.getRationale();
        assertNotNull(rationale, "Rationale should be provided");
        assertTrue(rationale.contains("target met") || rationale.contains("450"),
                   "Rationale should mention target met: " + rationale);
    }

    /**
     * Test 2: SKIP_BEAM - Latency exactly at target (500µs)
     */
    @Test
    @DisplayName("Decision: SKIP_BEAM when latency exactly 500µs")
    void testDecision_SkipBeam_ExactlyTarget() {
        var optimized = createMetrics("optimized", 500.0, 2.0, 85.0f, 0.65f);
        var coherence = createCoherence(0.68, 0.72);

        var result = decision.evaluate(optimized, coherence);

        assertEquals(StreamCActivationDecision.Decision.SKIP_BEAM, result,
                     "Should SKIP_BEAM when latency exactly 500µs");
    }

    /**
     * Test 3: ENABLE_BEAM - High latency (600µs) with high coherence (0.68)
     */
    @Test
    @DisplayName("Decision: ENABLE_BEAM when latency high and coherence >= 0.5")
    void testDecision_EnableBeam_HighCoherence() {
        var optimized = createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f);
        var coherence = createCoherence(0.68, 0.72);

        var result = decision.evaluate(optimized, coherence);

        assertEquals(StreamCActivationDecision.Decision.ENABLE_BEAM, result,
                     "Should ENABLE_BEAM when latency 600µs > 500µs and coherence 0.68 >= 0.5");

        var rationale = decision.getRationale();
        assertTrue(rationale.contains("coherent") || rationale.contains("0.68"),
                   "Rationale should mention coherence: " + rationale);
    }

    /**
     * Test 4: ENABLE_BEAM - Coherence exactly at threshold (0.5)
     */
    @Test
    @DisplayName("Decision: ENABLE_BEAM when coherence exactly 0.5")
    void testDecision_EnableBeam_CoherenceAtThreshold() {
        var optimized = createMetrics("optimized", 650.0, 1.54, 85.0f, 0.65f);
        var coherence = createCoherence(0.5, 0.65);

        var result = decision.evaluate(optimized, coherence);

        assertEquals(StreamCActivationDecision.Decision.ENABLE_BEAM, result,
                     "Should ENABLE_BEAM when coherence exactly 0.5");
    }

    /**
     * Test 5: INVESTIGATE_ALTERNATIVES - Low coherence (0.35)
     */
    @Test
    @DisplayName("Decision: INVESTIGATE_ALTERNATIVES when coherence < 0.5")
    void testDecision_Investigate_LowCoherence() {
        var optimized = createMetrics("optimized", 650.0, 1.54, 85.0f, 0.65f);
        var coherence = createCoherence(0.35, 0.40);

        var result = decision.evaluate(optimized, coherence);

        assertEquals(StreamCActivationDecision.Decision.INVESTIGATE_ALTERNATIVES, result,
                     "Should INVESTIGATE_ALTERNATIVES when coherence 0.35 < 0.5");

        var rationale = decision.getRationale();
        assertTrue(rationale.contains("low coherence") || rationale.contains("0.35"),
                   "Rationale should mention low coherence: " + rationale);
    }

    /**
     * Test 6: Decision priority - Latency check first
     */
    @Test
    @DisplayName("Decision priority: latency check happens before coherence")
    void testDecision_LatencyCheckFirst() {
        // Even with low coherence, if latency is met, should SKIP_BEAM
        var optimized = createMetrics("optimized", 450.0, 2.22, 85.0f, 0.65f);
        var coherence = createCoherence(0.35, 0.40); // Low coherence

        var result = decision.evaluate(optimized, coherence);

        assertEquals(StreamCActivationDecision.Decision.SKIP_BEAM, result,
                     "SKIP_BEAM should take priority when latency target met, even with low coherence");
    }

    /**
     * Test 7: Null optimized metrics should throw
     */
    @Test
    @DisplayName("Null optimized metrics should throw IllegalArgumentException")
    void testDecision_NullOptimized() {
        var coherence = createCoherence(0.65, 0.70);

        assertThrows(IllegalArgumentException.class,
                     () -> decision.evaluate(null, coherence),
                     "Null optimized metrics should throw");
    }

    /**
     * Test 8: Null coherence metrics should throw
     */
    @Test
    @DisplayName("Null coherence metrics should throw IllegalArgumentException")
    void testDecision_NullCoherence() {
        var optimized = createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f);

        assertThrows(IllegalArgumentException.class,
                     () -> decision.evaluate(optimized, null),
                     "Null coherence metrics should throw");
    }

    /**
     * Test 9: All Decision enum values covered
     */
    @Test
    @DisplayName("All Decision enum values should be covered by tests")
    void testDecision_AllEnumValuesCovered() {
        // SKIP_BEAM
        var skip = decision.evaluate(
            createMetrics("optimized", 450.0, 2.22, 85.0f, 0.65f),
            createCoherence(0.65, 0.70)
        );
        assertEquals(StreamCActivationDecision.Decision.SKIP_BEAM, skip);

        // ENABLE_BEAM
        var enable = decision.evaluate(
            createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f),
            createCoherence(0.68, 0.72)
        );
        assertEquals(StreamCActivationDecision.Decision.ENABLE_BEAM, enable);

        // INVESTIGATE_ALTERNATIVES
        var investigate = decision.evaluate(
            createMetrics("optimized", 650.0, 1.54, 85.0f, 0.65f),
            createCoherence(0.35, 0.40)
        );
        assertEquals(StreamCActivationDecision.Decision.INVESTIGATE_ALTERNATIVES, investigate);
    }

    /**
     * Test 10: Custom thresholds
     */
    @Test
    @DisplayName("Custom thresholds should affect decision")
    void testDecision_CustomThresholds() {
        var customDecision = new StreamCActivationDecision(400.0, 0.6);

        // With 450µs latency:
        // - Default (500µs): SKIP_BEAM
        // - Custom (400µs): ENABLE_BEAM (if coherence >= 0.6)
        var optimized = createMetrics("optimized", 450.0, 2.22, 85.0f, 0.65f);
        var coherence = createCoherence(0.65, 0.70);

        var result = customDecision.evaluate(optimized, coherence);
        assertEquals(StreamCActivationDecision.Decision.ENABLE_BEAM, result,
                     "Custom threshold 400µs should trigger ENABLE_BEAM at 450µs");
    }

    /**
     * Test 11: Rationale should be specific to decision
     */
    @Test
    @DisplayName("Rationale should be specific to each decision type")
    void testDecision_RationaleSpecific() {
        // SKIP_BEAM rationale
        decision.evaluate(
            createMetrics("optimized", 450.0, 2.22, 85.0f, 0.65f),
            createCoherence(0.65, 0.70)
        );
        var skipRationale = decision.getRationale();
        assertTrue(skipRationale.contains("target") || skipRationale.contains("met"),
                   "SKIP rationale should mention target met");

        // ENABLE_BEAM rationale
        decision.evaluate(
            createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f),
            createCoherence(0.68, 0.72)
        );
        var enableRationale = decision.getRationale();
        assertTrue(enableRationale.contains("coherent") || enableRationale.contains("enable"),
                   "ENABLE rationale should mention coherence");

        // INVESTIGATE rationale
        decision.evaluate(
            createMetrics("optimized", 650.0, 1.54, 85.0f, 0.65f),
            createCoherence(0.35, 0.40)
        );
        var investigateRationale = decision.getRationale();
        assertTrue(investigateRationale.contains("low") || investigateRationale.contains("alternative"),
                   "INVESTIGATE rationale should mention alternatives");
    }

    // Helper methods

    private PerformanceMetrics createMetrics(String scenario, double latency, double throughput,
                                             float occupancy, float cacheHit) {
        return new PerformanceMetrics(
            scenario,
            100_000,
            latency,
            throughput,
            occupancy,
            8,
            cacheHit,
            System.currentTimeMillis()
        );
    }

    private CoherenceMetrics createCoherence(double score, double upperSharing) {
        return new CoherenceMetrics(
            score,
            upperSharing,
            new double[32],
            1000,
            5000
        );
    }
}
