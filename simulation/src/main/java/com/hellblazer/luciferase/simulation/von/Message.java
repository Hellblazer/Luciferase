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
import javafx.geometry.Point3D;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sealed message types for VON P2P communication.
 * <p>
 * In v4.0 architecture, all VON communication after JOIN is point-to-point
 * between neighbors (NOT broadcast). These messages are sent directly to
 * specific neighbors via Transport.
 * <p>
 * Message Types:
 * <ul>
 *   <li>{@link JoinRequest} - Request to join the network at a position</li>
 *   <li>{@link JoinResponse} - Response with neighbor information</li>
 *   <li>{@link Move} - Notification of position/bounds change</li>
 *   <li>{@link Leave} - Notification of graceful departure</li>
 *   <li>{@link GhostSync} - Ghost entity synchronization</li>
 *   <li>{@link Ack} - Acknowledgment of received message</li>
 * </ul>
 * <p>
 * Phase 6B Extensions (non-sealed for distributed messages):
 * - RegisterProcessMessage
 * - TopologyUpdateMessage
 * - HeartbeatMessage
 *
 * @author hal.hildebrand
 */
public sealed interface Message
    permits Message.JoinRequest, Message.JoinResponse, Message.Move, Message.Leave,
            Message.GhostSync, Message.Ack, Message.Query, Message.QueryResponse,
            MigrationProtocolMessages {

    /**
     * Request to join the VON at a specific position.
     * <p>
     * Sent to the acceptor (node responsible for the region) to initiate JOIN protocol.
     *
     * @param joinerId  UUID of the joining node
     * @param position  Desired position in the network
     * @param bounds    Initial spatial bounds
     * @param timestamp Message creation time
     */
    record JoinRequest(UUID joinerId, Point3D position, BubbleBounds bounds, long timestamp) implements Message {
        // Compact constructor removed - use MessageFactory instead
    }

    /**
     * Response to a JOIN request with neighbor information.
     * <p>
     * Contains the set of neighbors the joiner should connect to.
     *
     * @param acceptorId UUID of the accepting node
     * @param neighbors  Set of neighbor information for the joiner
     * @param timestamp  Message creation time
     */
    record JoinResponse(UUID acceptorId, Set<NeighborInfo> neighbors, long timestamp) implements Message {
        // Compact constructor removed - use MessageFactory instead
    }

    /**
     * Notification of a node's position or bounds change.
     * <p>
     * Sent to all neighbors when this node moves.
     *
     * @param nodeId      UUID of the moving node
     * @param newPosition New position after movement
     * @param newBounds   New bounds after movement
     * @param timestamp   Message creation time
     */
    record Move(UUID nodeId, Point3D newPosition, BubbleBounds newBounds, long timestamp) implements Message {
        // Compact constructor removed - use MessageFactory instead
    }

    /**
     * Notification of a node's graceful departure.
     * <p>
     * Sent to all neighbors when this node is leaving the network.
     *
     * @param nodeId    UUID of the leaving node
     * @param timestamp Message creation time
     */
    record Leave(UUID nodeId, long timestamp) implements Message {
        // Compact constructor removed - use MessageFactory instead
    }

    /**
     * Ghost entity synchronization between neighbors.
     * <p>
     * Sent to neighboring bubbles to sync ghost entities.
     *
     * @param sourceBubbleId UUID of the source bubble
     * @param ghosts         List of transport ghost entities to sync
     * @param bucket         Simulation bucket for temporal ordering
     * @param timestamp      Message creation time
     */
    record GhostSync(UUID sourceBubbleId, List<TransportGhost> ghosts, long bucket,
                     long timestamp) implements Message {
        // Compact constructor removed - use MessageFactory instead
    }

    /**
     * Transport-friendly ghost entity representation.
     * <p>
     * Serializable format for P2P ghost synchronization carrying all
     * SimulationGhostEntity data needed for reconstruction on the receiver.
     * <p>
     * <b>Serialization Contract:</b>
     * <ul>
     *   <li>All fields use primitive types or standard Java types (String, Point3f)</li>
     *   <li>The {@code contentValue} field stores entity content as a String representation</li>
     *   <li>Simple content types (String, primitives, enums) can be serialized directly</li>
     *   <li>The {@code contentClass} field identifies the runtime type for deserialization</li>
     * </ul>
     * <p>
     * <b>Supported Content Types:</b>
     * <ul>
     *   <li>Primitive types: Integer, Long, Double, Boolean (as String)</li>
     *   <li>String values: Direct storage in contentValue</li>
     *   <li>Enum types: Enum.name() stored as String</li>
     *   <li>Simple value objects: toString() representation (if parseable)</li>
     * </ul>
     * <p>
     * <b>Limitations:</b>
     * <ul>
     *   <li>Complex objects with nested structures are NOT supported directly</li>
     *   <li>Binary data cannot be efficiently encoded as String</li>
     *   <li>Collections and arrays require custom serialization logic</li>
     *   <li>Loss of type information for generic types (e.g., {@code List<String>})</li>
     *   <li>No built-in support for circular references or object graphs</li>
     * </ul>
     * <p>
     * <b>Recommendations for Rich Content:</b>
     * <ul>
     *   <li>For complex objects: Implement custom serialization to JSON/XML in contentValue</li>
     *   <li>For binary data: Use Base64 encoding or external blob storage with references</li>
     *   <li>For large payloads: Consider splitting across multiple ghost entities</li>
     *   <li>For structured data: Use Protocol Buffers or similar serialization frameworks</li>
     *   <li>Document content serialization format in the entity's content class</li>
     * </ul>
     * <p>
     * <b>Example Usage:</b>
     * <pre>{@code
     * // Simple content (String)
     * var ghost = new TransportGhost("entity-1", position, "String", "hello", treeId, epoch, version, timestamp);
     *
     * // Enum content
     * var ghost = new TransportGhost("entity-2", position, "EntityType", EntityType.PLAYER.name(), treeId, epoch, version, timestamp);
     *
     * // Complex content (JSON serialization)
     * var json = objectMapper.writeValueAsString(complexObject);
     * var ghost = new TransportGhost("entity-3", position, "ComplexEntity", json, treeId, epoch, version, timestamp);
     * }</pre>
     *
     * @param entityId     Entity identifier as string
     * @param position     Entity 3D position
     * @param contentClass Content class name for reconstruction
     * @param contentValue Serialized content value (String representation)
     * @param sourceTreeId Source spatial tree identifier
     * @param epoch        Authority epoch for stale detection
     * @param version      Entity version within epoch
     * @param timestamp    Creation timestamp
     */
    record TransportGhost(
        String entityId,
        Point3f position,
        String contentClass,
        String contentValue,
        String sourceTreeId,
        long epoch,
        long version,
        long timestamp
    ) {
    }

    /**
     * Acknowledgment of a received message.
     * <p>
     * Sent back to confirm receipt of JOIN/MOVE/LEAVE messages.
     *
     * @param ackFor    UUID of the message being acknowledged
     * @param senderId  UUID of the acknowledging node
     * @param timestamp Message creation time
     */
    record Ack(UUID ackFor, UUID senderId, long timestamp) implements Message {
        // Compact constructor removed - use MessageFactory instead
    }

    /**
     * Query for remote bubble information.
     * <p>
     * Used by RemoteBubbleProxy to fetch state from remote bubbles.
     *
     * @param queryId   UUID for correlating query with response
     * @param senderId  UUID of the querying node
     * @param targetId  UUID of the target bubble
     * @param queryType Type of query (e.g., "position", "neighbors")
     * @param timestamp Message creation time
     */
    record Query(UUID queryId, UUID senderId, UUID targetId, String queryType, long timestamp) implements Message {
        // Compact constructor removed - use MessageFactory instead
    }

    /**
     * Response to a Query message.
     * <p>
     * Returns requested information from remote bubble to querying node.
     *
     * @param queryId      UUID correlating this response to original query
     * @param responderId  UUID of the responding node
     * @param responseData Serialized response data (JSON format)
     * @param timestamp    Message creation time
     */
    record QueryResponse(UUID queryId, UUID responderId, String responseData, long timestamp) implements Message {
        // Compact constructor removed - use MessageFactory instead
    }

    /**
     * Information about a VON neighbor.
     * <p>
     * Exchanged during JOIN to provide new nodes with neighbor details.
     *
     * @param nodeId   UUID of the neighbor
     * @param position Neighbor's current position
     * @param bounds   Neighbor's current bounds
     */
    record NeighborInfo(UUID nodeId, Point3D position, BubbleBounds bounds) {
    }
}
