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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.von.VonMessage;

import java.util.Map;
import java.util.UUID;

/**
 * Message broadcasting topology updates from coordinator to all processes.
 * <p>
 * Sent by coordinator when:
 * - A new process registers
 * - A process unregisters
 * - Bubbles are added/removed
 * <p>
 * Extends VonMessage for transport compatibility.
 *
 * @param coordinatorId  UUID of the coordinator sending the update
 * @param topology       Map of ProcessId → List of bubble UUIDs
 * @param sequenceNumber Monotonically increasing sequence number (Phase 6B2)
 * @param timestamp      Message creation time
 * @author hal.hildebrand
 */
public record TopologyUpdateMessage(
    UUID coordinatorId,
    Map<UUID, java.util.List<UUID>> topology,
    long sequenceNumber,
    long timestamp
) implements VonMessage {

    public TopologyUpdateMessage(UUID coordinatorId, Map<UUID, java.util.List<UUID>> topology, long sequenceNumber) {
        this(coordinatorId, topology, sequenceNumber, Clock.system().currentTimeMillis());
    }
}
