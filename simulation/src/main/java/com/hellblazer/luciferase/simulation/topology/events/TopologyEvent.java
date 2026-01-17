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

import javax.vecmath.Point3f;
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

/**
 * Event fired when a bubble splits into two bubbles.
 *
 * @param eventId        unique event identifier
 * @param timestamp      event timestamp (ms since epoch)
 * @param sourceBubbleId bubble that was split
 * @param newBubbleId    newly created bubble
 * @param entitiesMoved  number of entities moved to new bubble
 * @param success        whether split succeeded
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

/**
 * Event fired when two bubbles merge into one.
 *
 * @param eventId        unique event identifier
 * @param timestamp      event timestamp (ms since epoch)
 * @param sourceBubbleId bubble that was absorbed
 * @param targetBubbleId bubble that absorbed the source
 * @param entitiesMoved  number of entities moved
 * @param success        whether merge succeeded
 */
public record MergeEvent(
    UUID eventId,
    long timestamp,
    UUID sourceBubbleId,
    UUID targetBubbleId,
    int entitiesMoved,
    boolean success
) implements TopologyEvent {
    @Override
    public String eventType() {
        return "merge";
    }
}

/**
 * Event fired when a bubble boundary moves.
 *
 * @param eventId        unique event identifier
 * @param timestamp      event timestamp (ms since epoch)
 * @param bubbleId       bubble that moved
 * @param oldCentroidX   old centroid X coordinate
 * @param oldCentroidY   old centroid Y coordinate
 * @param oldCentroidZ   old centroid Z coordinate
 * @param newCentroidX   new centroid X coordinate
 * @param newCentroidY   new centroid Y coordinate
 * @param newCentroidZ   new centroid Z coordinate
 * @param success        whether move succeeded
 */
public record MoveEvent(
    UUID eventId,
    long timestamp,
    UUID bubbleId,
    float oldCentroidX,
    float oldCentroidY,
    float oldCentroidZ,
    float newCentroidX,
    float newCentroidY,
    float newCentroidZ,
    boolean success
) implements TopologyEvent {
    @Override
    public String eventType() {
        return "move";
    }

    /**
     * Convenience constructor with Point3f.
     */
    public MoveEvent(UUID eventId, long timestamp, UUID bubbleId, Point3f oldCentroid, Point3f newCentroid, boolean success) {
        this(eventId, timestamp, bubbleId,
             oldCentroid.x, oldCentroid.y, oldCentroid.z,
             newCentroid.x, newCentroid.y, newCentroid.z,
             success);
    }
}

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

/**
 * Event fired during consensus voting for topology changes.
 *
 * @param eventId    unique event identifier
 * @param timestamp  event timestamp (ms since epoch)
 * @param proposalId topology proposal being voted on
 * @param voterId    ID of the voter (bubble or process)
 * @param vote       vote cast (approve, reject, abstain)
 * @param quorum     current quorum count
 * @param needed     votes needed for approval
 */
public record ConsensusVoteEvent(
    UUID eventId,
    long timestamp,
    UUID proposalId,
    UUID voterId,
    String vote,
    int quorum,
    int needed
) implements TopologyEvent {
    @Override
    public String eventType() {
        return "consensus_vote";
    }
}
