/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.distributed;

import java.util.List;
import java.util.UUID;

/**
 * Metadata for a registered process in the distributed topology.
 * <p>
 * Immutable record containing process identification, bubble assignments,
 * and readiness state.
 * <p>
 * Phase 4.1.3: Heartbeat tracking removed (use Fireflies view changes for failure detection)
 *
 * @param processId UUID of the process
 * @param bubbles   List of bubble UUIDs hosted by this process
 * @param ready     Whether process is ready for simulation ticks
 * @author hal.hildebrand
 */
public record ProcessMetadata(
    UUID processId,
    List<UUID> bubbles,
    boolean ready
) {
    public ProcessMetadata {
        if (processId == null) {
            throw new IllegalArgumentException("processId cannot be null");
        }
        if (bubbles == null) {
            throw new IllegalArgumentException("bubbles cannot be null");
        }
    }

    /**
     * Create metadata for a newly registered process.
     *
     * @param processId UUID of the process
     * @param bubbles   List of bubbles hosted by this process
     * @return ProcessMetadata with ready=true
     */
    public static ProcessMetadata create(UUID processId, List<UUID> bubbles) {
        return new ProcessMetadata(processId, List.copyOf(bubbles), true);
    }

    /**
     * Update bubble list.
     *
     * @param newBubbles New bubble list
     * @return New ProcessMetadata with updated bubbles
     */
    public ProcessMetadata withBubbles(List<UUID> newBubbles) {
        return new ProcessMetadata(processId, List.copyOf(newBubbles), ready);
    }
}
