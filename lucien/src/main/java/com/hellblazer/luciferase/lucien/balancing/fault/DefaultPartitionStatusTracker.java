/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of PartitionStatusTracker.
 *
 * <p>Extends SimpleFaultHandler with time-based health tracking and status history.
 * Maintains a complete audit trail of partition status transitions for diagnostic
 * analysis and recovery planning.
 *
 * <p><b>Thread-Safe</b>: Uses ConcurrentHashMap and CopyOnWriteArrayList for
 * thread-safe concurrent access.
 */
public class DefaultPartitionStatusTracker extends SimpleFaultHandler
        implements PartitionStatusTracker {

    private volatile Clock clock = Clock.systemDefaultZone();

    // Per-partition tracking
    private final Map<UUID, List<StatusHistoryEntry>> statusHistory =
            new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastHealthyTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> transitionCounts = new ConcurrentHashMap<>();

    /**
     * Create a new DefaultPartitionStatusTracker with default configuration.
     */
    public DefaultPartitionStatusTracker() {
        super(FaultConfiguration.defaultConfig());
    }

    /**
     * Create a new DefaultPartitionStatusTracker with specific configuration.
     *
     * @param config fault detection configuration
     */
    public DefaultPartitionStatusTracker(FaultConfiguration config) {
        super(config);
    }

    @Override
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Duration getTimeSinceLastHealthy(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        var lastHealthy = lastHealthyTime.get(partitionId);
        if (lastHealthy == null) {
            // Never marked healthy, return 0
            return Duration.ZERO;
        }

        var now = clock.instant();
        return Duration.between(lastHealthy, now);
    }

    @Override
    public List<StatusHistoryEntry> getStatusHistory(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        return new ArrayList<>(statusHistory.getOrDefault(partitionId, List.of()));
    }

    @Override
    public boolean isStale(UUID partitionId, Duration threshold) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(threshold, "threshold must not be null");

        var timeSince = getTimeSinceLastHealthy(partitionId);
        return timeSince.compareTo(threshold) > 0;
    }

    @Override
    public int getTransitionCount(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        return transitionCounts.getOrDefault(partitionId, 0);
    }

    @Override
    public void markHealthy(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        super.markHealthy(partitionId);

        // Record timestamp
        var now = clock.instant();
        lastHealthyTime.put(partitionId, now);

        // Record in history
        recordStatusTransition(partitionId, PartitionStatus.HEALTHY, "Marked healthy");
    }

    @Override
    public void reportHeartbeatFailure(UUID partitionId, UUID nodeId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        super.reportHeartbeatFailure(partitionId, nodeId);
        recordStatusTransition(partitionId, PartitionStatus.SUSPECTED, "Heartbeat failure for node " + nodeId);
    }

    @Override
    public void reportBarrierTimeout(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        super.reportBarrierTimeout(partitionId);
        recordStatusTransition(partitionId, PartitionStatus.FAILED, "Barrier timeout");
    }

    @Override
    public void reportSyncFailure(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        super.reportSyncFailure(partitionId);
        recordStatusTransition(partitionId, PartitionStatus.FAILED, "Sync failure");
    }

    /**
     * Record a status transition in history and update transition count.
     *
     * @param partitionId ID of the partition
     * @param status new status
     * @param reason description of reason for transition
     */
    private void recordStatusTransition(UUID partitionId, PartitionStatus status,
                                        String reason) {
        var now = clock.instant();
        var entry = new StatusHistoryEntry(status, now, reason);

        // Add to history
        statusHistory.computeIfAbsent(partitionId, k -> new CopyOnWriteArrayList<>())
                .add(entry);

        // Increment transition count
        transitionCounts.compute(partitionId, (k, v) -> (v == null ? 1 : v + 1));
    }
}
