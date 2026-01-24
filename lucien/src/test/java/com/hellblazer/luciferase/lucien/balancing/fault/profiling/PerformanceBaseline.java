package com.hellblazer.luciferase.lucien.balancing.fault.profiling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Performance baseline metrics for regression tracking.
 *
 * <p>Establishes reference performance metrics that can be compared
 * against future runs to detect regressions or improvements.
 *
 * <p>Baseline metrics include:
 * <ul>
 *   <li>Single-partition recovery: target <5s</li>
 *   <li>Multi-partition concurrent: target <10s</li>
 *   <li>VON topology update: target <100ms</li>
 *   <li>Listener notification: target <10μs p99</li>
 *   <li>Ghost layer validation: target <50ms</li>
 *   <li>Memory usage: target <100MB per partition</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class PerformanceBaseline {

    private static final Logger log = LoggerFactory.getLogger(PerformanceBaseline.class);
    private static final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Baseline metric definitions.
     */
    public enum BaselineMetric {
        SINGLE_PARTITION_RECOVERY_MS("Single-partition recovery", 5000.0),
        MULTI_PARTITION_CONCURRENT_MS("Multi-partition concurrent recovery", 10000.0),
        VON_TOPOLOGY_UPDATE_MS("VON topology update", 100.0),
        LISTENER_NOTIFICATION_P99_US("Listener notification p99", 10.0),
        GHOST_LAYER_VALIDATION_MS("Ghost layer validation", 50.0),
        MEMORY_PER_PARTITION_MB("Memory per partition", 100.0),
        MESSAGE_THROUGHPUT_PER_SEC("Message throughput", 1000.0),
        FAULT_DETECTION_LATENCY_MS("Fault detection latency", 100.0);

        public final String description;
        public final double targetValue;

        BaselineMetric(String description, double targetValue) {
            this.description = description;
            this.targetValue = targetValue;
        }
    }

    private final Map<BaselineMetric, Double> actualValues = new HashMap<>();
    private String testEnvironment;
    private Instant timestamp;
    private String gitCommit;

    /**
     * Create an empty baseline.
     */
    public PerformanceBaseline() {
        this.timestamp = Instant.now();
    }

    /**
     * Create a baseline from a performance report.
     *
     * @param report the performance report
     * @param testEnvironment description of test environment
     */
    public static PerformanceBaseline fromReport(
        PerformanceProfiler.PerformanceReport report,
        String testEnvironment
    ) {
        var baseline = new PerformanceBaseline();
        baseline.testEnvironment = testEnvironment;
        baseline.timestamp = Instant.now();

        // Extract metrics from report
        var completePhase = report.phaseLatencies.get(PerformanceProfiler.RecoveryPhase.COMPLETE);
        if (completePhase != null && completePhase.count() > 0) {
            // Assume this is single-partition if only one partition tracked
            if (report.partitionMetrics.size() == 1) {
                baseline.record(BaselineMetric.SINGLE_PARTITION_RECOVERY_MS, completePhase.avgMs());
            } else if (report.partitionMetrics.size() > 1) {
                baseline.record(BaselineMetric.MULTI_PARTITION_CONCURRENT_MS, completePhase.avgMs());
            }
        }

        // VON topology update
        var vonStats = report.metrics.get(PerformanceProfiler.MetricType.VON_TOPOLOGY_UPDATE);
        if (vonStats != null && vonStats.count() > 0) {
            baseline.record(BaselineMetric.VON_TOPOLOGY_UPDATE_MS,
                vonStats.avg() / 1_000_000.0); // nanos to ms
        }

        // Listener notification
        var listenerStats = report.metrics.get(PerformanceProfiler.MetricType.LISTENER_NOTIFICATION);
        if (listenerStats != null && listenerStats.count() > 0) {
            baseline.record(BaselineMetric.LISTENER_NOTIFICATION_P99_US,
                listenerStats.max() / 1000.0); // nanos to micros, use max as p99 proxy
        }

        // Ghost layer validation
        var ghostStats = report.metrics.get(PerformanceProfiler.MetricType.GHOST_LAYER_VALIDATION);
        if (ghostStats != null && ghostStats.count() > 0) {
            baseline.record(BaselineMetric.GHOST_LAYER_VALIDATION_MS,
                ghostStats.avg() / 1_000_000.0); // nanos to ms
        }

        // Memory per partition
        if (!report.partitionMetrics.isEmpty()) {
            var memoryDelta = report.currentMemoryMB - report.baselineMemoryMB;
            var memoryPerPartition = memoryDelta / report.partitionMetrics.size();
            baseline.record(BaselineMetric.MEMORY_PER_PARTITION_MB, memoryPerPartition);
        }

        // Message throughput
        var throughputStats = report.metrics.get(PerformanceProfiler.MetricType.THROUGHPUT);
        if (throughputStats != null && throughputStats.count() > 0) {
            baseline.record(BaselineMetric.MESSAGE_THROUGHPUT_PER_SEC, throughputStats.avg());
        }

        // Fault detection latency
        var detectPhase = report.phaseLatencies.get(PerformanceProfiler.RecoveryPhase.DETECTING);
        if (detectPhase != null && detectPhase.count() > 0) {
            baseline.record(BaselineMetric.FAULT_DETECTION_LATENCY_MS, detectPhase.avgMs());
        }

        log.info("Created baseline from performance report with {} metrics",
            baseline.actualValues.size());

        return baseline;
    }

    /**
     * Record an actual metric value.
     *
     * @param metric the metric to record
     * @param actualValue the measured value
     */
    public void record(BaselineMetric metric, double actualValue) {
        actualValues.put(metric, actualValue);
    }

    /**
     * Get recorded value for a metric.
     *
     * @param metric the metric
     * @return recorded value, or null if not recorded
     */
    public Double getActualValue(BaselineMetric metric) {
        return actualValues.get(metric);
    }

    /**
     * Get target value for a metric.
     *
     * @param metric the metric
     * @return target value from baseline definition
     */
    public double getTargetValue(BaselineMetric metric) {
        return metric.targetValue;
    }

    /**
     * Check if a metric meets its target.
     *
     * @param metric the metric to check
     * @return true if metric meets or beats target
     */
    public boolean meetsTarget(BaselineMetric metric) {
        var actual = actualValues.get(metric);
        if (actual == null) return false;

        // Lower is better for all current metrics
        return actual <= metric.targetValue;
    }

    /**
     * Get percentage difference from target.
     *
     * @param metric the metric
     * @return percentage (positive = worse than target, negative = better)
     */
    public double percentageFromTarget(BaselineMetric metric) {
        var actual = actualValues.get(metric);
        if (actual == null) return Double.NaN;

        return ((actual - metric.targetValue) / metric.targetValue) * 100.0;
    }

    /**
     * Get all recorded metrics.
     *
     * @return map of metrics to actual values
     */
    public Map<BaselineMetric, Double> getAllMetrics() {
        return Map.copyOf(actualValues);
    }

    /**
     * Set test environment description.
     */
    public void setTestEnvironment(String environment) {
        this.testEnvironment = environment;
    }

    /**
     * Set git commit hash.
     */
    public void setGitCommit(String commit) {
        this.gitCommit = commit;
    }

    /**
     * Export baseline to JSON file.
     *
     * @param file the output file
     * @throws IOException if export fails
     */
    public void exportToJson(File file) throws IOException {
        var data = new BaselineData();
        data.timestamp = timestamp.toString();
        data.testEnvironment = testEnvironment;
        data.gitCommit = gitCommit;
        data.metrics = new HashMap<>();

        for (var entry : actualValues.entrySet()) {
            var metricData = new MetricData();
            metricData.target = entry.getKey().targetValue;
            metricData.actual = entry.getValue();
            metricData.meetsTarget = meetsTarget(entry.getKey());
            metricData.percentageFromTarget = percentageFromTarget(entry.getKey());
            data.metrics.put(entry.getKey().name(), metricData);
        }

        mapper.writeValue(file, data);
        log.info("Exported baseline to {}", file.getAbsolutePath());
    }

    /**
     * Import baseline from JSON file.
     *
     * @param file the input file
     * @return loaded baseline
     * @throws IOException if import fails
     */
    public static PerformanceBaseline importFromJson(File file) throws IOException {
        var data = mapper.readValue(file, BaselineData.class);

        var baseline = new PerformanceBaseline();
        baseline.timestamp = Instant.parse(data.timestamp);
        baseline.testEnvironment = data.testEnvironment;
        baseline.gitCommit = data.gitCommit;

        for (var entry : data.metrics.entrySet()) {
            var metric = BaselineMetric.valueOf(entry.getKey());
            baseline.record(metric, entry.getValue().actual);
        }

        log.info("Imported baseline from {} with {} metrics",
            file.getAbsolutePath(), baseline.actualValues.size());

        return baseline;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("\n=== Performance Baseline ===\n");
        if (testEnvironment != null) {
            sb.append(String.format("Environment: %s\n", testEnvironment));
        }
        if (timestamp != null) {
            sb.append(String.format("Timestamp: %s\n", timestamp));
        }
        if (gitCommit != null) {
            sb.append(String.format("Git Commit: %s\n", gitCommit));
        }

        sb.append("\n--- Metrics ---\n");
        for (var metric : BaselineMetric.values()) {
            var actual = actualValues.get(metric);
            if (actual != null) {
                var status = meetsTarget(metric) ? "✅" : "❌";
                var pct = percentageFromTarget(metric);
                sb.append(String.format("  %s %s: %.2f (target: %.2f, diff: %+.1f%%)\n",
                    status, metric.description, actual, metric.targetValue, pct));
            }
        }

        return sb.toString();
    }

    // === JSON Serialization Classes ===

    public static class BaselineData {
        public String timestamp;
        public String testEnvironment;
        public String gitCommit;
        public Map<String, MetricData> metrics;
    }

    public static class MetricData {
        public double target;
        public double actual;
        public boolean meetsTarget;
        public double percentageFromTarget;
    }
}
