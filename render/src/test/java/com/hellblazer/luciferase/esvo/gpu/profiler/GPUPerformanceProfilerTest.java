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
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.1 P1: GPU Performance Profiler Test Suite
 *
 * Tests baseline and optimized GPU performance measurement.
 * GPU tests are conditional and can be mocked for CI/CD.
 *
 * @author hal.hildebrand
 */
@DisplayName("GPUPerformanceProfiler Tests")
class GPUPerformanceProfilerTest {

    private DAGOctreeData testDAG;
    private ESVORay[] testRays;

    @BeforeEach
    void setUp() {
        // Create minimal test DAG (8 nodes, depth 2)
        testDAG = createMinimalDAG();

        // Create test rays (100 rays for fast testing)
        testRays = createTestRays(100);
    }

    @Test
    @DisplayName("Create profiler with default configuration")
    void testCreateProfiler() {
        var profiler = new GPUPerformanceProfiler();
        assertNotNull(profiler);
    }

    @Test
    @DisplayName("Profile baseline with mock measurements")
    void testProfileBaseline_Mock() {
        var profiler = new GPUPerformanceProfiler();

        // Use mock mode (no actual GPU required)
        var metrics = profiler.profileBaseline(testDAG, 100, true);

        assertNotNull(metrics);
        assertEquals("baseline", metrics.scenario());
        assertEquals(100, metrics.rayCount());
        assertTrue(metrics.latencyMicroseconds() > 0, "Should have positive latency");
        assertTrue(metrics.throughputRaysPerMicrosecond() > 0, "Should have positive throughput");
    }

    @Test
    @DisplayName("Profile optimized with mock measurements")
    void testProfileOptimized_Mock() {
        var profiler = new GPUPerformanceProfiler();

        // Use mock mode (no actual GPU required)
        var metrics = profiler.profileOptimized(testDAG, 100, true);

        assertNotNull(metrics);
        assertTrue(metrics.scenario().contains("optimized"));
        assertEquals(100, metrics.rayCount());
        assertTrue(metrics.latencyMicroseconds() > 0, "Should have positive latency");
        assertTrue(metrics.cacheHitRate() > 0, "Optimized should have cache hits");
    }

    @Test
    @DisplayName("Optimized faster than baseline (mock)")
    void testOptimizedFasterThanBaseline_Mock() {
        var profiler = new GPUPerformanceProfiler();

        var baseline = profiler.profileBaseline(testDAG, 100, true);
        var optimized = profiler.profileOptimized(testDAG, 100, true);

        assertTrue(optimized.latencyMicroseconds() < baseline.latencyMicroseconds(),
                   "Optimized should be faster than baseline");
        assertTrue(optimized.throughputRaysPerMicrosecond() > baseline.throughputRaysPerMicrosecond(),
                   "Optimized should have higher throughput");
    }

    @Test
    @DisplayName("Profile with 100K rays (mock)")
    void testProfile100KRays_Mock() {
        var profiler = new GPUPerformanceProfiler();

        var metrics = profiler.profileBaseline(testDAG, 100_000, true);

        assertEquals(100_000, metrics.rayCount());
        assertTrue(metrics.latencyMicroseconds() > 0);
    }

    @Test
    @DisplayName("Profile with 1M rays (mock)")
    void testProfile1MRays_Mock() {
        var profiler = new GPUPerformanceProfiler();

        var metrics = profiler.profileBaseline(testDAG, 1_000_000, true);

        assertEquals(1_000_000, metrics.rayCount());
        assertTrue(metrics.latencyMicroseconds() > 0);
    }

    @Test
    @DisplayName("Profile with custom configuration")
    void testProfileWithConfig() {
        var profiler = new GPUPerformanceProfiler();
        var config = new ProfilerConfig(
            100,                // rayCount
            true,               // enableCache
            64,                 // workgroupSize
            16,                 // maxTraversalDepth
            5                   // iterations
        );

        var metrics = profiler.profileWithConfig(testDAG, config, true);

        assertNotNull(metrics);
        assertEquals(100, metrics.rayCount());
    }

    @Test
    @DisplayName("Compare baseline vs optimized")
    void testCompareBaselineVsOptimized() {
        var profiler = new GPUPerformanceProfiler();

        var baseline = profiler.profileBaseline(testDAG, 1000, true);
        var optimized = profiler.profileOptimized(testDAG, 1000, true);

        var comparison = profiler.compare(baseline, optimized);

        assertNotNull(comparison);
        assertTrue(comparison.improvement() > 0, "Should show improvement");
        assertEquals(baseline, comparison.baseline());
        assertEquals(optimized, comparison.optimized());
    }

    @Test
    @DisplayName("Multiple iterations for stability")
    void testMultipleIterations() {
        var profiler = new GPUPerformanceProfiler();
        var config = new ProfilerConfig(100, false, 64, 16, 10);

        var metrics = profiler.profileWithConfig(testDAG, config, true);

        // Multiple iterations should produce stable results
        assertNotNull(metrics);
        assertTrue(metrics.latencyMicroseconds() > 0);
    }

    @Test
    @DisplayName("Measure throughput correctly")
    void testThroughputMeasurement() {
        var profiler = new GPUPerformanceProfiler();

        var metrics = profiler.profileBaseline(testDAG, 10_000, true);

        // Throughput = rayCount / latencyMicros
        var expectedThroughput = metrics.rayCount() / metrics.latencyMicroseconds();
        assertEquals(expectedThroughput, metrics.throughputRaysPerMicrosecond(), 0.001);
    }

    @Test
    @DisplayName("GPU occupancy within valid range")
    void testGPUOccupancyRange() {
        var profiler = new GPUPerformanceProfiler();

        var metrics = profiler.profileOptimized(testDAG, 1000, true);

        assertTrue(metrics.gpuOccupancyPercent() >= 0.0f, "Occupancy >= 0%");
        assertTrue(metrics.gpuOccupancyPercent() <= 100.0f, "Occupancy <= 100%");
    }

    @Test
    @DisplayName("Cache hit rate increases with optimization")
    void testCacheHitRateIncrease() {
        var profiler = new GPUPerformanceProfiler();

        var baseline = profiler.profileBaseline(testDAG, 1000, true);
        var optimized = profiler.profileOptimized(testDAG, 1000, true);

        assertTrue(optimized.cacheHitRate() > baseline.cacheHitRate(),
                   "Optimized should have higher cache hit rate");
    }

    @Test
    @DisplayName("Traversal depth consistent across measurements")
    void testTraversalDepthConsistency() {
        var profiler = new GPUPerformanceProfiler();

        var baseline = profiler.profileBaseline(testDAG, 100, true);
        var optimized = profiler.profileOptimized(testDAG, 100, true);

        // Same DAG and rays should produce same average depth
        assertEquals(baseline.averageTraversalDepth(), optimized.averageTraversalDepth(),
                     "Traversal depth should be consistent");
    }

    @Test
    @DisplayName("Timestamp recorded")
    void testTimestampRecorded() {
        var profiler = new GPUPerformanceProfiler();
        var before = System.currentTimeMillis();

        var metrics = profiler.profileBaseline(testDAG, 100, true);

        var after = System.currentTimeMillis();

        assertTrue(metrics.timestamp() >= before);
        assertTrue(metrics.timestamp() <= after);
    }

    @Test
    @DisplayName("Profiler handles empty DAG gracefully")
    void testEmptyDAG() {
        var profiler = new GPUPerformanceProfiler();
        var emptySVO = TestOctreeFactory.createEmpty();
        var emptyDAG = DAGBuilder.from(emptySVO).build();

        // Should not throw, but may return zero metrics
        var metrics = profiler.profileBaseline(emptyDAG, 100, true);
        assertNotNull(metrics);
    }

    // Conditional GPU tests - only run if GPU hardware available
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @DisplayName("Profile baseline with real GPU")
    void testProfileBaseline_RealGPU() {
        var profiler = new GPUPerformanceProfiler();

        var metrics = profiler.profileBaseline(testDAG, 100_000, false);

        assertNotNull(metrics);
        assertEquals("baseline", metrics.scenario());
        assertEquals(100_000, metrics.rayCount());
        assertTrue(metrics.latencyMicroseconds() > 0);

        System.out.printf("Real GPU Baseline: %s%n", metrics);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @DisplayName("Profile optimized with real GPU")
    void testProfileOptimized_RealGPU() {
        var profiler = new GPUPerformanceProfiler();

        var metrics = profiler.profileOptimized(testDAG, 100_000, false);

        assertNotNull(metrics);
        assertTrue(metrics.scenario().contains("optimized"));
        assertEquals(100_000, metrics.rayCount());
        assertTrue(metrics.latencyMicroseconds() > 0);

        System.out.printf("Real GPU Optimized: %s%n", metrics);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @DisplayName("Full profiling workflow with real GPU")
    void testFullProfilingWorkflow_RealGPU() {
        var profiler = new GPUPerformanceProfiler();

        // Measure baseline
        var baseline = profiler.profileBaseline(testDAG, 100_000, false);

        // Measure optimized
        var optimized = profiler.profileOptimized(testDAG, 100_000, false);

        // Compare
        var comparison = profiler.compare(baseline, optimized);

        System.out.printf("Baseline: %s%n", baseline);
        System.out.printf("Optimized: %s%n", optimized);
        System.out.printf("Improvement: %.2f%%%n", comparison.improvement());

        assertTrue(comparison.improvement() > 0, "Optimized should be faster");
    }

    // Helper methods

    private DAGOctreeData createMinimalDAG() {
        // Create simple 2-level SVO, then convert to DAG
        var svo = TestOctreeFactory.createSimpleTestOctree();
        return DAGBuilder.from(svo).build();
    }

    private ESVORay[] createTestRays(int count) {
        var rays = new ESVORay[count];
        for (var i = 0; i < count; i++) {
            // Create rays pointing into the scene from the left
            rays[i] = new ESVORay(-1.0f, 0.5f, 0.5f,  // origin
                                   1.0f, 0.0f, 0.0f);  // direction (pointing right)
        }
        return rays;
    }
}
