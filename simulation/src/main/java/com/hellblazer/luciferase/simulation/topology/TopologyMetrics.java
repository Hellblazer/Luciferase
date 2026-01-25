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
package com.hellblazer.luciferase.simulation.topology;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks operational metrics for topology changes.
 * <p>
 * Provides counters for monitoring topology operations in production:
 * <ul>
 *   <li>Split operations (successful/failed)</li>
 *   <li>Merge operations (successful/failed)</li>
 *   <li>Move operations (successful/failed)</li>
 *   <li>Entities moved during operations</li>
 *   <li>Cooldown rejections</li>
 * </ul>
 * <p>
 * All counters are thread-safe using AtomicLong for concurrent updates.
 * Metrics can be exported to monitoring systems via getMetrics().
 * <p>
 * Phase 9C: Topology Reorganization & Execution
 * Phase P1.1: Diagnostic Enhancement - Split Failure Metrics
 *
 * @author hal.hildebrand
 */
public class TopologyMetrics {

    // Split operation metrics
    private final AtomicLong splitAttempts = new AtomicLong();
    private final AtomicLong splitsSuccessful = new AtomicLong();
    private final AtomicLong splitsFailed = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> splitFailuresByReason = new ConcurrentHashMap<>();
    private final AtomicLong totalLevelsExhausted = new AtomicLong();
    private final AtomicLong levelsExhaustedCount = new AtomicLong();

    // Merge operation metrics
    private final AtomicLong mergesSuccessful = new AtomicLong();
    private final AtomicLong mergesFailed = new AtomicLong();

    // Move operation metrics
    private final AtomicLong movesSuccessful = new AtomicLong();
    private final AtomicLong movesFailed = new AtomicLong();

    // Entity tracking
    private final AtomicLong entitiesMoved = new AtomicLong();

    // Cooldown tracking
    private final AtomicLong cooldownRejections = new AtomicLong();

    /**
     * Record a split operation attempt.
     * <p>
     * Called at the start of each split operation execution.
     * Phase P1.1: Diagnostic Enhancement
     */
    public void recordSplitAttempt() {
        splitAttempts.incrementAndGet();
    }

    /**
     * Record a successful split operation.
     */
    public void recordSplitSuccess() {
        splitsSuccessful.incrementAndGet();
    }

    /**
     * Record a failed split operation.
     */
    public void recordSplitFailure() {
        splitsFailed.incrementAndGet();
    }

    /**
     * Record a failed split operation with categorized reason.
     * <p>
     * Common reasons:
     * <ul>
     *   <li>KEY_COLLISION - Unable to find unoccupied key at target level</li>
     *   <li>NO_AVAILABLE_KEY - Exhausted all levels without finding available key</li>
     *   <li>ENTITY_CONSERVATION_FAILED - Entity count mismatch after split</li>
     *   <li>SOURCE_BUBBLE_NOT_FOUND - Source bubble does not exist</li>
     *   <li>NO_ENTITIES - Source bubble has no entities to split</li>
     * </ul>
     * Phase P1.1: Diagnostic Enhancement
     *
     * @param reason the failure reason category
     */
    public void recordSplitFailure(String reason) {
        splitsFailed.incrementAndGet();
        splitFailuresByReason.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Record the number of tree levels exhausted during a failed split.
     * <p>
     * Used to calculate average depth searched before failure.
     * Phase P1.1: Diagnostic Enhancement
     *
     * @param levels number of levels attempted before failure
     */
    public void recordLevelsExhaustedOnFailure(int levels) {
        totalLevelsExhausted.addAndGet(levels);
        levelsExhaustedCount.incrementAndGet();
    }

    /**
     * Get comprehensive split operation metrics.
     * <p>
     * Returns diagnostic data for understanding split retry patterns and failure modes.
     * Phase P1.1: Diagnostic Enhancement
     *
     * @return split metrics snapshot
     */
    public SplitMetrics getSplitMetrics() {
        var failuresByReason = new HashMap<String, Long>();
        splitFailuresByReason.forEach((reason, counter) -> failuresByReason.put(reason, counter.get()));

        long levelsCount = levelsExhaustedCount.get();
        double avgLevels = levelsCount > 0
            ? (double) totalLevelsExhausted.get() / levelsCount
            : 0.0;

        return new SplitMetrics(
            splitAttempts.get(),
            splitsSuccessful.get(),
            splitsFailed.get(),
            failuresByReason,
            avgLevels
        );
    }

    /**
     * Record a successful merge operation.
     */
    public void recordMergeSuccess() {
        mergesSuccessful.incrementAndGet();
    }

    /**
     * Record a failed merge operation.
     */
    public void recordMergeFailure() {
        mergesFailed.incrementAndGet();
    }

    /**
     * Record a successful move operation.
     */
    public void recordMoveSuccess() {
        movesSuccessful.incrementAndGet();
    }

    /**
     * Record a failed move operation.
     */
    public void recordMoveFailure() {
        movesFailed.incrementAndGet();
    }

    /**
     * Record entities moved during an operation.
     *
     * @param count number of entities moved
     */
    public void recordEntitiesMoved(int count) {
        entitiesMoved.addAndGet(count);
    }

    /**
     * Record a topology proposal rejected due to cooldown.
     */
    public void recordCooldownRejection() {
        cooldownRejections.incrementAndGet();
    }

    /**
     * Get all metrics as a map for export to monitoring systems.
     * <p>
     * Metric names follow Prometheus naming conventions:
     * - topology_splits_successful_total
     * - topology_splits_failed_total
     * - topology_merges_successful_total
     * - topology_merges_failed_total
     * - topology_moves_successful_total
     * - topology_moves_failed_total
     * - topology_entities_moved_total
     * - topology_cooldown_rejections_total
     *
     * @return map of metric name to current value
     */
    public Map<String, Long> getMetrics() {
        var metrics = new HashMap<String, Long>();
        metrics.put("topology_splits_successful_total", splitsSuccessful.get());
        metrics.put("topology_splits_failed_total", splitsFailed.get());
        metrics.put("topology_merges_successful_total", mergesSuccessful.get());
        metrics.put("topology_merges_failed_total", mergesFailed.get());
        metrics.put("topology_moves_successful_total", movesSuccessful.get());
        metrics.put("topology_moves_failed_total", movesFailed.get());
        metrics.put("topology_entities_moved_total", entitiesMoved.get());
        metrics.put("topology_cooldown_rejections_total", cooldownRejections.get());
        return metrics;
    }

    /**
     * Get the total number of split operations attempted.
     *
     * @return total splits (successful + failed)
     */
    public long getTotalSplits() {
        return splitsSuccessful.get() + splitsFailed.get();
    }

    /**
     * Get the total number of merge operations attempted.
     *
     * @return total merges (successful + failed)
     */
    public long getTotalMerges() {
        return mergesSuccessful.get() + mergesFailed.get();
    }

    /**
     * Get the total number of move operations attempted.
     *
     * @return total moves (successful + failed)
     */
    public long getTotalMoves() {
        return movesSuccessful.get() + movesFailed.get();
    }

    /**
     * Get the total number of topology operations attempted.
     *
     * @return total operations (splits + merges + moves)
     */
    public long getTotalOperations() {
        return getTotalSplits() + getTotalMerges() + getTotalMoves();
    }

    /**
     * Get the success rate for split operations.
     *
     * @return success rate (0.0 to 1.0), or 0.0 if no splits attempted
     */
    public double getSplitSuccessRate() {
        long total = getTotalSplits();
        return total == 0 ? 0.0 : (double) splitsSuccessful.get() / total;
    }

    /**
     * Get the success rate for merge operations.
     *
     * @return success rate (0.0 to 1.0), or 0.0 if no merges attempted
     */
    public double getMergeSuccessRate() {
        long total = getTotalMerges();
        return total == 0 ? 0.0 : (double) mergesSuccessful.get() / total;
    }

    /**
     * Get the success rate for move operations.
     *
     * @return success rate (0.0 to 1.0), or 0.0 if no moves attempted
     */
    public double getMoveSuccessRate() {
        long total = getTotalMoves();
        return total == 0 ? 0.0 : (double) movesSuccessful.get() / total;
    }

    /**
     * Reset all metrics to zero.
     * <p>
     * Used for testing or when starting a new monitoring period.
     */
    public void reset() {
        splitAttempts.set(0);
        splitsSuccessful.set(0);
        splitsFailed.set(0);
        splitFailuresByReason.clear();
        totalLevelsExhausted.set(0);
        levelsExhaustedCount.set(0);
        mergesSuccessful.set(0);
        mergesFailed.set(0);
        movesSuccessful.set(0);
        movesFailed.set(0);
        entitiesMoved.set(0);
        cooldownRejections.set(0);
    }
}
