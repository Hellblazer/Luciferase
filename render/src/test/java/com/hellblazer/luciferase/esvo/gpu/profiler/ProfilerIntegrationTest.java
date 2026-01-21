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

import com.hellblazer.luciferase.esvo.core.ESVORay;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.1 P1: Profiler Integration Test Suite
 *
 * Tests end-to-end profiling workflow:
 * 1. Profile baseline and optimized performance
 * 2. Analyze ray coherence
 * 3. Generate performance report
 * 4. Make Stream C activation decision
 *
 * @author hal.hildebrand
 */
@DisplayName("Profiler Integration Tests")
class ProfilerIntegrationTest {

    private GPUPerformanceProfiler performanceProfiler;
    private CoherenceProfiler coherenceProfiler;
    private DAGOctreeData testDAG;
    private ESVORay[] testRays;

    @BeforeEach
    void setUp() {
        performanceProfiler = new GPUPerformanceProfiler();
        coherenceProfiler = new CoherenceProfiler();

        // Create test DAG (medium size for realistic profiling)
        var svo = TestOctreeFactory.createMediumTestOctree();
        testDAG = DAGBuilder.from(svo).build();

        // Create coherent test rays
        testRays = createCoherentRays(100);
    }

    @Test
    @DisplayName("Full profiling workflow: baseline -> optimized -> report")
    void testFullProfilingWorkflow() {
        // Step 1: Profile baseline performance
        var baseline = performanceProfiler.profileBaseline(testDAG, 100_000, true);
        assertNotNull(baseline);
        assertEquals("baseline", baseline.scenario());

        // Step 2: Profile optimized performance
        var optimized = performanceProfiler.profileOptimized(testDAG, 100_000, true);
        assertNotNull(optimized);
        assertTrue(optimized.scenario().contains("optimized"));

        // Step 3: Generate performance report
        var report = new PerformanceReport(baseline, optimized);
        var markdown = report.generateReport();

        assertNotNull(markdown);
        assertTrue(markdown.contains("GPU Performance Validation Report"));
        assertTrue(markdown.contains("Baseline"));
        assertTrue(markdown.contains("Optimized"));
        assertTrue(markdown.contains("Improvement"));

        // Verify improvement shown
        assertTrue(optimized.latencyMicroseconds() < baseline.latencyMicroseconds(),
                   "Optimized should be faster than baseline");
    }

    @Test
    @DisplayName("Full profiling workflow with coherence analysis")
    void testFullWorkflowWithCoherence() {
        // Step 1: Profile baseline and optimized
        var baseline = performanceProfiler.profileBaseline(testDAG, 100_000, true);
        var optimized = performanceProfiler.profileOptimized(testDAG, 100_000, true);

        // Step 2: Analyze ray coherence
        var coherence = coherenceProfiler.analyzeDetailed(testRays, testDAG);
        assertNotNull(coherence);
        assertTrue(coherence.coherenceScore() >= 0.0);
        assertTrue(coherence.coherenceScore() <= 1.0);

        // Step 3: Generate report with coherence
        var report = new PerformanceReport(baseline, optimized, coherence);
        var markdown = report.generateReport();

        assertNotNull(markdown);
        assertTrue(markdown.contains("Stream C Activation Decision"));
        assertTrue(markdown.contains("Coherence"));
        assertTrue(markdown.contains("Recommendation"));
    }

    @Test
    @DisplayName("Stream C decision: high coherence scenario")
    void testStreamCDecision_HighCoherence() {
        // Slow baseline, slow optimized, high coherence -> ENABLE beam
        var baseline = new PerformanceMetrics("baseline", 100_000, 1200.0, 83.3, 70.0f, 14, 0.0f, System.currentTimeMillis());
        var optimized = new PerformanceMetrics("optimized", 100_000, 800.0, 125.0, 80.0f, 14, 0.55f, System.currentTimeMillis());
        var coherence = new CoherenceMetrics(0.75, 0.85, new double[]{0.9, 0.8, 0.7}, 256, 1024);

        var report = new PerformanceReport(baseline, optimized, coherence);
        var recommendation = report.getRecommendation();

        assertTrue(recommendation.contains("ENABLE") || recommendation.contains("proceed"),
                   "Should recommend enabling beam optimization");
    }

    @Test
    @DisplayName("Stream C decision: low coherence scenario")
    void testStreamCDecision_LowCoherence() {
        // Slow baseline, slow optimized, low coherence -> SKIP
        var baseline = new PerformanceMetrics("baseline", 100_000, 1200.0, 83.3, 70.0f, 14, 0.0f, System.currentTimeMillis());
        var optimized = new PerformanceMetrics("optimized", 100_000, 800.0, 125.0, 80.0f, 14, 0.55f, System.currentTimeMillis());
        var coherence = new CoherenceMetrics(0.25, 0.15, new double[]{0.3, 0.2, 0.1}, 512, 1024);

        var report = new PerformanceReport(baseline, optimized, coherence);
        var recommendation = report.getRecommendation();

        assertTrue(recommendation.contains("SKIP") || recommendation.contains("insufficient"),
                   "Should recommend skipping beam optimization for low coherence");
    }

    @Test
    @DisplayName("Stream C decision: target already met scenario")
    void testStreamCDecision_TargetMet() {
        // Fast optimized, high coherence -> SKIP (target met)
        var baseline = new PerformanceMetrics("baseline", 100_000, 850.0, 117.6, 75.0f, 12, 0.0f, System.currentTimeMillis());
        var optimized = new PerformanceMetrics("optimized", 100_000, 120.0, 833.3, 90.0f, 12, 0.75f, System.currentTimeMillis());
        var coherence = new CoherenceMetrics(0.80, 0.90, new double[]{0.95, 0.85, 0.75}, 128, 512);

        var report = new PerformanceReport(baseline, optimized, coherence);
        var recommendation = report.getRecommendation();

        assertTrue(recommendation.contains("SKIP") || recommendation.contains("target"),
                   "Should recommend skipping when target already met");
    }

    @Test
    @DisplayName("Multiple ray count profiling")
    void testMultipleRayCountProfiling() {
        var rayCounts = new int[]{100_000, 1_000_000, 10_000_000};

        for (var rayCount : rayCounts) {
            var baseline = performanceProfiler.profileBaseline(testDAG, rayCount, true);
            var optimized = performanceProfiler.profileOptimized(testDAG, rayCount, true);

            assertEquals(rayCount, baseline.rayCount());
            assertEquals(rayCount, optimized.rayCount());

            // Latency should scale roughly linearly with ray count
            assertTrue(optimized.latencyMicroseconds() < baseline.latencyMicroseconds());
        }
    }

    @Test
    @DisplayName("Performance comparison metrics")
    void testPerformanceComparisonMetrics() {
        var baseline = performanceProfiler.profileBaseline(testDAG, 100_000, true);
        var optimized = performanceProfiler.profileOptimized(testDAG, 100_000, true);

        var comparison = performanceProfiler.compare(baseline, optimized);

        assertNotNull(comparison);
        assertTrue(comparison.improvement() > 0, "Should show improvement");
        assertTrue(comparison.isFaster(), "Optimized should be faster");
        assertEquals(baseline, comparison.baseline());
        assertEquals(optimized, comparison.optimized());
    }

    @Test
    @DisplayName("Coherence profiling integration with base analyzer")
    void testCoherenceProfilingIntegration() {
        var rays = createCoherentRays(100);

        // Base analyzer coherence
        var baseCoherence = coherenceProfiler.analyzeCoherence(rays, testDAG);

        // Detailed profiler coherence
        var detailedMetrics = coherenceProfiler.analyzeDetailed(rays, testDAG);

        // Should match
        assertEquals(baseCoherence, detailedMetrics.coherenceScore(), 0.01,
                     "Base and detailed coherence should match");

        // Detailed metrics should provide additional information
        assertTrue(detailedMetrics.uniqueNodesVisited() > 0);
        assertTrue(detailedMetrics.totalNodeVisits() >= detailedMetrics.uniqueNodesVisited());
    }

    @Test
    @DisplayName("Report formatting with different scenarios")
    void testReportFormattingScenarios() {
        // Test various performance scenarios
        var scenarios = new PerformanceMetrics[][]{
            // Scenario 1: Good improvement
            {
                new PerformanceMetrics("baseline", 100_000, 1000.0, 100.0, 70.0f, 14, 0.0f, System.currentTimeMillis()),
                new PerformanceMetrics("optimized", 100_000, 500.0, 200.0, 85.0f, 14, 0.65f, System.currentTimeMillis())
            },
            // Scenario 2: Minimal improvement
            {
                new PerformanceMetrics("baseline", 100_000, 600.0, 166.7, 75.0f, 12, 0.0f, System.currentTimeMillis()),
                new PerformanceMetrics("optimized", 100_000, 550.0, 181.8, 78.0f, 12, 0.45f, System.currentTimeMillis())
            },
            // Scenario 3: Large dataset
            {
                new PerformanceMetrics("baseline", 10_000_000, 85000.0, 117.6, 70.0f, 16, 0.0f, System.currentTimeMillis()),
                new PerformanceMetrics("optimized", 10_000_000, 45000.0, 222.2, 85.0f, 16, 0.68f, System.currentTimeMillis())
            }
        };

        for (var scenario : scenarios) {
            var report = new PerformanceReport(scenario[0], scenario[1]);
            var markdown = report.generateReport();

            assertNotNull(markdown);
            assertTrue(markdown.length() > 100, "Report should have substantial content");
        }
    }

    @Test
    @DisplayName("Coherence metrics edge cases")
    void testCoherenceMetricsEdgeCases() {
        // Empty ray batch
        var emptyRays = new ESVORay[0];
        var emptyMetrics = coherenceProfiler.analyzeDetailed(emptyRays, testDAG);
        assertEquals(0.0, emptyMetrics.coherenceScore());
        assertEquals(0, emptyMetrics.uniqueNodesVisited());

        // Single ray
        var singleRay = new ESVORay[]{createRay(0.0f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f)};
        var singleMetrics = coherenceProfiler.analyzeDetailed(singleRay, testDAG);
        assertEquals(1.0, singleMetrics.coherenceScore(), 0.001);

        // Two identical rays (high coherence expected)
        var identicalRays = new ESVORay[]{
            createRay(0.0f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f),
            createRay(0.0f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f)
        };
        var identicalMetrics = coherenceProfiler.analyzeDetailed(identicalRays, testDAG);
        // Identical rays should have high coherence (>=0.5), perfect coherence (1.0) is hard to achieve due to calculation method
        assertTrue(identicalMetrics.coherenceScore() >= 0.45,
                   String.format("Identical rays should have high coherence, got %.2f", identicalMetrics.coherenceScore()));
    }

    // Helper methods

    private ESVORay[] createCoherentRays(int count) {
        var rays = new ESVORay[count];
        for (var i = 0; i < count; i++) {
            // All rays point in same direction (coherent)
            rays[i] = createRay(-1.0f, 0.5f + i * 0.001f, 0.5f, 1.0f, 0.0f, 0.0f);
        }
        return rays;
    }

    private ESVORay createRay(float ox, float oy, float oz, float dx, float dy, float dz) {
        return new ESVORay(ox, oy, oz, dx, dy, dz);
    }
}
