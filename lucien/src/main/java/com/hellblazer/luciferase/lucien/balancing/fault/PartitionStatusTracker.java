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
import java.util.List;
import java.util.UUID;

/**
 * Tracks partition status transitions with timestamps for diagnostic analysis.
 *
 * <p>Extends FaultHandler with time-based health analysis, maintaining a complete
 * history of status changes for each partition. Enables calculation of staleness,
 * transition frequency, and recovery patterns.
 *
 * <p><b>Thread-Safe</b>: All operations are thread-safe for concurrent partition
 * monitoring.
 */
public interface PartitionStatusTracker extends FaultHandler {

    /**
     * Get the time elapsed since partition was last marked healthy.
     *
     * @param partitionId ID of the partition
     * @return Duration since last healthy state, or current time if never marked healthy
     */
    Duration getTimeSinceLastHealthy(UUID partitionId);

    /**
     * Get complete status transition history for a partition.
     *
     * <p>History entries are ordered chronologically from earliest to latest.
     *
     * @param partitionId ID of the partition
     * @return List of status history entries, empty if no history
     */
    List<StatusHistoryEntry> getStatusHistory(UUID partitionId);

    /**
     * Check if a partition is stale (no updates within threshold).
     *
     * <p>Staleness is determined by comparing time since last health update
     * against the provided threshold.
     *
     * @param partitionId ID of the partition
     * @param threshold duration threshold for staleness
     * @return true if time since last healthy exceeds threshold
     */
    boolean isStale(UUID partitionId, Duration threshold);

    /**
     * Get total count of status transitions for a partition.
     *
     * <p>Includes transitions to HEALTHY, SUSPECTED, FAILED, RECOVERING, and DEGRADED.
     *
     * @param partitionId ID of the partition
     * @return number of status transitions recorded
     */
    int getTransitionCount(UUID partitionId);

    /**
     * Set the clock for deterministic testing.
     *
     * <p>Allows tests to inject a fixed or controlled clock (e.g., Clock.fixed(),
     * Clock.offset()) for deterministic timestamp generation.
     *
     * @param clock the clock to use for timestamp generation
     * @throws NullPointerException if clock is null
     */
    void setClock(Clock clock);

    /**
     * Record of a single partition status transition.
     *
     * @param status the status after this transition
     * @param timestamp when the transition occurred
     * @param reason description of why the transition occurred
     */
    record StatusHistoryEntry(
        PartitionStatus status,
        Instant timestamp,
        String reason
    ) {}
}
