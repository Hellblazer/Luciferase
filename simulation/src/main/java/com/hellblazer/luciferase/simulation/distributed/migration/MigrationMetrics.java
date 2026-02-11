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

package com.hellblazer.luciferase.simulation.distributed.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance metrics for cross-process entity migrations with percentile tracking (Luciferase-6k23).
 * <p>
 * Tracks:
 * - Success/failure counts
 * - Latency statistics (P50, P95, P99, mean, min, max)
 * - Duplicate rejection count
 * - Abort count
 * - Rollback failures (orphaned entities)
 * - Concurrent migration gauge
 * <p>
 * Thread-safe using atomic counters and synchronized percentile calculation.
 * <p>
 * **Recommended Prometheus/Grafana Alerts** (Luciferase-6k23):
 * <ul>
 *   <li><b>CRITICAL</b>: {@code migration_rollback_failures > 0} → Page oncall (data loss risk, orphaned entities)</li>
 *   <li><b>CRITICAL</b>: {@code migration_p99_latency_ms > 500} → Warning (performance degradation, timeout risk)</li>
 *   <li><b>WARNING</b>: {@code migration_p95_latency_ms > 250} → Warning (elevated latency)</li>
 *   <li><b>WARNING</b>: {@code migration_failure_rate > 0.01} → Warning (>1% failure rate, degraded service)</li>
 *   <li><b>WARNING</b>: {@code migration_concurrent > 100} → Warning (overload, capacity planning needed)</li>
 * </ul>
 * <p>
 * **Example Grafana Dashboard Queries**:
 * <pre>
 * # P99 latency (graph)
 * migration_p99_latency_ms
 *
 * # Failure rate (graph)
 * rate(migration_failures_total[5m]) / rate(migration_total[5m])
 *
 * # Concurrent migrations (gauge)
 * migration_concurrent
 *
 * # Rollback failures (counter, should always be 0)
 * migration_rollback_failures_total
 * </pre>
 * <p>
 * Example usage:
 * <pre>
 * var metrics = new MigrationMetrics();
 *
 * metrics.incrementConcurrent();
 * try {
 *     long startTime = System.currentTimeMillis();
 *     // ... perform migration ...
 *     long latency = System.currentTimeMillis() - startTime;
 *     metrics.recordSuccess(latency);  // Logs warning if latency > 500ms
 * } catch (Exception e) {
 *     metrics.recordFailure(e.getMessage());
 * } finally {
 *     metrics.decrementConcurrent();
 * }
 * </pre>
 *
 * @author hal.hildebrand
 */
public class MigrationMetrics {

    private static final Logger log = LoggerFactory.getLogger(MigrationMetrics.class);
    private static final long SLOW_MIGRATION_THRESHOLD_MS = 500;  // Luciferase-6k23: Alert threshold

    /**
     * Latency statistics tracker with percentile support (Luciferase-6k23).
     * <p>
     * Tracks count, sum, min, max, P50, P95, P99 for dashboarding and alerting.
     * <p>
     * **Alert Thresholds** (Prometheus/Grafana):
     * <ul>
     *   <li>P99 latency > 500ms → Warning (performance degradation)</li>
     *   <li>P95 latency > 250ms → Warning (elevated latency)</li>
     *   <li>max latency > 1000ms → Critical (timeout risk)</li>
     * </ul>
     * <p>
     * Uses fixed-size circular buffer (1000 samples) for percentile calculation.
     * Thread-safe via synchronized methods for percentile access.
     */
    public static class LatencyStats {
        private static final int HISTOGRAM_SIZE = 1000;  // Fixed-size circular buffer

        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong sum   = new AtomicLong(0);
        private final AtomicLong min   = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max   = new AtomicLong(Long.MIN_VALUE);

        // Circular buffer for percentile calculation (Luciferase-6k23)
        private final long[] histogram = new long[HISTOGRAM_SIZE];
        private int histogramIndex = 0;

        public void record(long latencyMs) {
            count.incrementAndGet();
            sum.addAndGet(latencyMs);

            // Update min
            var currentMin = min.get();
            while (latencyMs < currentMin && !min.compareAndSet(currentMin, latencyMs)) {
                currentMin = min.get();
            }

            // Update max
            var currentMax = max.get();
            while (latencyMs > currentMax && !max.compareAndSet(currentMax, latencyMs)) {
                currentMax = max.get();
            }

            // Store in circular buffer for percentile calculation (Luciferase-6k23)
            synchronized (histogram) {
                histogram[histogramIndex] = latencyMs;
                histogramIndex = (histogramIndex + 1) % HISTOGRAM_SIZE;
            }
        }

        public long count() {
            return count.get();
        }

        public double mean() {
            var c = count.get();
            return c > 0 ? (double) sum.get() / c : 0.0;
        }

        public long min() {
            var c = count.get();
            return c > 0 ? min.get() : 0;
        }

        public long max() {
            var c = count.get();
            return c > 0 ? max.get() : 0;
        }

        /**
         * Get P50 (median) latency in milliseconds (Luciferase-6k23).
         * <p>
         * **Alert Threshold**: P50 > 100ms → Warning (baseline degradation)
         *
         * @return P50 latency or 0 if no data
         */
        public long getP50Latency() {
            return getPercentile(0.50);
        }

        /**
         * Get P95 latency in milliseconds (Luciferase-6k23).
         * <p>
         * **Alert Threshold**: P95 > 250ms → Warning (elevated latency)
         *
         * @return P95 latency or 0 if no data
         */
        public long getP95Latency() {
            return getPercentile(0.95);
        }

        /**
         * Get P99 latency in milliseconds (Luciferase-6k23).
         * <p>
         * **Alert Threshold**: P99 > 500ms → Warning (performance degradation)
         *
         * @return P99 latency or 0 if no data
         */
        public long getP99Latency() {
            return getPercentile(0.99);
        }

        /**
         * Calculate percentile from histogram (Luciferase-6k23).
         *
         * @param percentile Percentile to calculate (0.0-1.0)
         * @return Latency at percentile or 0 if insufficient data
         */
        private long getPercentile(double percentile) {
            synchronized (histogram) {
                var c = count.get();
                if (c == 0) return 0;

                // Copy and sort histogram (only populated portion)
                var sampleCount = (int) Math.min(c, HISTOGRAM_SIZE);
                var sorted = new long[sampleCount];
                System.arraycopy(histogram, 0, sorted, 0, sampleCount);
                java.util.Arrays.sort(sorted);

                // Calculate percentile index
                var index = (int) Math.ceil(percentile * sampleCount) - 1;
                index = Math.max(0, Math.min(index, sampleCount - 1));

                return sorted[index];
            }
        }
    }

    // Counters
    private final AtomicLong    successfulMigrations = new AtomicLong(0);
    private final AtomicLong    failedMigrations     = new AtomicLong(0);
    private final AtomicLong    duplicatesRejected   = new AtomicLong(0);
    private final AtomicLong    aborts               = new AtomicLong(0);
    private final AtomicLong    rollbackFailures     = new AtomicLong(0);
    private final AtomicLong    alreadyMigrating     = new AtomicLong(0);
    private final AtomicInteger concurrentMigrations = new AtomicInteger(0);

    // Latency tracking
    private final LatencyStats prepareLatency = new LatencyStats();
    private final LatencyStats commitLatency  = new LatencyStats();
    private final LatencyStats totalLatency   = new LatencyStats();

    /**
     * Record a successful migration (Luciferase-6k23: with slow migration warning).
     *
     * @param latencyMs Total migration latency
     */
    public void recordSuccess(long latencyMs) {
        successfulMigrations.incrementAndGet();
        totalLatency.record(latencyMs);
        prepareLatency.record(latencyMs); // Record same latency (actual phase tracking done elsewhere)

        // Luciferase-6k23: Log slow migrations (P99 threshold)
        if (latencyMs > SLOW_MIGRATION_THRESHOLD_MS) {
            log.warn("Slow migration detected: {}ms (threshold: {}ms, P99: {}ms)",
                     latencyMs, SLOW_MIGRATION_THRESHOLD_MS, totalLatency.getP99Latency());
        }
    }

    /**
     * Record a failed migration.
     *
     * @param reason Failure reason
     */
    public void recordFailure(String reason) {
        failedMigrations.incrementAndGet();
    }

    /**
     * Record an aborted migration.
     *
     * @param reason Abort reason
     */
    public void recordAbort(String reason) {
        aborts.incrementAndGet();
    }

    /**
     * Record a duplicate token rejection.
     */
    public void recordDuplicateRejection() {
        duplicatesRejected.incrementAndGet();
    }

    /**
     * Increment concurrent migration gauge.
     */
    public void incrementConcurrent() {
        concurrentMigrations.incrementAndGet();
    }

    /**
     * Decrement concurrent migration gauge.
     */
    public void decrementConcurrent() {
        concurrentMigrations.decrementAndGet();
    }

    /**
     * Record a rollback failure (C3).
     * <p>
     * Called when abort/rollback fails to restore entity to source.
     * This is a critical error that requires manual intervention.
     */
    public void recordRollbackFailure() {
        rollbackFailures.incrementAndGet();
    }

    /**
     * Record an entity already being migrated.
     * <p>
     * Called when migration lock acquisition fails (C1).
     */
    public void recordAlreadyMigrating() {
        alreadyMigrating.incrementAndGet();
    }

    // Getters

    public long getSuccessfulMigrations() {
        return successfulMigrations.get();
    }

    public long getFailedMigrations() {
        return failedMigrations.get();
    }

    public long getDuplicatesRejected() {
        return duplicatesRejected.get();
    }

    public long getAborts() {
        return aborts.get();
    }

    public int getConcurrentMigrations() {
        return concurrentMigrations.get();
    }

    public long getRollbackFailures() {
        return rollbackFailures.get();
    }

    public long getAlreadyMigrating() {
        return alreadyMigrating.get();
    }

    public LatencyStats getPrepareLatency() {
        return prepareLatency;
    }

    public LatencyStats getCommitLatency() {
        return commitLatency;
    }

    public LatencyStats getTotalLatency() {
        return totalLatency;
    }

    @Override
    public String toString() {
        return String.format(
            "MigrationMetrics{success=%d, failed=%d, duplicates=%d, aborts=%d, rollbackFailures=%d, alreadyMigrating=%d, concurrent=%d, avgLatency=%.2fms, p50=%dms, p95=%dms, p99=%dms}",
            successfulMigrations.get(), failedMigrations.get(), duplicatesRejected.get(), aborts.get(),
            rollbackFailures.get(), alreadyMigrating.get(), concurrentMigrations.get(), totalLatency.mean(),
            totalLatency.getP50Latency(), totalLatency.getP95Latency(), totalLatency.getP99Latency());
    }
}
