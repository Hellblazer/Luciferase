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

import com.hellblazer.luciferase.common.time.Clock;
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
    private volatile Clock clock = Clock.system();

    /**
     * Inject a clock for deterministic timestamp testing.
     *
     * @param clock the clock to use for timestamps in PerformanceMetrics
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Profile baseline GPU performance (Phase 2: DAG kernel only, no optimizations).
     *
     * <p><b>Note</b>: Both {@code mockMode=true} and {@code mockMode=false} currently return
     * model-derived estimates from compile-time constants. The {@code mockMode} flag is preserved
     * for a future real-GPU path: when a real GPU is available, {@code mockMode=false} will execute
     * the DAG kernel and return measured timings. Until then, both paths are equivalent estimates.
     *
     * @param dag      DAG octree data structure
     * @param rayCount number of rays to trace
     * @param mockMode reserved for future real-GPU path; currently both modes return model estimates
     * @return baseline performance metrics (model-derived)
     */
    public PerformanceMetrics profileBaseline(DAGOctreeData dag, int rayCount, boolean mockMode) {
        log.debug("Profiling baseline with {} rays (mock={})", rayCount, mockMode);
        return modelEstimateBaseline(dag, rayCount);
    }

    /**
     * Profile optimized GPU performance (Streams A+B: cache + tuning).
     *
     * <p><b>Note</b>: Both {@code mockMode=true} and {@code mockMode=false} currently return
     * model-derived estimates. See {@link #profileBaseline} for the future-real-GPU note.
     *
     * @param dag      DAG octree data structure
     * @param rayCount number of rays to trace
     * @param mockMode reserved for future real-GPU path; currently both modes return model estimates
     * @return optimized performance metrics (model-derived)
     */
    public PerformanceMetrics profileOptimized(DAGOctreeData dag, int rayCount, boolean mockMode) {
        log.debug("Profiling optimized with {} rays (mock={})", rayCount, mockMode);
        return modelEstimateOptimized(dag, rayCount);
    }

    /**
     * Profile with custom configuration.
     *
     * <p><b>Note</b>: Both {@code mockMode=true} and {@code mockMode=false} currently return
     * model-derived estimates. See {@link #profileBaseline} for the future-real-GPU note.
     *
     * @param dag      DAG octree data structure
     * @param config   profiler configuration
     * @param mockMode reserved for future real-GPU path; currently both modes return model estimates
     * @return performance metrics (model-derived)
     */
    public PerformanceMetrics profileWithConfig(DAGOctreeData dag, ProfilerConfig config, boolean mockMode) {
        log.debug("Profiling with custom config: {} (mock={})", config, mockMode);
        return modelEstimateWithConfig(dag, config);
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

    // Model-estimate methods — NOT real GPU measurements.
    // Both mock and non-mock paths currently route here; real GPU measurement requires
    // DAGOpenCLRenderer initialization and actual kernel execution (not yet implemented).
    // When real-GPU support is added, the non-mock path must be replaced, not wrapped.

    /**
     * Model estimate for baseline GPU performance.
     *
     * <p>Derives latency from compile-time constant BASELINE_LATENCY_PER_100K_RAYS scaled
     * by ray count, with small random jitter. Does NOT execute any GPU kernel.
     */
    private PerformanceMetrics modelEstimateBaseline(DAGOctreeData dag, int rayCount) {
        log.debug("Model-estimating baseline GPU performance (not real GPU) for {} rays", rayCount);

        var avgDepth = estimateTraversalDepth(dag);
        var scaleFactor = rayCount / 100_000.0;
        var latencyMicros = BASELINE_LATENCY_PER_100K_RAYS * scaleFactor
                            * (1.0 + (random.nextDouble() - 0.5) * 0.05);
        var throughput = rayCount / latencyMicros;

        log.debug("Model-estimated baseline: {} rays, {}µs ({} rays/µs)",
                  rayCount, String.format("%.2f", latencyMicros), String.format("%.1f", throughput));

        return new PerformanceMetrics(
            "baseline",
            rayCount,
            latencyMicros,
            throughput,
            BASELINE_GPU_OCCUPANCY + (float) ((random.nextDouble() - 0.5) * 2.0),
            avgDepth,
            0.0f, // No cache in baseline
            clock.currentTimeMillis()
        );
    }

    /**
     * Model estimate for optimized GPU performance (Streams A+B).
     *
     * <p>Derives latency from compile-time constant OPTIMIZED_LATENCY_PER_100K_RAYS scaled
     * by ray count, with small random jitter. Does NOT execute any GPU kernel.
     */
    private PerformanceMetrics modelEstimateOptimized(DAGOctreeData dag, int rayCount) {
        log.debug("Model-estimating optimized GPU performance (not real GPU) for {} rays", rayCount);

        var avgDepth = estimateTraversalDepth(dag);
        var scaleFactor = rayCount / 100_000.0;
        var latencyMicros = OPTIMIZED_LATENCY_PER_100K_RAYS * scaleFactor
                            * (1.0 + (random.nextDouble() - 0.5) * 0.05);
        var throughput = rayCount / latencyMicros;
        var cacheHitRate = OPTIMIZED_CACHE_HIT_RATE + (float) ((random.nextDouble() - 0.5) * 0.1);

        log.debug("Model-estimated optimized: {} rays, {}µs ({} rays/µs, cache hit: {}%)",
                  rayCount, String.format("%.2f", latencyMicros), String.format("%.1f", throughput),
                  String.format("%.1f", cacheHitRate * 100));

        return new PerformanceMetrics(
            "optimized_A+B",
            rayCount,
            latencyMicros,
            throughput,
            OPTIMIZED_GPU_OCCUPANCY + (float) ((random.nextDouble() - 0.5) * 2.0),
            avgDepth,
            cacheHitRate,
            clock.currentTimeMillis()
        );
    }

    /**
     * Model estimate for GPU performance with a custom configuration.
     *
     * <p>Does NOT execute any GPU kernel. Results are model-derived estimates only.
     */
    private PerformanceMetrics modelEstimateWithConfig(DAGOctreeData dag, ProfilerConfig config) {
        log.debug("Model-estimating GPU performance (not real GPU) for config: {}", config);

        var avgDepth = estimateTraversalDepth(dag);
        var baseLatency = config.enableCache() ? OPTIMIZED_LATENCY_PER_100K_RAYS : BASELINE_LATENCY_PER_100K_RAYS;
        var scaleFactor = config.rayCount() / 100_000.0;
        var jitterFactor = config.iterations() > 1
                           ? (1.0 + (random.nextDouble() - 0.5) * 0.02)
                           : (1.0 + (random.nextDouble() - 0.5) * 0.1);
        var latencyMicros = baseLatency * scaleFactor * jitterFactor;

        var occupancyAdjustment = (config.workgroupSize() - 64) / 64.0 * 5.0;
        var baseOccupancy = config.enableCache() ? OPTIMIZED_GPU_OCCUPANCY : BASELINE_GPU_OCCUPANCY;
        var occupancy = Math.max(0.0f, Math.min(100.0f, baseOccupancy + (float) occupancyAdjustment));

        var throughput = config.rayCount() / latencyMicros;
        var cacheHitRate = config.enableCache() ? OPTIMIZED_CACHE_HIT_RATE : 0.0f;
        var scenario = config.enableCache() ? "optimized_custom" : "baseline_custom";

        log.debug("Model-estimated custom: {} rays, {}µs (workgroup: {}, cache: {})",
                  config.rayCount(), String.format("%.2f", latencyMicros),
                  config.workgroupSize(), config.enableCache());

        return new PerformanceMetrics(
            scenario,
            config.rayCount(),
            latencyMicros,
            throughput,
            occupancy,
            avgDepth,
            cacheHitRate,
            clock.currentTimeMillis()
        );
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
