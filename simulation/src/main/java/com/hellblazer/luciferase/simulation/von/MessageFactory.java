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

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.von.Message.*;
import javafx.geometry.Point3D;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Factory for creating Message records with injected clock for deterministic testing.
 * <p>
 * Enables Clock injection for immutable Message records. Production code uses
 * {@link #system()} factory for default System clock behavior. Test code can inject
 * a {@link com.hellblazer.luciferase.simulation.distributed.integration.TestClock}
 * for deterministic time control.
 * <p>
 * Usage:
 * <pre>{@code
 * // Production
 * var factory = MessageFactory.system();
 * var msg = factory.createJoinRequest(id, position, bounds);
 *
 * // Testing
 * var testClock = new TestClock(1000L);
 * var factory = new MessageFactory(testClock);
 * testClock.advance(500);
 * var msg = factory.createMove(id, newPos, newBounds); // timestamp = 1500
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class MessageFactory {
    private final Clock clock;

    /**
     * Creates a factory with the specified clock.
     *
     * @param clock the clock to use for message timestamps
     * @throws NullPointerException if clock is null
     */
    public MessageFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates a factory using the system clock (production default).
     *
     * @return factory with system clock
     */
    public static MessageFactory system() {
        return new MessageFactory(Clock.system());
    }

    /**
     * Creates a JoinRequest message.
     *
     * @param joinerId UUID of the joining node
     * @param position desired position in the network
     * @param bounds   initial spatial bounds
     * @return new JoinRequest with current timestamp
     */
    public JoinRequest createJoinRequest(UUID joinerId, Point3D position, BubbleBounds bounds) {
        return new JoinRequest(joinerId, position, bounds, clock.currentTimeMillis());
    }

    /**
     * Creates a JoinResponse message.
     *
     * @param acceptorId UUID of the accepting node
     * @param neighbors  set of neighbor information for the joiner
     * @return new JoinResponse with current timestamp
     */
    public JoinResponse createJoinResponse(UUID acceptorId, Set<NeighborInfo> neighbors) {
        return new JoinResponse(acceptorId, neighbors, clock.currentTimeMillis());
    }

    /**
     * Creates a Move message.
     *
     * @param nodeId      UUID of the moving node
     * @param newPosition new position after movement
     * @param newBounds   new bounds after movement
     * @return new Move with current timestamp
     */
    public Move createMove(UUID nodeId, Point3D newPosition, BubbleBounds newBounds) {
        return new Move(nodeId, newPosition, newBounds, clock.currentTimeMillis());
    }

    /**
     * Creates a Leave message.
     *
     * @param nodeId UUID of the leaving node
     * @return new Leave with current timestamp
     */
    public Leave createLeave(UUID nodeId) {
        return new Leave(nodeId, clock.currentTimeMillis());
    }

    /**
     * Creates a GhostSync message.
     *
     * @param sourceBubbleId UUID of the source bubble
     * @param ghosts         list of transport ghost entities to sync
     * @param bucket         simulation bucket for temporal ordering
     * @return new GhostSync with current timestamp
     */
    public GhostSync createGhostSync(UUID sourceBubbleId, List<TransportGhost> ghosts, long bucket) {
        return new GhostSync(sourceBubbleId, ghosts, bucket, clock.currentTimeMillis());
    }

    /**
     * Creates an Ack message.
     *
     * @param ackFor   UUID of the message being acknowledged
     * @param senderId UUID of the acknowledging node
     * @return new Ack with current timestamp
     */
    public Ack createAck(UUID ackFor, UUID senderId) {
        return new Ack(ackFor, senderId, clock.currentTimeMillis());
    }

    /**
     * Creates a Query message.
     *
     * @param senderId  UUID of the querying node
     * @param targetId  UUID of the target bubble
     * @param queryType type of query (e.g., "position", "neighbors")
     * @return new Query with generated queryId and current timestamp
     */
    public Query createQuery(UUID senderId, UUID targetId, String queryType) {
        return new Query(UUID.randomUUID(), senderId, targetId, queryType, clock.currentTimeMillis());
    }

    /**
     * Creates a QueryResponse message.
     *
     * @param queryId      UUID correlating to original query
     * @param responderId  UUID of the responding node
     * @param responseData serialized response data (JSON format)
     * @return new QueryResponse with current timestamp
     */
    public QueryResponse createQueryResponse(UUID queryId, UUID responderId, String responseData) {
        return new QueryResponse(queryId, responderId, responseData, clock.currentTimeMillis());
    }
}
