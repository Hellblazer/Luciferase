/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.persistence;

import com.hellblazer.luciferase.common.time.Clock;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata for a recovery checkpoint.
 * Tracks the sequence number and timestamp of a checkpoint for recovery purposes.
 *
 * @author hal.hildebrand
 */
public record CheckpointMetadata(long sequenceNumber, Instant timestamp) {

    public CheckpointMetadata {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be non-negative");
        }
    }

    /**
     * Create a new checkpoint at the system clock's current time.
     * Convenience overload; prefer {@link #now(long, Clock)} in production paths
     * that hold an injected clock so timestamps remain deterministic in tests.
     *
     * @param sequenceNumber Sequence number for this checkpoint
     * @return New checkpoint metadata
     */
    public static CheckpointMetadata now(long sequenceNumber) {
        return now(sequenceNumber, Clock.system());
    }

    /**
     * Create a new checkpoint at the supplied clock's current time.
     *
     * @param sequenceNumber Sequence number for this checkpoint
     * @param clock          Injected clock providing the timestamp (deterministic in tests)
     * @return New checkpoint metadata
     */
    public static CheckpointMetadata now(long sequenceNumber, Clock clock) {
        return new CheckpointMetadata(sequenceNumber, Instant.ofEpochMilli(clock.currentTimeMillis()));
    }
}
