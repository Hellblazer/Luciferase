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

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.delos.GossipAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Delos-aware implementation of GossipAdapter.
 * <p>
 * Provides topic-based pub/sub messaging integrated with a Fireflies cluster.
 * When multiple adapters are part of the same cluster (via {@link ClusterGossip}),
 * broadcasts are delivered to all members.
 * <p>
 * Uses View for membership tracking and registration for view change notifications.
 *
 * @author hal.hildebrand
 */
public class DelosGossipAdapter implements GossipAdapter {

    private static final Logger log = LoggerFactory.getLogger(DelosGossipAdapter.class);

    private final View                                 view;
    private final Member                               localMember;
    private final Digest                               localId;
    private final Map<String, List<Consumer<Message>>> subscribers = new ConcurrentHashMap<>();
    private       ClusterGossip                        clusterGossip;

    /**
     * Create a new DelosGossipAdapter wrapping a Fireflies View.
     *
     * @param view        the Delos Fireflies View
     * @param localMember the local member
     */
    public DelosGossipAdapter(View view, Member localMember) {
        this.view = view;
        this.localMember = localMember;
        this.localId = localMember.getId();
    }

    /**
     * Get the local member's Digest ID.
     */
    public Digest getLocalId() {
        return localId;
    }

    /**
     * Get the Fireflies View.
     */
    public View getView() {
        return view;
    }

    /**
     * Set the cluster gossip coordinator.
     * Called by {@link ClusterGossip} when this adapter joins a cluster.
     */
    void setClusterGossip(ClusterGossip clusterGossip) {
        this.clusterGossip = clusterGossip;
    }

    @Override
    public void broadcast(String topic, Message message) {
        if (clusterGossip != null) {
            // Broadcast to all cluster members via ClusterGossip
            clusterGossip.broadcast(topic, message, localId);
        } else {
            // Standalone mode - deliver locally only
            deliverLocally(topic, message);
        }
    }

    @Override
    public void subscribe(String topic, Consumer<Message> handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.debug("Subscribed to topic '{}' on {}", topic, localId);
    }

    /**
     * Deliver a message to local subscribers on a topic.
     * Called by ClusterGossip for cluster-wide delivery.
     *
     * @param topic   the topic
     * @param message the message
     */
    void deliverLocally(String topic, Message message) {
        var handlers = subscribers.get(topic);
        if (handlers != null && !handlers.isEmpty()) {
            log.trace("Delivering message on topic '{}' to {} handlers on {}",
                      topic, handlers.size(), localId);
            handlers.forEach(handler -> {
                try {
                    handler.accept(message);
                } catch (Exception e) {
                    log.error("Error in message handler for topic '{}' on {}", topic, localId, e);
                }
            });
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
    public static byte[] serialize(Message message) {
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
    public static Message deserialize(byte[] bytes) {
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

    /**
     * Cluster-wide gossip coordinator.
     * <p>
     * Maintains the set of adapters in a cluster and routes broadcasts
     * to all members. Each adapter in the cluster receives messages
     * broadcast on any topic by any member.
     */
    public static class ClusterGossip {
        private final Map<Digest, DelosGossipAdapter> adapters = new ConcurrentHashMap<>();

        private ClusterGossip() {}

        /**
         * Create a ClusterGossip instance connecting the given adapters.
         *
         * @param adapterList list of adapters to connect
         * @return the ClusterGossip coordinator
         */
        public static ClusterGossip create(List<DelosGossipAdapter> adapterList) {
            var cluster = new ClusterGossip();
            for (var adapter : adapterList) {
                cluster.addAdapter(adapter);
            }
            return cluster;
        }

        /**
         * Add an adapter to this cluster.
         *
         * @param adapter the adapter to add
         */
        public void addAdapter(DelosGossipAdapter adapter) {
            adapters.put(adapter.getLocalId(), adapter);
            adapter.setClusterGossip(this);
            log.debug("Added adapter {} to cluster gossip ({} total)",
                      adapter.getLocalId(), adapters.size());
        }

        /**
         * Remove an adapter from this cluster.
         *
         * @param adapter the adapter to remove
         */
        public void removeAdapter(DelosGossipAdapter adapter) {
            adapters.remove(adapter.getLocalId());
            adapter.setClusterGossip(null);
            log.debug("Removed adapter {} from cluster gossip ({} remaining)",
                      adapter.getLocalId(), adapters.size());
        }

        /**
         * Broadcast a message to all adapters in the cluster.
         *
         * @param topic    the topic
         * @param message  the message
         * @param senderId the sender's Digest ID
         */
        void broadcast(String topic, Message message, Digest senderId) {
            log.trace("Broadcasting on topic '{}' from {} to {} adapters",
                      topic, senderId, adapters.size());
            for (var adapter : adapters.values()) {
                adapter.deliverLocally(topic, message);
            }
        }

        /**
         * Get the number of adapters in this cluster.
         */
        public int size() {
            return adapters.size();
        }

        /**
         * Get all adapters in this cluster.
         */
        public Collection<DelosGossipAdapter> getAdapters() {
            return Collections.unmodifiableCollection(adapters.values());
        }
    }
}
