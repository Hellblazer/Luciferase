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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance metrics for cross-process entity migrations.
 * <p>
 * Tracks:
 * - Success/failure counts
 * - Latency statistics (prepare, commit, total)
 * - Duplicate rejection count
 * - Abort count
 * - Concurrent migration gauge
 * <p>
 * Thread-safe using atomic counters and LatencyStats.
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
 *     metrics.recordSuccess(latency);
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

    /**
     * Simple latency statistics tracker.
     * <p>
     * Tracks count, sum, min, max for calculating mean.
     */
    public static class LatencyStats {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong sum   = new AtomicLong(0);
        private final AtomicLong min   = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max   = new AtomicLong(Long.MIN_VALUE);

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
    }

    // Counters
    private final AtomicLong    successfulMigrations = new AtomicLong(0);
    private final AtomicLong    failedMigrations     = new AtomicLong(0);
    private final AtomicLong    duplicatesRejected   = new AtomicLong(0);
    private final AtomicLong    aborts               = new AtomicLong(0);
    private final AtomicInteger concurrentMigrations = new AtomicInteger(0);

    // Latency tracking
    private final LatencyStats prepareLatency = new LatencyStats();
    private final LatencyStats commitLatency  = new LatencyStats();
    private final LatencyStats totalLatency   = new LatencyStats();

    /**
     * Record a successful migration.
     *
     * @param latencyMs Total migration latency
     */
    public void recordSuccess(long latencyMs) {
        successfulMigrations.incrementAndGet();
        totalLatency.record(latencyMs);
        prepareLatency.record(latencyMs); // Record same latency (actual phase tracking done elsewhere)
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
            "MigrationMetrics{success=%d, failed=%d, duplicates=%d, aborts=%d, concurrent=%d, avgLatency=%.2fms}",
            successfulMigrations.get(), failedMigrations.get(), duplicatesRejected.get(), aborts.get(),
            concurrentMigrations.get(), totalLatency.mean());
    }
}
