package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Statistics record for FaultTolerantDistributedForest operations.
 *
 * <p>All fields are populated correctly by FaultTolerantDistributedForest.getStats().
 *
 * @param totalPartitions total number of partitions tracked
 * @param healthyPartitions partitions in HEALTHY state
 * @param suspectedPartitions partitions in SUSPECTED state
 * @param failedPartitions partitions in FAILED state
 * @param totalFailuresDetected cumulative failure transitions detected
 * @param totalRecoveriesAttempted cumulative recovery attempts
 * @param totalRecoveriesSucceeded cumulative successful recoveries
 * @param averageDetectionLatencyMs average time to detect failures (ms)
 * @param averageRecoveryLatencyMs average recovery duration (ms)
 */
public record FaultTolerantForestStats(
    int totalPartitions,
    int healthyPartitions,
    int suspectedPartitions,
    int failedPartitions,
    long totalFailuresDetected,
    long totalRecoveriesAttempted,
    long totalRecoveriesSucceeded,
    long averageDetectionLatencyMs,
    long averageRecoveryLatencyMs
) {
    /**
     * Internal accumulator for collecting stats during operation.
     */
    public static class StatsAccumulator {
        private final AtomicLong failuresDetected = new AtomicLong();
        private final AtomicLong recoveriesAttempted = new AtomicLong();
        private final AtomicLong recoveriesSucceeded = new AtomicLong();
        private final AtomicLong recoveriesBlocked = new AtomicLong();
        private final AtomicLong totalDetectionLatency = new AtomicLong();
        private final AtomicLong totalRecoveryLatency = new AtomicLong();

        /**
         * Record a status change event.
         */
        public void recordStatusChange(PartitionChangeEvent event) {
            if (event.newStatus() == PartitionStatus.FAILED) {
                failuresDetected.incrementAndGet();
            }
        }

        /**
         * Record a recovery attempt and its outcome.
         */
        public void recordRecoveryAttempt(long durationMs, boolean success) {
            recoveriesAttempted.incrementAndGet();
            totalRecoveryLatency.addAndGet(durationMs);
            if (success) {
                recoveriesSucceeded.incrementAndGet();
            }
        }

        /**
         * Record a recovery that was blocked due to quorum loss.
         */
        public void recordRecoveryBlocked() {
            recoveriesBlocked.incrementAndGet();
        }

        /**
         * Get a snapshot of accumulated stats.
         *
         * <p>Note: Partition counts (total, healthy, suspected, failed) are
         * set to 0 here and filled in by FaultTolerantDistributedForest.getStats()
         * from live partition state.
         */
        public FaultTolerantForestStats snapshot() {
            var failures = failuresDetected.get();
            var recoveries = recoveriesAttempted.get();
            return new FaultTolerantForestStats(
                0, 0, 0, 0,  // Partition counts filled by caller from live state
                failures,
                recoveries,
                recoveriesSucceeded.get(),
                failures > 0 ? totalDetectionLatency.get() / failures : 0,
                recoveries > 0 ? totalRecoveryLatency.get() / recoveries : 0
            );
        }

        /**
         * Get count of recoveries blocked due to quorum loss.
         */
        public long getRecoveriesBlocked() {
            return recoveriesBlocked.get();
        }
    }
}
