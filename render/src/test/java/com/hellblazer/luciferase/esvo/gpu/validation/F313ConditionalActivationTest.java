/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.gpu.validation;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.beam.StreamCActivationDecision;
import com.hellblazer.luciferase.esvo.gpu.beam.StreamCActivationDecision.Decision;
import com.hellblazer.luciferase.esvo.gpu.profiler.CoherenceMetrics;
import com.hellblazer.luciferase.esvo.gpu.profiler.GPUPerformanceProfiler;
import com.hellblazer.luciferase.esvo.gpu.profiler.PerformanceMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.1.3: Conditional Activation Validation Test Suite
 *
 * <p>Validates that Stream C (beam optimization) is correctly gated:
 * <ul>
 *   <li>SKIP_BEAM when Streams A+B meet latency target (&lt;500µs)</li>
 *   <li>ENABLE_BEAM when coherence &gt;= 0.5 and target not met</li>
 *   <li>INVESTIGATE_ALTERNATIVES when coherence &lt; 0.5</li>
 * </ul>
 *
 * <p>Per the F3.1.3 specification, this is a CONDITIONAL optimization that
 * only triggers if Streams A+B miss the performance target.
 *
 * @see StreamCActivationDecision
 */
@DisplayName("F3.1.3: Conditional Activation Validation")
class F313ConditionalActivationTest {

    private StreamCActivationDecision decision;
    private GPUPerformanceProfiler profiler;
    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        decision = new StreamCActivationDecision();
        profiler = new GPUPerformanceProfiler();
        testDAG = createTestDAG();
    }

    @Nested
    @DisplayName("Decision Gate Logic")
    class DecisionGateLogic {

        @Test
        @DisplayName("SKIP_BEAM when latency meets target")
        void testSkipBeamWhenTargetMet() {
            // Latency below 500µs target
            var optimized = createMetrics("optimized", 100_000, 400.0, 250.0f, 0.65f, 8);
            var coherence = createCoherence(0.7, 0.6);

            var result = decision.evaluate(optimized, coherence);

            assertEquals(Decision.SKIP_BEAM, result);
            assertNotNull(decision.getRationale());
            assertTrue(decision.getRationale().contains("Target met"));
        }

        @Test
        @DisplayName("ENABLE_BEAM when target missed but high coherence")
        void testEnableBeamWhenHighCoherence() {
            // Latency above 500µs target, high coherence
            var optimized = createMetrics("optimized", 100_000, 800.0, 125.0f, 0.65f, 8);
            var coherence = createCoherence(0.6, 0.55);

            var result = decision.evaluate(optimized, coherence);

            assertEquals(Decision.ENABLE_BEAM, result);
            assertNotNull(decision.getRationale());
            assertTrue(decision.getRationale().contains("High coherence"));
        }

        @Test
        @DisplayName("INVESTIGATE_ALTERNATIVES when low coherence")
        void testInvestigateWhenLowCoherence() {
            // Latency above target, low coherence
            var optimized = createMetrics("optimized", 100_000, 800.0, 125.0f, 0.65f, 8);
            var coherence = createCoherence(0.3, 0.25);

            var result = decision.evaluate(optimized, coherence);

            assertEquals(Decision.INVESTIGATE_ALTERNATIVES, result);
            assertNotNull(decision.getRationale());
            assertTrue(decision.getRationale().contains("Low coherence"));
        }

        @Test
        @DisplayName("Rationale includes performance data")
        void testRationaleIncludesData() {
            var optimized = createMetrics("optimized", 100_000, 400.0, 250.0f, 0.65f, 8);
            var coherence = createCoherence(0.7, 0.6);

            decision.evaluate(optimized, coherence);
            var rationale = decision.getRationale();

            assertNotNull(rationale);
            assertTrue(rationale.contains("400") || rationale.contains("Target met"),
                "Rationale should reference performance data");
        }

        @Test
        @DisplayName("Custom thresholds respected")
        void testCustomThresholds() {
            // Create decision with stricter thresholds
            var strictDecision = new StreamCActivationDecision(300.0, 0.7);

            // Would pass default (500µs) but fail strict (300µs)
            var optimized = createMetrics("optimized", 100_000, 400.0, 250.0f, 0.65f, 8);
            var coherence = createCoherence(0.6, 0.55);

            var result = strictDecision.evaluate(optimized, coherence);

            // Latency 400 > 300 threshold, but coherence 0.6 < 0.7 threshold
            assertEquals(Decision.INVESTIGATE_ALTERNATIVES, result);
        }
    }

    @Nested
    @DisplayName("Integration with Profiler")
    class ProfilerIntegration {

        @Test
        @DisplayName("Profiler metrics feed into decision correctly (mock)")
        void testProfilerMetricsIntegration_Mock() {
            var metrics = profiler.profileOptimized(testDAG, 100_000, true);

            assertNotNull(metrics);
            assertTrue(metrics.latencyMicroseconds() > 0);

            // Create coherence metrics based on cache hit rate
            var coherence = createCoherence(metrics.cacheHitRate(), metrics.cacheHitRate() * 0.9);

            var result = decision.evaluate(metrics, coherence);

            // Mock mode should produce a valid decision
            assertNotNull(result);
            assertNotNull(decision.getRationale());
        }

        @Test
        @DisplayName("Decision consistent across multiple evaluations")
        void testDecisionConsistency() {
            var optimized = createMetrics("optimized", 100_000, 400.0, 250.0f, 0.65f, 8);
            var coherence = createCoherence(0.7, 0.6);

            // Evaluate multiple times
            var result1 = decision.evaluate(optimized, coherence);
            var result2 = decision.evaluate(optimized, coherence);
            var result3 = decision.evaluate(optimized, coherence);

            assertEquals(result1, result2);
            assertEquals(result2, result3);
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
        @DisplayName("End-to-end decision with real GPU")
        void testEndToEndDecision_RealGPU() {
            var baseline = profiler.profileBaseline(testDAG, 100_000, false);
            var optimized = profiler.profileOptimized(testDAG, 100_000, false);
            var comparison = profiler.compare(baseline, optimized);

            System.out.printf("Baseline: %.2fµs%n", baseline.latencyMicroseconds());
            System.out.printf("Optimized: %.2fµs%n", optimized.latencyMicroseconds());
            System.out.printf("Improvement: %.2f%%%n", comparison.improvement());

            // Create coherence from cache hit rate
            var coherence = createCoherence(
                optimized.cacheHitRate(),
                optimized.cacheHitRate() * 0.9
            );

            var result = decision.evaluate(optimized, coherence);

            System.out.printf("Decision: %s%n", result);
            System.out.printf("Rationale: %s%n", decision.getRationale());

            assertNotNull(result);
            // If performance target met, should skip beam
            if (optimized.latencyMicroseconds() < 500.0) {
                assertEquals(Decision.SKIP_BEAM, result,
                    "Should skip beam when target met");
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Exactly at latency threshold")
        void testExactlyAtLatencyThreshold() {
            var optimized = createMetrics("optimized", 100_000, 500.0, 200.0f, 0.65f, 8);
            var coherence = createCoherence(0.7, 0.6);

            var result = decision.evaluate(optimized, coherence);

            // At exactly 500µs, target is met (<=)
            assertEquals(Decision.SKIP_BEAM, result);
        }

        @Test
        @DisplayName("Exactly at coherence threshold")
        void testExactlyAtCoherenceThreshold() {
            var optimized = createMetrics("optimized", 100_000, 600.0, 166.67f, 0.65f, 8);
            var coherence = createCoherence(0.5, 0.45);

            var result = decision.evaluate(optimized, coherence);

            // At exactly 0.5, coherence is met (>=)
            assertEquals(Decision.ENABLE_BEAM, result);
        }

        @Test
        @DisplayName("Very high latency with high coherence")
        void testVeryHighLatencyHighCoherence() {
            var optimized = createMetrics("optimized", 100_000, 10000.0, 10.0f, 0.65f, 8);
            var coherence = createCoherence(0.9, 0.85);

            var result = decision.evaluate(optimized, coherence);

            assertEquals(Decision.ENABLE_BEAM, result,
                "High coherence should enable beam even with very high latency");
        }

        @Test
        @DisplayName("Very low latency overrides coherence check")
        void testVeryLowLatencyOverridesCoherence() {
            // Even with high coherence, low latency should skip beam
            var optimized = createMetrics("optimized", 100_000, 100.0, 1000.0f, 0.65f, 8);
            var coherence = createCoherence(0.95, 0.9);

            var result = decision.evaluate(optimized, coherence);

            assertEquals(Decision.SKIP_BEAM, result,
                "Low latency should skip beam regardless of coherence");
        }

        @Test
        @DisplayName("Null metrics throw exception")
        void testNullMetricsThrow() {
            var coherence = createCoherence(0.7, 0.6);

            assertThrows(IllegalArgumentException.class,
                () -> decision.evaluate(null, coherence));
        }

        @Test
        @DisplayName("Null coherence throws exception")
        void testNullCoherenceThrows() {
            var optimized = createMetrics("optimized", 100_000, 400.0, 250.0f, 0.65f, 8);

            assertThrows(IllegalArgumentException.class,
                () -> decision.evaluate(optimized, null));
        }
    }

    @Nested
    @DisplayName("Threshold Configuration")
    class ThresholdConfiguration {

        @Test
        @DisplayName("Invalid latency threshold throws")
        void testInvalidLatencyThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> new StreamCActivationDecision(0.0, 0.5));
            assertThrows(IllegalArgumentException.class,
                () -> new StreamCActivationDecision(-100.0, 0.5));
        }

        @Test
        @DisplayName("Invalid coherence threshold throws")
        void testInvalidCoherenceThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> new StreamCActivationDecision(500.0, -0.1));
            assertThrows(IllegalArgumentException.class,
                () -> new StreamCActivationDecision(500.0, 1.5));
        }

        @Test
        @DisplayName("Getters return configured values")
        void testGettersReturnConfigured() {
            var custom = new StreamCActivationDecision(750.0, 0.6);

            assertEquals(750.0, custom.getLatencyTarget());
            assertEquals(0.6, custom.getCoherenceThreshold());
        }

        @Test
        @DisplayName("Default thresholds are sensible")
        void testDefaultThresholds() {
            assertEquals(500.0, decision.getLatencyTarget(),
                "Default latency target should be 500µs");
            assertEquals(0.5, decision.getCoherenceThreshold(),
                "Default coherence threshold should be 0.5");
        }
    }

    // Helper methods

    private DAGOctreeData createTestDAG() {
        var svo = createSimpleTestOctree();
        return DAGBuilder.from(svo).build();
    }

    private ESVOOctreeData createSimpleTestOctree() {
        var octree = new ESVOOctreeData(16);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF);
        root.setChildPtr(1);
        octree.setNode(0, root);

        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }

    private PerformanceMetrics createMetrics(String scenario, int rayCount,
                                              double latencyMicros, double throughput,
                                              float cacheHitRate, int avgDepth) {
        return new PerformanceMetrics(
            scenario,
            rayCount,
            latencyMicros,
            throughput,
            75.0f,  // GPU occupancy
            avgDepth,
            cacheHitRate,
            System.currentTimeMillis()
        );
    }

    private CoherenceMetrics createCoherence(double coherenceScore, double upperLevelSharing) {
        return new CoherenceMetrics(
            coherenceScore,
            upperLevelSharing,
            new double[]{0.1, 0.2, 0.3, 0.25, 0.15},  // depthDistribution
            1000,   // uniqueNodesVisited
            5000    // totalNodeVisits
        );
    }
}
