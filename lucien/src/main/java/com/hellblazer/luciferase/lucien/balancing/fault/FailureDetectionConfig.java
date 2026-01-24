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

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for failure detection timeouts and check intervals.
 *
 * <p>Configures timeouts for detecting partition failures via heartbeat analysis:
 * - Suspect timeout: Time after missing heartbeat to mark as SUSPECTED
 * - Failure timeout: Time after missing heartbeat to mark as FAILED
 * - Check interval: Frequency of background health checks
 */
public record FailureDetectionConfig(
    Duration heartbeatInterval,     // Default: 500ms
    Duration suspectTimeout,        // Default: 2000ms (4x heartbeat)
    Duration failureTimeout,        // Default: 5000ms (10x heartbeat)
    int checkIntervalMs             // Default: 100ms
) {

    /**
     * Default configuration for failure detection.
     *
     * @return config with reasonable defaults
     */
    public static FailureDetectionConfig defaultConfig() {
        return new FailureDetectionConfig(
            Duration.ofMillis(500),  // heartbeatInterval
            Duration.ofMillis(2000), // suspectTimeout (4x)
            Duration.ofMillis(5000), // failureTimeout (10x)
            100                      // checkIntervalMs
        );
    }

    /**
     * Create builder-style copy with different heartbeat interval.
     *
     * @param interval new heartbeat interval
     * @return new config with updated interval
     */
    public FailureDetectionConfig withHeartbeatInterval(Duration interval) {
        return new FailureDetectionConfig(interval, suspectTimeout, failureTimeout,
                                         checkIntervalMs);
    }

    /**
     * Create builder-style copy with different suspect timeout.
     *
     * @param timeout new suspect timeout
     * @return new config with updated timeout
     */
    public FailureDetectionConfig withSuspectTimeout(Duration timeout) {
        return new FailureDetectionConfig(heartbeatInterval, timeout, failureTimeout,
                                         checkIntervalMs);
    }

    /**
     * Create builder-style copy with different failure timeout.
     *
     * @param timeout new failure timeout
     * @return new config with updated timeout
     */
    public FailureDetectionConfig withFailureTimeout(Duration timeout) {
        return new FailureDetectionConfig(heartbeatInterval, suspectTimeout, timeout,
                                         checkIntervalMs);
    }

    /**
     * Create builder-style copy with different check interval.
     *
     * @param intervalMs new check interval in milliseconds
     * @return new config with updated interval
     */
    public FailureDetectionConfig withCheckIntervalMs(int intervalMs) {
        return new FailureDetectionConfig(heartbeatInterval, suspectTimeout, failureTimeout,
                                         intervalMs);
    }

    /**
     * Construct with validation.
     *
     * @param heartbeatInterval interval between heartbeats
     * @param suspectTimeout timeout to mark as suspected
     * @param failureTimeout timeout to mark as failed
     * @param checkIntervalMs background check frequency
     * @throws NullPointerException if durations are null
     * @throws IllegalArgumentException if durations are negative or out of order
     */
    public FailureDetectionConfig {
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval must not be null");
        Objects.requireNonNull(suspectTimeout, "suspectTimeout must not be null");
        Objects.requireNonNull(failureTimeout, "failureTimeout must not be null");

        if (heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be non-negative");
        }
        if (suspectTimeout.isNegative()) {
            throw new IllegalArgumentException("suspectTimeout must be non-negative");
        }
        if (failureTimeout.isNegative()) {
            throw new IllegalArgumentException("failureTimeout must be non-negative");
        }
        if (checkIntervalMs <= 0) {
            throw new IllegalArgumentException("checkIntervalMs must be positive");
        }

        // Suspect timeout should be > heartbeat interval
        if (suspectTimeout.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException(
                "suspectTimeout must be greater than heartbeatInterval");
        }

        // Failure timeout should be > suspect timeout
        if (failureTimeout.compareTo(suspectTimeout) <= 0) {
            throw new IllegalArgumentException(
                "failureTimeout must be greater than suspectTimeout");
        }
    }
}
