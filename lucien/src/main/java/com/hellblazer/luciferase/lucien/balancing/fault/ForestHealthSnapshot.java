/*
 * Copyright (c) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.Map;
import java.util.UUID;

/**
 * Point-in-time snapshot of forest health state.
 * <p>
 * Provides an immutable view of system health for monitoring dashboards,
 * alerting systems, and diagnostic tools. All values are captured atomically
 * at snapshot creation time.
 * <p>
 * Part of F4.2.5 Monitoring & Documentation (bead: Luciferase-u3hz).
 *
 * @param timestampMs        Snapshot creation time (milliseconds since epoch)
 * @param totalPartitions    Total number of partitions in the system
 * @param healthyPartitions  Partitions in HEALTHY state
 * @param suspectedPartitions Partitions in SUSPECTED state (potential failures)
 * @param failedPartitions   Partitions in FAILED state (confirmed failures)
 * @param quorumMaintained   Whether majority quorum is maintained
 * @param inRecoveryMode     Whether the system is currently in recovery mode
 * @param totalTrees         Total number of trees across all forests
 * @param totalEntities      Total entity count across all trees
 * @param avgDetectionLatencyMs Average failure detection latency
 * @param avgRecoveryLatencyMs  Average recovery operation latency
 * @param totalFailuresDetected Cumulative failure count since startup
 * @param totalRecoveriesAttempted Cumulative recovery attempts
 * @param totalRecoveriesSucceeded Successful recovery count
 * @param recoverySuccessRate  Recovery success rate (0.0 to 1.0)
 * @param partitionStatuses  Per-partition status map for detailed inspection
 * @author hal.hildebrand
 */
public record ForestHealthSnapshot(
    long timestampMs,
    int totalPartitions,
    int healthyPartitions,
    int suspectedPartitions,
    int failedPartitions,
    boolean quorumMaintained,
    boolean inRecoveryMode,
    int totalTrees,
    int totalEntities,
    long avgDetectionLatencyMs,
    long avgRecoveryLatencyMs,
    long totalFailuresDetected,
    long totalRecoveriesAttempted,
    long totalRecoveriesSucceeded,
    double recoverySuccessRate,
    Map<UUID, PartitionStatus> partitionStatuses
) {

    /**
     * Overall health status derived from partition states.
     */
    public enum HealthLevel {
        /** All partitions healthy, system operating normally */
        HEALTHY,
        /** Some partitions suspected, monitoring closely */
        DEGRADED,
        /** One or more partitions failed, recovery in progress */
        CRITICAL,
        /** Quorum lost, manual intervention required */
        QUORUM_LOST
    }

    /**
     * Compute overall health level from snapshot data.
     *
     * @return the computed health level
     */
    public HealthLevel healthLevel() {
        if (!quorumMaintained) {
            return HealthLevel.QUORUM_LOST;
        }
        if (failedPartitions > 0) {
            return HealthLevel.CRITICAL;
        }
        if (suspectedPartitions > 0) {
            return HealthLevel.DEGRADED;
        }
        return HealthLevel.HEALTHY;
    }

    /**
     * Check if any alert thresholds are exceeded.
     *
     * @param thresholds the alert thresholds to check
     * @return true if any threshold is exceeded
     */
    public boolean exceedsThresholds(AlertThresholds thresholds) {
        if (failedPartitions > thresholds.maxFailedPartitions()) {
            return true;
        }
        if (suspectedPartitions > thresholds.maxSuspectedPartitions()) {
            return true;
        }
        if (avgDetectionLatencyMs > thresholds.maxDetectionLatencyMs()) {
            return true;
        }
        if (avgRecoveryLatencyMs > thresholds.maxRecoveryLatencyMs()) {
            return true;
        }
        if (recoverySuccessRate < thresholds.minRecoverySuccessRate()) {
            return true;
        }
        return !quorumMaintained;
    }

    /**
     * Format as human-readable summary for logging.
     *
     * @return formatted summary string
     */
    public String toSummary() {
        return String.format(
            "Health: %s | Partitions: %d/%d healthy | Recovery: %.1f%% success | Latency: detect=%dms, recover=%dms",
            healthLevel(),
            healthyPartitions, totalPartitions,
            recoverySuccessRate * 100,
            avgDetectionLatencyMs,
            avgRecoveryLatencyMs
        );
    }

    /**
     * Alert thresholds for health monitoring.
     *
     * @param maxFailedPartitions Maximum failed partitions before alert
     * @param maxSuspectedPartitions Maximum suspected partitions before alert
     * @param maxDetectionLatencyMs Maximum detection latency (ms) before alert
     * @param maxRecoveryLatencyMs Maximum recovery latency (ms) before alert
     * @param minRecoverySuccessRate Minimum recovery success rate (0.0-1.0)
     */
    public record AlertThresholds(
        int maxFailedPartitions,
        int maxSuspectedPartitions,
        long maxDetectionLatencyMs,
        long maxRecoveryLatencyMs,
        double minRecoverySuccessRate
    ) {
        /**
         * Default thresholds for production monitoring.
         */
        public static AlertThresholds defaultThresholds() {
            return new AlertThresholds(
                0,      // Alert on any failed partition
                2,      // Allow up to 2 suspected partitions
                500,    // 500ms detection latency
                5000,   // 5 second recovery latency
                0.9     // 90% recovery success rate
            );
        }

        /**
         * Relaxed thresholds for development/testing.
         */
        public static AlertThresholds relaxedThresholds() {
            return new AlertThresholds(
                1,      // Allow 1 failed partition
                5,      // Allow up to 5 suspected partitions
                2000,   // 2 second detection latency
                30000,  // 30 second recovery latency
                0.5     // 50% recovery success rate
            );
        }
    }
}
