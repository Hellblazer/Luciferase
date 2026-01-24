package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Performance regression tracking and comparison.
 *
 * <p>Compares current performance against established baselines to detect:
 * <ul>
 *   <li>Regressions (>10% slower than baseline)</li>
 *   <li>Improvements (>10% faster than baseline)</li>
 *   <li>Stability (within ±10% of baseline)</li>
 * </ul>
 *
 * <p>Generates detailed regression reports with actionable findings.
 *
 * @author hal.hildebrand
 */
public class PerformanceRegression {

    private static final Logger log = LoggerFactory.getLogger(PerformanceRegression.class);

    // Thresholds for regression detection
    private static final double REGRESSION_THRESHOLD = 10.0;  // >10% slower
    private static final double IMPROVEMENT_THRESHOLD = 10.0; // >10% faster

    /**
     * Regression severity levels.
     */
    public enum Severity {
        CRITICAL,   // >50% regression
        MAJOR,      // >25% regression
        MINOR,      // >10% regression
        ACCEPTABLE  // Within thresholds
    }

    /**
     * Compare current performance against a baseline.
     *
     * @param current current baseline
     * @param reference reference baseline to compare against
     * @return regression report
     */
    public RegressionReport compare(PerformanceBaseline current, PerformanceBaseline reference) {
        log.info("Comparing current performance against reference baseline");

        var report = new RegressionReport();
        report.comparisonTimestamp = new Date();

        // Compare each metric
        for (var metric : PerformanceBaseline.BaselineMetric.values()) {
            var currentValue = current.getActualValue(metric);
            var referenceValue = reference.getActualValue(metric);

            if (currentValue == null || referenceValue == null) {
                continue; // Skip if either is missing
            }

            var comparison = compareMetric(metric, currentValue, referenceValue);
            report.comparisons.put(metric, comparison);

            // Categorize
            switch (comparison.status) {
                case REGRESSION -> {
                    report.regressions.add(comparison);
                    if (comparison.severity == Severity.CRITICAL || comparison.severity == Severity.MAJOR) {
                        report.criticalRegressions.add(comparison);
                    }
                }
                case IMPROVEMENT -> report.improvements.add(comparison);
                case STABLE -> report.stableMetrics.add(comparison);
            }
        }

        log.info("Comparison complete: {} regressions, {} improvements, {} stable",
            report.regressions.size(), report.improvements.size(), report.stableMetrics.size());

        return report;
    }

    /**
     * Compare a single metric.
     */
    private MetricComparison compareMetric(
        PerformanceBaseline.BaselineMetric metric,
        double currentValue,
        double referenceValue
    ) {
        var percentChange = ((currentValue - referenceValue) / referenceValue) * 100.0;
        var absoluteChange = currentValue - referenceValue;

        // Determine status (lower is better for all current metrics)
        ComparisonStatus status;
        if (percentChange > REGRESSION_THRESHOLD) {
            status = ComparisonStatus.REGRESSION;
        } else if (percentChange < -IMPROVEMENT_THRESHOLD) {
            status = ComparisonStatus.IMPROVEMENT;
        } else {
            status = ComparisonStatus.STABLE;
        }

        // Determine severity for regressions
        Severity severity = Severity.ACCEPTABLE;
        if (status == ComparisonStatus.REGRESSION) {
            if (Math.abs(percentChange) > 50.0) {
                severity = Severity.CRITICAL;
            } else if (Math.abs(percentChange) > 25.0) {
                severity = Severity.MAJOR;
            } else {
                severity = Severity.MINOR;
            }
        }

        return new MetricComparison(
            metric,
            referenceValue,
            currentValue,
            absoluteChange,
            percentChange,
            status,
            severity
        );
    }

    /**
     * Analyze a regression report and generate recommendations.
     *
     * @param report the regression report
     * @return analysis with recommendations
     */
    public RegressionAnalysis analyze(RegressionReport report) {
        var analysis = new RegressionAnalysis();

        // Check for critical issues
        if (!report.criticalRegressions.isEmpty()) {
            analysis.overallStatus = "FAILED - Critical regressions detected";
            analysis.recommendations.add(
                "URGENT: Address critical regressions before merging changes"
            );

            for (var regression : report.criticalRegressions) {
                analysis.recommendations.add(String.format(
                    "  - %s: %.1f%% regression (%.2f → %.2f)",
                    regression.metric.description,
                    regression.percentChange,
                    regression.referenceValue,
                    regression.currentValue
                ));
            }
        } else if (!report.regressions.isEmpty()) {
            analysis.overallStatus = "WARNING - Minor regressions detected";
            analysis.recommendations.add(
                "Review and justify the following regressions:"
            );

            for (var regression : report.regressions) {
                analysis.recommendations.add(String.format(
                    "  - %s: %.1f%% regression",
                    regression.metric.description,
                    regression.percentChange
                ));
            }
        } else {
            analysis.overallStatus = "PASSED - No significant regressions";
        }

        // Highlight improvements
        if (!report.improvements.isEmpty()) {
            analysis.recommendations.add(String.format(
                "Good: %d metrics improved by >10%%",
                report.improvements.size()
            ));
        }

        // Overall health
        var totalMetrics = report.comparisons.size();
        var stableCount = report.stableMetrics.size();
        if (totalMetrics > 0) {
            var stabilityPercent = (stableCount * 100.0) / totalMetrics;
            analysis.stabilityScore = stabilityPercent;
            analysis.recommendations.add(String.format(
                "Stability: %.1f%% of metrics within ±10%% of baseline",
                stabilityPercent
            ));
        }

        return analysis;
    }

    // === Result Types ===

    public enum ComparisonStatus {
        REGRESSION,   // Performance worse
        IMPROVEMENT,  // Performance better
        STABLE        // Within thresholds
    }

    public record MetricComparison(
        PerformanceBaseline.BaselineMetric metric,
        double referenceValue,
        double currentValue,
        double absoluteChange,
        double percentChange,
        ComparisonStatus status,
        Severity severity
    ) {
        @Override
        public String toString() {
            var statusSymbol = switch (status) {
                case REGRESSION -> "❌";
                case IMPROVEMENT -> "✅";
                case STABLE -> "➖";
            };

            return String.format("%s %s: %.2f → %.2f (%+.1f%%, %+.2f) [%s]",
                statusSymbol,
                metric.description,
                referenceValue,
                currentValue,
                percentChange,
                absoluteChange,
                severity);
        }
    }

    public static class RegressionReport {
        public Date comparisonTimestamp;
        public Map<PerformanceBaseline.BaselineMetric, MetricComparison> comparisons = new EnumMap<>(
            PerformanceBaseline.BaselineMetric.class
        );
        public List<MetricComparison> regressions = new ArrayList<>();
        public List<MetricComparison> improvements = new ArrayList<>();
        public List<MetricComparison> stableMetrics = new ArrayList<>();
        public List<MetricComparison> criticalRegressions = new ArrayList<>();

        public boolean hasRegressions() {
            return !regressions.isEmpty();
        }

        public boolean hasCriticalRegressions() {
            return !criticalRegressions.isEmpty();
        }

        public boolean isPassing() {
            return criticalRegressions.isEmpty() && regressions.size() < 2;
        }

        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("\n=== Performance Regression Report ===\n");
            sb.append(String.format("Comparison Time: %s\n", comparisonTimestamp));
            sb.append(String.format("Total Metrics: %d\n", comparisons.size()));
            sb.append(String.format("  Regressions: %d (Critical: %d)\n",
                regressions.size(), criticalRegressions.size()));
            sb.append(String.format("  Improvements: %d\n", improvements.size()));
            sb.append(String.format("  Stable: %d\n", stableMetrics.size()));

            if (!criticalRegressions.isEmpty()) {
                sb.append("\n--- CRITICAL REGRESSIONS ---\n");
                criticalRegressions.forEach(c -> sb.append("  ").append(c).append("\n"));
            }

            if (!regressions.isEmpty()) {
                sb.append("\n--- Regressions ---\n");
                regressions.forEach(c -> sb.append("  ").append(c).append("\n"));
            }

            if (!improvements.isEmpty()) {
                sb.append("\n--- Improvements ---\n");
                improvements.stream()
                    .limit(5) // Top 5
                    .forEach(c -> sb.append("  ").append(c).append("\n"));
            }

            return sb.toString();
        }
    }

    public static class RegressionAnalysis {
        public String overallStatus;
        public double stabilityScore;
        public List<String> recommendations = new ArrayList<>();

        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("\n=== Regression Analysis ===\n");
            sb.append(String.format("Overall Status: %s\n", overallStatus));
            sb.append(String.format("Stability Score: %.1f%%\n", stabilityScore));

            if (!recommendations.isEmpty()) {
                sb.append("\n--- Recommendations ---\n");
                recommendations.forEach(r -> sb.append("  ").append(r).append("\n"));
            }

            return sb.toString();
        }
    }
}
