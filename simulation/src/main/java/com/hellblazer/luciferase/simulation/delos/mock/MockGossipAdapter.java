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

package com.hellblazer.luciferase.simulation.delos.mock;

import com.hellblazer.luciferase.simulation.delos.GossipAdapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Mock implementation of GossipAdapter for testing.
 * <p>
 * Provides in-memory pub/sub messaging for testing cluster coordination.
 * Messages are delivered synchronously to all subscribers on the same topic.
 * <p>
 * For testing verification, all broadcast messages are recorded and can be
 * retrieved via {@link #getMessages()}.
 *
 * @author hal.hildebrand
 */
public class MockGossipAdapter implements GossipAdapter {

    private final Map<String, List<Consumer<Message>>> subscribers = new ConcurrentHashMap<>();
    private final List<Message> broadcastMessages = new CopyOnWriteArrayList<>();

    @Override
    public void broadcast(String topic, Message message) {
        // Record for testing verification
        broadcastMessages.add(message);

        // Deliver to subscribers
        var handlers = subscribers.get(topic);
        if (handlers != null) {
            handlers.forEach(handler -> handler.accept(message));
        }
    }

    @Override
    public void subscribe(String topic, Consumer<Message> handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Get all broadcast messages (for testing verification).
     *
     * @return list of all messages broadcast via this adapter
     */
    public List<Message> getMessages() {
        return List.copyOf(broadcastMessages);
    }

    /**
     * Clear all recorded messages (for test isolation).
     */
    public void clearMessages() {
        broadcastMessages.clear();
    }
}
