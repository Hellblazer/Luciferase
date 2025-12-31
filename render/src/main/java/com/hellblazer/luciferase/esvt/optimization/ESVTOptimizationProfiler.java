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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance profiler for ESVT optimization operations.
 *
 * <p>Tracks timing, memory usage, and optimization effectiveness across
 * multiple optimization runs with detailed per-phase metrics.
 *
 * <p>Key metrics tracked:
 * <ul>
 *   <li>Optimization phase timing (memory, bandwidth, coalescing, traversal)</li>
 *   <li>Memory footprint before/after optimization</li>
 *   <li>Cache efficiency improvements</li>
 *   <li>GPU bandwidth utilization estimates</li>
 *   <li>Tetrahedron type distribution impact</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTOptimizationProfiler {
    private static final Logger log = LoggerFactory.getLogger(ESVTOptimizationProfiler.class);

    private final Map<String, PhaseProfile> phaseProfiles = new ConcurrentHashMap<>();
    private final AtomicLong totalProfilingTime = new AtomicLong(0);
    private final List<OptimizationRun> optimizationHistory = Collections.synchronizedList(new ArrayList<>());

    private static final int MAX_HISTORY_SIZE = 100;

    /**
     * Profile for a single optimization phase.
     */
    public static class PhaseProfile {
        private final AtomicLong totalExecutions = new AtomicLong(0);
        private final AtomicLong totalDurationNanos = new AtomicLong(0);
        private final AtomicLong minDurationNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxDurationNanos = new AtomicLong(0);
        private final AtomicLong totalImprovementPercent = new AtomicLong(0);

        public void recordExecution(long durationNanos, float improvementPercent) {
            totalExecutions.incrementAndGet();
            totalDurationNanos.addAndGet(durationNanos);
            minDurationNanos.updateAndGet(curr -> Math.min(curr, durationNanos));
            maxDurationNanos.updateAndGet(curr -> Math.max(curr, durationNanos));
            totalImprovementPercent.addAndGet((long) (improvementPercent * 100));
        }

        public long getTotalExecutions() { return totalExecutions.get(); }
        public long getTotalDurationNanos() { return totalDurationNanos.get(); }
        public long getMinDurationNanos() { return minDurationNanos.get(); }
        public long getMaxDurationNanos() { return maxDurationNanos.get(); }

        public float getAverageDurationMs() {
            var executions = totalExecutions.get();
            return executions > 0 ? totalDurationNanos.get() / (executions * 1_000_000.0f) : 0.0f;
        }

        public float getAverageImprovement() {
            var executions = totalExecutions.get();
            return executions > 0 ? totalImprovementPercent.get() / (executions * 100.0f) : 0.0f;
        }

        void reset() {
            totalExecutions.set(0);
            totalDurationNanos.set(0);
            minDurationNanos.set(Long.MAX_VALUE);
            maxDurationNanos.set(0);
            totalImprovementPercent.set(0);
        }
    }

    /**
     * Record of a single optimization run.
     */
    public static class OptimizationRun {
        private final long timestamp;
        private final int nodeCount;
        private final long totalDurationMs;
        private final Map<String, Long> phaseDurations;
        private final Map<String, Float> metrics;

        public OptimizationRun(long timestamp, int nodeCount, long totalDurationMs,
                               Map<String, Long> phaseDurations, Map<String, Float> metrics) {
            this.timestamp = timestamp;
            this.nodeCount = nodeCount;
            this.totalDurationMs = totalDurationMs;
            this.phaseDurations = new HashMap<>(phaseDurations);
            this.metrics = new HashMap<>(metrics);
        }

        public long getTimestamp() { return timestamp; }
        public int getNodeCount() { return nodeCount; }
        public long getTotalDurationMs() { return totalDurationMs; }
        public Map<String, Long> getPhaseDurations() { return Collections.unmodifiableMap(phaseDurations); }
        public Map<String, Float> getMetrics() { return Collections.unmodifiableMap(metrics); }
    }

    /**
     * Comprehensive profiling result.
     */
    public static class ProfilingResult {
        private final ESVTData inputData;
        private final ESVTData optimizedData;
        private final Map<String, Long> phaseDurations;
        private final Map<String, Float> metrics;
        private final long totalDurationMs;

        public ProfilingResult(ESVTData inputData, ESVTData optimizedData,
                               Map<String, Long> phaseDurations, Map<String, Float> metrics,
                               long totalDurationMs) {
            this.inputData = inputData;
            this.optimizedData = optimizedData;
            this.phaseDurations = new HashMap<>(phaseDurations);
            this.metrics = new HashMap<>(metrics);
            this.totalDurationMs = totalDurationMs;
        }

        public ESVTData getInputData() { return inputData; }
        public ESVTData getOptimizedData() { return optimizedData; }
        public Map<String, Long> getPhaseDurations() { return Collections.unmodifiableMap(phaseDurations); }
        public Map<String, Float> getMetrics() { return Collections.unmodifiableMap(metrics); }
        public long getTotalDurationMs() { return totalDurationMs; }
    }

    /**
     * Profile a complete optimization pipeline run.
     */
    public ProfilingResult profileOptimization(ESVTData inputData, ESVTOptimizationPipeline pipeline) {
        var startTime = System.nanoTime();
        var phaseDurations = new HashMap<String, Long>();
        var metrics = new HashMap<String, Float>();

        // Record input metrics
        metrics.put("inputNodeCount", (float) inputData.nodeCount());
        metrics.put("inputLeafCount", (float) inputData.leafCount());
        metrics.put("inputInternalCount", (float) inputData.internalCount());
        metrics.put("inputMaxDepth", (float) inputData.maxDepth());

        // Run optimization
        var result = pipeline.optimize(inputData);
        var optimizedData = result.getOptimizedData();

        // Record output metrics
        metrics.put("outputNodeCount", (float) optimizedData.nodeCount());
        metrics.put("outputLeafCount", (float) optimizedData.leafCount());

        // Extract phase durations from report
        for (var step : result.getOptimizationReport().getOptimizationSteps()) {
            phaseDurations.put(step.getOptimizerName(), (long) step.getExecutionTime());

            // Record to phase profiles
            var profile = phaseProfiles.computeIfAbsent(
                step.getOptimizerName(), k -> new PhaseProfile());
            profile.recordExecution(
                (long) (step.getExecutionTime() * 1_000_000),
                (step.getImprovementFactor() - 1.0f) * 100
            );
        }

        // Calculate improvement metrics
        metrics.put("overallImprovement", result.getOptimizationReport().getOverallImprovement());
        metrics.putAll(result.getPerformanceMetrics());

        var totalDuration = (System.nanoTime() - startTime) / 1_000_000;
        totalProfilingTime.addAndGet(totalDuration);

        // Record to history
        var run = new OptimizationRun(
            System.currentTimeMillis(),
            inputData.nodeCount(),
            totalDuration,
            phaseDurations,
            metrics
        );
        addToHistory(run);

        log.debug("Profiled optimization: {} nodes, {}ms total, {} phases",
                inputData.nodeCount(), totalDuration, phaseDurations.size());

        return new ProfilingResult(inputData, optimizedData, phaseDurations, metrics, totalDuration);
    }

    /**
     * Profile individual optimizer.
     */
    public Map<String, Object> profileOptimizer(String optimizerName,
                                                 ESVTData inputData,
                                                 java.util.function.Function<ESVTData, ESVTData> optimizer) {
        var results = new HashMap<String, Object>();
        var startTime = System.nanoTime();

        // Record input state
        var inputProfile = analyzeDataProfile(inputData);
        results.put("inputProfile", inputProfile);

        // Run optimizer
        var optimizedData = optimizer.apply(inputData);

        var duration = System.nanoTime() - startTime;
        results.put("durationNanos", duration);
        results.put("durationMs", duration / 1_000_000.0f);

        // Record output state
        var outputProfile = analyzeDataProfile(optimizedData);
        results.put("outputProfile", outputProfile);

        // Calculate improvement
        var inputEfficiency = (Float) inputProfile.getOrDefault("cacheEfficiency", 0.0f);
        var outputEfficiency = (Float) outputProfile.getOrDefault("cacheEfficiency", 0.0f);
        var improvement = inputEfficiency > 0 ? (outputEfficiency / inputEfficiency - 1.0f) * 100 : 0.0f;
        results.put("improvementPercent", improvement);

        // Update phase profile
        var profile = phaseProfiles.computeIfAbsent(optimizerName, k -> new PhaseProfile());
        profile.recordExecution(duration, improvement);

        return results;
    }

    /**
     * Get summary statistics for all phases.
     */
    public Map<String, Object> getSummaryStats() {
        var stats = new HashMap<String, Object>();

        stats.put("totalProfilingTime", totalProfilingTime.get());
        stats.put("totalRuns", optimizationHistory.size());

        // Phase summaries
        var phaseSummaries = new HashMap<String, Map<String, Object>>();
        for (var entry : phaseProfiles.entrySet()) {
            var phaseName = entry.getKey();
            var profile = entry.getValue();

            var phaseSummary = new HashMap<String, Object>();
            phaseSummary.put("totalExecutions", profile.getTotalExecutions());
            phaseSummary.put("averageDurationMs", profile.getAverageDurationMs());
            phaseSummary.put("minDurationMs", profile.getMinDurationNanos() / 1_000_000.0f);
            phaseSummary.put("maxDurationMs", profile.getMaxDurationNanos() / 1_000_000.0f);
            phaseSummary.put("averageImprovement", profile.getAverageImprovement());

            phaseSummaries.put(phaseName, phaseSummary);
        }
        stats.put("phases", phaseSummaries);

        // Recent history stats
        if (!optimizationHistory.isEmpty()) {
            var recentRuns = optimizationHistory.subList(
                Math.max(0, optimizationHistory.size() - 10),
                optimizationHistory.size()
            );

            var avgDuration = recentRuns.stream()
                .mapToLong(OptimizationRun::getTotalDurationMs)
                .average()
                .orElse(0.0);
            stats.put("recentAverageDurationMs", avgDuration);

            var avgNodeCount = recentRuns.stream()
                .mapToInt(OptimizationRun::getNodeCount)
                .average()
                .orElse(0.0);
            stats.put("recentAverageNodeCount", avgNodeCount);
        }

        return stats;
    }

    /**
     * Get optimization history.
     */
    public List<OptimizationRun> getOptimizationHistory() {
        return Collections.unmodifiableList(new ArrayList<>(optimizationHistory));
    }

    /**
     * Get phase profile for specific optimizer.
     */
    public Optional<PhaseProfile> getPhaseProfile(String optimizerName) {
        return Optional.ofNullable(phaseProfiles.get(optimizerName));
    }

    /**
     * Clear all profiling data.
     */
    public void clearProfiles() {
        phaseProfiles.values().forEach(PhaseProfile::reset);
        optimizationHistory.clear();
        totalProfilingTime.set(0);
        log.debug("Cleared all profiling data");
    }

    /**
     * Generate performance report.
     */
    public String generateReport() {
        var sb = new StringBuilder();
        sb.append("=== ESVT Optimization Profiler Report ===\n\n");

        var stats = getSummaryStats();
        sb.append(String.format("Total Profiling Time: %d ms\n", totalProfilingTime.get()));
        sb.append(String.format("Total Optimization Runs: %d\n\n", optimizationHistory.size()));

        sb.append("Phase Performance:\n");
        sb.append("-".repeat(60)).append("\n");

        for (var entry : phaseProfiles.entrySet()) {
            var phaseName = entry.getKey();
            var profile = entry.getValue();

            sb.append(String.format("  %s:\n", phaseName));
            sb.append(String.format("    Executions: %d\n", profile.getTotalExecutions()));
            sb.append(String.format("    Avg Duration: %.2f ms\n", profile.getAverageDurationMs()));
            sb.append(String.format("    Min/Max: %.2f / %.2f ms\n",
                    profile.getMinDurationNanos() / 1_000_000.0f,
                    profile.getMaxDurationNanos() / 1_000_000.0f));
            sb.append(String.format("    Avg Improvement: %.1f%%\n\n", profile.getAverageImprovement()));
        }

        // Recent runs summary
        if (!optimizationHistory.isEmpty()) {
            sb.append("\nRecent Runs:\n");
            sb.append("-".repeat(60)).append("\n");

            var recentCount = Math.min(5, optimizationHistory.size());
            for (int i = optimizationHistory.size() - recentCount; i < optimizationHistory.size(); i++) {
                var run = optimizationHistory.get(i);
                sb.append(String.format("  Run %d: %d nodes, %d ms\n",
                        i + 1, run.getNodeCount(), run.getTotalDurationMs()));
            }
        }

        return sb.toString();
    }

    // Private helper methods

    private Map<String, Object> analyzeDataProfile(ESVTData data) {
        var profile = new HashMap<String, Object>();

        profile.put("nodeCount", data.nodeCount());
        profile.put("leafCount", data.leafCount());
        profile.put("internalCount", data.internalCount());
        profile.put("maxDepth", data.maxDepth());
        profile.put("contourCount", data.contourCount());
        profile.put("farPointerCount", data.farPointerCount());

        // Estimate memory footprint
        var memoryBytes = (long) data.nodeCount() * 8 +
                          (long) data.contourCount() * 4 +
                          (long) data.farPointerCount() * 4;
        profile.put("estimatedMemoryBytes", memoryBytes);

        // Analyze tetrahedron type distribution
        var tetTypeCounts = new int[6];
        for (var node : data.nodes()) {
            if (node.isValid()) {
                var type = node.getTetType();
                if (type >= 0 && type < 6) {
                    tetTypeCounts[type]++;
                }
            }
        }
        profile.put("tetTypeDistribution", tetTypeCounts);

        // Estimate cache efficiency (sequential access assumption)
        var cacheLineSize = 64;
        var nodeSize = 8;
        var nodesPerCacheLine = cacheLineSize / nodeSize;
        var cacheEfficiency = Math.min(1.0f, (float) nodesPerCacheLine / data.nodeCount());
        profile.put("cacheEfficiency", cacheEfficiency);

        return profile;
    }

    private void addToHistory(OptimizationRun run) {
        synchronized (optimizationHistory) {
            if (optimizationHistory.size() >= MAX_HISTORY_SIZE) {
                optimizationHistory.remove(0);
            }
            optimizationHistory.add(run);
        }
    }
}
