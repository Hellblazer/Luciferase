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

package com.hellblazer.luciferase.simulation.delos;

import java.util.function.Consumer;

/**
 * Abstraction for gossip-based broadcast communication.
 * <p>
 * Provides pub/sub style messaging for cluster coordination.
 * Implementations may be backed by mock in-memory delivery for testing
 * or real Fireflies gossip for production.
 *
 * @author hal.hildebrand
 */
public interface GossipAdapter {

    /**
     * Broadcast a message to all cluster members on the specified topic.
     *
     * @param topic   the topic to broadcast on
     * @param message the message to broadcast
     */
    void broadcast(String topic, Message message);

    /**
     * Subscribe to messages on a specific topic.
     *
     * @param topic   the topic to subscribe to
     * @param handler consumer that receives messages
     */
    void subscribe(String topic, Consumer<Message> handler);

    /**
     * Represents a gossip message.
     *
     * @param senderId the UUID of the sender
     * @param payload  the message payload as bytes
     */
    record Message(java.util.UUID senderId, byte[] payload) {
    }
}
