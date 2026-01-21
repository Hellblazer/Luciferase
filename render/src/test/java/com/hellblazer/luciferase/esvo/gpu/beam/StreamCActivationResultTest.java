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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Tests for StreamCActivationResult - Phase 4.2 P2
 *
 * Tests the decision report formatting with metrics and recommendations.
 *
 * @author hal.hildebrand
 */
@DisplayName("Stream C: Activation Result Report")
class StreamCActivationResultTest {

    /**
     * Test 1: Generate report for SKIP_BEAM decision
     */
    @Test
    @DisplayName("Report for SKIP_BEAM should indicate success")
    void testReport_SkipBeam() {
        var decision = StreamCActivationDecision.Decision.SKIP_BEAM;
        var optimized = createMetrics("optimized", 450.0, 2.22, 85.0f, 0.65f);
        var coherence = createCoherence(0.65, 0.70);

        var result = new StreamCActivationResult(decision, optimized, coherence, "Target met");

        var report = result.generateDecisionReport();

        assertNotNull(report, "Report should not be null");
        assertTrue(report.contains("450"), "Report should contain latency value");
        assertTrue(report.contains("SKIP_BEAM") || report.contains("SUCCESS"),
                   "Report should indicate skip decision");
        assertTrue(report.contains("target") || report.contains("met"),
                   "Report should mention target met");
        assertTrue(report.contains("0.65") || report.contains("65"),
                   "Report should contain coherence score");
    }

    /**
     * Test 2: Generate report for ENABLE_BEAM decision
     */
    @Test
    @DisplayName("Report for ENABLE_BEAM should include activation instructions")
    void testReport_EnableBeam() {
        var decision = StreamCActivationDecision.Decision.ENABLE_BEAM;
        var optimized = createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f);
        var coherence = createCoherence(0.68, 0.72);

        var result = new StreamCActivationResult(decision, optimized, coherence, "High coherence detected");

        var report = result.generateDecisionReport();

        assertNotNull(report, "Report should not be null");
        assertTrue(report.contains("600"), "Report should contain latency value");
        assertTrue(report.contains("ENABLE") || report.contains("enable"),
                   "Report should indicate enable decision");
        assertTrue(report.contains("batch") || report.contains("kernel"),
                   "Report should mention batch kernel");
        assertTrue(report.contains("0.68") || report.contains("68"),
                   "Report should contain coherence score");
        assertTrue(report.contains("30%") || report.contains("node reduction"),
                   "Report should mention expected improvement");
    }

    /**
     * Test 3: Generate report for INVESTIGATE_ALTERNATIVES decision
     */
    @Test
    @DisplayName("Report for INVESTIGATE_ALTERNATIVES should suggest alternatives")
    void testReport_InvestigateAlternatives() {
        var decision = StreamCActivationDecision.Decision.INVESTIGATE_ALTERNATIVES;
        var optimized = createMetrics("optimized", 650.0, 1.54, 85.0f, 0.65f);
        var coherence = createCoherence(0.35, 0.40);

        var result = new StreamCActivationResult(decision, optimized, coherence, "Low coherence");

        var report = result.generateDecisionReport();

        assertNotNull(report, "Report should not be null");
        assertTrue(report.contains("650"), "Report should contain latency value");
        assertTrue(report.contains("INVESTIGATE") || report.contains("alternative"),
                   "Report should indicate investigate decision");
        assertTrue(report.contains("0.35") || report.contains("35"),
                   "Report should contain coherence score");
        assertTrue(report.contains("memory") || report.contains("bandwidth") || report.contains("optimization"),
                   "Report should suggest alternative optimizations");
    }

    /**
     * Test 4: Report should include all performance metrics
     */
    @Test
    @DisplayName("Report should include latency, throughput, occupancy, coherence")
    void testReport_IncludesAllMetrics() {
        var decision = StreamCActivationDecision.Decision.ENABLE_BEAM;
        var optimized = createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f);
        var coherence = createCoherence(0.68, 0.72);

        var result = new StreamCActivationResult(decision, optimized, coherence, "Test");

        var report = result.generateDecisionReport();

        // Check for latency
        assertTrue(report.contains("600") || report.contains("latency"),
                   "Report should include latency");

        // Check for throughput
        assertTrue(report.contains("1.67") || report.contains("throughput"),
                   "Report should include throughput");

        // Check for occupancy
        assertTrue(report.contains("85") || report.contains("occupancy"),
                   "Report should include occupancy");

        // Check for coherence
        assertTrue(report.contains("0.68") || report.contains("68%"),
                   "Report should include coherence score");
    }

    /**
     * Test 5: Report should compare to performance target
     */
    @Test
    @DisplayName("Report should compare current performance to 10x target")
    void testReport_CompareToTarget() {
        var decision = StreamCActivationDecision.Decision.SKIP_BEAM;
        var optimized = createMetrics("optimized", 450.0, 2.22, 85.0f, 0.65f);
        var coherence = createCoherence(0.65, 0.70);

        var result = new StreamCActivationResult(decision, optimized, coherence, "Target met");

        var report = result.generateDecisionReport();

        // Should mention 10x target or 5ms target
        assertTrue(report.contains("10x") || report.contains("5ms") || report.contains("5000"),
                   "Report should mention 10x speedup target");

        // Should show current vs target comparison
        assertTrue(report.contains("better") || report.contains("faster") || report.contains("target"),
                   "Report should compare current to target");
    }

    /**
     * Test 6: Report should be human-readable
     */
    @Test
    @DisplayName("Report should be well-formatted and human-readable")
    void testReport_HumanReadable() {
        var decision = StreamCActivationDecision.Decision.ENABLE_BEAM;
        var optimized = createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f);
        var coherence = createCoherence(0.68, 0.72);

        var result = new StreamCActivationResult(decision, optimized, coherence, "High coherence");

        var report = result.generateDecisionReport();

        // Should have sections
        assertTrue(report.contains("Performance") || report.contains("Metrics"),
                   "Report should have performance section");
        assertTrue(report.contains("Coherence") || report.contains("Ray"),
                   "Report should have coherence section");
        assertTrue(report.contains("Decision") || report.contains("Recommendation"),
                   "Report should have decision section");

        // Should have some structure (newlines, formatting)
        assertTrue(report.contains("\n"), "Report should have multiple lines");
        assertTrue(report.length() > 200, "Report should be reasonably detailed");
    }

    /**
     * Test 7: Null decision should throw
     */
    @Test
    @DisplayName("Null decision should throw IllegalArgumentException")
    void testConstruction_NullDecision() {
        var optimized = createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f);
        var coherence = createCoherence(0.68, 0.72);

        assertThrows(IllegalArgumentException.class,
                     () -> new StreamCActivationResult(null, optimized, coherence, "Test"),
                     "Null decision should throw");
    }

    /**
     * Test 8: Null metrics should throw
     */
    @Test
    @DisplayName("Null metrics should throw IllegalArgumentException")
    void testConstruction_NullMetrics() {
        var decision = StreamCActivationDecision.Decision.ENABLE_BEAM;
        var coherence = createCoherence(0.68, 0.72);

        assertThrows(IllegalArgumentException.class,
                     () -> new StreamCActivationResult(decision, null, coherence, "Test"),
                     "Null optimized metrics should throw");
    }

    /**
     * Test 9: Null coherence should throw
     */
    @Test
    @DisplayName("Null coherence should throw IllegalArgumentException")
    void testConstruction_NullCoherence() {
        var decision = StreamCActivationDecision.Decision.ENABLE_BEAM;
        var optimized = createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f);

        assertThrows(IllegalArgumentException.class,
                     () -> new StreamCActivationResult(decision, optimized, null, "Test"),
                     "Null coherence metrics should throw");
    }

    /**
     * Test 10: Report should include activation instructions
     */
    @Test
    @DisplayName("Report should provide clear next steps for each decision")
    void testReport_IncludesActivationInstructions() {
        // SKIP_BEAM: Declare success
        var skip = new StreamCActivationResult(
            StreamCActivationDecision.Decision.SKIP_BEAM,
            createMetrics("optimized", 450.0, 2.22, 85.0f, 0.65f),
            createCoherence(0.65, 0.70),
            "Target met"
        );
        var skipReport = skip.generateDecisionReport();
        assertTrue(skipReport.contains("success") || skipReport.contains("complete") || skipReport.contains("met"),
                   "SKIP report should indicate completion");

        // ENABLE_BEAM: Activate batch kernel
        var enable = new StreamCActivationResult(
            StreamCActivationDecision.Decision.ENABLE_BEAM,
            createMetrics("optimized", 600.0, 1.67, 85.0f, 0.65f),
            createCoherence(0.68, 0.72),
            "High coherence"
        );
        var enableReport = enable.generateDecisionReport();
        assertTrue(enableReport.contains("batch") || enableReport.contains("activate") || enableReport.contains("kernel"),
                   "ENABLE report should mention batch kernel activation");

        // INVESTIGATE: Consider alternatives
        var investigate = new StreamCActivationResult(
            StreamCActivationDecision.Decision.INVESTIGATE_ALTERNATIVES,
            createMetrics("optimized", 650.0, 1.54, 85.0f, 0.65f),
            createCoherence(0.35, 0.40),
            "Low coherence"
        );
        var investigateReport = investigate.generateDecisionReport();
        assertTrue(investigateReport.contains("alternative") || investigateReport.contains("consider") ||
                   investigateReport.contains("investigate"),
                   "INVESTIGATE report should suggest alternatives");
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
