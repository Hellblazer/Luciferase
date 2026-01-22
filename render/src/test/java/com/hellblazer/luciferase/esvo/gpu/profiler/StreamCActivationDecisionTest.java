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
package com.hellblazer.luciferase.esvo.gpu.profiler;

import com.hellblazer.luciferase.esvo.gpu.GPUVendor;
import com.hellblazer.luciferase.esvo.gpu.profiler.StreamCActivationDecision.ActivationState;
import com.hellblazer.luciferase.esvo.gpu.report.MultiVendorConsistencyReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Test Suite for Stream C Activation Decision Engine
 *
 * Tests decision logic for enabling beam optimization based on:
 * - Coherence threshold (≥0.5 required)
 * - Latency target (<500µs may skip)
 * - Vendor consistency (<20% variance required)
 * - GPU occupancy (≥60% required)
 *
 * @author hal.hildebrand
 */
class StreamCActivationDecisionTest {

    private StreamCActivationDecision engine;

    @BeforeEach
    void setUp() {
        engine = new StreamCActivationDecision();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Coherence Threshold Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testHighCoherenceEnablesBeamOptimization() {
        var baseline = createBaselineReport(0.75, 600.0, 0.70);  // 75% coherence, high latency, good occupancy
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.ENABLE, decision.state());
        assertTrue(decision.rationale().contains("coherence"));
        assertTrue(decision.rationale().contains("0.75"));
    }

    @Test
    void testLowCoherenceSkipsBeamOptimization() {
        var baseline = createBaselineReport(0.3, 600.0, 0.70);  // 30% coherence - too low
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.SKIP, decision.state());
        assertTrue(decision.rationale().contains("low coherence"));
        assertTrue(decision.rationale().contains("0.30"));
    }

    @Test
    void testMarginalCoherenceActivatesConditionally() {
        var baseline = createBaselineReport(0.65, 600.0, 0.70);  // Between 0.5 and 0.7
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.ENABLE_CONDITIONAL, decision.state());
        assertTrue(decision.rationale().contains("marginal coherence"));
        assertTrue(decision.recommendations().stream()
            .anyMatch(r -> r.contains("high-coherence scenes")));
    }

    @Test
    void testExactCoherenceThresholdEnables() {
        var baseline = createBaselineReport(0.5, 600.0, 0.70);  // Exactly at threshold
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertNotEquals(ActivationState.SKIP, decision.state());  // Should not skip at exact threshold
    }

    @Test
    void testVeryHighCoherenceStronglyRecommends() {
        var baseline = createBaselineReport(0.95, 600.0, 0.80);  // 95% coherence
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.ENABLE, decision.state());
        assertTrue(decision.rationale().contains("high coherence"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Latency Target Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testLatencyTargetMetSkipsOptimization() {
        var baseline = createBaselineReport(0.7, 250.0, 0.70);  // < 500µs latency
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.SKIP, decision.state());
        assertTrue(decision.rationale().contains("latency target already met"));
    }

    @Test
    void testHighLatencyEnablesOptimization() {
        var baseline = createBaselineReport(0.7, 1200.0, 0.70);  // 1200µs - well above target
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.ENABLE, decision.state());
        assertFalse(decision.warnings().isEmpty());
    }

    @Test
    void testExactLatencyThreshold() {
        var baseline = createBaselineReport(0.7, 500.0, 0.70);  // Exactly 500µs
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        // At threshold, should not skip (needs optimization)
        assertNotEquals(ActivationState.SKIP, decision.state());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GPU Occupancy Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testLowGPUOccupancySkips() {
        var baseline = createBaselineReport(0.7, 600.0, 0.45);  // 45% occupancy - too low
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.SKIP, decision.state());
        assertTrue(decision.rationale().contains("GPU occupancy"));
        assertTrue(decision.rationale().contains("45"));
    }

    @Test
    void testGoodOccupancyEnables() {
        var baseline = createBaselineReport(0.7, 600.0, 0.75);  // 75% occupancy - good
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.ENABLE, decision.state());
    }

    @Test
    void testExactOccupancyThreshold() {
        var baseline = createBaselineReport(0.7, 600.0, 0.60);  // Exactly 60% threshold
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertNotEquals(ActivationState.SKIP, decision.state());  // Should not skip at exact threshold
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Vendor Consistency Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testVendorInconsistencyFlagsWarning() {
        var baseline = createBaselineReport(0.7, 600.0, 0.70);
        var optimized = createOptimizedReport();
        var consistency = createInconsistentReport();  // >20% variance

        var decision = engine.decide(baseline, optimized, consistency);

        // Should flag warning but not necessarily skip
        assertFalse(decision.warnings().isEmpty());
        assertTrue(decision.warnings().stream()
            .anyMatch(w -> w.contains("vendor") || w.contains("consistency")));
    }

    @Test
    void testConsistentVendorsNoWarning() {
        var baseline = createBaselineReport(0.7, 600.0, 0.70);
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        // No vendor warnings for consistent results
        assertTrue(decision.warnings().stream()
            .noneMatch(w -> w.contains("vendor inconsistency")));
    }

    @Test
    void testVendorFailuresRequireMonitoring() {
        var baseline = createBaselineReport(0.7, 600.0, 0.70);
        var optimized = createOptimizedReport();
        var consistency = createReportWithFailures();

        var decision = engine.decide(baseline, optimized, consistency);

        // Should recommend monitoring when there are vendor failures
        assertTrue(decision.state() == ActivationState.MONITOR ||
                   decision.warnings().stream().anyMatch(w -> w.contains("monitor")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Combined Decision Logic Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testMonitoringStateOnPerformanceImprovement() {
        var baseline = createBaselineReport(0.7, 600.0, 0.70);  // High latency
        var optimized = createOptimizedReport(250.0);    // Dramatic improvement
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.MONITOR, decision.state());
        assertTrue(decision.recommendations().stream()
            .anyMatch(r -> r.contains("monitor") || r.contains("performance")));
    }

    @Test
    void testMultipleFailureConditionsPriority() {
        // Low coherence AND low occupancy - should prioritize the blocker
        var baseline = createBaselineReport(0.3, 600.0, 0.45);
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.SKIP, decision.state());
        // Should mention both issues
        var rationale = decision.rationale().toLowerCase();
        assertTrue(rationale.contains("coherence") || rationale.contains("occupancy"));
    }

    @Test
    void testAllConditionsMetEnables() {
        // Perfect scenario: high coherence, high latency, good occupancy, consistent vendors
        var baseline = createBaselineReport(0.8, 800.0, 0.75);
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.ENABLE, decision.state());
        assertFalse(decision.recommendations().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge Cases and Null Handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testNullCoherenceMetricsHandled() {
        var baseline = new PerformanceReport(
            createMetrics("baseline", 600.0, 0.70f),
            createMetrics("optimized", 550.0, 0.72f),
            null  // No coherence metrics
        );
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.SKIP, decision.state());
        assertTrue(decision.rationale().contains("coherence metrics unavailable"));
    }

    @Test
    void testZeroLatencyHandled() {
        var baseline = createBaselineReport(0.7, 0.0, 0.70);  // Invalid zero latency
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.SKIP, decision.state());
        assertTrue(decision.rationale().contains("invalid"));
    }

    @Test
    void testNegativeCoherenceHandled() {
        var baseline = createBaselineReport(-0.5, 600.0, 0.70);  // Invalid negative coherence
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertEquals(ActivationState.SKIP, decision.state());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Recommendation Content Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testRecommendationsIncludeNextSteps() {
        var baseline = createBaselineReport(0.7, 600.0, 0.70);
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertFalse(decision.recommendations().isEmpty());
        assertTrue(decision.recommendations().stream()
            .anyMatch(r -> r.toLowerCase().contains("enable") ||
                          r.toLowerCase().contains("monitor") ||
                          r.toLowerCase().contains("skip")));
    }

    @Test
    void testMetricsIncludedInDecision() {
        var baseline = createBaselineReport(0.7, 600.0, 0.70);
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        assertFalse(decision.metrics().isEmpty());
        assertTrue(decision.metrics().containsKey("coherence"));
        assertTrue(decision.metrics().containsKey("latency"));
        assertTrue(decision.metrics().containsKey("occupancy"));
    }

    @Test
    void testWarningsOnlyWhenRelevant() {
        var baseline = createBaselineReport(0.8, 800.0, 0.75);  // All good
        var optimized = createOptimizedReport();
        var consistency = createConsistentReport();

        var decision = engine.decide(baseline, optimized, consistency);

        // Should have minimal or no warnings when everything is good
        assertTrue(decision.warnings().size() <= 1);  // Maybe one monitoring note
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test Helper Methods
    // ═══════════════════════════════════════════════════════════════════════════

    private PerformanceReport createBaselineReport(double coherence, double latency, double occupancy) {
        var metrics = new CoherenceMetrics(
            coherence,
            0.7,  // upperLevelSharingPercent
            new double[]{0.1, 0.2, 0.3, 0.2, 0.1, 0.1},
            1000,
            5000
        );

        return new PerformanceReport(
            createMetrics("baseline", latency, (float) occupancy),
            createMetrics("optimized", latency * 0.9, (float) (occupancy + 0.02)),
            metrics
        );
    }

    private PerformanceReport createOptimizedReport() {
        return createOptimizedReport(550.0);
    }

    private PerformanceReport createOptimizedReport(double latency) {
        return new PerformanceReport(
            createMetrics("baseline", 600.0, 0.70f),
            createMetrics("optimized", latency, 0.72f),
            null
        );
    }

    private PerformanceMetrics createMetrics(String scenario, double latency, float occupancy) {
        return new PerformanceMetrics(
            scenario,
            100_000,
            latency,
            100_000.0 / latency,
            occupancy * 100.0f,
            12,
            0.85f,
            System.currentTimeMillis()
        );
    }

    private MultiVendorConsistencyReport createConsistentReport() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(100)
            .testsPassed(100)
            .build());

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 7900 XTX")
            .testsRun(100)
            .testsPassed(98)
            .build());

        return report;
    }

    private MultiVendorConsistencyReport createInconsistentReport() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(100)
            .testsPassed(100)
            .build());

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 7900 XTX")
            .testsRun(100)
            .testsPassed(70)  // 30% failure rate - high variance
            .addFailedTest("test1")
            .addFailedTest("test2")
            .build());

        return report;
    }

    private MultiVendorConsistencyReport createReportWithFailures() {
        var report = new MultiVendorConsistencyReport();

        report.addResult(MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 4090")
            .testsRun(100)
            .testsPassed(95)
            .addFailedTest("flaky_test_1")
            .build());

        return report;
    }
}
