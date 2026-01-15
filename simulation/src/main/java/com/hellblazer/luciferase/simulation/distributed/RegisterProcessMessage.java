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

import java.util.List;
import java.util.UUID;

/**
 * Message to register a process with the coordinator.
 * <p>
 * Sent by a process to the coordinator to register itself and declare
 * which bubbles it hosts.
 * <p>
 * Extends VonMessage for transport compatibility.
 *
 * @param processId UUID of the registering process
 * @param bubbles   List of bubble UUIDs hosted by this process
 * @param timestamp Message creation time
 * @author hal.hildebrand
 */
public record RegisterProcessMessage(
    UUID processId,
    List<UUID> bubbles,
    long timestamp
) implements VonMessage {

    public RegisterProcessMessage(UUID processId, List<UUID> bubbles) {
        this(processId, bubbles, System.currentTimeMillis());
    }
}
