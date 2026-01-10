/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.ghost;

import java.util.concurrent.atomic.AtomicLong;

/**
 * GhostPhysicsMetrics - Performance metrics tracking for ghost physics operations (Phase 7D.2 Part 2 Phase C).
 * <p>
 * Tracks operation counts and latencies for ghost state management using lock-free atomic operations:
 * <ul>
 *   <li>updateGhost() - count and latency tracking</li>
 *   <li>removeGhost() - count and latency tracking</li>
 *   <li>reconcileGhostState() - count tracking</li>
 *   <li>Average latency calculation</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> All operations are thread-safe using AtomicLong (NO synchronized, NO locks).
 * <p>
 * <strong>Performance Targets:</strong>
 * <ul>
 *   <li>Hard target: Average operation latency < 100ms for 1000 ghosts</li>
 *   <li>Stretch goal: Average operation latency < 0.1ms (most operations < 1ms)</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var metrics = new GhostPhysicsMetrics();
 * ghostStateManager.setMetrics(metrics);
 *
 * // Operations automatically recorded
 * ghostStateManager.updateGhost(sourceBubbleId, event);
 *
 * // Check metrics
 * var avgLatency = metrics.getUpdateGhostAverage();  // Average latency in nanoseconds
 * var avgLatencyMs = avgLatency / 1_000_000.0;       // Convert to milliseconds
 * </pre>
 *
 * @author hal.hildebrand
 */
public class GhostPhysicsMetrics {

    /**
     * Total number of updateGhost() calls.
     */
    private final AtomicLong updateGhostCount = new AtomicLong(0);

    /**
     * Total number of removeGhost() calls.
     */
    private final AtomicLong removeGhostCount = new AtomicLong(0);

    /**
     * Total number of reconcileGhostState() calls.
     */
    private final AtomicLong reconciliationCount = new AtomicLong(0);

    /**
     * Total latency for all updateGhost() calls (nanoseconds).
     */
    private final AtomicLong updateGhostLatency = new AtomicLong(0);

    /**
     * Total latency for all removeGhost() calls (nanoseconds).
     */
    private final AtomicLong removeGhostLatency = new AtomicLong(0);

    /**
     * Record updateGhost() operation.
     *
     * @param latencyNs Operation latency in nanoseconds
     */
    public void recordUpdateGhost(long latencyNs) {
        updateGhostCount.incrementAndGet();
        updateGhostLatency.addAndGet(latencyNs);
    }

    /**
     * Record removeGhost() operation.
     *
     * @param latencyNs Operation latency in nanoseconds
     */
    public void recordRemoveGhost(long latencyNs) {
        removeGhostCount.incrementAndGet();
        removeGhostLatency.addAndGet(latencyNs);
    }

    /**
     * Record reconcileGhostState() operation.
     */
    public void recordReconciliation() {
        reconciliationCount.incrementAndGet();
    }

    /**
     * Get total updateGhost() calls.
     *
     * @return Update ghost count
     */
    public long getUpdateGhostCount() {
        return updateGhostCount.get();
    }

    /**
     * Get total removeGhost() calls.
     *
     * @return Remove ghost count
     */
    public long getRemoveGhostCount() {
        return removeGhostCount.get();
    }

    /**
     * Get total reconcileGhostState() calls.
     *
     * @return Reconciliation count
     */
    public long getReconciliationCount() {
        return reconciliationCount.get();
    }

    /**
     * Get total updateGhost() latency (nanoseconds).
     *
     * @return Total latency in nanoseconds
     */
    public long getUpdateGhostLatency() {
        return updateGhostLatency.get();
    }

    /**
     * Get total removeGhost() latency (nanoseconds).
     *
     * @return Total latency in nanoseconds
     */
    public long getRemoveGhostLatency() {
        return removeGhostLatency.get();
    }

    /**
     * Calculate average updateGhost() latency.
     * Division guard: returns 0 if no operations recorded.
     *
     * @return Average latency in nanoseconds, or 0 if no operations
     */
    public long getUpdateGhostAverage() {
        var count = updateGhostCount.get();
        if (count == 0) {
            return 0L;  // Division guard
        }
        return updateGhostLatency.get() / count;
    }

    /**
     * Calculate average removeGhost() latency.
     * Division guard: returns 0 if no operations recorded.
     *
     * @return Average latency in nanoseconds, or 0 if no operations
     */
    public long getRemoveGhostAverage() {
        var count = removeGhostCount.get();
        if (count == 0) {
            return 0L;  // Division guard
        }
        return removeGhostLatency.get() / count;
    }

    /**
     * Reset all metrics to zero.
     * Useful for test isolation or periodic metric snapshots.
     */
    public void reset() {
        updateGhostCount.set(0);
        removeGhostCount.set(0);
        reconciliationCount.set(0);
        updateGhostLatency.set(0);
        removeGhostLatency.set(0);
    }

    @Override
    public String toString() {
        var avgUpdateMs = getUpdateGhostAverage() / 1_000_000.0;
        var avgRemoveMs = getRemoveGhostAverage() / 1_000_000.0;

        return String.format(
            "GhostPhysicsMetrics{updates=%d (avg %.3fms), removes=%d (avg %.3fms), reconciliations=%d}",
            updateGhostCount.get(), avgUpdateMs,
            removeGhostCount.get(), avgRemoveMs,
            reconciliationCount.get()
        );
    }
}
