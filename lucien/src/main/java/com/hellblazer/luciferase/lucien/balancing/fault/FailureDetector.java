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
import java.util.UUID;

/**
 * Time-based failure detector for monitoring partition heartbeats.
 *
 * <p>Performs background health checks on registered partitions and reports
 * failures to a FaultHandler when timeout thresholds are exceeded. Uses
 * configurable timeouts for suspect and failure states.
 *
 * <p><b>Lifecycle</b>:
 * <ul>
 *   <li>start() - Begin background monitoring</li>
 *   <li>registerPartition(id) - Add partition to monitoring</li>
 *   <li>recordHeartbeat(id) - Reset timeout on heartbeat</li>
 *   <li>stop() - Stop monitoring and clean up</li>
 * </ul>
 *
 * <p><b>Thread-Safe</b>: All operations are thread-safe for concurrent
 * monitoring of multiple partitions.
 */
public interface FailureDetector {

    /**
     * Start background health monitoring.
     *
     * <p>Begins periodic health checks using ScheduledExecutorService.
     * Multiple calls to start are idempotent.
     */
    void start();

    /**
     * Stop health monitoring.
     *
     * <p>Stops the background executor and cleans up resources.
     * Multiple calls to stop are idempotent.
     */
    void stop();

    /**
     * Check if detector is running.
     *
     * @return true if monitoring is active
     */
    boolean isRunning();

    /**
     * Register a partition for monitoring.
     *
     * <p>Initializes heartbeat timestamp to current time.
     *
     * @param partitionId ID of partition to monitor
     * @throws NullPointerException if partitionId is null
     * @throws IllegalStateException if detector is not running
     */
    void registerPartition(UUID partitionId);

    /**
     * Unregister a partition from monitoring.
     *
     * <p>Removes partition from active monitoring set.
     *
     * @param partitionId ID of partition to stop monitoring
     * @throws NullPointerException if partitionId is null
     */
    void unregisterPartition(UUID partitionId);

    /**
     * Record a heartbeat from a partition.
     *
     * <p>Resets the timeout timer for this partition.
     *
     * @param partitionId ID of partition sending heartbeat
     * @throws NullPointerException if partitionId is null
     */
    void recordHeartbeat(UUID partitionId);

    /**
     * Get failure detection configuration.
     *
     * @return current configuration
     */
    FailureDetectionConfig getConfig();

    /**
     * Set the clock for deterministic testing.
     *
     * <p>Allows tests to inject a fixed or controlled clock (e.g., Clock.fixed(),
     * Clock.offset()) for deterministic timing.
     *
     * @param clock the clock to use for timeout calculations
     * @throws NullPointerException if clock is null
     */
    void setClock(Clock clock);
}
