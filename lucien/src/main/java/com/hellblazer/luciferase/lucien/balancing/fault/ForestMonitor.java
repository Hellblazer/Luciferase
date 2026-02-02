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

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Centralized monitoring aggregator for Forest health and fault tolerance metrics.
 * <p>
 * ForestMonitor provides:
 * <ul>
 *   <li>Unified health snapshots aggregating FaultHandler and Forest metrics</li>
 *   <li>Periodic polling with configurable intervals</li>
 *   <li>Alert callbacks when thresholds are exceeded</li>
 *   <li>Dashboard-friendly data export (Map format)</li>
 *   <li>Historical trend tracking (optional)</li>
 * </ul>
 * <p>
 * <b>Usage Pattern</b>:
 * <pre>{@code
 * var monitor = new ForestMonitor.Builder()
 *     .withFaultHandler(faultHandler)
 *     .withForest(forest)
 *     .withTopology(topology)
 *     .withAlertThresholds(AlertThresholds.defaultThresholds())
 *     .withAlertCallback(snapshot -> logger.warn("Alert: {}", snapshot.toSummary()))
 *     .build();
 *
 * monitor.start(executor, 1, TimeUnit.SECONDS);
 *
 * // Query current health
 * var snapshot = monitor.getHealthSnapshot();
 * System.out.println(snapshot.toSummary());
 *
 * // Export for dashboard
 * var dashboardData = monitor.exportDashboardData();
 * }</pre>
 * <p>
 * Part of F4.2.5 Monitoring & Documentation (bead: Luciferase-u3hz).
 *
 * @author hal.hildebrand
 */
public class ForestMonitor {

    private static final Logger log = LoggerFactory.getLogger(ForestMonitor.class);

    // Data sources
    private final FaultHandler faultHandler;
    private final PartitionTopology topology;
    private final List<Forest<?, ?, ?>> forests;

    // Optional fault-tolerant forest for additional stats
    private final FaultTolerantDistributedForest<?, ?, ?> faultTolerantForest;

    // Configuration
    private final ForestHealthSnapshot.AlertThresholds alertThresholds;
    private final int historySize;
    private final LongSupplier timeSource;

    // Alert callbacks
    private final List<Consumer<ForestHealthSnapshot>> alertCallbacks;

    // State
    private final List<ForestHealthSnapshot> history;
    private volatile ForestHealthSnapshot lastSnapshot;
    private volatile ScheduledFuture<?> pollTask;

    /**
     * Private constructor - use Builder.
     */
    private ForestMonitor(
        FaultHandler faultHandler,
        PartitionTopology topology,
        List<Forest<?, ?, ?>> forests,
        FaultTolerantDistributedForest<?, ?, ?> faultTolerantForest,
        ForestHealthSnapshot.AlertThresholds alertThresholds,
        int historySize,
        LongSupplier timeSource,
        List<Consumer<ForestHealthSnapshot>> alertCallbacks
    ) {
        this.faultHandler = faultHandler;
        this.topology = topology;
        this.forests = new CopyOnWriteArrayList<>(forests);
        this.faultTolerantForest = faultTolerantForest;
        this.alertThresholds = alertThresholds;
        this.historySize = historySize;
        this.timeSource = timeSource;
        this.alertCallbacks = new CopyOnWriteArrayList<>(alertCallbacks);
        this.history = Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * Start periodic health polling.
     *
     * @param executor the scheduler to use for polling
     * @param interval polling interval
     * @param unit time unit for interval
     */
    public void start(ScheduledExecutorService executor, long interval, TimeUnit unit) {
        if (pollTask != null) {
            log.warn("ForestMonitor already started");
            return;
        }

        pollTask = executor.scheduleAtFixedRate(
            this::poll,
            0,
            interval,
            unit
        );

        log.info("ForestMonitor started with {}ms polling interval", unit.toMillis(interval));
    }

    /**
     * Stop periodic polling.
     */
    public void stop() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
            log.info("ForestMonitor stopped");
        }
    }

    /**
     * Poll current health and check thresholds.
     * <p>
     * This method is called automatically when polling is active,
     * but can also be called manually for immediate snapshot.
     */
    public void poll() {
        try {
            var snapshot = captureSnapshot();
            lastSnapshot = snapshot;

            // Add to history
            synchronized (history) {
                history.add(snapshot);
                while (history.size() > historySize) {
                    history.remove(0);
                }
            }

            // Check thresholds and fire alerts
            if (alertThresholds != null && snapshot.exceedsThresholds(alertThresholds)) {
                fireAlerts(snapshot);
            }

            log.trace("Health poll: {}", snapshot.toSummary());

        } catch (Exception e) {
            log.error("Error during health poll: {}", e.getMessage(), e);
        }
    }

    /**
     * Get the most recent health snapshot.
     * <p>
     * If no polling has occurred yet, captures a new snapshot immediately.
     *
     * @return current health snapshot
     */
    public ForestHealthSnapshot getHealthSnapshot() {
        if (lastSnapshot == null) {
            return captureSnapshot();
        }
        return lastSnapshot;
    }

    /**
     * Get historical snapshots for trend analysis.
     *
     * @return unmodifiable list of historical snapshots (oldest first)
     */
    public List<ForestHealthSnapshot> getHistory() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /**
     * Export data in dashboard-friendly format.
     * <p>
     * Returns a Map suitable for JSON serialization or template rendering.
     *
     * @return dashboard data map
     */
    public Map<String, Object> exportDashboardData() {
        var snapshot = getHealthSnapshot();
        var data = new LinkedHashMap<String, Object>();

        // Summary
        data.put("timestamp", snapshot.timestampMs());
        data.put("healthLevel", snapshot.healthLevel().name());
        data.put("summary", snapshot.toSummary());

        // Partition health
        var partitions = new LinkedHashMap<String, Object>();
        partitions.put("total", snapshot.totalPartitions());
        partitions.put("healthy", snapshot.healthyPartitions());
        partitions.put("suspected", snapshot.suspectedPartitions());
        partitions.put("failed", snapshot.failedPartitions());
        partitions.put("quorumMaintained", snapshot.quorumMaintained());
        data.put("partitions", partitions);

        // Forest metrics
        var forest = new LinkedHashMap<String, Object>();
        forest.put("trees", snapshot.totalTrees());
        forest.put("entities", snapshot.totalEntities());
        data.put("forest", forest);

        // Performance metrics
        var performance = new LinkedHashMap<String, Object>();
        performance.put("avgDetectionLatencyMs", snapshot.avgDetectionLatencyMs());
        performance.put("avgRecoveryLatencyMs", snapshot.avgRecoveryLatencyMs());
        performance.put("recoverySuccessRate", String.format("%.1f%%", snapshot.recoverySuccessRate() * 100));
        data.put("performance", performance);

        // Recovery stats
        var recovery = new LinkedHashMap<String, Object>();
        recovery.put("totalFailures", snapshot.totalFailuresDetected());
        recovery.put("totalAttempts", snapshot.totalRecoveriesAttempted());
        recovery.put("totalSucceeded", snapshot.totalRecoveriesSucceeded());
        recovery.put("inRecoveryMode", snapshot.inRecoveryMode());
        data.put("recovery", recovery);

        // Per-partition status (for detailed view)
        var statuses = new LinkedHashMap<String, String>();
        for (var entry : snapshot.partitionStatuses().entrySet()) {
            statuses.put(entry.getKey().toString(), entry.getValue().name());
        }
        data.put("partitionStatuses", statuses);

        return data;
    }

    /**
     * Add an alert callback.
     *
     * @param callback callback to invoke when thresholds are exceeded
     */
    public void addAlertCallback(Consumer<ForestHealthSnapshot> callback) {
        alertCallbacks.add(callback);
    }

    /**
     * Remove an alert callback.
     *
     * @param callback callback to remove
     */
    public void removeAlertCallback(Consumer<ForestHealthSnapshot> callback) {
        alertCallbacks.remove(callback);
    }

    /**
     * Add a forest to monitor.
     *
     * @param forest the forest to add
     */
    public void addForest(Forest<?, ?, ?> forest) {
        forests.add(forest);
    }

    /**
     * Remove a forest from monitoring.
     *
     * @param forest the forest to remove
     */
    public void removeForest(Forest<?, ?, ?> forest) {
        forests.remove(forest);
    }

    // ========== Private Methods ==========

    /**
     * Capture current health state as an immutable snapshot.
     */
    private ForestHealthSnapshot captureSnapshot() {
        long timestamp = timeSource.getAsLong();

        // Collect partition statuses
        var partitionStatuses = new HashMap<UUID, PartitionStatus>();
        int healthyCount = 0;
        int suspectedCount = 0;
        int failedCount = 0;

        for (var rank : topology.activeRanks()) {
            topology.partitionFor(rank).ifPresent(partitionId -> {
                var status = faultHandler.checkHealth(partitionId);
                partitionStatuses.put(partitionId, status);
            });
        }

        for (var status : partitionStatuses.values()) {
            switch (status) {
                case HEALTHY -> healthyCount++;
                case SUSPECTED -> suspectedCount++;
                case FAILED -> failedCount++;
            }
        }

        int totalPartitions = partitionStatuses.size();

        // Check quorum (majority healthy)
        boolean quorumMaintained = healthyCount > totalPartitions / 2;

        // Check recovery mode
        boolean inRecoveryMode = faultTolerantForest != null && faultTolerantForest.isInRecoveryMode();

        // Aggregate forest statistics
        int totalTrees = 0;
        int totalEntities = 0;
        for (var forest : forests) {
            totalTrees += forest.getTreeCount();
            totalEntities += forest.getTotalEntityCount();
        }

        // Get fault metrics
        FaultMetrics metrics = faultHandler.getAggregateMetrics();
        double successRate = metrics.recoveryAttempts() > 0
            ? (double) metrics.successfulRecoveries() / metrics.recoveryAttempts()
            : 1.0;

        // Optionally merge with FaultTolerantForestStats
        long totalFailures = metrics.failureCount();
        long totalAttempts = metrics.recoveryAttempts();
        long totalSucceeded = metrics.successfulRecoveries();

        if (faultTolerantForest != null) {
            var ftStats = faultTolerantForest.getStats();
            totalFailures = Math.max(totalFailures, ftStats.totalFailuresDetected());
            totalAttempts = Math.max(totalAttempts, ftStats.totalRecoveriesAttempted());
            totalSucceeded = Math.max(totalSucceeded, ftStats.totalRecoveriesSucceeded());
        }

        return new ForestHealthSnapshot(
            timestamp,
            totalPartitions,
            healthyCount,
            suspectedCount,
            failedCount,
            quorumMaintained,
            inRecoveryMode,
            totalTrees,
            totalEntities,
            metrics.detectionLatencyMs(),
            metrics.recoveryLatencyMs(),
            totalFailures,
            totalAttempts,
            totalSucceeded,
            successRate,
            Map.copyOf(partitionStatuses)
        );
    }

    /**
     * Fire alert callbacks.
     */
    private void fireAlerts(ForestHealthSnapshot snapshot) {
        log.warn("Health threshold exceeded: {}", snapshot.toSummary());

        for (var callback : alertCallbacks) {
            try {
                callback.accept(snapshot);
            } catch (Exception e) {
                log.error("Alert callback failed: {}", e.getMessage(), e);
            }
        }
    }

    // ========== Builder ==========

    /**
     * Builder for ForestMonitor.
     */
    public static class Builder {
        private FaultHandler faultHandler;
        private PartitionTopology topology;
        private final List<Forest<?, ?, ?>> forests = new ArrayList<>();
        private FaultTolerantDistributedForest<?, ?, ?> faultTolerantForest;
        private ForestHealthSnapshot.AlertThresholds alertThresholds;
        private int historySize = 100;
        private LongSupplier timeSource = System::currentTimeMillis;
        private final List<Consumer<ForestHealthSnapshot>> alertCallbacks = new ArrayList<>();

        /**
         * Set the fault handler (required).
         */
        public Builder withFaultHandler(FaultHandler faultHandler) {
            this.faultHandler = faultHandler;
            return this;
        }

        /**
         * Set the partition topology (required).
         */
        public Builder withTopology(PartitionTopology topology) {
            this.topology = topology;
            return this;
        }

        /**
         * Add a forest to monitor.
         */
        public Builder withForest(Forest<?, ?, ?> forest) {
            this.forests.add(forest);
            return this;
        }

        /**
         * Set the fault-tolerant forest (optional, for additional stats).
         */
        public <K extends SpatialKey<K>, ID extends EntityID, C> Builder withFaultTolerantForest(
            FaultTolerantDistributedForest<K, ID, C> forest
        ) {
            this.faultTolerantForest = forest;
            return this;
        }

        /**
         * Set alert thresholds (optional, enables alerting).
         */
        public Builder withAlertThresholds(ForestHealthSnapshot.AlertThresholds thresholds) {
            this.alertThresholds = thresholds;
            return this;
        }

        /**
         * Add an alert callback.
         */
        public Builder withAlertCallback(Consumer<ForestHealthSnapshot> callback) {
            this.alertCallbacks.add(callback);
            return this;
        }

        /**
         * Set history size for trend tracking (default: 100).
         */
        public Builder withHistorySize(int size) {
            this.historySize = size;
            return this;
        }

        /**
         * Set custom time source for testing.
         */
        public Builder withTimeSource(LongSupplier timeSource) {
            this.timeSource = timeSource;
            return this;
        }

        /**
         * Build the ForestMonitor.
         *
         * @throws NullPointerException if faultHandler or topology is null
         */
        public ForestMonitor build() {
            Objects.requireNonNull(faultHandler, "faultHandler is required");
            Objects.requireNonNull(topology, "topology is required");

            return new ForestMonitor(
                faultHandler,
                topology,
                forests,
                faultTolerantForest,
                alertThresholds,
                historySize,
                timeSource,
                alertCallbacks
            );
        }
    }
}
