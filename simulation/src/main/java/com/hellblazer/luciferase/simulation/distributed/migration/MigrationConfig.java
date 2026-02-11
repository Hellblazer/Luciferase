/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

/**
 * Configuration for CrossProcessMigration timeouts and retry behavior (Luciferase-65qu).
 * <p>
 * Provides three profiles optimized for different deployment scenarios:
 * <ul>
 *   <li><b>defaults()</b>: Balanced for typical LAN deployments (100ms phases, 300ms total)</li>
 *   <li><b>forHighLatency()</b>: Relaxed for WAN or slow storage (500ms phases, 1500ms total)</li>
 *   <li><b>forLowLatency()</b>: Aggressive for fast local deployments (50ms phases, 150ms total)</li>
 * </ul>
 * <p>
 * Validation ensures:
 * - All timeouts are positive
 * - Total timeout >= 3 × phase timeout (room for prepare + commit + abort)
 * - Lock timeout < phase timeout (lock must resolve before phase times out)
 * - Lock retry interval > 0
 * - Max lock retries > 0
 * <p>
 * Example usage:
 * <pre>
 * // Use defaults for typical deployment
 * var migration = new CrossProcessMigration(dedup, metrics, MigrationConfig.defaults());
 *
 * // Use high-latency profile for WAN deployment
 * var migration = new CrossProcessMigration(dedup, metrics, MigrationConfig.forHighLatency());
 *
 * // Custom config for specific requirements
 * var config = new MigrationConfig(200, 600, 100, 10_000_000, 20);
 * var migration = new CrossProcessMigration(dedup, metrics, config);
 * </pre>
 *
 * @param phaseTimeoutMs      Timeout for each migration phase (prepare/commit/abort) in milliseconds
 * @param totalTimeoutMs      Total timeout for entire migration in milliseconds
 * @param lockTimeoutMs       Timeout for acquiring entity migration lock in milliseconds
 * @param lockRetryIntervalNs Retry interval for lock acquisition attempts in nanoseconds
 * @param maxLockRetries      Maximum number of lock acquisition retry attempts
 * @author hal.hildebrand
 */
public record MigrationConfig(
    long phaseTimeoutMs,
    long totalTimeoutMs,
    long lockTimeoutMs,
    long lockRetryIntervalNs,
    int maxLockRetries
) {

    /**
     * Compact constructor with validation (Luciferase-65qu).
     * Enforces timeout constraints and relationship invariants.
     *
     * @throws IllegalArgumentException if any validation constraint is violated
     */
    public MigrationConfig {
        // All timeouts must be positive
        if (phaseTimeoutMs <= 0) {
            throw new IllegalArgumentException("phaseTimeoutMs must be > 0, got: " + phaseTimeoutMs);
        }
        if (totalTimeoutMs <= 0) {
            throw new IllegalArgumentException("totalTimeoutMs must be > 0, got: " + totalTimeoutMs);
        }
        if (lockTimeoutMs <= 0) {
            throw new IllegalArgumentException("lockTimeoutMs must be > 0, got: " + lockTimeoutMs);
        }
        if (lockRetryIntervalNs <= 0) {
            throw new IllegalArgumentException("lockRetryIntervalNs must be > 0, got: " + lockRetryIntervalNs);
        }
        if (maxLockRetries <= 0) {
            throw new IllegalArgumentException("maxLockRetries must be > 0, got: " + maxLockRetries);
        }

        // Total timeout must accommodate all three phases (prepare + commit + abort)
        if (totalTimeoutMs < phaseTimeoutMs * 3) {
            throw new IllegalArgumentException(
                String.format("totalTimeoutMs (%d) must be >= 3 × phaseTimeoutMs (%d) to accommodate prepare+commit+abort",
                             totalTimeoutMs, phaseTimeoutMs));
        }

        // Lock timeout must be less than phase timeout to avoid deadlock
        if (lockTimeoutMs >= phaseTimeoutMs) {
            throw new IllegalArgumentException(
                String.format("lockTimeoutMs (%d) must be < phaseTimeoutMs (%d) to avoid phase timeout during lock wait",
                             lockTimeoutMs, phaseTimeoutMs));
        }
    }

    /**
     * Default configuration for typical LAN deployments.
     * <p>
     * Profile characteristics:
     * - Phase timeout: 100ms (suitable for local bubble operations)
     * - Total timeout: 300ms (prepare + commit + abort)
     * - Lock timeout: 50ms (fast conflict resolution)
     * - Lock retry: 5ms interval, 10 retries (50ms total lock wait)
     *
     * @return Configuration optimized for LAN with typical network and storage latency
     */
    public static MigrationConfig defaults() {
        return new MigrationConfig(
            100,        // phaseTimeoutMs
            300,        // totalTimeoutMs
            50,         // lockTimeoutMs
            5_000_000,  // lockRetryIntervalNs (5ms)
            10          // maxLockRetries
        );
    }

    /**
     * High-latency configuration for WAN deployments or slow storage.
     * <p>
     * Profile characteristics:
     * - Phase timeout: 500ms (accommodates high network latency)
     * - Total timeout: 1500ms (prepare + commit + abort with headroom)
     * - Lock timeout: 250ms (tolerates slow lock acquisition)
     * - Lock retry: 10ms interval, 25 retries (250ms total lock wait)
     * <p>
     * Use this profile when:
     * - Bubbles are distributed across WAN links (>50ms RTT)
     * - Storage systems have high latency (NFS, cloud storage)
     * - Observing frequent timeout failures with defaults()
     *
     * @return Configuration optimized for high-latency environments
     */
    public static MigrationConfig forHighLatency() {
        return new MigrationConfig(
            500,         // phaseTimeoutMs (5x defaults)
            1500,        // totalTimeoutMs (5x defaults)
            250,         // lockTimeoutMs (5x defaults)
            10_000_000,  // lockRetryIntervalNs (10ms)
            25           // maxLockRetries (2.5x defaults)
        );
    }

    /**
     * Low-latency configuration for fast local deployments.
     * <p>
     * Profile characteristics:
     * - Phase timeout: 50ms (assumes low network and storage latency)
     * - Total timeout: 150ms (prepare + commit + abort)
     * - Lock timeout: 25ms (fast conflict resolution)
     * - Lock retry: 2.5ms interval, 10 retries (25ms total lock wait)
     * <p>
     * Use this profile when:
     * - All bubbles run on same host or LAN (<5ms RTT)
     * - Storage is local SSD or in-memory
     * - Need to minimize migration latency for responsive simulations
     * <p>
     * WARNING: May cause false timeout failures if actual latency exceeds assumptions.
     *
     * @return Configuration optimized for low-latency environments
     */
    public static MigrationConfig forLowLatency() {
        return new MigrationConfig(
            50,         // phaseTimeoutMs (0.5x defaults)
            150,        // totalTimeoutMs (0.5x defaults)
            25,         // lockTimeoutMs (0.5x defaults)
            2_500_000,  // lockRetryIntervalNs (2.5ms)
            10          // maxLockRetries (same as defaults)
        );
    }

    @Override
    public String toString() {
        return String.format(
            "MigrationConfig{phase=%dms, total=%dms, lock=%dms, retry=%dns×%d}",
            phaseTimeoutMs, totalTimeoutMs, lockTimeoutMs, lockRetryIntervalNs, maxLockRetries
        );
    }
}
