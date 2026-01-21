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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.1 P1: Performance Report Test Suite
 *
 * Tests report generation and formatting for GPU performance validation.
 *
 * @author hal.hildebrand
 */
@DisplayName("PerformanceReport Tests")
class PerformanceReportTest {

    @Test
    @DisplayName("Generate report with baseline and optimized metrics")
    void testGenerateReport() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);
        var markdown = report.generateReport();

        assertNotNull(markdown);
        assertTrue(markdown.contains("GPU Performance Validation Report"));
        assertTrue(markdown.contains("Baseline"));
        assertTrue(markdown.contains("Optimized"));
        assertTrue(markdown.contains("Improvement"));
    }

    @Test
    @DisplayName("Report shows baseline latency correctly")
    void testReportShowsBaselineLatency() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);
        var markdown = report.generateReport();

        assertTrue(markdown.contains("850"), "Should show baseline latency 850µs");
    }

    @Test
    @DisplayName("Report shows optimized latency correctly")
    void testReportShowsOptimizedLatency() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);
        var markdown = report.generateReport();

        assertTrue(markdown.contains("450"), "Should show optimized latency 450µs");
    }

    @Test
    @DisplayName("Report calculates improvement percentage")
    void testReportCalculatesImprovement() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);
        var markdown = report.generateReport();

        assertTrue(markdown.contains("47%") || markdown.contains("47.0%"),
                   "Should show 47% improvement");
    }

    @Test
    @DisplayName("Report shows speedup factor")
    void testReportShowsSpeedupFactor() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);
        var markdown = report.generateReport();

        assertTrue(markdown.contains("1.89x") || markdown.contains("1.9x"),
                   "Should show 1.89x speedup");
    }

    @Test
    @DisplayName("Report shows 10x target status")
    void testReportShowsTargetStatus() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);
        var markdown = report.generateReport();

        assertTrue(markdown.contains("Target Status") || markdown.contains("target"),
                   "Should include target status section");
    }

    @Test
    @DisplayName("Report includes coherence recommendation")
    void testReportIncludesCoherenceRecommendation() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();
        var coherence = createCoherenceMetrics();

        var report = new PerformanceReport(baseline, optimized, coherence);
        var markdown = report.generateReport();

        assertTrue(markdown.contains("Coherence") || markdown.contains("coherence"),
                   "Should include coherence analysis");
        assertTrue(markdown.contains("Recommendation") || markdown.contains("recommendation"),
                   "Should include recommendation");
    }

    @Test
    @DisplayName("Report formats throughput correctly")
    void testReportFormatsThroughput() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);
        var markdown = report.generateReport();

        assertTrue(markdown.contains("Kray/ms") || markdown.contains("rays/ms"),
                   "Should format throughput with units");
    }

    @Test
    @DisplayName("Check if meets target threshold")
    void testMeetsTarget() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);

        // 10x target for 100K rays: <5ms = 5000µs
        // Current optimized: 450µs
        assertTrue(report.meetsTarget(5000.0), "Should meet 10x target");
        assertFalse(report.meetsTarget(100.0), "Should not meet aggressive 100µs target");
    }

    @Test
    @DisplayName("Get speedup factor")
    void testGetSpeedupFactor() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();

        var report = new PerformanceReport(baseline, optimized);

        // 850µs / 450µs = 1.889x
        assertEquals(1.889, report.getSpeedupFactor(), 0.01);
    }

    @Test
    @DisplayName("Get recommendation for low latency scenario")
    void testGetRecommendation_LowLatency() {
        // Already fast enough - skip beam optimization
        var baseline = new PerformanceMetrics("baseline", 100_000, 150.0, 666.7, 75.0f, 12, 0.0f, System.currentTimeMillis());
        var optimized = new PerformanceMetrics("optimized", 100_000, 80.0, 1250.0, 85.0f, 12, 0.65f, System.currentTimeMillis());

        var report = new PerformanceReport(baseline, optimized);
        var recommendation = report.getRecommendation();

        assertTrue(recommendation.contains("SKIP") || recommendation.contains("skip"),
                   "Should recommend skipping beam optimization when target met");
    }

    @Test
    @DisplayName("Get recommendation for high coherence scenario")
    void testGetRecommendation_HighCoherence() {
        var baseline = createBaselineMetrics();
        // Use slower optimized (>500µs) to test coherence decision logic
        var optimized = new PerformanceMetrics("optimized", 100_000, 800.0, 125.0, 80.0f, 14, 0.55f, System.currentTimeMillis());
        var coherence = new CoherenceMetrics(0.75, 0.85, new double[]{0.8, 0.7, 0.6, 0.5}, 512, 2048);

        var report = new PerformanceReport(baseline, optimized, coherence);
        var recommendation = report.getRecommendation();

        assertTrue(recommendation.contains("ENABLE") || recommendation.contains("enable") || recommendation.contains("proceed"),
                   "Should recommend enabling beam optimization for high coherence");
    }

    @Test
    @DisplayName("Get recommendation for low coherence scenario")
    void testGetRecommendation_LowCoherence() {
        var baseline = createBaselineMetrics();
        var optimized = createOptimizedMetrics();
        var coherence = new CoherenceMetrics(0.25, 0.15, new double[]{0.3, 0.2, 0.1, 0.05}, 256, 1024);

        var report = new PerformanceReport(baseline, optimized, coherence);
        var recommendation = report.getRecommendation();

        assertTrue(recommendation.contains("SKIP") || recommendation.contains("skip"),
                   "Should recommend skipping beam optimization for low coherence");
    }

    @Test
    @DisplayName("Report formats multiple ray count measurements")
    void testReportMultipleRayCounts() {
        var baseline100K = createBaselineMetrics();
        var optimized100K = createOptimizedMetrics();

        var baseline1M = new PerformanceMetrics("baseline", 1_000_000, 8500.0, 117.6, 75.0f, 12, 0.0f, System.currentTimeMillis());
        var optimized1M = new PerformanceMetrics("optimized_A+B", 1_000_000, 4500.0, 222.2, 85.0f, 12, 0.65f, System.currentTimeMillis());

        var report1 = new PerformanceReport(baseline100K, optimized100K);
        var report2 = new PerformanceReport(baseline1M, optimized1M);

        var markdown1 = report1.generateReport();
        var markdown2 = report2.generateReport();

        assertTrue(markdown1.contains("100,000") || markdown1.contains("100K"),
                   "Should format 100K ray count");
        assertTrue(markdown2.contains("1,000,000") || markdown2.contains("1M"),
                   "Should format 1M ray count");
    }

    // Helper methods

    private PerformanceMetrics createBaselineMetrics() {
        return new PerformanceMetrics(
            "baseline",
            100_000,
            850.0,
            117.6,
            75.0f,
            12,
            0.0f,
            System.currentTimeMillis()
        );
    }

    private PerformanceMetrics createOptimizedMetrics() {
        return new PerformanceMetrics(
            "optimized_A+B",
            100_000,
            450.0,
            222.2,
            85.0f,
            12,
            0.65f,
            System.currentTimeMillis()
        );
    }

    private CoherenceMetrics createCoherenceMetrics() {
        return new CoherenceMetrics(
            0.65,                          // coherenceScore
            0.75,                          // upperLevelSharingPercent
            new double[]{0.8, 0.7, 0.6, 0.5}, // depthDistribution
            512,                           // uniqueNodesVisited
            2048                           // totalNodeVisits
        );
    }
}
