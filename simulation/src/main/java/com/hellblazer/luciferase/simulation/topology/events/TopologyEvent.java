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
 * Base interface for topology change events.
 * <p>
 * Topology events are generated during dynamic topology adaptation and
 * can be streamed to visualization clients for real-time monitoring.
 * <p>
 * Event Types:
 * <ul>
 *   <li>{@link SplitEvent} - Bubble split operation</li>
 *   <li>{@link MergeEvent} - Bubble merge operation</li>
 *   <li>{@link MoveEvent} - Bubble boundary move</li>
 *   <li>{@link DensityStateChangeEvent} - Bubble density state transition</li>
 *   <li>{@link ConsensusVoteEvent} - Topology consensus voting</li>
 * </ul>
 * <p>
 * Interactive Visualization Demo Enhancement
 *
 * @author hal.hildebrand
 */
public sealed interface TopologyEvent permits SplitEvent, MergeEvent, MoveEvent, DensityStateChangeEvent, ConsensusVoteEvent {

    /**
     * Get the event type name for JSON serialization.
     *
     * @return event type (split, merge, move, density_state_change, consensus_vote)
     */
    String eventType();

    /**
     * Get the event timestamp (milliseconds since epoch).
     *
     * @return timestamp
     */
    long timestamp();

    /**
     * Get the event ID (unique per event).
     *
     * @return event UUID
     */
    UUID eventId();
}
