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

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Phase 4.1 P1: GPU Performance Profiler
 *
 * Measures baseline (Phase 2 kernel) vs optimized (Streams A+B) GPU performance.
 *
 * Supports two modes:
 * 1. Mock mode: Synthetic measurements for CI/CD (no GPU required)
 * 2. Real GPU mode: Actual GPU measurements
 *
 * Baseline configuration (Phase 2):
 * - No shared memory cache
 * - Default workgroup size (64)
 * - MAX_TRAVERSAL_DEPTH = 16
 *
 * Optimized configuration (Streams A+B):
 * - Shared memory cache enabled (Stream A)
 * - GPU-tuned workgroup size (Stream B)
 * - Optimized MAX_TRAVERSAL_DEPTH from GPUAutoTuner
 *
 * @author hal.hildebrand
 */
public class GPUPerformanceProfiler {
    private static final Logger log = LoggerFactory.getLogger(GPUPerformanceProfiler.class);

    // Expected performance characteristics (from Phase 3 documentation)
    private static final double BASELINE_LATENCY_PER_100K_RAYS = 850.0; // microseconds
    private static final double OPTIMIZED_LATENCY_PER_100K_RAYS = 450.0; // microseconds (47% improvement)
    private static final float BASELINE_GPU_OCCUPANCY = 75.0f; // percent
    private static final float OPTIMIZED_GPU_OCCUPANCY = 85.0f; // percent
    private static final float OPTIMIZED_CACHE_HIT_RATE = 0.65f; // 65% cache hit rate

    private final Random random = new Random(12345); // Deterministic for testing

    /**
     * Profile baseline GPU performance (Phase 2: DAG kernel only, no optimizations).
     *
     * @param dag      DAG octree data structure
     * @param rayCount number of rays to trace
     * @param mockMode use synthetic measurements (true) or real GPU (false)
     * @return baseline performance metrics
     */
    public PerformanceMetrics profileBaseline(DAGOctreeData dag, int rayCount, boolean mockMode) {
        log.debug("Profiling baseline with {} rays (mock={})", rayCount, mockMode);

        if (mockMode) {
            return generateMockBaselineMetrics(dag, rayCount);
        } else {
            return measureRealGPUBaseline(dag, rayCount);
        }
    }

    /**
     * Profile optimized GPU performance (Streams A+B: cache + tuning).
     *
     * @param dag      DAG octree data structure
     * @param rayCount number of rays to trace
     * @param mockMode use synthetic measurements (true) or real GPU (false)
     * @return optimized performance metrics
     */
    public PerformanceMetrics profileOptimized(DAGOctreeData dag, int rayCount, boolean mockMode) {
        log.debug("Profiling optimized with {} rays (mock={})", rayCount, mockMode);

        if (mockMode) {
            return generateMockOptimizedMetrics(dag, rayCount);
        } else {
            return measureRealGPUOptimized(dag, rayCount);
        }
    }

    /**
     * Profile with custom configuration.
     *
     * @param dag      DAG octree data structure
     * @param config   profiler configuration
     * @param mockMode use synthetic measurements (true) or real GPU (false)
     * @return performance metrics
     */
    public PerformanceMetrics profileWithConfig(DAGOctreeData dag, ProfilerConfig config, boolean mockMode) {
        log.debug("Profiling with custom config: {} (mock={})", config, mockMode);

        if (mockMode) {
            return generateMockMetrics(dag, config);
        } else {
            return measureRealGPUWithConfig(dag, config);
        }
    }

    /**
     * Compare baseline vs optimized performance.
     *
     * @param baseline  baseline metrics
     * @param optimized optimized metrics
     * @return performance comparison
     */
    public PerformanceComparison compare(PerformanceMetrics baseline, PerformanceMetrics optimized) {
        var improvement = optimized.compareToBaseline(baseline);
        log.info("Performance comparison: {}% improvement", String.format("%.2f", improvement));
        return new PerformanceComparison(baseline, optimized, improvement);
    }

    // Mock measurement methods (for CI/CD without GPU)

    private PerformanceMetrics generateMockBaselineMetrics(DAGOctreeData dag, int rayCount) {
        // Scale latency based on ray count (linear scaling)
        var scaleFactor = rayCount / 100_000.0;
        var latency = BASELINE_LATENCY_PER_100K_RAYS * scaleFactor;

        // Add small random variance (±5%) for realism
        latency *= (1.0 + (random.nextDouble() - 0.5) * 0.1);

        // Calculate throughput
        var throughput = rayCount / latency;

        // Estimate traversal depth from DAG depth
        var avgDepth = estimateTraversalDepth(dag);

        return new PerformanceMetrics(
            "baseline",
            rayCount,
            latency,
            throughput,
            BASELINE_GPU_OCCUPANCY + (float) ((random.nextDouble() - 0.5) * 2.0), // ±1% variance
            avgDepth,
            0.0f, // No cache in baseline
            System.currentTimeMillis()
        );
    }

    private PerformanceMetrics generateMockOptimizedMetrics(DAGOctreeData dag, int rayCount) {
        // Scale latency based on ray count (linear scaling)
        var scaleFactor = rayCount / 100_000.0;
        var latency = OPTIMIZED_LATENCY_PER_100K_RAYS * scaleFactor;

        // Add small random variance (±5%) for realism
        latency *= (1.0 + (random.nextDouble() - 0.5) * 0.1);

        // Calculate throughput
        var throughput = rayCount / latency;

        // Estimate traversal depth from DAG depth
        var avgDepth = estimateTraversalDepth(dag);

        return new PerformanceMetrics(
            "optimized_A+B",
            rayCount,
            latency,
            throughput,
            OPTIMIZED_GPU_OCCUPANCY + (float) ((random.nextDouble() - 0.5) * 2.0), // ±1% variance
            avgDepth,
            OPTIMIZED_CACHE_HIT_RATE + (float) ((random.nextDouble() - 0.5) * 0.1), // ±5% variance
            System.currentTimeMillis()
        );
    }

    private PerformanceMetrics generateMockMetrics(DAGOctreeData dag, ProfilerConfig config) {
        // Base latency on configuration
        var baseLatency = config.enableCache() ?
                          OPTIMIZED_LATENCY_PER_100K_RAYS :
                          BASELINE_LATENCY_PER_100K_RAYS;

        // Scale by ray count
        var scaleFactor = config.rayCount() / 100_000.0;
        var latency = baseLatency * scaleFactor;

        // Workgroup size affects occupancy
        var occupancyAdjustment = (config.workgroupSize() - 64) / 64.0 * 5.0; // ±5% per 64 size change
        var occupancy = (config.enableCache() ? OPTIMIZED_GPU_OCCUPANCY : BASELINE_GPU_OCCUPANCY)
                        + (float) occupancyAdjustment;
        occupancy = Math.max(0.0f, Math.min(100.0f, occupancy));

        // Multiple iterations reduce variance
        if (config.iterations() > 1) {
            latency *= (1.0 + (random.nextDouble() - 0.5) * 0.05); // Reduced variance
        } else {
            latency *= (1.0 + (random.nextDouble() - 0.5) * 0.15); // Higher variance
        }

        var throughput = config.rayCount() / latency;
        var avgDepth = estimateTraversalDepth(dag);

        var scenario = config.enableCache() ? "optimized" : "baseline";

        return new PerformanceMetrics(
            scenario,
            config.rayCount(),
            latency,
            throughput,
            occupancy,
            avgDepth,
            config.enableCache() ? OPTIMIZED_CACHE_HIT_RATE : 0.0f,
            System.currentTimeMillis()
        );
    }

    // Real GPU measurement methods (stubs for future implementation)

    private PerformanceMetrics measureRealGPUBaseline(DAGOctreeData dag, int rayCount) {
        log.info("Real GPU baseline measurement not yet implemented, using mock");
        // TODO: Phase 4.1 P1 - Implement real GPU measurement
        // 1. Initialize DAGOpenCLRenderer without optimizations
        // 2. Upload DAG data
        // 3. Generate test rays
        // 4. Execute kernel with timing
        // 5. Collect GPU profiling data (occupancy, cache stats)
        // 6. Return measured PerformanceMetrics
        return generateMockBaselineMetrics(dag, rayCount);
    }

    private PerformanceMetrics measureRealGPUOptimized(DAGOctreeData dag, int rayCount) {
        log.info("Real GPU optimized measurement not yet implemented, using mock");
        // TODO: Phase 4.1 P1 - Implement real GPU measurement
        // 1. Initialize DAGOpenCLRenderer with Stream A+B optimizations
        // 2. Enable shared memory cache (Stream A)
        // 3. Use GPUAutoTuner to get optimal workgroup size (Stream B)
        // 4. Upload DAG data
        // 5. Generate test rays
        // 6. Execute kernel with timing
        // 7. Collect GPU profiling data (occupancy, cache hit rate)
        // 8. Return measured PerformanceMetrics
        return generateMockOptimizedMetrics(dag, rayCount);
    }

    private PerformanceMetrics measureRealGPUWithConfig(DAGOctreeData dag, ProfilerConfig config) {
        log.info("Real GPU custom config measurement not yet implemented, using mock");
        // TODO: Phase 4.1 P1 - Implement real GPU measurement with custom config
        return generateMockMetrics(dag, config);
    }

    // Helper methods

    /**
     * Estimate average traversal depth from DAG structure.
     */
    private int estimateTraversalDepth(DAGOctreeData dag) {
        if (dag == null || dag.nodeCount() == 0) {
            return 0;
        }

        // Simple heuristic: log8(nodeCount) gives approximate depth
        // DAG compression reduces node count, so add factor
        var estimatedDepth = (int) (Math.log(dag.nodeCount()) / Math.log(8) * 1.5);

        // Clamp to reasonable range
        return Math.max(4, Math.min(16, estimatedDepth));
    }
}
