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
package com.hellblazer.luciferase.sparse.optimization;

import com.hellblazer.luciferase.sparse.core.SparseVoxelData;
import com.hellblazer.luciferase.sparse.core.SparseVoxelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for sparse voxel optimization pipelines.
 *
 * <p>Coordinates multiple optimization strategies for comprehensive performance
 * improvement. Subclasses provide type-specific optimizer execution logic.
 *
 * <p>This pipeline supports:
 * <ul>
 *   <li>Sequential optimizer execution</li>
 *   <li>Per-optimizer statistics tracking</li>
 *   <li>Detailed optimization reporting</li>
 *   <li>Error handling with graceful fallback</li>
 * </ul>
 *
 * @param <D> the type of sparse voxel data this pipeline optimizes
 * @author hal.hildebrand
 */
public abstract class AbstractOptimizationPipeline<D extends SparseVoxelData<? extends SparseVoxelNode>> {

    private static final Logger log = LoggerFactory.getLogger(AbstractOptimizationPipeline.class);

    protected final List<Object> optimizers = new ArrayList<>();
    protected final AtomicLong totalOptimizationTimeNs = new AtomicLong(0);
    protected final Map<String, OptimizerStats> optimizerStats = new HashMap<>();
    protected volatile boolean parallelExecution = false;

    /**
     * Add an optimizer to the pipeline.
     *
     * @param optimizer the optimizer to add
     * @throws IllegalArgumentException if optimizer is null
     */
    public void addOptimizer(Object optimizer) {
        if (optimizer == null) {
            throw new IllegalArgumentException("Optimizer cannot be null");
        }
        optimizers.add(optimizer);
        optimizerStats.put(optimizer.getClass().getSimpleName(), new OptimizerStats());
        log.debug("Added optimizer: {}", optimizer.getClass().getSimpleName());
    }

    /**
     * Remove an optimizer from the pipeline.
     *
     * @param optimizer the optimizer to remove
     * @return true if the optimizer was removed
     */
    public boolean removeOptimizer(Object optimizer) {
        var removed = optimizers.remove(optimizer);
        if (removed) {
            optimizerStats.remove(optimizer.getClass().getSimpleName());
        }
        return removed;
    }

    /**
     * Set whether optimizers should run in parallel.
     *
     * @param parallel true to enable parallel execution
     */
    public void setParallelExecution(boolean parallel) {
        this.parallelExecution = parallel;
    }

    /**
     * Run the complete optimization pipeline.
     *
     * @param inputData the data to optimize
     * @return optimization result containing optimized data and report
     */
    public OptimizationResult<D> optimize(D inputData) {
        var startTime = System.nanoTime();

        var steps = new ArrayList<OptimizationStep>();
        var currentData = inputData;
        var cumulativeImprovement = 1.0f;
        var performanceMetrics = new HashMap<String, Float>();

        log.info("Starting {} optimization pipeline with {} optimizers, {} nodes",
            getDataTypeName(), optimizers.size(), inputData.nodeCount());

        // Execute optimizers in sequence
        for (var optimizer : optimizers) {
            var stepResult = executeOptimizerStep(optimizer, currentData);

            if (stepResult.data() != null) {
                currentData = stepResult.data();
                cumulativeImprovement *= stepResult.improvementFactor();
                steps.add(stepResult.step());

                // Merge performance metrics
                stepResult.metrics().forEach((key, value) -> {
                    performanceMetrics.merge(key, value, Float::sum);
                });

                // Update optimizer stats
                var stats = optimizerStats.get(optimizer.getClass().getSimpleName());
                if (stats != null) {
                    stats.recordExecution(
                        (long) (stepResult.step().executionTimeMs() * 1_000_000),
                        stepResult.step().improvementFactor()
                    );
                }
            }
        }

        var totalTimeNs = System.nanoTime() - startTime;
        var totalTimeMs = totalTimeNs / 1_000_000.0f;
        totalOptimizationTimeNs.addAndGet(totalTimeNs);

        // Create optimization report
        var summary = createOptimizationSummary(steps, cumulativeImprovement);
        var report = new OptimizationReport(steps, totalTimeMs, cumulativeImprovement, summary);

        log.info("{} optimization complete: {}ms, {}x improvement",
            getDataTypeName(), String.format("%.1f", totalTimeMs), String.format("%.2f", cumulativeImprovement));

        return new OptimizationResult<>(currentData, report, performanceMetrics);
    }

    /**
     * Get pipeline statistics.
     *
     * @return map of pipeline statistics
     */
    public Map<String, Object> getPipelineStats() {
        var stats = new HashMap<String, Object>();

        stats.put("totalOptimizers", optimizers.size());
        stats.put("totalOptimizationTimeMs", totalOptimizationTimeNs.get() / 1_000_000.0f);
        stats.put("parallelExecution", parallelExecution);
        stats.put("dataType", getDataTypeName());

        var optimizerStatsSummary = new HashMap<String, Map<String, Object>>();
        for (var entry : optimizerStats.entrySet()) {
            var optimizerName = entry.getKey();
            var optimizerStat = entry.getValue();

            var optimizerSummary = new HashMap<String, Object>();
            optimizerSummary.put("totalExecutions", optimizerStat.getTotalExecutions());
            optimizerSummary.put("averageExecutionTimeMs", optimizerStat.getAverageExecutionTimeMs());
            optimizerSummary.put("averageImprovement", optimizerStat.getAverageImprovement());

            optimizerStatsSummary.put(optimizerName, optimizerSummary);
        }
        stats.put("optimizerStats", optimizerStatsSummary);

        return stats;
    }

    /**
     * Clear all pipeline statistics.
     */
    public void clearStats() {
        totalOptimizationTimeNs.set(0);
        optimizerStats.values().forEach(OptimizerStats::reset);
    }

    /**
     * Get list of registered optimizer names.
     *
     * @return list of optimizer class names
     */
    public List<String> getRegisteredOptimizers() {
        return optimizers.stream()
            .map(optimizer -> optimizer.getClass().getSimpleName())
            .toList();
    }

    /**
     * Get the data type name for logging.
     *
     * @return data type name (e.g., "ESVO" or "ESVT")
     */
    protected abstract String getDataTypeName();

    /**
     * Execute a single optimizer step.
     *
     * <p>Subclasses implement this to provide type-specific optimizer execution.
     *
     * @param optimizer the optimizer to execute
     * @param inputData the input data
     * @return step result containing optimized data and metrics
     */
    protected abstract StepResult<D> executeOptimizerStep(Object optimizer, D inputData);

    /**
     * Result of executing a single optimizer step.
     */
    protected record StepResult<D>(
        D data,
        OptimizationStep step,
        float improvementFactor,
        Map<String, Float> metrics
    ) {
        public StepResult(D data, OptimizationStep step, float improvementFactor, Map<String, Float> metrics) {
            this.data = data;
            this.step = step;
            this.improvementFactor = improvementFactor;
            this.metrics = metrics != null ? new HashMap<>(metrics) : Map.of();
        }
    }

    /**
     * Create an optimization summary from the steps.
     *
     * @param steps the optimization steps
     * @param cumulativeImprovement the cumulative improvement factor
     * @return summary map
     */
    protected Map<String, Object> createOptimizationSummary(List<OptimizationStep> steps,
                                                            float cumulativeImprovement) {
        var summary = new HashMap<String, Object>();

        summary.put("totalSteps", steps.size());
        summary.put("cumulativeImprovement", cumulativeImprovement);
        summary.put("totalExecutionTimeMs", steps.stream()
            .map(OptimizationStep::executionTimeMs)
            .reduce(0.0f, Float::sum));

        var stepImprovements = steps.stream()
            .map(OptimizationStep::improvementFactor)
            .toList();
        summary.put("stepImprovements", stepImprovements);

        var mostEffective = steps.stream()
            .max(Comparator.comparing(OptimizationStep::improvementFactor))
            .map(OptimizationStep::optimizerName)
            .orElse("none");
        summary.put("mostEffectiveOptimizer", mostEffective);

        var avgImprovement = steps.stream()
            .map(OptimizationStep::improvementFactor)
            .reduce(0.0f, Float::sum) / Math.max(1, steps.size());
        summary.put("averageImprovementPerStep", avgImprovement);

        return summary;
    }

    /**
     * Create a step result for a failed optimizer.
     *
     * @param optimizer the optimizer that failed
     * @param inputData the input data (returned unchanged)
     * @param error the error message
     * @param executionTimeMs execution time in milliseconds
     * @return step result with error information
     */
    protected StepResult<D> createErrorResult(Object optimizer, D inputData,
                                               String error, float executionTimeMs) {
        var stepDetails = Map.<String, Object>of(
            "error", error,
            "optimizerType", optimizer.getClass().getSimpleName()
        );
        var step = new OptimizationStep(
            optimizer.getClass().getSimpleName(),
            executionTimeMs,
            1.0f,
            stepDetails
        );
        return new StepResult<>(inputData, step, 1.0f, Map.of("error", 1.0f));
    }
}
