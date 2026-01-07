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
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * VON-enabled bubble that integrates P2P transport for distributed operation.
 * <p>
 * VonBubble extends EnhancedBubble with:
 * <ul>
 *   <li>Node interface implementation for VON protocols</li>
 *   <li>VonTransport integration for P2P communication</li>
 *   <li>Neighbor tracking via P2P messages (not broadcast)</li>
 *   <li>Message handlers for JOIN/MOVE/LEAVE/GHOST_SYNC</li>
 * </ul>
 * <p>
 * In v4.0 architecture, VonBubble IS a VON node - the bubble provides:
 * - Spatial bounds (TetreeKey + RDGCS)
 * - Position (entity centroid)
 * - Neighbor management (via P2P)
 * <p>
 * Thread-safe for concurrent P2P message handling.
 *
 * @author hal.hildebrand
 */
public class VonBubble extends EnhancedBubble implements Node {

    private static final Logger log = LoggerFactory.getLogger(VonBubble.class);

    private final VonTransport transport;
    private final Map<UUID, NeighborState> neighborStates;
    private final List<Consumer<Event>> eventListeners;
    private final Consumer<VonMessage> messageHandler;

    /**
     * Create a VonBubble with P2P transport.
     *
     * @param id            Unique bubble identifier
     * @param spatialLevel  Tetree refinement level (typically 10)
     * @param targetFrameMs Target simulation frame time budget
     * @param transport     P2P transport for VON communication
     */
    public VonBubble(UUID id, byte spatialLevel, long targetFrameMs, VonTransport transport) {
        super(id, spatialLevel, targetFrameMs);
        this.transport = transport;
        this.neighborStates = new ConcurrentHashMap<>();
        this.eventListeners = new ArrayList<>();

        // Register message handler
        this.messageHandler = this::handleMessage;
        transport.onMessage(messageHandler);

        log.debug("VonBubble created: id={}", id);
    }

    // ========== Node Interface Implementation ==========

    @Override
    public UUID id() {
        return super.id();
    }

    @Override
    public Point3D position() {
        var centroid = centroid();
        return centroid != null ? centroid : new Point3D(0, 0, 0);
    }

    @Override
    public BubbleBounds bounds() {
        return super.bounds();
    }

    @Override
    public Set<UUID> neighbors() {
        return Collections.unmodifiableSet(neighborStates.keySet());
    }

    @Override
    public void notifyMove(Node neighbor) {
        // Update our tracked state for this neighbor
        var state = neighborStates.get(neighbor.id());
        if (state != null) {
            neighborStates.put(neighbor.id(), new NeighborState(
                neighbor.id(),
                neighbor.position(),
                neighbor.bounds(),
                System.currentTimeMillis()
            ));
            log.trace("Neighbor {} moved to {}", neighbor.id(), neighbor.position());
        }
    }

    @Override
    public void notifyLeave(Node neighbor) {
        removeNeighbor(neighbor.id());
        emitEvent(new Event.Leave(neighbor.id(), neighbor.position()));
        log.debug("Neighbor {} left", neighbor.id());
    }

    @Override
    public void notifyJoin(Node neighbor) {
        addNeighbor(neighbor.id());
        neighborStates.put(neighbor.id(), new NeighborState(
            neighbor.id(),
            neighbor.position(),
            neighbor.bounds(),
            System.currentTimeMillis()
        ));
        emitEvent(new Event.Join(neighbor.id(), neighbor.position()));
        log.debug("Neighbor {} joined at {}", neighbor.id(), neighbor.position());
    }

    @Override
    public void addNeighbor(UUID neighborId) {
        super.addVonNeighbor(neighborId);
        if (!neighborStates.containsKey(neighborId)) {
            // Initialize with unknown state - will be updated on first message
            neighborStates.put(neighborId, new NeighborState(
                neighborId,
                new Point3D(0, 0, 0),
                null,
                System.currentTimeMillis()
            ));
        }
    }

    @Override
    public void removeNeighbor(UUID neighborId) {
        super.removeVonNeighbor(neighborId);
        neighborStates.remove(neighborId);
    }

    // ========== P2P Transport Methods ==========

    /**
     * Send a MOVE notification to all neighbors.
     * <p>
     * Called when this bubble's position or bounds change significantly.
     */
    public void broadcastMove() {
        var moveMsg = new VonMessage.Move(id(), position(), bounds());

        for (UUID neighborId : neighbors()) {
            try {
                transport.sendToNeighbor(neighborId, moveMsg);
            } catch (VonTransport.TransportException e) {
                log.warn("Failed to send MOVE to neighbor {}: {}", neighborId, e.getMessage());
            }
        }

        log.trace("Broadcast MOVE to {} neighbors", neighbors().size());
    }

    /**
     * Send a LEAVE notification to all neighbors.
     * <p>
     * Called during graceful shutdown.
     */
    public void broadcastLeave() {
        var leaveMsg = new VonMessage.Leave(id());

        for (UUID neighborId : neighbors()) {
            try {
                transport.sendToNeighbor(neighborId, leaveMsg);
            } catch (VonTransport.TransportException e) {
                log.warn("Failed to send LEAVE to neighbor {}: {}", neighborId, e.getMessage());
            }
        }

        log.debug("Broadcast LEAVE to {} neighbors", neighbors().size());
    }

    /**
     * Initiate JOIN to the VON at a specific position.
     * <p>
     * Contacts the node responsible for the target region and requests to join.
     *
     * @param targetPosition Desired position in the network
     * @return true if JOIN was accepted, false otherwise
     */
    public boolean initiateJoin(Point3D targetPosition) {
        try {
            // Find the acceptor for this position
            var acceptor = findAcceptorForPosition(targetPosition);
            if (acceptor == null) {
                log.warn("No acceptor found for position {}", targetPosition);
                return false;
            }

            // Send JOIN request
            var joinRequest = new VonMessage.JoinRequest(id(), targetPosition, bounds());
            transport.sendToNeighbor(acceptor.nodeId(), joinRequest);

            log.debug("Sent JOIN request to {} for position {}", acceptor.nodeId(), targetPosition);
            return true;

        } catch (VonTransport.TransportException e) {
            log.error("JOIN failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the transport for this bubble.
     *
     * @return P2P transport
     */
    public VonTransport getTransport() {
        return transport;
    }

    /**
     * Get the state of a specific neighbor.
     *
     * @param neighborId Neighbor UUID
     * @return NeighborState or null if not a neighbor
     */
    public NeighborState getNeighborState(UUID neighborId) {
        return neighborStates.get(neighborId);
    }

    /**
     * Get all neighbor states.
     *
     * @return Unmodifiable map of neighbor states
     */
    public Map<UUID, NeighborState> getNeighborStates() {
        return Collections.unmodifiableMap(neighborStates);
    }

    /**
     * Register an event listener for VON events.
     *
     * @param listener Consumer to receive events
     */
    public void addEventListener(Consumer<Event> listener) {
        eventListeners.add(listener);
    }

    /**
     * Remove an event listener.
     *
     * @param listener Consumer to remove
     */
    public void removeEventListener(Consumer<Event> listener) {
        eventListeners.remove(listener);
    }

    /**
     * Close this bubble and release resources.
     * <p>
     * Sends LEAVE to all neighbors and unregisters message handler.
     */
    public void close() {
        broadcastLeave();
        transport.removeMessageHandler(messageHandler);
        neighborStates.clear();
        eventListeners.clear();
        log.debug("VonBubble {} closed", id());
    }

    // ========== Private Methods ==========

    /**
     * Handle incoming P2P messages.
     */
    private void handleMessage(VonMessage message) {
        switch (message) {
            case VonMessage.JoinRequest req -> handleJoinRequest(req);
            case VonMessage.JoinResponse resp -> handleJoinResponse(resp);
            case VonMessage.Move move -> handleMove(move);
            case VonMessage.Leave leave -> handleLeave(leave);
            case VonMessage.GhostSync sync -> handleGhostSync(sync);
            case VonMessage.Ack ack -> handleAck(ack);
        }
    }

    private void handleJoinRequest(VonMessage.JoinRequest req) {
        log.debug("Received JOIN request from {} at {}", req.joinerId(), req.position());

        // Add as neighbor
        addNeighbor(req.joinerId());
        neighborStates.put(req.joinerId(), new NeighborState(
            req.joinerId(),
            req.position(),
            req.bounds(),
            req.timestamp()
        ));

        // Respond with our current neighbors
        var neighborInfos = new HashSet<VonMessage.NeighborInfo>();
        for (var entry : neighborStates.entrySet()) {
            if (!entry.getKey().equals(req.joinerId())) {
                var state = entry.getValue();
                neighborInfos.add(new VonMessage.NeighborInfo(
                    state.nodeId(),
                    state.position(),
                    state.bounds()
                ));
            }
        }
        // Include ourselves
        neighborInfos.add(new VonMessage.NeighborInfo(id(), position(), bounds()));

        var response = new VonMessage.JoinResponse(id(), neighborInfos);
        try {
            transport.sendToNeighbor(req.joinerId(), response);
        } catch (VonTransport.TransportException e) {
            log.warn("Failed to send JOIN response to {}: {}", req.joinerId(), e.getMessage());
        }

        emitEvent(new Event.Join(req.joinerId(), req.position()));
    }

    private void handleJoinResponse(VonMessage.JoinResponse resp) {
        log.debug("Received JOIN response from {} with {} neighbors", resp.acceptorId(), resp.neighbors().size());

        // Add all neighbors from the response
        for (var neighborInfo : resp.neighbors()) {
            addNeighbor(neighborInfo.nodeId());
            neighborStates.put(neighborInfo.nodeId(), new NeighborState(
                neighborInfo.nodeId(),
                neighborInfo.position(),
                neighborInfo.bounds(),
                resp.timestamp()
            ));
        }

        // Send ACK
        try {
            transport.sendToNeighbor(resp.acceptorId(), new VonMessage.Ack(resp.acceptorId(), id()));
        } catch (VonTransport.TransportException e) {
            log.warn("Failed to send ACK to {}: {}", resp.acceptorId(), e.getMessage());
        }
    }

    private void handleMove(VonMessage.Move move) {
        if (!neighborStates.containsKey(move.nodeId())) {
            log.trace("Ignoring MOVE from non-neighbor {}", move.nodeId());
            return;
        }

        neighborStates.put(move.nodeId(), new NeighborState(
            move.nodeId(),
            move.newPosition(),
            move.newBounds(),
            move.timestamp()
        ));

        emitEvent(new Event.Move(move.nodeId(), move.newPosition(), move.newBounds()));
        log.trace("Neighbor {} moved to {}", move.nodeId(), move.newPosition());
    }

    private void handleLeave(VonMessage.Leave leave) {
        var state = neighborStates.get(leave.nodeId());
        if (state != null) {
            removeNeighbor(leave.nodeId());
            emitEvent(new Event.Leave(leave.nodeId(), state.position()));
            log.debug("Neighbor {} left", leave.nodeId());
        }
    }

    private void handleGhostSync(VonMessage.GhostSync sync) {
        log.trace("Received GHOST_SYNC from {} with {} ghosts", sync.sourceBubbleId(), sync.ghosts().size());
        // Ghost handling is delegated to external ghost manager
        // Emit event for external processing
        emitEvent(new Event.GhostSync(sync.sourceBubbleId(), sync.ghosts()));
    }

    private void handleAck(VonMessage.Ack ack) {
        log.trace("Received ACK from {} for {}", ack.senderId(), ack.ackFor());
        // ACKs are handled by transport layer for async operations
    }

    private void emitEvent(Event event) {
        for (var listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("Event listener error: {}", e.getMessage());
            }
        }
    }

    private VonTransport.MemberInfo findAcceptorForPosition(Point3D position) {
        // Use transport's routing to find the member responsible for this position
        // For now, use a simple approach: get any available member
        // In production, this would use TetreeKey routing
        try {
            var bounds = this.bounds();
            if (bounds != null && bounds.rootKey() != null) {
                return transport.routeToKey(bounds.rootKey());
            }
        } catch (VonTransport.TransportException e) {
            log.warn("Route to key failed: {}", e.getMessage());
        }
        return null;
    }

    // ========== Nested Types ==========

    /**
     * State tracking for a neighbor.
     *
     * @param nodeId       Neighbor UUID
     * @param position     Last known position
     * @param bounds       Last known bounds
     * @param lastUpdateMs Timestamp of last update
     */
    public record NeighborState(UUID nodeId, Point3D position, BubbleBounds bounds, long lastUpdateMs) {
    }
}
