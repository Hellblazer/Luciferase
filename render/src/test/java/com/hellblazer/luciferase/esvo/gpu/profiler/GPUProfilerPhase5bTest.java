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

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5b: GPU Profiler Real Measurements - Integration Tests
 *
 * Validates that GPUPerformanceProfiler correctly measures:
 * - Baseline performance (Phase 2: DAG kernel only)
 * - Optimized performance (Streams A+B: cache + tuning)
 * - Custom configuration performance
 *
 * Uses mock GPU profiling (not requiring actual GPU hardware).
 * Real GPU profiling enabled via GPU_ENABLED environment variable.
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 5b: GPU Profiler Real Measurements")
class GPUProfilerPhase5bTest {

    private GPUPerformanceProfiler profiler;
    private DAGOctreeData testDAG;
    private ESVOOctreeData testSVO;

    @BeforeEach
    void setUp() {
        profiler = new GPUPerformanceProfiler();
        testSVO = buildTestOctree();
        testDAG = buildTestDAG(testSVO);
    }

    /**
     * Test that real GPU baseline measurement produces valid metrics
     */
    @Test
    @DisplayName("Real GPU baseline measurement returns valid metrics")
    void testRealGPUBaselineMetrics() {
        var rayCount = 100_000;

        // profileBaseline with mockMode=false attempts real GPU measurement
        // Falls back to mock if GPU unavailable
        var metrics = profiler.profileBaseline(testDAG, rayCount, false);

        assertNotNull(metrics, "Metrics should not be null");
        assertEquals(rayCount, metrics.rayCount(), "Ray count should match");
        assertTrue(metrics.latencyMicroseconds() > 0, "Latency should be positive");
        assertTrue(metrics.throughputRaysPerMicrosecond() > 0, "Throughput should be positive");
        assertTrue(metrics.gpuOccupancyPercent() > 0 && metrics.gpuOccupancyPercent() <= 100,
                   "Occupancy should be in (0, 100]");

        System.out.println("Baseline metrics: " + metrics);
    }

    /**
     * Test that real GPU optimized measurement includes cache hit rate
     */
    @Test
    @DisplayName("Real GPU optimized measurement tracks cache hit rate")
    void testRealGPUOptimizedMetrics() {
        var rayCount = 100_000;

        // profileOptimized with mockMode=false attempts real GPU measurement
        var metrics = profiler.profileOptimized(testDAG, rayCount, false);

        assertNotNull(metrics, "Metrics should not be null");
        assertEquals(rayCount, metrics.rayCount(), "Ray count should match");
        assertTrue(metrics.latencyMicroseconds() > 0, "Latency should be positive");
        assertTrue(metrics.throughputRaysPerMicrosecond() > 0, "Throughput should be positive");

        // Optimized should have cache hit rate > 0
        assertTrue(metrics.cacheHitRate() >= 0.0 && metrics.cacheHitRate() <= 1.0,
                   "Cache hit rate should be in [0, 1]");
        assertTrue(metrics.cacheHitRate() > 0.0, "Optimized should have cache hits");

        System.out.println("Optimized metrics: " + metrics);
    }

    /**
     * Test that real GPU baseline < optimized in latency
     */
    @Test
    @DisplayName("Optimized GPU performance improves over baseline")
    void testOptimizedImprovement() {
        var rayCount = 100_000;

        var baseline = profiler.profileBaseline(testDAG, rayCount, false);
        var optimized = profiler.profileOptimized(testDAG, rayCount, false);

        assertTrue(optimized.latencyMicroseconds() < baseline.latencyMicroseconds(),
                   "Optimized latency should be less than baseline");
        assertTrue(optimized.throughputRaysPerMicrosecond() > baseline.throughputRaysPerMicrosecond(),
                   "Optimized throughput should be greater than baseline");

        var improvement = optimized.compareToBaseline(baseline);
        System.out.println("Improvement: " + String.format("%.1f%%", improvement));
        assertTrue(improvement > 0, "Should show improvement");
    }

    /**
     * Test custom configuration profiling with cache enabled
     */
    @Test
    @DisplayName("Real GPU profiling with custom config (cache enabled)")
    void testCustomConfigWithCache() {
        var config = new ProfilerConfig(100_000, true, 128, 16, 1);

        var metrics = profiler.profileWithConfig(testDAG, config, false);

        assertNotNull(metrics, "Metrics should not be null");
        assertEquals(100_000, metrics.rayCount(), "Ray count should match config");
        assertTrue(metrics.cacheHitRate() >= 0.0, "Cache hit rate should be non-negative");

        System.out.println("Custom config (cache enabled): " + metrics);
    }

    /**
     * Test custom configuration profiling without cache
     */
    @Test
    @DisplayName("Real GPU profiling with custom config (cache disabled)")
    void testCustomConfigWithoutCache() {
        var config = new ProfilerConfig(100_000, false, 64, 16, 1);

        var metrics = profiler.profileWithConfig(testDAG, config, false);

        assertNotNull(metrics, "Metrics should not be null");
        assertEquals(100_000, metrics.rayCount(), "Ray count should match config");
        assertTrue(metrics.cacheHitRate() == 0.0, "Cache hit rate should be zero without cache");

        System.out.println("Custom config (cache disabled): " + metrics);
    }

    /**
     * Test that larger workgroup size affects occupancy
     */
    @Test
    @DisplayName("Larger workgroup size improves occupancy")
    void testWorkgroupSizeImpact() {
        var smallWorkgroup = new ProfilerConfig(100_000, true, 64, 16, 1);
        var largeWorkgroup = new ProfilerConfig(100_000, true, 256, 16, 1);

        var smallMetrics = profiler.profileWithConfig(testDAG, smallWorkgroup, false);
        var largeMetrics = profiler.profileWithConfig(testDAG, largeWorkgroup, false);

        // Larger workgroup should have similar or better occupancy
        assertTrue(largeMetrics.gpuOccupancyPercent() >= smallMetrics.gpuOccupancyPercent() * 0.95,
                   "Larger workgroup should have similar or better occupancy");

        System.out.println("Small workgroup (64): occupancy " + smallMetrics.gpuOccupancyPercent() + "%");
        System.out.println("Large workgroup (256): occupancy " + largeMetrics.gpuOccupancyPercent() + "%");
    }

    /**
     * Test that performance comparison works
     */
    @Test
    @DisplayName("Performance comparison calculates improvement correctly")
    void testPerformanceComparison() {
        var rayCount = 100_000;

        var baseline = profiler.profileBaseline(testDAG, rayCount, false);
        var optimized = profiler.profileOptimized(testDAG, rayCount, false);
        var comparison = profiler.compare(baseline, optimized);

        assertNotNull(comparison, "Comparison should not be null");
        assertTrue(comparison.isFaster(), "Should show improvement");

        var speedup = comparison.speedupFactor();
        System.out.println("Performance improvement: speedup " + String.format("%.2fx", speedup));
    }

    /**
     * Test scaling across different ray counts
     */
    @Test
    @DisplayName("Profiling scales correctly with ray count")
    void testScaling() {
        var rayCounts = new int[]{10_000, 100_000, 1_000_000};

        for (int rayCount : rayCounts) {
            var baseline = profiler.profileBaseline(testDAG, rayCount, false);
            var optimized = profiler.profileOptimized(testDAG, rayCount, false);

            // Latency should scale roughly linearly with ray count
            assertTrue(baseline.latencyMicroseconds() > 0, "Baseline latency should be positive");
            assertTrue(optimized.latencyMicroseconds() > 0, "Optimized latency should be positive");

            var improvement = optimized.compareToBaseline(baseline);
            System.out.println(String.format("Ray count %,d: %.1f%% improvement",
                    rayCount, improvement));

            // Improvement should be consistent across ray counts
            assertTrue(improvement > 30 && improvement < 60, "Improvement should be in expected range");
        }
    }

    // ==================== Helper Methods ====================

    private ESVOOctreeData buildTestOctree() {
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

    private DAGOctreeData buildTestDAG(ESVOOctreeData octree) {
        return DAGBuilder.from(octree).build();
    }
}
