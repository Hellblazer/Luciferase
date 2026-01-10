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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * State recovered from write-ahead log during crash recovery.
 * Contains the recovered events and metadata about the recovery process.
 *
 * @author hal.hildebrand
 */
public record RecoveredState(
    CheckpointMetadata checkpoint,
    List<Map<String, Object>> events,
    int totalEventsReplayed,
    int skippedEvents
) {

    public RecoveredState {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Objects.requireNonNull(events, "events must not be null");
        if (totalEventsReplayed < 0) {
            throw new IllegalArgumentException("totalEventsReplayed must be non-negative");
        }
        if (skippedEvents < 0) {
            throw new IllegalArgumentException("skippedEvents must be non-negative");
        }
    }

    /**
     * Create empty recovered state (no events to replay).
     *
     * @param checkpoint Checkpoint metadata
     * @return Empty recovered state
     */
    public static RecoveredState empty(CheckpointMetadata checkpoint) {
        return new RecoveredState(checkpoint, List.of(), 0, 0);
    }
}
