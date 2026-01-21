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

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.profiler.GPUPerformanceProfiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Tests for StreamCPerformanceEvaluation - Phase 4.2 P2
 *
 * Tests the performance evaluation workflow that collects baseline, optimized, and coherence metrics.
 *
 * @author hal.hildebrand
 */
@DisplayName("Stream C: Performance Evaluation Workflow")
class StreamCPerformanceEvaluationTest {

    private StreamCPerformanceEvaluation evaluation;
    private DAGOctreeData testDag;

    @BeforeEach
    void setUp() {
        evaluation = new StreamCPerformanceEvaluation();
        testDag = createTestDAG();
    }

    /**
     * Test 1: Evaluate with mock mode - collect all metrics
     */
    @Test
    @DisplayName("Collect baseline, optimized, and coherence metrics in mock mode")
    void testEvaluate_MockMode() {
        var rayCount = 100_000;

        var result = evaluation.evaluate(testDag, rayCount, true);

        assertNotNull(result, "Evaluation result should not be null");
        assertNotNull(result.baseline(), "Baseline metrics should be collected");
        assertNotNull(result.optimized(), "Optimized metrics should be collected");
        assertNotNull(result.coherence(), "Coherence metrics should be collected");

        // Verify ray count
        assertEquals(rayCount, result.baseline().rayCount(), "Baseline ray count should match");
        assertEquals(rayCount, result.optimized().rayCount(), "Optimized ray count should match");
    }

    /**
     * Test 2: Verify baseline metrics are reasonable
     */
    @Test
    @DisplayName("Baseline metrics should be reasonable (higher latency than optimized)")
    void testEvaluate_BaselineMetricsReasonable() {
        var result = evaluation.evaluate(testDag, 100_000, true);

        var baseline = result.baseline();

        assertTrue(baseline.latencyMicroseconds() > 0, "Baseline latency should be positive");
        assertTrue(baseline.throughputRaysPerMicrosecond() > 0, "Baseline throughput should be positive");
        assertTrue(baseline.gpuOccupancyPercent() > 0, "Baseline occupancy should be positive");
        assertTrue(baseline.gpuOccupancyPercent() <= 100, "Baseline occupancy should be <= 100%");
    }

    /**
     * Test 3: Verify optimized metrics show improvement
     */
    @Test
    @DisplayName("Optimized metrics should show improvement over baseline")
    void testEvaluate_OptimizedShowsImprovement() {
        var result = evaluation.evaluate(testDag, 100_000, true);

        var baseline = result.baseline();
        var optimized = result.optimized();

        // Optimized should be faster (lower latency)
        assertTrue(optimized.latencyMicroseconds() < baseline.latencyMicroseconds(),
                   "Optimized latency should be lower than baseline");

        // Optimized should have higher throughput
        assertTrue(optimized.throughputRaysPerMicrosecond() > baseline.throughputRaysPerMicrosecond(),
                   "Optimized throughput should be higher than baseline");

        // Optimized should have cache hits
        assertTrue(optimized.cacheHitRate() > 0, "Optimized should have cache hits");
        assertEquals(0, baseline.cacheHitRate(), "Baseline should have no cache");
    }

    /**
     * Test 4: Coherence metrics should be valid
     */
    @Test
    @DisplayName("Coherence metrics should be within valid ranges")
    void testEvaluate_CoherenceMetricsValid() {
        var result = evaluation.evaluate(testDag, 100_000, true);

        var coherence = result.coherence();

        assertTrue(coherence.coherenceScore() >= 0.0 && coherence.coherenceScore() <= 1.0,
                   "Coherence score should be in [0, 1]");
        assertTrue(coherence.upperLevelSharingPercent() >= 0.0 && coherence.upperLevelSharingPercent() <= 1.0,
                   "Upper-level sharing should be in [0, 1]");
        assertTrue(coherence.uniqueNodesVisited() > 0, "Should have visited some nodes");
        assertTrue(coherence.totalNodeVisits() >= coherence.uniqueNodesVisited(),
                   "Total visits should be >= unique nodes");
    }

    /**
     * Test 5: Different ray counts scale correctly
     */
    @Test
    @DisplayName("Latency should scale with ray count")
    void testEvaluate_RayCountScaling() {
        var result100K = evaluation.evaluate(testDag, 100_000, true);
        var result1M = evaluation.evaluate(testDag, 1_000_000, true);

        var latency100K = result100K.optimized().latencyMicroseconds();
        var latency1M = result1M.optimized().latencyMicroseconds();

        // 1M rays should take approximately 10x longer than 100K rays
        var scaleFactor = latency1M / latency100K;
        assertTrue(scaleFactor > 8.0 && scaleFactor < 12.0,
                   "Latency should scale roughly linearly (scale factor: " + scaleFactor + ")");
    }

    /**
     * Test 6: Null DAG should throw exception
     */
    @Test
    @DisplayName("Null DAG should throw IllegalArgumentException")
    void testEvaluate_NullDAG() {
        assertThrows(IllegalArgumentException.class,
                     () -> evaluation.evaluate(null, 100_000, true),
                     "Null DAG should throw exception");
    }

    /**
     * Test 7: Invalid ray count should throw exception
     */
    @Test
    @DisplayName("Invalid ray count should throw IllegalArgumentException")
    void testEvaluate_InvalidRayCount() {
        assertThrows(IllegalArgumentException.class,
                     () -> evaluation.evaluate(testDag, 0, true),
                     "Zero ray count should throw exception");

        assertThrows(IllegalArgumentException.class,
                     () -> evaluation.evaluate(testDag, -1000, true),
                     "Negative ray count should throw exception");
    }

    /**
     * Test 8: Evaluation result provides all necessary data
     */
    @Test
    @DisplayName("EvaluationResult should provide complete data")
    void testEvaluationResult_CompleteData() {
        var result = evaluation.evaluate(testDag, 100_000, true);

        // All three components should be present
        assertNotNull(result.baseline(), "Baseline should be present");
        assertNotNull(result.optimized(), "Optimized should be present");
        assertNotNull(result.coherence(), "Coherence should be present");

        // Verify data consistency
        assertEquals(result.baseline().rayCount(), result.optimized().rayCount(),
                     "Baseline and optimized should use same ray count");
    }

    /**
     * Test 9: Evaluation with custom profiler
     */
    @Test
    @DisplayName("Evaluation should work with custom profiler")
    void testEvaluate_CustomProfiler() {
        var customProfiler = new GPUPerformanceProfiler();
        var customEvaluation = new StreamCPerformanceEvaluation(customProfiler);

        var result = customEvaluation.evaluate(testDag, 100_000, true);

        assertNotNull(result, "Custom profiler evaluation should succeed");
    }

    /**
     * Test 10: Repeated evaluations should be consistent
     */
    @Test
    @DisplayName("Repeated evaluations should produce consistent results")
    void testEvaluate_ConsistentResults() {
        var result1 = evaluation.evaluate(testDag, 100_000, true);
        var result2 = evaluation.evaluate(testDag, 100_000, true);

        // Results should be similar (within 10% variance due to random noise)
        var latencyDiff = Math.abs(result1.optimized().latencyMicroseconds()
                                   - result2.optimized().latencyMicroseconds());
        var avgLatency = (result1.optimized().latencyMicroseconds()
                         + result2.optimized().latencyMicroseconds()) / 2.0;

        assertTrue(latencyDiff / avgLatency < 0.15,
                   "Repeated evaluations should be consistent (within 15% variance)");
    }

    // Helper methods

    private DAGOctreeData createTestDAG() {
        // Create minimal test DAG for evaluation
        // Create simple 2-level SVO (root + 8 leaf children), then convert to DAG
        var octree = new ESVOOctreeData(16);

        // Root node at index 0
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF); // All 8 children present
        root.setChildPtr(1); // Children start at index 1
        octree.setNode(0, root);

        // Create 8 leaf children (indices 1-8)
        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0); // No children (leaf)
            octree.setNode(i + 1, leaf);
        }

        // Convert SVO to DAG
        return DAGBuilder.from(octree).build();
    }
}
