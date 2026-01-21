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

import com.hellblazer.luciferase.esvo.core.ESVORay;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.profiler.CoherenceMetrics;
import com.hellblazer.luciferase.esvo.gpu.profiler.CoherenceProfiler;
import com.hellblazer.luciferase.esvo.gpu.profiler.GPUPerformanceProfiler;
import com.hellblazer.luciferase.esvo.gpu.profiler.PerformanceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Phase 4.2 P2: Stream C Performance Evaluation
 *
 * Orchestrates the performance measurement workflow for Stream C activation decision:
 * 1. Measures baseline performance (Phase 2: DAG kernel only)
 * 2. Measures optimized performance (Streams A+B: cache + tuning)
 * 3. Analyzes ray coherence for beam optimization potential
 *
 * All three metrics feed into StreamCActivationDecision for final determination.
 *
 * @author hal.hildebrand
 */
public class StreamCPerformanceEvaluation {
    private static final Logger log = LoggerFactory.getLogger(StreamCPerformanceEvaluation.class);

    private final GPUPerformanceProfiler performanceProfiler;
    private final CoherenceProfiler coherenceProfiler;

    /**
     * Create evaluation with default profilers.
     */
    public StreamCPerformanceEvaluation() {
        this(new GPUPerformanceProfiler(), new CoherenceProfiler());
    }

    /**
     * Create evaluation with custom profiler.
     *
     * @param performanceProfiler GPU performance profiler
     */
    public StreamCPerformanceEvaluation(GPUPerformanceProfiler performanceProfiler) {
        this(performanceProfiler, new CoherenceProfiler());
    }

    /**
     * Create evaluation with custom profilers.
     *
     * @param performanceProfiler GPU performance profiler
     * @param coherenceProfiler   Ray coherence profiler
     */
    public StreamCPerformanceEvaluation(GPUPerformanceProfiler performanceProfiler,
                                        CoherenceProfiler coherenceProfiler) {
        this.performanceProfiler = Objects.requireNonNull(performanceProfiler, "performanceProfiler must not be null");
        this.coherenceProfiler = Objects.requireNonNull(coherenceProfiler, "coherenceProfiler must not be null");
    }

    /**
     * Execute full performance evaluation workflow.
     *
     * Collects:
     * - Baseline metrics (Phase 2: DAG kernel only)
     * - Optimized metrics (Streams A+B: cache + tuning)
     * - Coherence metrics (ray sharing analysis)
     *
     * @param dag      DAG octree data structure
     * @param rayCount number of rays to trace
     * @param mockMode use synthetic measurements (true) or real GPU (false)
     * @return evaluation result with all three metric types
     * @throws IllegalArgumentException if dag is null or rayCount is invalid
     */
    public EvaluationResult evaluate(DAGOctreeData dag, int rayCount, boolean mockMode) {
        if (dag == null) {
            throw new IllegalArgumentException("dag must not be null");
        }
        if (rayCount <= 0) {
            throw new IllegalArgumentException("rayCount must be positive: " + rayCount);
        }

        log.info("Starting Stream C evaluation: {} rays (mock={})", rayCount, mockMode);

        // Phase 1: Measure baseline (Phase 2 kernel)
        log.debug("Measuring baseline performance...");
        var baseline = performanceProfiler.profileBaseline(dag, rayCount, mockMode);
        log.info("Baseline: {}", baseline);

        // Phase 2: Measure optimized (Streams A+B)
        log.debug("Measuring optimized performance...");
        var optimized = performanceProfiler.profileOptimized(dag, rayCount, mockMode);
        log.info("Optimized: {}", optimized);

        // Phase 3: Analyze ray coherence
        log.debug("Analyzing ray coherence...");
        var testRays = generateTestRays(rayCount);
        var coherence = coherenceProfiler.analyzeDetailed(testRays, dag);
        log.info("Coherence: {}", coherence);

        // Calculate improvement
        var improvement = optimized.compareToBaseline(baseline);
        log.info("Performance improvement: {:.2f}% ({:.2f}x speedup)",
                 improvement, optimized.speedupFactor(baseline));

        return new EvaluationResult(baseline, optimized, coherence);
    }

    /**
     * Generate test rays for coherence analysis.
     *
     * Creates rays in a grid pattern to ensure some coherence for analysis.
     * In production, would use actual camera rays.
     */
    private ESVORay[] generateTestRays(int rayCount) {
        var rays = new ESVORay[rayCount];

        // Generate rays in a grid for coherence
        var gridSize = (int) Math.sqrt(rayCount);
        var index = 0;

        for (int y = 0; y < gridSize && index < rayCount; y++) {
            for (int x = 0; x < gridSize && index < rayCount; x++) {
                // Origin at camera position
                var originX = 0.0f;
                var originY = 0.0f;
                var originZ = -10.0f;

                // Map grid to -1..1 range for direction
                var dx = (x / (float) gridSize) * 2.0f - 1.0f;
                var dy = (y / (float) gridSize) * 2.0f - 1.0f;

                // Create direction vector and normalize
                var length = (float) Math.sqrt(dx * dx + dy * dy + 1.0f);
                var directionX = dx / length;
                var directionY = dy / length;
                var directionZ = 1.0f / length;

                rays[index++] = new ESVORay(originX, originY, originZ, directionX, directionY, directionZ);
            }
        }

        // Fill remaining with random rays if needed
        while (index < rayCount) {
            var originX = 0.0f;
            var originY = 0.0f;
            var originZ = -10.0f;

            var dx = (float) Math.random() * 2.0f - 1.0f;
            var dy = (float) Math.random() * 2.0f - 1.0f;

            // Normalize direction
            var length = (float) Math.sqrt(dx * dx + dy * dy + 1.0f);
            var directionX = dx / length;
            var directionY = dy / length;
            var directionZ = 1.0f / length;

            rays[index++] = new ESVORay(originX, originY, originZ, directionX, directionY, directionZ);
        }

        return rays;
    }

    /**
     * Evaluation result containing all three metric types.
     *
     * @param baseline  baseline performance (Phase 2 kernel)
     * @param optimized optimized performance (Streams A+B)
     * @param coherence ray coherence metrics
     */
    public record EvaluationResult(
        PerformanceMetrics baseline,
        PerformanceMetrics optimized,
        CoherenceMetrics coherence
    ) {
        public EvaluationResult {
            Objects.requireNonNull(baseline, "baseline must not be null");
            Objects.requireNonNull(optimized, "optimized must not be null");
            Objects.requireNonNull(coherence, "coherence must not be null");
        }
    }
}
