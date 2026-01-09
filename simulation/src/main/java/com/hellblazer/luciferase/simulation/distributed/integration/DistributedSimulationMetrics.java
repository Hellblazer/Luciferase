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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregated metrics for distributed simulation.
 * <p>
 * Tracks migration counts, entity totals, clock skew, and TPS across all
 * processes in the cluster.
 * <p>
 * Thread-safe: Uses atomic counters for all metrics.
 * <p>
 * Phase 6B5.2: TestProcessCluster Infrastructure
 *
 * @author hal.hildebrand
 */
public class DistributedSimulationMetrics {

    private final AtomicLong totalMigrations = new AtomicLong(0);
    private final AtomicLong successfulMigrations = new AtomicLong(0);
    private final AtomicLong failedMigrations = new AtomicLong(0);
    private final AtomicLong totalEntities = new AtomicLong(0);
    private final AtomicInteger activeProcessCount = new AtomicInteger(0);
    private final AtomicLong maxClockSkewMs = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final AtomicLong latencyCount = new AtomicLong(0);
    private volatile long startTimeMs = 0;
    private final Set<UUID> crashedProcesses = ConcurrentHashMap.newKeySet();
    private final Set<UUID> recoveredProcesses = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> metrics = new ConcurrentHashMap<>();

    /**
     * Creates a new metrics instance.
     */
    public DistributedSimulationMetrics() {
        // Default constructor
    }

    /**
     * Records a successful migration.
     *
     * @param latencyMs migration latency in milliseconds
     */
    public void recordMigrationSuccess(long latencyMs) {
        totalMigrations.incrementAndGet();
        successfulMigrations.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        latencyCount.incrementAndGet();
    }

    /**
     * Records a failed migration.
     */
    public void recordMigrationFailure() {
        totalMigrations.incrementAndGet();
        failedMigrations.incrementAndGet();
    }

    /**
     * Records a clock skew observation.
     *
     * @param skewMs clock skew in milliseconds
     */
    public void recordClockSkew(long skewMs) {
        var absSkew = Math.abs(skewMs);
        maxClockSkewMs.accumulateAndGet(absSkew, Math::max);
    }

    /**
     * Sets the total entity count.
     *
     * @param count entity count
     */
    public void setTotalEntities(long count) {
        totalEntities.set(count);
    }

    /**
     * Increments the entity count.
     *
     * @param delta amount to add
     */
    public void addEntities(int delta) {
        totalEntities.addAndGet(delta);
    }

    /**
     * Sets the active process count.
     *
     * @param count process count
     */
    public void setActiveProcessCount(int count) {
        activeProcessCount.set(count);
    }

    /**
     * Starts the metrics timer.
     */
    public void startTimer() {
        startTimeMs = System.currentTimeMillis();
    }

    /**
     * Gets the total number of migrations (successful + failed).
     *
     * @return total migration count
     */
    public long getTotalMigrations() {
        return totalMigrations.get();
    }

    /**
     * Gets the number of successful migrations.
     *
     * @return successful migration count
     */
    public long getSuccessfulMigrations() {
        return successfulMigrations.get();
    }

    /**
     * Gets the number of failed migrations.
     *
     * @return failed migration count
     */
    public long getFailedMigrations() {
        return failedMigrations.get();
    }

    /**
     * Gets the total entity count.
     *
     * @return entity count
     */
    public long getTotalEntities() {
        return totalEntities.get();
    }

    /**
     * Gets the active process count.
     *
     * @return process count
     */
    public int getActiveProcessCount() {
        return activeProcessCount.get();
    }

    /**
     * Gets the maximum clock skew observed.
     *
     * @return max clock skew in milliseconds
     */
    public long getMaxClockSkewMs() {
        return maxClockSkewMs.get();
    }

    /**
     * Gets the average migration latency.
     *
     * @return average latency in milliseconds
     */
    public double getAverageLatencyMs() {
        var count = latencyCount.get();
        return count > 0 ? (double) totalLatencyMs.get() / count : 0.0;
    }

    /**
     * Gets the transactions per second (TPS).
     *
     * @return TPS value
     */
    public double getTPS() {
        if (startTimeMs == 0) {
            return 0.0;
        }
        var elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000.0;
        return elapsedSeconds > 0 ? successfulMigrations.get() / elapsedSeconds : 0.0;
    }

    /**
     * Gets the migration success rate.
     *
     * @return success rate as percentage (0-100)
     */
    public double getSuccessRate() {
        var total = totalMigrations.get();
        return total > 0 ? (successfulMigrations.get() * 100.0) / total : 100.0;
    }

    /**
     * Records a process crash.
     *
     * @param processId the process UUID
     */
    public void recordProcessCrash(UUID processId) {
        crashedProcesses.add(processId);
    }

    /**
     * Records a process recovery.
     *
     * @param processId the process UUID
     */
    public void recordProcessRecovery(UUID processId) {
        recoveredProcesses.add(processId);
        crashedProcesses.remove(processId);
    }

    /**
     * Gets the crashed processes.
     *
     * @return set of crashed process UUIDs
     */
    public Set<UUID> getCrashedProcesses() {
        return new HashSet<>(crashedProcesses);
    }

    /**
     * Gets the metrics map.
     *
     * @return metrics map
     */
    public Map<String, Object> getMetrics() {
        return new HashMap<>(metrics);
    }

    /**
     * Resets all metrics.
     */
    public void reset() {
        totalMigrations.set(0);
        successfulMigrations.set(0);
        failedMigrations.set(0);
        totalEntities.set(0);
        maxClockSkewMs.set(0);
        totalLatencyMs.set(0);
        latencyCount.set(0);
        startTimeMs = 0;
        crashedProcesses.clear();
        recoveredProcesses.clear();
        metrics.clear();
    }

    /**
     * Returns a snapshot of current metrics.
     *
     * @return MetricsSnapshot
     */
    public MetricsSnapshot snapshot() {
        return new MetricsSnapshot(
            totalMigrations.get(),
            successfulMigrations.get(),
            failedMigrations.get(),
            totalEntities.get(),
            activeProcessCount.get(),
            maxClockSkewMs.get(),
            getAverageLatencyMs(),
            getTPS(),
            getSuccessRate()
        );
    }

    /**
     * Immutable snapshot of metrics at a point in time.
     */
    public record MetricsSnapshot(
        long totalMigrations,
        long successfulMigrations,
        long failedMigrations,
        long totalEntities,
        int activeProcessCount,
        long maxClockSkewMs,
        double averageLatencyMs,
        double tps,
        double successRate
    ) {
    }
}
