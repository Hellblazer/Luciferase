/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.events;

import java.util.UUID;

/**
 * Event fired when a bubble splits into two bubbles.
 *
 * @param eventId        unique event identifier
 * @param timestamp      event timestamp (ms since epoch)
 * @param sourceBubbleId bubble that was split
 * @param newBubbleId    newly created bubble
 * @param entitiesMoved  number of entities moved to new bubble
 * @param success        whether split succeeded
 *
 * @author hal.hildebrand
 */
public record SplitEvent(
    UUID eventId,
    long timestamp,
    UUID sourceBubbleId,
    UUID newBubbleId,
    int entitiesMoved,
    boolean success
) implements TopologyEvent {
    @Override
    public String eventType() {
        return "split";
    }
}
