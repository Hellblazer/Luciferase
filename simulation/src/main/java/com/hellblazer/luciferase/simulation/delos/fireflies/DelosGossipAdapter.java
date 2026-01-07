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

package com.hellblazer.luciferase.simulation.delos.fireflies;

import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.delos.GossipAdapter;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Delos-aware implementation of GossipAdapter.
 * <p>
 * Provides topic-based pub/sub messaging for cluster coordination.
 * Uses in-memory routing as Fireflies doesn't expose a simple
 * application-level message passing API. Adapters can be connected
 * to simulate cluster-wide gossip for testing and development.
 * <p>
 * Future versions may integrate with Delos messaging protocols
 * when available.
 *
 * @author hal.hildebrand
 */
public class DelosGossipAdapter implements GossipAdapter {

    // Global registry for simulating cluster-wide gossip
    private static final Map<String, Set<DelosGossipAdapter>> clusterRegistry = new ConcurrentHashMap<>();

    private final View                                 view;
    private final Member                               localMember;
    private final Map<String, List<Consumer<Message>>> subscribers = new ConcurrentHashMap<>();
    private final Set<DelosGossipAdapter>              connectedAdapters = ConcurrentHashMap.newKeySet();

    /**
     * Create a new DelosGossipAdapter wrapping a Fireflies View.
     *
     * @param view        the Delos Fireflies View (for member tracking)
     * @param localMember the local member
     */
    public DelosGossipAdapter(View view, Member localMember) {
        this.view = view;
        this.localMember = localMember;
    }

    /**
     * Connect two adapters to simulate cluster-wide gossip.
     * <p>
     * This is a testing utility that allows messages broadcast by
     * one adapter to be received by subscribers on another adapter.
     *
     * @param adapter1 first adapter
     * @param adapter2 second adapter
     */
    public static void connectAdapters(DelosGossipAdapter adapter1, DelosGossipAdapter adapter2) {
        adapter1.connectedAdapters.add(adapter2);
        adapter2.connectedAdapters.add(adapter1);
    }

    @Override
    public void broadcast(String topic, Message message) {
        // Deliver to local subscribers
        deliverLocally(topic, message);

        // Deliver to connected adapters (simulating cluster gossip)
        for (var adapter : connectedAdapters) {
            adapter.deliverLocally(topic, message);
        }
    }

    @Override
    public void subscribe(String topic, Consumer<Message> handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Deliver a message to local subscribers on a topic.
     *
     * @param topic   the topic
     * @param message the message
     */
    private void deliverLocally(String topic, Message message) {
        var handlers = subscribers.get(topic);
        if (handlers != null) {
            handlers.forEach(handler -> handler.accept(message));
        }
    }

    /**
     * Serialize a message for transmission.
     * <p>
     * Format: [UUID (16 bytes)][payload length (4 bytes)][payload (N bytes)]
     *
     * @param message the message to serialize
     * @return serialized bytes
     */
    static byte[] serialize(Message message) {
        var payloadLength = message.payload().length;
        var buffer = ByteBuffer.allocate(16 + 4 + payloadLength);

        // Write UUID as two longs
        buffer.putLong(message.senderId().getMostSignificantBits());
        buffer.putLong(message.senderId().getLeastSignificantBits());

        // Write payload length and payload
        buffer.putInt(payloadLength);
        buffer.put(message.payload());

        return buffer.array();
    }

    /**
     * Deserialize a message from bytes.
     *
     * @param bytes the serialized message
     * @return the deserialized message
     */
    static Message deserialize(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);

        // Read UUID
        var mostSigBits = buffer.getLong();
        var leastSigBits = buffer.getLong();
        var senderId = new UUID(mostSigBits, leastSigBits);

        // Read payload
        var payloadLength = buffer.getInt();
        var payload = new byte[payloadLength];
        buffer.get(payload);

        return new Message(senderId, payload);
    }
}
