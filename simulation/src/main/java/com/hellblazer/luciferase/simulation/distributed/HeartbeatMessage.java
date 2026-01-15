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

import com.hellblazer.luciferase.simulation.von.VonMessage;

import java.util.UUID;

/**
 * Heartbeat message for process liveness monitoring.
 * <p>
 * Sent periodically (every 1000ms) from each process to the coordinator
 * to indicate it is still alive and responsive.
 * <p>
 * Coordinator uses these to detect failed processes (timeout: 3000ms).
 * <p>
 * Extends VonMessage for transport compatibility.
 *
 * @param senderProcessId UUID of the process sending heartbeat
 * @param timestamp       Message creation time
 * @author hal.hildebrand
 */
public record HeartbeatMessage(
    UUID senderProcessId,
    long timestamp
) implements VonMessage {

    public HeartbeatMessage(UUID senderProcessId) {
        this(senderProcessId, System.currentTimeMillis());
    }
}
