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

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Point-to-point transport interface for VON communication.
 * <p>
 * In v4.0 architecture, VON communication after JOIN is exclusively P2P
 * between neighbors - NO broadcast. This interface abstracts the transport
 * layer to allow:
 * <ul>
 *   <li>In-process testing via LocalServerTransport</li>
 *   <li>gRPC production transport</li>
 *   <li>Mock transport for unit tests</li>
 * </ul>
 * <p>
 * Key Operations:
 * <ul>
 *   <li>{@link #sendToNeighbor} - Direct P2P message to a specific neighbor</li>
 *   <li>{@link #sendToNeighborAsync} - Async P2P message with completion future</li>
 *   <li>{@link #onMessage} - Register handler for incoming messages</li>
 *   <li>{@link #lookupMember} - Find member info by UUID</li>
 *   <li>{@link #routeToKey} - Find member responsible for a spatial region</li>
 * </ul>
 * <p>
 * Thread-safe: All implementations must be thread-safe for concurrent use.
 *
 * @author hal.hildebrand
 */
public interface Transport {

    /**
     * Send a message directly to a specific neighbor (P2P).
     * <p>
     * Blocks until the message is sent (but not acknowledged).
     *
     * @param neighborId UUID of the target neighbor
     * @param message    Message to send
     * @throws TransportException if send fails
     */
    void sendToNeighbor(UUID neighborId, Message message) throws TransportException;

    /**
     * Send a message asynchronously to a specific neighbor (P2P).
     * <p>
     * Returns immediately with a future that completes when the
     * message is acknowledged by the recipient.
     *
     * @param neighborId UUID of the target neighbor
     * @param message    Message to send
     * @return Future that completes with ACK or fails with TransportException
     */
    CompletableFuture<Message.Ack> sendToNeighborAsync(UUID neighborId, Message message);

    /**
     * Register a handler for incoming messages.
     * <p>
     * Handler is called for each received message. Multiple handlers can be
     * registered (all will be called).
     *
     * @param handler Consumer to process incoming messages
     */
    void onMessage(Consumer<Message> handler);

    /**
     * Remove a previously registered message handler.
     *
     * @param handler Handler to remove
     */
    void removeMessageHandler(Consumer<Message> handler);

    /**
     * Look up member information by UUID.
     * <p>
     * Used during JOIN to contact the first member discovered via Fireflies.
     *
     * @param memberId UUID of the member to look up
     * @return MemberInfo if found, empty if unknown
     */
    Optional<MemberInfo> lookupMember(UUID memberId);

    /**
     * Find the member responsible for a spatial region.
     * <p>
     * Routes based on TetreeKey hash to determine which member owns the region.
     * Used during JOIN to find the acceptor for a position.
     *
     * @param key TetreeKey identifying the spatial region
     * @return MemberInfo for the responsible member
     * @throws TransportException if routing fails (e.g., no members)
     */
    MemberInfo routeToKey(TetreeKey<?> key) throws TransportException;

    /**
     * Get the UUID of the local node.
     *
     * @return Local node's UUID
     */
    UUID getLocalId();

    /**
     * Check if the transport is connected and operational.
     *
     * @return true if ready to send/receive messages
     */
    boolean isConnected();

    /**
     * Close the transport and release resources.
     */
    void close();

    /**
     * Information about a cluster member.
     *
     * @param nodeId   UUID of the member
     * @param endpoint Network endpoint for direct communication
     */
    record MemberInfo(UUID nodeId, String endpoint) {
    }

    /**
     * Exception thrown when transport operations fail.
     */
    class TransportException extends RuntimeException {
        public TransportException(String message) {
            super(message);
        }

        public TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
