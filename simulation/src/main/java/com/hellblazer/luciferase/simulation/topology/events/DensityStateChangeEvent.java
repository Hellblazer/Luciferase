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

import com.hellblazer.luciferase.simulation.topology.metrics.DensityState;

import java.util.UUID;

/**
 * Event fired when a bubble's density state changes.
 * <p>
 * Density states: NORMAL, APPROACHING_SPLIT, NEEDS_SPLIT,
 * APPROACHING_MERGE, NEEDS_MERGE.
 *
 * @param eventId      unique event identifier
 * @param timestamp    event timestamp (ms since epoch)
 * @param bubbleId     bubble with state change
 * @param oldState     previous density state
 * @param newState     new density state
 * @param entityCount  current entity count
 * @param densityRatio entity count / split threshold ratio
 *
 * @author hal.hildebrand
 */
public record DensityStateChangeEvent(
    UUID eventId,
    long timestamp,
    UUID bubbleId,
    DensityState oldState,
    DensityState newState,
    int entityCount,
    float densityRatio
) implements TopologyEvent {
    @Override
    public String eventType() {
        return "density_state_change";
    }
}
