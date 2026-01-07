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

import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.delos.GossipAdapter;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.von.Event;
import com.hellblazer.luciferase.simulation.von.Node;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Integrated VON (Voronoi Overlay Network) node backed by Delos cluster.
 * <p>
 * Combines:
 * <ul>
 *   <li>{@link DelosClusterNode} for cluster membership and messaging</li>
 *   <li>{@link Node} interface for VON spatial coordination</li>
 * </ul>
 * <p>
 * Message Protocol:
 * <ul>
 *   <li>Topic "von.join" - Node join announcements</li>
 *   <li>Topic "von.leave" - Node leave announcements</li>
 *   <li>Topic "von.move" - Node position updates</li>
 *   <li>Topic "von.discover" - Neighbor discovery requests</li>
 * </ul>
 * <p>
 * This class bridges Fireflies cluster membership with VON spatial coordination,
 * enabling distributed spatial simulation across a Delos cluster.
 *
 * @author hal.hildebrand
 */
public class DelosVonNode implements Node {

    private static final Logger log = LoggerFactory.getLogger(DelosVonNode.class);

    // Gossip topics for VON protocol
    public static final String TOPIC_JOIN     = "von.join";
    public static final String TOPIC_LEAVE    = "von.leave";
    public static final String TOPIC_MOVE     = "von.move";
    public static final String TOPIC_DISCOVER = "von.discover";

    private final DelosClusterNode       clusterNode;
    private final UUID                   nodeId;
    private final Set<UUID>              neighbors = new CopyOnWriteArraySet<>();
    private final Map<UUID, Point3D>     neighborPositions = new ConcurrentHashMap<>();
    private final Consumer<Event>        eventEmitter;

    // Current position and bounds (mutable)
    private volatile Point3D      position;
    private volatile BubbleBounds bounds;

    /**
     * Create a DelosVonNode wrapping a cluster node.
     *
     * @param clusterNode  the Delos cluster node
     * @param initialPosition initial spatial position
     * @param eventEmitter callback for VON events
     */
    public DelosVonNode(DelosClusterNode clusterNode,
                        Point3D initialPosition,
                        Consumer<Event> eventEmitter) {
        this.clusterNode = clusterNode;
        this.nodeId = clusterNode.getNodeUuid();
        this.position = initialPosition;
        this.eventEmitter = eventEmitter != null ? eventEmitter : e -> {};

        // Subscribe to VON protocol messages
        subscribeToProtocol();

        // Listen for membership changes
        clusterNode.getMembershipView().addListener(this::handleMembershipChange);

        log.info("Created DelosVonNode {} at position {}", nodeId, position);
    }

    /**
     * Subscribe to VON protocol topics.
     */
    private void subscribeToProtocol() {
        var gossip = clusterNode.getGossipAdapter();

        gossip.subscribe(TOPIC_JOIN, this::handleJoinMessage);
        gossip.subscribe(TOPIC_LEAVE, this::handleLeaveMessage);
        gossip.subscribe(TOPIC_MOVE, this::handleMoveMessage);
        gossip.subscribe(TOPIC_DISCOVER, this::handleDiscoverMessage);
    }

    // ========== Node Interface Implementation ==========

    @Override
    public UUID id() {
        return nodeId;
    }

    @Override
    public Point3D position() {
        return position;
    }

    @Override
    public BubbleBounds bounds() {
        return bounds;
    }

    @Override
    public Set<UUID> neighbors() {
        return Collections.unmodifiableSet(neighbors);
    }

    @Override
    public void notifyMove(Node neighbor) {
        // Update tracked position
        neighborPositions.put(neighbor.id(), neighbor.position());
        eventEmitter.accept(new Event.Move(neighbor.id(), neighbor.position()));
    }

    @Override
    public void notifyLeave(Node neighbor) {
        removeNeighbor(neighbor.id());
        eventEmitter.accept(new Event.Leave(neighbor.id()));
    }

    @Override
    public void notifyJoin(Node neighbor) {
        addNeighbor(neighbor.id());
        neighborPositions.put(neighbor.id(), neighbor.position());
        eventEmitter.accept(new Event.Join(neighbor.id(), neighbor.position()));
    }

    @Override
    public void addNeighbor(UUID neighborId) {
        if (neighbors.add(neighborId)) {
            log.debug("Node {} added neighbor {}", nodeId, neighborId);
        }
    }

    @Override
    public void removeNeighbor(UUID neighborId) {
        if (neighbors.remove(neighborId)) {
            neighborPositions.remove(neighborId);
            log.debug("Node {} removed neighbor {}", nodeId, neighborId);
        }
    }

    // ========== VON Protocol Methods ==========

    /**
     * Update this node's position and broadcast to neighbors.
     *
     * @param newPosition the new position
     */
    public void move(Point3D newPosition) {
        this.position = newPosition;

        // Broadcast move to cluster
        broadcastMove();

        log.trace("Node {} moved to {}", nodeId, newPosition);
    }

    /**
     * Update this node's bounds.
     *
     * @param newBounds the new bounds
     */
    public void updateBounds(BubbleBounds newBounds) {
        this.bounds = newBounds;
    }

    /**
     * Announce this node's presence to the cluster.
     */
    public void announceJoin() {
        var message = new GossipAdapter.Message(nodeId, serializeJoin(nodeId, position));
        clusterNode.getGossipAdapter().broadcast(TOPIC_JOIN, message);
        log.info("Node {} announced join at {}", nodeId, position);
    }

    /**
     * Announce this node's departure from the cluster.
     */
    public void announceLeave() {
        var message = new GossipAdapter.Message(nodeId, serializeLeave(nodeId));
        clusterNode.getGossipAdapter().broadcast(TOPIC_LEAVE, message);
        log.info("Node {} announced leave", nodeId);
    }

    /**
     * Broadcast current position to cluster.
     */
    public void broadcastMove() {
        var message = new GossipAdapter.Message(nodeId, serializeMove(nodeId, position));
        clusterNode.getGossipAdapter().broadcast(TOPIC_MOVE, message);
    }

    /**
     * Request neighbor discovery from the cluster.
     */
    public void requestDiscovery() {
        var message = new GossipAdapter.Message(nodeId, serializeDiscover(nodeId, position));
        clusterNode.getGossipAdapter().broadcast(TOPIC_DISCOVER, message);
        log.debug("Node {} requested discovery", nodeId);
    }

    /**
     * Get the position of a neighbor.
     *
     * @param neighborId the neighbor UUID
     * @return the position, or null if unknown
     */
    public Point3D getNeighborPosition(UUID neighborId) {
        return neighborPositions.get(neighborId);
    }

    /**
     * Get the underlying cluster node.
     */
    public DelosClusterNode getClusterNode() {
        return clusterNode;
    }

    // ========== Message Handlers ==========

    private void handleJoinMessage(GossipAdapter.Message message) {
        if (message.senderId().equals(nodeId)) return; // Ignore own messages

        var parsed = deserializeJoin(message.payload());
        var joinerId = parsed.nodeId();
        var joinerPosition = parsed.position();

        // Add as potential neighbor
        addNeighbor(joinerId);
        neighborPositions.put(joinerId, joinerPosition);

        eventEmitter.accept(new Event.Join(joinerId, joinerPosition));
        log.debug("Node {} received join from {} at {}", nodeId, joinerId, joinerPosition);
    }

    private void handleLeaveMessage(GossipAdapter.Message message) {
        if (message.senderId().equals(nodeId)) return;

        var leaverId = deserializeLeave(message.payload());
        removeNeighbor(leaverId);

        eventEmitter.accept(new Event.Leave(leaverId));
        log.debug("Node {} received leave from {}", nodeId, leaverId);
    }

    private void handleMoveMessage(GossipAdapter.Message message) {
        if (message.senderId().equals(nodeId)) return;

        var parsed = deserializeMove(message.payload());
        var moverId = parsed.nodeId();
        var newPosition = parsed.position();

        if (neighbors.contains(moverId)) {
            neighborPositions.put(moverId, newPosition);
            eventEmitter.accept(new Event.Move(moverId, newPosition));
            log.trace("Node {} received move from {} to {}", nodeId, moverId, newPosition);
        }
    }

    private void handleDiscoverMessage(GossipAdapter.Message message) {
        if (message.senderId().equals(nodeId)) return;

        var parsed = deserializeDiscover(message.payload());
        var requesterId = parsed.nodeId();
        var requesterPosition = parsed.position();

        // If requester is close enough, respond with join
        // TODO: Implement AOI-based filtering
        addNeighbor(requesterId);
        neighborPositions.put(requesterId, requesterPosition);

        log.debug("Node {} received discovery request from {}", nodeId, requesterId);
    }

    private void handleMembershipChange(MembershipView.ViewChange<Member> change) {
        // Handle nodes leaving the Fireflies cluster
        for (var member : change.left()) {
            var memberId = DelosClusterNode.digestToUuid(member.getId());
            if (neighbors.remove(memberId)) {
                neighborPositions.remove(memberId);
                eventEmitter.accept(new Event.Leave(memberId));
                log.debug("Node {} detected membership leave: {}", nodeId, memberId);
            }
        }
    }

    // ========== Serialization ==========

    private record JoinData(UUID nodeId, Point3D position) {}
    private record MoveData(UUID nodeId, Point3D position) {}
    private record DiscoverData(UUID nodeId, Point3D position) {}

    private static byte[] serializeJoin(UUID nodeId, Point3D position) {
        return serializeNodePosition(nodeId, position);
    }

    private static JoinData deserializeJoin(byte[] bytes) {
        var np = deserializeNodePosition(bytes);
        return new JoinData(np.nodeId, np.position);
    }

    private static byte[] serializeLeave(UUID nodeId) {
        var buffer = ByteBuffer.allocate(16);
        buffer.putLong(nodeId.getMostSignificantBits());
        buffer.putLong(nodeId.getLeastSignificantBits());
        return buffer.array();
    }

    private static UUID deserializeLeave(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] serializeMove(UUID nodeId, Point3D position) {
        return serializeNodePosition(nodeId, position);
    }

    private static MoveData deserializeMove(byte[] bytes) {
        var np = deserializeNodePosition(bytes);
        return new MoveData(np.nodeId, np.position);
    }

    private static byte[] serializeDiscover(UUID nodeId, Point3D position) {
        return serializeNodePosition(nodeId, position);
    }

    private static DiscoverData deserializeDiscover(byte[] bytes) {
        var np = deserializeNodePosition(bytes);
        return new DiscoverData(np.nodeId, np.position);
    }

    private static byte[] serializeNodePosition(UUID nodeId, Point3D position) {
        var buffer = ByteBuffer.allocate(16 + 24); // UUID + 3 doubles
        buffer.putLong(nodeId.getMostSignificantBits());
        buffer.putLong(nodeId.getLeastSignificantBits());
        buffer.putDouble(position.getX());
        buffer.putDouble(position.getY());
        buffer.putDouble(position.getZ());
        return buffer.array();
    }

    private record NodePosition(UUID nodeId, Point3D position) {}

    private static NodePosition deserializeNodePosition(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        var nodeId = new UUID(buffer.getLong(), buffer.getLong());
        var position = new Point3D(buffer.getDouble(), buffer.getDouble(), buffer.getDouble());
        return new NodePosition(nodeId, position);
    }

    @Override
    public String toString() {
        return String.format("DelosVonNode[%s, pos=%s, neighbors=%d, active=%d]",
                             nodeId, position, neighbors.size(),
                             clusterNode.getActiveCount());
    }
}
