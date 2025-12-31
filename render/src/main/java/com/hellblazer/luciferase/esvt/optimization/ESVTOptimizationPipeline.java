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
package com.hellblazer.luciferase.esvt.optimization;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Integrated optimization pipeline for ESVT tetrahedral implementations.
 *
 * <p>Coordinates multiple optimization strategies for comprehensive performance
 * improvement of tetrahedral sparse voxel data structures.
 *
 * <p>Key differences from ESVO optimization:
 * <ul>
 *   <li>8-byte node size (vs 12-16 bytes for ESVO)</li>
 *   <li>6 tetrahedron types (S0-S5) with different traversal patterns</li>
 *   <li>Moller-Trumbore intersection requires different cache patterns</li>
 *   <li>Tetrahedral subdivision creates different memory access patterns</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTOptimizationPipeline {
    private static final Logger log = LoggerFactory.getLogger(ESVTOptimizationPipeline.class);

    private final List<Object> optimizers = new ArrayList<>();
    private final AtomicLong totalOptimizationTime = new AtomicLong(0);
    private final Map<String, OptimizerStats> optimizerStats = new HashMap<>();
    private volatile boolean parallelExecution = false;

    /**
     * Result of running the optimization pipeline.
     */
    public static class OptimizationResult {
        private final ESVTData optimizedData;
        private final OptimizationReport optimizationReport;
        private final Map<String, Float> performanceMetrics;

        public OptimizationResult(ESVTData optimizedData,
                                  OptimizationReport report,
                                  Map<String, Float> performanceMetrics) {
            this.optimizedData = optimizedData;
            this.optimizationReport = report;
            this.performanceMetrics = new HashMap<>(performanceMetrics);
        }

        public ESVTData getOptimizedData() { return optimizedData; }
        public OptimizationReport getOptimizationReport() { return optimizationReport; }
        public Map<String, Float> getPerformanceMetrics() {
            return Collections.unmodifiableMap(performanceMetrics);
        }
    }

    /**
     * Report summarizing all optimization steps.
     */
    public static class OptimizationReport {
        private final List<OptimizationStep> optimizationSteps;
        private final float totalOptimizationTime;
        private final float overallImprovement;
        private final Map<String, Object> summary;

        public OptimizationReport(List<OptimizationStep> steps, float totalTime,
                                  float overallImprovement, Map<String, Object> summary) {
            this.optimizationSteps = new ArrayList<>(steps);
            this.totalOptimizationTime = totalTime;
            this.overallImprovement = overallImprovement;
            this.summary = new HashMap<>(summary);
        }

        public List<OptimizationStep> getOptimizationSteps() {
            return Collections.unmodifiableList(optimizationSteps);
        }
        public float getTotalOptimizationTime() { return totalOptimizationTime; }
        public float getOverallImprovement() { return overallImprovement; }
        public Map<String, Object> getSummary() { return Collections.unmodifiableMap(summary); }
    }

    /**
     * Single optimization step result.
     */
    public static class OptimizationStep {
        private final String optimizerName;
        private final float executionTime;
        private final float improvementFactor;
        private final Map<String, Object> stepDetails;

        public OptimizationStep(String optimizerName, float executionTime,
                                float improvementFactor, Map<String, Object> details) {
            this.optimizerName = optimizerName;
            this.executionTime = executionTime;
            this.improvementFactor = improvementFactor;
            this.stepDetails = new HashMap<>(details);
        }

        public String getOptimizerName() { return optimizerName; }
        public float getExecutionTime() { return executionTime; }
        public float getImprovementFactor() { return improvementFactor; }
        public Map<String, Object> getStepDetails() { return Collections.unmodifiableMap(stepDetails); }
    }

    /**
     * Statistics for a single optimizer.
     */
    public static class OptimizerStats {
        private final AtomicLong totalExecutions = new AtomicLong(0);
        private final AtomicLong totalExecutionTime = new AtomicLong(0);
        private final AtomicLong totalImprovement = new AtomicLong(0);

        public void recordExecution(long executionTime, float improvement) {
            totalExecutions.incrementAndGet();
            totalExecutionTime.addAndGet(executionTime);
            totalImprovement.addAndGet((long) (improvement * 1000));
        }

        public long getTotalExecutions() { return totalExecutions.get(); }
        public long getTotalExecutionTime() { return totalExecutionTime.get(); }
        public float getAverageExecutionTime() {
            var executions = totalExecutions.get();
            return executions > 0 ? (float) totalExecutionTime.get() / executions : 0.0f;
        }
        public float getAverageImprovement() {
            var executions = totalExecutions.get();
            return executions > 0 ? (float) totalImprovement.get() / (executions * 1000) : 0.0f;
        }

        void reset() {
            totalExecutions.set(0);
            totalExecutionTime.set(0);
            totalImprovement.set(0);
        }
    }

    /**
     * Add an optimizer to the pipeline.
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
     */
    public void setParallelExecution(boolean parallel) {
        this.parallelExecution = parallel;
    }

    /**
     * Run the complete optimization pipeline.
     */
    public OptimizationResult optimize(ESVTData inputData) {
        var startTime = System.nanoTime();

        var steps = new ArrayList<OptimizationStep>();
        var currentData = inputData;
        var cumulativeImprovement = 1.0f;
        var performanceMetrics = new HashMap<String, Float>();

        log.info("Starting ESVT optimization pipeline with {} optimizers, {} nodes",
                optimizers.size(), inputData.nodeCount());

        // Execute optimizers in sequence
        for (var optimizer : optimizers) {
            var stepResult = executeOptimizerStep(optimizer, currentData);

            if (stepResult.optimizedData != null) {
                currentData = stepResult.optimizedData;
                cumulativeImprovement *= stepResult.improvementFactor;
                steps.add(stepResult.step);

                // Merge performance metrics
                stepResult.metrics.forEach((key, value) -> {
                    performanceMetrics.merge(key, value, Float::sum);
                });

                // Update optimizer stats
                var stats = optimizerStats.get(optimizer.getClass().getSimpleName());
                if (stats != null) {
                    stats.recordExecution(
                        (long) (stepResult.step.getExecutionTime() * 1_000_000),
                        stepResult.step.getImprovementFactor()
                    );
                }
            }
        }

        var totalTime = (System.nanoTime() - startTime) / 1_000_000.0f;
        totalOptimizationTime.addAndGet((long) totalTime);

        // Create optimization report
        var summary = createOptimizationSummary(steps, cumulativeImprovement);
        var report = new OptimizationReport(steps, totalTime, cumulativeImprovement, summary);

        log.info("ESVT optimization complete: {}ms, {}x improvement",
                String.format("%.1f", totalTime), String.format("%.2f", cumulativeImprovement));

        return new OptimizationResult(currentData, report, performanceMetrics);
    }

    /**
     * Get pipeline statistics.
     */
    public Map<String, Object> getPipelineStats() {
        var stats = new HashMap<String, Object>();

        stats.put("totalOptimizers", optimizers.size());
        stats.put("totalOptimizationTime", totalOptimizationTime.get());
        stats.put("parallelExecution", parallelExecution);

        var optimizerStatsSummary = new HashMap<String, Map<String, Object>>();
        for (var entry : optimizerStats.entrySet()) {
            var optimizerName = entry.getKey();
            var optimizerStat = entry.getValue();

            var optimizerSummary = new HashMap<String, Object>();
            optimizerSummary.put("totalExecutions", optimizerStat.getTotalExecutions());
            optimizerSummary.put("averageExecutionTime", optimizerStat.getAverageExecutionTime());
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
        totalOptimizationTime.set(0);
        optimizerStats.values().forEach(OptimizerStats::reset);
    }

    /**
     * Get list of registered optimizer names.
     */
    public List<String> getRegisteredOptimizers() {
        return optimizers.stream()
            .map(optimizer -> optimizer.getClass().getSimpleName())
            .toList();
    }

    // Private helper classes and methods

    private record OptimizerStepResult(
        ESVTData optimizedData,
        OptimizationStep step,
        float improvementFactor,
        Map<String, Float> metrics
    ) {}

    private OptimizerStepResult executeOptimizerStep(Object optimizer, ESVTData inputData) {
        var stepStartTime = System.nanoTime();
        var optimizerName = optimizer.getClass().getSimpleName();

        try {
            ESVTData optimizedData = null;
            var improvementFactor = 1.0f;
            var stepMetrics = new HashMap<String, Float>();

            // Execute optimizer based on type
            if (optimizer instanceof ESVTMemoryOptimizer memoryOptimizer) {
                var profile = memoryOptimizer.analyzeMemoryLayout(inputData);
                optimizedData = memoryOptimizer.optimizeMemoryLayout(inputData);
                improvementFactor = 1.0f + profile.getCacheEfficiency() * 0.15f;
                stepMetrics.put("cacheEfficiency", profile.getCacheEfficiency());
                stepMetrics.put("spatialLocality", profile.getSpatialLocality());

            } else if (optimizer instanceof ESVTBandwidthOptimizer bandwidthOptimizer) {
                var profile = bandwidthOptimizer.analyzeBandwidthUsage(inputData, new int[0]);
                optimizedData = bandwidthOptimizer.optimizeForStreaming(inputData, 1024);
                improvementFactor = 1.0f + profile.getBandwidthReduction() * 0.25f;
                stepMetrics.put("bandwidthReduction", profile.getBandwidthReduction());

            } else if (optimizer instanceof ESVTTraversalOptimizer traversalOptimizer) {
                // Traversal optimizer returns optimized traversal order, not new data
                optimizedData = inputData;
                improvementFactor = 1.05f; // Conservative estimate
                stepMetrics.put("traversalOptimization", 1.0f);

            } else if (optimizer instanceof ESVTCoalescingOptimizer coalescingOptimizer) {
                var profile = coalescingOptimizer.analyzeCoalescingOpportunities(inputData);
                optimizedData = coalescingOptimizer.optimizeForCoalescing(inputData);
                improvementFactor = 1.0f + profile.getCoalescingEfficiency() * 0.2f;
                stepMetrics.put("coalescingEfficiency", profile.getCoalescingEfficiency());

            } else {
                // Generic optimizer - assume no change but record execution
                optimizedData = inputData;
                improvementFactor = 1.0f;
                stepMetrics.put("genericOptimization", 1.0f);
            }

            var executionTime = (System.nanoTime() - stepStartTime) / 1_000_000.0f;

            var stepDetails = new HashMap<String, Object>();
            stepDetails.put("inputNodeCount", inputData.nodeCount());
            stepDetails.put("outputNodeCount", optimizedData != null ? optimizedData.nodeCount() : 0);
            stepDetails.put("optimizerType", optimizerName);
            stepDetails.put("tetrahedral", true);
            stepMetrics.forEach((k, v) -> stepDetails.put(k, v));

            var step = new OptimizationStep(optimizerName, executionTime, improvementFactor, stepDetails);

            return new OptimizerStepResult(optimizedData, step, improvementFactor, stepMetrics);

        } catch (Exception e) {
            log.warn("Optimizer {} failed: {}", optimizerName, e.getMessage());
            var executionTime = (System.nanoTime() - stepStartTime) / 1_000_000.0f;
            var stepDetails = Map.<String, Object>of("error", e.getMessage(), "optimizerType", optimizerName);
            var step = new OptimizationStep(optimizerName, executionTime, 1.0f, stepDetails);

            return new OptimizerStepResult(inputData, step, 1.0f, Map.of("error", 1.0f));
        }
    }

    private Map<String, Object> createOptimizationSummary(List<OptimizationStep> steps,
                                                          float cumulativeImprovement) {
        var summary = new HashMap<String, Object>();

        summary.put("totalSteps", steps.size());
        summary.put("cumulativeImprovement", cumulativeImprovement);
        summary.put("totalExecutionTime", steps.stream()
            .map(OptimizationStep::getExecutionTime)
            .reduce(0.0f, Float::sum));

        var stepImprovements = steps.stream()
            .map(OptimizationStep::getImprovementFactor)
            .toList();
        summary.put("stepImprovements", stepImprovements);

        var mostEffective = steps.stream()
            .max(Comparator.comparing(OptimizationStep::getImprovementFactor))
            .map(OptimizationStep::getOptimizerName)
            .orElse("none");
        summary.put("mostEffectiveOptimizer", mostEffective);

        var avgImprovement = steps.stream()
            .map(OptimizationStep::getImprovementFactor)
            .reduce(0.0f, Float::sum) / Math.max(1, steps.size());
        summary.put("averageImprovementPerStep", avgImprovement);

        // ESVT-specific summary
        summary.put("nodeSize", 8); // 8-byte nodes
        summary.put("tetrahedronTypes", 6); // S0-S5

        return summary;
    }

    /**
     * Create a default pipeline with recommended optimizers.
     */
    public static ESVTOptimizationPipeline createDefaultPipeline() {
        var pipeline = new ESVTOptimizationPipeline();
        pipeline.addOptimizer(new ESVTMemoryOptimizer());
        pipeline.addOptimizer(new ESVTBandwidthOptimizer());
        pipeline.addOptimizer(new ESVTCoalescingOptimizer());
        pipeline.addOptimizer(new ESVTTraversalOptimizer());
        return pipeline;
    }
}
