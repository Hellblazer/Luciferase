package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes performance profiles to identify bottlenecks and optimization opportunities.
 *
 * <p>Provides:
 * <ul>
 *   <li>Bottleneck identification (slowest recovery phases)</li>
 *   <li>Overhead analysis (listener, VON, ghost layer costs)</li>
 *   <li>Resource pressure detection (memory, thread contention)</li>
 *   <li>ROI estimation for optimization targets</li>
 *   <li>Actionable recommendations</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class OptimizationAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OptimizationAnalyzer.class);

    // Thresholds for identifying problems
    private static final double SLOW_PHASE_THRESHOLD_MS = 1000.0;  // Phases >1s are slow
    private static final double HIGH_OVERHEAD_THRESHOLD_US = 100.0; // Component calls >100μs are expensive
    private static final double MEMORY_PRESSURE_THRESHOLD_MB = 50.0; // >50MB delta is pressure
    private static final int HIGH_THREAD_COUNT_THRESHOLD = 50;      // >50 threads is high

    /**
     * Analyze a performance report and identify optimization opportunities.
     *
     * @param report the performance report to analyze
     * @return analysis result with recommendations
     */
    public AnalysisResult analyze(PerformanceProfiler.PerformanceReport report) {
        log.info("Analyzing performance report for optimization opportunities");

        var result = new AnalysisResult();

        // Identify bottlenecks
        identifyPhaseBottlenecks(report, result);
        identifyComponentOverhead(report, result);
        identifyResourcePressure(report, result);

        // Estimate ROI for optimizations
        estimateOptimizationROI(result);

        // Generate recommendations
        generateRecommendations(result);

        log.info("Analysis complete: {} bottlenecks identified, {} recommendations generated",
            result.bottlenecks.size(), result.recommendations.size());

        return result;
    }

    /**
     * Identify slowest recovery phases.
     */
    private void identifyPhaseBottlenecks(
        PerformanceProfiler.PerformanceReport report,
        AnalysisResult result
    ) {
        for (var entry : report.phaseLatencies.entrySet()) {
            var phase = entry.getKey();
            var stats = entry.getValue();

            if (stats.count() == 0) continue;

            // Check if phase is slow
            if (stats.avgMs() > SLOW_PHASE_THRESHOLD_MS) {
                result.bottlenecks.add(new Bottleneck(
                    BottleneckType.SLOW_PHASE,
                    phase.name(),
                    stats.avgMs(),
                    String.format("Average latency %.2fms exceeds threshold %.0fms",
                        stats.avgMs(), SLOW_PHASE_THRESHOLD_MS)
                ));
            }

            // Check p99 latency
            if (stats.p99Ms() > SLOW_PHASE_THRESHOLD_MS * 2) {
                result.bottlenecks.add(new Bottleneck(
                    BottleneckType.HIGH_TAIL_LATENCY,
                    phase.name() + " (p99)",
                    stats.p99Ms(),
                    String.format("P99 latency %.2fms indicates high variance",
                        stats.p99Ms())
                ));
            }

            // Check memory usage
            if (Math.abs(stats.avgMemoryMB()) > MEMORY_PRESSURE_THRESHOLD_MB) {
                result.bottlenecks.add(new Bottleneck(
                    BottleneckType.MEMORY_PRESSURE,
                    phase.name(),
                    Math.abs(stats.avgMemoryMB()),
                    String.format("Memory delta %.2fMB exceeds threshold %.0fMB",
                        Math.abs(stats.avgMemoryMB()), MEMORY_PRESSURE_THRESHOLD_MB)
                ));
            }
        }
    }

    /**
     * Identify expensive component operations.
     */
    private void identifyComponentOverhead(
        PerformanceProfiler.PerformanceReport report,
        AnalysisResult result
    ) {
        // Check listener notification overhead
        var listenerStats = report.metrics.get(PerformanceProfiler.MetricType.LISTENER_NOTIFICATION);
        if (listenerStats != null && listenerStats.count() > 0) {
            var avgMicros = listenerStats.avg() / 1000.0; // Convert nanos to micros
            if (avgMicros > HIGH_OVERHEAD_THRESHOLD_US) {
                result.bottlenecks.add(new Bottleneck(
                    BottleneckType.EXPENSIVE_LISTENER,
                    "Listener Notification",
                    avgMicros,
                    String.format("Average listener overhead %.2fμs exceeds threshold %.0fμs",
                        avgMicros, HIGH_OVERHEAD_THRESHOLD_US)
                ));
            }
        }

        // Check VON topology update overhead
        var vonStats = report.metrics.get(PerformanceProfiler.MetricType.VON_TOPOLOGY_UPDATE);
        if (vonStats != null && vonStats.count() > 0) {
            var avgMs = vonStats.avg() / 1_000_000.0; // Convert nanos to ms
            if (avgMs > 50.0) { // VON updates >50ms are slow
                result.bottlenecks.add(new Bottleneck(
                    BottleneckType.EXPENSIVE_VON_UPDATE,
                    "VON Topology Update",
                    avgMs,
                    String.format("Average VON update %.2fms exceeds threshold 50ms", avgMs)
                ));
            }
        }

        // Check ghost layer validation overhead
        var ghostStats = report.metrics.get(PerformanceProfiler.MetricType.GHOST_LAYER_VALIDATION);
        if (ghostStats != null && ghostStats.count() > 0) {
            var avgMs = ghostStats.avg() / 1_000_000.0; // Convert nanos to ms
            if (avgMs > 30.0) { // Ghost validation >30ms is slow
                result.bottlenecks.add(new Bottleneck(
                    BottleneckType.EXPENSIVE_GHOST_VALIDATION,
                    "Ghost Layer Validation",
                    avgMs,
                    String.format("Average ghost validation %.2fms exceeds threshold 30ms", avgMs)
                ));
            }
        }
    }

    /**
     * Identify resource pressure points.
     */
    private void identifyResourcePressure(
        PerformanceProfiler.PerformanceReport report,
        AnalysisResult result
    ) {
        // Check memory pressure
        var memoryDelta = report.currentMemoryMB - report.baselineMemoryMB;
        if (memoryDelta > 100.0) { // >100MB increase is concerning
            result.bottlenecks.add(new Bottleneck(
                BottleneckType.MEMORY_PRESSURE,
                "Overall Memory Usage",
                memoryDelta,
                String.format("Memory increased by %.2fMB from baseline", memoryDelta)
            ));
        }

        // Check thread count
        if (report.activeThreads > HIGH_THREAD_COUNT_THRESHOLD) {
            result.bottlenecks.add(new Bottleneck(
                BottleneckType.THREAD_CONTENTION,
                "Thread Count",
                report.activeThreads,
                String.format("%d active threads exceeds threshold %d",
                    report.activeThreads, HIGH_THREAD_COUNT_THRESHOLD)
            ));
        }
    }

    /**
     * Estimate ROI for optimizing identified bottlenecks.
     */
    private void estimateOptimizationROI(AnalysisResult result) {
        // Sort bottlenecks by impact (descending)
        result.bottlenecks.sort(Comparator.comparingDouble((Bottleneck b) -> b.impact).reversed());

        // Assign ROI estimates
        for (var bottleneck : result.bottlenecks) {
            var roi = estimateROI(bottleneck);
            bottleneck.estimatedROI = roi;
        }
    }

    /**
     * Estimate ROI for a specific bottleneck.
     */
    private OptimizationROI estimateROI(Bottleneck bottleneck) {
        return switch (bottleneck.type) {
            case SLOW_PHASE -> {
                // Slow phases have high impact if they're on critical path
                var potentialSavingsMs = bottleneck.impact * 0.5; // Assume 50% improvement possible
                var effort = EstimatedEffort.MEDIUM;
                var priority = potentialSavingsMs > 500 ? Priority.HIGH : Priority.MEDIUM;
                yield new OptimizationROI(priority, effort, potentialSavingsMs,
                    "Optimize critical path to reduce recovery time");
            }

            case HIGH_TAIL_LATENCY -> {
                // Tail latency indicates variance - medium impact
                var potentialSavingsMs = bottleneck.impact * 0.3; // Assume 30% tail reduction
                var effort = EstimatedEffort.HIGH; // Tail latency is hard to fix
                var priority = Priority.MEDIUM;
                yield new OptimizationROI(priority, effort, potentialSavingsMs,
                    "Investigate and eliminate outliers");
            }

            case EXPENSIVE_LISTENER -> {
                // Listener overhead is usually easy to optimize
                var potentialSavingsUs = bottleneck.impact * 0.7; // Assume 70% reduction possible
                var effort = EstimatedEffort.LOW;
                var priority = Priority.HIGH; // Easy win
                yield new OptimizationROI(priority, effort, potentialSavingsUs,
                    "Batch listener notifications or defer non-critical callbacks");
            }

            case EXPENSIVE_VON_UPDATE -> {
                // VON updates can be optimized
                var potentialSavingsMs = bottleneck.impact * 0.5;
                var effort = EstimatedEffort.MEDIUM;
                var priority = Priority.MEDIUM;
                yield new OptimizationROI(priority, effort, potentialSavingsMs,
                    "Cache VON neighbor topology or batch updates");
            }

            case EXPENSIVE_GHOST_VALIDATION -> {
                // Ghost validation can be optimized
                var potentialSavingsMs = bottleneck.impact * 0.6;
                var effort = EstimatedEffort.MEDIUM;
                var priority = Priority.HIGH;
                yield new OptimizationROI(priority, effort, potentialSavingsMs,
                    "Incremental validation or validation caching");
            }

            case MEMORY_PRESSURE -> {
                // Memory pressure needs investigation
                var potentialSavingsMB = bottleneck.impact * 0.4;
                var effort = EstimatedEffort.HIGH;
                var priority = Priority.MEDIUM;
                yield new OptimizationROI(priority, effort, potentialSavingsMB,
                    "Profile allocations and reduce object creation");
            }

            case THREAD_CONTENTION -> {
                // Thread contention is complex
                var effort = EstimatedEffort.HIGH;
                var priority = Priority.LOW;
                yield new OptimizationROI(priority, effort, 0,
                    "Reduce thread usage or improve thread pool configuration");
            }
        };
    }

    /**
     * Generate actionable recommendations.
     */
    private void generateRecommendations(AnalysisResult result) {
        // Group bottlenecks by priority
        var highPriority = result.bottlenecks.stream()
            .filter(b -> b.estimatedROI.priority == Priority.HIGH)
            .collect(Collectors.toList());

        var mediumPriority = result.bottlenecks.stream()
            .filter(b -> b.estimatedROI.priority == Priority.MEDIUM)
            .collect(Collectors.toList());

        // Generate recommendations
        if (!highPriority.isEmpty()) {
            result.recommendations.add(new Recommendation(
                Priority.HIGH,
                "Address High-Priority Bottlenecks",
                String.format("Found %d high-priority optimization opportunities with >10%% potential improvement",
                    highPriority.size()),
                highPriority.stream()
                    .map(b -> "  - " + b.component + ": " + b.estimatedROI.description)
                    .collect(Collectors.toList())
            ));
        }

        if (!mediumPriority.isEmpty()) {
            result.recommendations.add(new Recommendation(
                Priority.MEDIUM,
                "Consider Medium-Priority Optimizations",
                String.format("Found %d medium-priority opportunities", mediumPriority.size()),
                mediumPriority.stream()
                    .limit(3) // Top 3
                    .map(b -> "  - " + b.component + ": " + b.estimatedROI.description)
                    .collect(Collectors.toList())
            ));
        }

        // Add general recommendations
        if (result.bottlenecks.isEmpty()) {
            result.recommendations.add(new Recommendation(
                Priority.LOW,
                "Performance Acceptable",
                "No significant bottlenecks identified. System is performing within acceptable thresholds.",
                List.of()
            ));
        }
    }

    // === Result Types ===

    public enum BottleneckType {
        SLOW_PHASE,
        HIGH_TAIL_LATENCY,
        EXPENSIVE_LISTENER,
        EXPENSIVE_VON_UPDATE,
        EXPENSIVE_GHOST_VALIDATION,
        MEMORY_PRESSURE,
        THREAD_CONTENTION
    }

    public enum Priority {
        HIGH, MEDIUM, LOW
    }

    public enum EstimatedEffort {
        LOW, MEDIUM, HIGH
    }

    public static class Bottleneck {
        public final BottleneckType type;
        public final String component;
        public final double impact; // Latency (ms/μs) or resource units
        public final String description;
        public OptimizationROI estimatedROI;

        public Bottleneck(BottleneckType type, String component, double impact, String description) {
            this.type = type;
            this.component = component;
            this.impact = impact;
            this.description = description;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: impact=%.2f - %s (ROI: %s)",
                type, component, impact, description,
                estimatedROI != null ? estimatedROI.priority : "N/A");
        }
    }

    public record OptimizationROI(
        Priority priority,
        EstimatedEffort effort,
        double potentialSavings,
        String description
    ) {}

    public record Recommendation(
        Priority priority,
        String title,
        String summary,
        List<String> details
    ) {
        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append(String.format("[%s] %s\n", priority, title));
            sb.append(String.format("  %s\n", summary));
            if (!details.isEmpty()) {
                sb.append("  Actions:\n");
                details.forEach(detail -> sb.append(detail).append("\n"));
            }
            return sb.toString();
        }
    }

    /**
     * Complete analysis result.
     */
    public static class AnalysisResult {
        public final List<Bottleneck> bottlenecks = new ArrayList<>();
        public final List<Recommendation> recommendations = new ArrayList<>();

        public boolean hasHighPriorityIssues() {
            return bottlenecks.stream().anyMatch(b ->
                b.estimatedROI != null && b.estimatedROI.priority == Priority.HIGH
            );
        }

        public boolean isPerformanceAcceptable() {
            return bottlenecks.isEmpty();
        }

        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("\n=== Optimization Analysis ===\n");
            sb.append(String.format("Bottlenecks Identified: %d\n", bottlenecks.size()));
            sb.append(String.format("High Priority: %d\n",
                bottlenecks.stream().filter(b -> b.estimatedROI != null &&
                    b.estimatedROI.priority == Priority.HIGH).count()));

            if (!bottlenecks.isEmpty()) {
                sb.append("\n--- Bottlenecks ---\n");
                bottlenecks.forEach(b -> sb.append("  ").append(b).append("\n"));
            }

            if (!recommendations.isEmpty()) {
                sb.append("\n--- Recommendations ---\n");
                recommendations.forEach(r -> sb.append(r).append("\n"));
            }

            return sb.toString();
        }
    }
}
