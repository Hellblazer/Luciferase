/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects and analyzes timing metrics for hybrid RealTimeController + BucketScheduler validation.
 * <p>
 * Tracks:
 * <ul>
 *   <li>Clock drift between bubbles at bucket boundaries</li>
 *   <li>Event dispatch overhead as percentage of bucket duration</li>
 *   <li>Entity retention check results</li>
 * </ul>
 * <p>
 * Phase 0: Inc7 Go/No-Go Validation Gate
 *
 * @author hal.hildebrand
 */
public class HybridTimingMetrics {

    private static final Logger log = LoggerFactory.getLogger(HybridTimingMetrics.class);

    /**
     * Clock drift sample at a bucket boundary.
     *
     * @param bucket      Bucket number
     * @param maxDriftMs  Maximum drift in milliseconds (max - min simulation time)
     * @param rawTimes    Raw simulation times per bubble
     */
    public record DriftSample(long bucket, long maxDriftMs, Map<UUID, Long> rawTimes) {
        /**
         * Calculate drift from raw times (in ticks).
         * Assumes 100Hz = 10ms per tick, so drift in ms = drift in ticks * 10.
         */
        public static DriftSample fromRawTimes(long bucket, Map<UUID, Long> simulationTimes) {
            if (simulationTimes.isEmpty()) {
                return new DriftSample(bucket, 0, Map.of());
            }
            var values = simulationTimes.values();
            long max = Collections.max(values);
            long min = Collections.min(values);
            // Drift in ticks; at 100Hz, each tick = 10ms
            long driftTicks = max - min;
            long driftMs = driftTicks * 10;  // Convert to milliseconds
            return new DriftSample(bucket, driftMs, Map.copyOf(simulationTimes));
        }
    }

    /**
     * Overhead sample for a bucket.
     *
     * @param bucket       Bucket number
     * @param durationNs   Total tick callback duration in nanoseconds
     * @param percentage   Overhead as percentage of bucket time budget
     */
    public record OverheadSample(long bucket, long durationNs, double percentage) {}

    /**
     * Retention check result.
     *
     * @param bucket  Bucket number
     * @param passed  Whether all entities were retained
     */
    public record RetentionCheck(long bucket, boolean passed) {}

    private final List<DriftSample> driftSamples = new CopyOnWriteArrayList<>();
    private final List<OverheadSample> overheadSamples = new CopyOnWriteArrayList<>();
    private final List<RetentionCheck> retentionChecks = new CopyOnWriteArrayList<>();

    /**
     * Record clock drift at a bucket boundary.
     *
     * @param bucket          Bucket number
     * @param simulationTimes Map of bubble ID to simulation time (tick count)
     */
    public void recordClockDrift(long bucket, Map<UUID, Long> simulationTimes) {
        var sample = DriftSample.fromRawTimes(bucket, simulationTimes);
        driftSamples.add(sample);

        if (sample.maxDriftMs() > 20) {
            log.warn("High clock drift at bucket {}: {}ms", bucket, sample.maxDriftMs());
        }
    }

    /**
     * Record tick overhead for a bucket.
     *
     * @param bucket     Bucket number
     * @param durationNs Total tick callback duration in nanoseconds
     * @param budgetNs   Total time budget in nanoseconds (bucket duration * bubble count)
     */
    public void recordTickOverhead(long bucket, long durationNs, long budgetNs) {
        double percentage = (budgetNs > 0) ? (durationNs * 100.0 / budgetNs) : 0.0;
        overheadSamples.add(new OverheadSample(bucket, durationNs, percentage));

        if (percentage > 3.0) {
            log.warn("High overhead at bucket {}: {:.2f}%", bucket, percentage);
        }
    }

    /**
     * Record entity retention check result.
     *
     * @param bucket Bucket number
     * @param passed Whether all entities were retained
     */
    public void recordRetentionCheck(long bucket, boolean passed) {
        retentionChecks.add(new RetentionCheck(bucket, passed));

        if (!passed) {
            log.error("Entity retention FAILED at bucket {}", bucket);
        }
    }

    /**
     * Get maximum clock drift across all samples.
     *
     * @return Maximum drift in milliseconds
     */
    public long getMaxDrift() {
        return driftSamples.stream()
                .mapToLong(DriftSample::maxDriftMs)
                .max()
                .orElse(0L);
    }

    /**
     * Get 95th percentile clock drift.
     *
     * @return P95 drift in milliseconds
     */
    public long getP95Drift() {
        if (driftSamples.isEmpty()) {
            return 0L;
        }

        var sorted = driftSamples.stream()
                .mapToLong(DriftSample::maxDriftMs)
                .sorted()
                .toArray();

        int p95Index = (int) (sorted.length * 0.95);
        return sorted[Math.min(p95Index, sorted.length - 1)];
    }

    /**
     * Get average clock drift.
     *
     * @return Average drift in milliseconds
     */
    public double getAverageDrift() {
        return driftSamples.stream()
                .mapToLong(DriftSample::maxDriftMs)
                .average()
                .orElse(0.0);
    }

    /**
     * Get average overhead percentage.
     *
     * @return Average overhead as percentage
     */
    public double getAverageOverheadPercent() {
        return overheadSamples.stream()
                .mapToDouble(OverheadSample::percentage)
                .average()
                .orElse(0.0);
    }

    /**
     * Get maximum overhead percentage.
     *
     * @return Maximum overhead as percentage
     */
    public double getMaxOverheadPercent() {
        return overheadSamples.stream()
                .mapToDouble(OverheadSample::percentage)
                .max()
                .orElse(0.0);
    }

    /**
     * Check if all retention checks passed.
     *
     * @return true if all checks passed
     */
    public boolean allRetentionChecksPassed() {
        return retentionChecks.stream().allMatch(RetentionCheck::passed);
    }

    /**
     * Get number of failed retention checks.
     *
     * @return Count of failed checks
     */
    public long getFailedRetentionCount() {
        return retentionChecks.stream().filter(r -> !r.passed()).count();
    }

    /**
     * Get total number of drift samples.
     *
     * @return Sample count
     */
    public int getDriftSampleCount() {
        return driftSamples.size();
    }

    /**
     * Get total number of overhead samples.
     *
     * @return Sample count
     */
    public int getOverheadSampleCount() {
        return overheadSamples.size();
    }

    /**
     * Generate a validation report.
     *
     * @return Markdown-formatted report string
     */
    public String generateReport() {
        var sb = new StringBuilder();
        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        sb.append("# Phase 0 Validation Report\n\n");
        sb.append("**Generated**: ").append(timestamp).append("\n\n");

        // Summary
        sb.append("## Summary\n\n");
        sb.append("| Metric | Value | Threshold | Result |\n");
        sb.append("|--------|-------|-----------|--------|\n");

        long maxDrift = getMaxDrift();
        long p95Drift = getP95Drift();
        double avgOverhead = getAverageOverheadPercent();
        boolean retention = allRetentionChecksPassed();

        sb.append(String.format("| Clock Drift (Max) | %dms | <50ms | %s |\n",
                maxDrift, maxDrift < 50 ? "PASS" : "FAIL"));
        sb.append(String.format("| Clock Drift (P95) | %dms | <10ms | %s |\n",
                p95Drift, p95Drift < 10 ? "PASS" : "FAIL"));
        sb.append(String.format("| Event Overhead | %.2f%% | <5%% | %s |\n",
                avgOverhead, avgOverhead < 5.0 ? "PASS" : "FAIL"));
        sb.append(String.format("| Entity Retention | %s | 100%% | %s |\n",
                retention ? "100%" : "LOSS", retention ? "PASS" : "FAIL"));

        sb.append("\n");

        // Verdict
        sb.append("## Verdict\n\n");
        boolean allPass = maxDrift < 50 && p95Drift < 10 && avgOverhead < 5.0 && retention;
        if (allPass) {
            sb.append("**GO**: All criteria met. Proceed with Inc7 Phases 7A-7F.\n\n");
        } else {
            sb.append("**NO-GO**: One or more criteria failed. Escalate to user.\n\n");
            sb.append("### Failed Criteria\n\n");
            if (maxDrift >= 50) sb.append("- Clock drift max (").append(maxDrift).append("ms) >= 50ms\n");
            if (p95Drift >= 10) sb.append("- Clock drift P95 (").append(p95Drift).append("ms) >= 10ms\n");
            if (avgOverhead >= 5.0) sb.append("- Event overhead (").append(String.format("%.2f", avgOverhead)).append("%) >= 5%\n");
            if (!retention) sb.append("- Entity retention failed (").append(getFailedRetentionCount()).append(" checks)\n");
            sb.append("\n");
        }

        // Statistics
        sb.append("## Detailed Statistics\n\n");
        sb.append("### Clock Drift\n\n");
        sb.append(String.format("- Samples: %d\n", getDriftSampleCount()));
        sb.append(String.format("- Max: %dms\n", maxDrift));
        sb.append(String.format("- P95: %dms\n", p95Drift));
        sb.append(String.format("- Average: %.2fms\n", getAverageDrift()));
        sb.append("\n");

        sb.append("### Event Overhead\n\n");
        sb.append(String.format("- Samples: %d\n", getOverheadSampleCount()));
        sb.append(String.format("- Max: %.2f%%\n", getMaxOverheadPercent()));
        sb.append(String.format("- Average: %.2f%%\n", avgOverhead));
        sb.append("\n");

        sb.append("### Entity Retention\n\n");
        sb.append(String.format("- Checks: %d\n", retentionChecks.size()));
        sb.append(String.format("- Passed: %d\n", retentionChecks.stream().filter(RetentionCheck::passed).count()));
        sb.append(String.format("- Failed: %d\n", getFailedRetentionCount()));
        sb.append("\n");

        // Configuration
        sb.append("## Test Configuration\n\n");
        sb.append("- Bucket duration: 100ms\n");
        sb.append("- Tick rate: 100Hz (10ms per tick)\n");
        sb.append("- Ticks per bucket: 10\n");
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Write report to file.
     *
     * @param path File path for report
     * @throws IOException if write fails
     */
    public void writeReportToFile(Path path) throws IOException {
        var report = generateReport();
        Files.createDirectories(path.getParent());
        Files.writeString(path, report);
        log.info("Validation report written to: {}", path);
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        driftSamples.clear();
        overheadSamples.clear();
        retentionChecks.clear();
    }
}
