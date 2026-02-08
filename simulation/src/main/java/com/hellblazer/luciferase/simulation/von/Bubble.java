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
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * VON-enabled bubble that integrates P2P transport for distributed operation.
 * <p>
 * Bubble extends EnhancedBubble with:
 * <ul>
 *   <li>Node interface implementation for VON protocols</li>
 *   <li>Transport integration for P2P communication</li>
 *   <li>Neighbor tracking via P2P messages (not broadcast)</li>
 *   <li>Message handlers for JOIN/MOVE/LEAVE/GHOST_SYNC</li>
 * </ul>
 * <p>
 * In v4.0 architecture, Bubble IS a VON node - the bubble provides:
 * - Spatial bounds (TetreeKey + RDGCS)
 * - Position (entity centroid)
 * - Neighbor management (via P2P)
 * <p>
 * Thread-safe for concurrent P2P message handling.
 *
 * @author hal.hildebrand
 */
public class Bubble extends EnhancedBubble implements Node {

    private static final Logger log = LoggerFactory.getLogger(Bubble.class);

    private final Transport transport;
    private volatile MessageFactory factory;
    private final Map<UUID, NeighborState> neighborStates;
    private final Set<UUID> introducedTo;  // Track neighbors we've introduced ourselves to
    private final List<Consumer<Event>> eventListeners;
    private final Consumer<Message> messageHandler;
    private volatile Clock clock = Clock.system();

    /**
     * Set the clock for deterministic testing.
     * <p>
     * Updates both the clock field and recreates the factory to use the new clock
     * for all subsequent message timestamps.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
        this.factory = new MessageFactory(clock);
    }

    /**
     * Create a Bubble with P2P transport.
     *
     * @param id            Unique bubble identifier
     * @param spatialLevel  Tetree refinement level (typically 10)
     * @param targetFrameMs Target simulation frame time budget
     * @param transport     P2P transport for VON communication
     */
    public Bubble(UUID id, byte spatialLevel, long targetFrameMs, Transport transport) {
        super(id, spatialLevel, targetFrameMs);
        this.transport = transport;
        this.factory = MessageFactory.system();
        this.neighborStates = new ConcurrentHashMap<>();
        this.introducedTo = ConcurrentHashMap.newKeySet();
        this.eventListeners = new CopyOnWriteArrayList<>();

        // Register message handler
        this.messageHandler = this::handleMessage;
        transport.onMessage(messageHandler);

        log.debug("Bubble created: id={}", id);
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
                clock.currentTimeMillis()
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
            clock.currentTimeMillis()
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
                clock.currentTimeMillis()
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
        var moveMsg = factory.createMove(id(), position(), bounds());

        // Create snapshot to avoid ConcurrentModificationException during iteration
        var neighborSnapshot = new ArrayList<>(neighbors());
        for (UUID neighborId : neighborSnapshot) {
            try {
                transport.sendToNeighbor(neighborId, moveMsg);
            } catch (Transport.TransportException e) {
                log.warn("Failed to send MOVE to neighbor {}: {}", neighborId, e.getMessage());
            }
        }

        log.trace("Broadcast MOVE to {} neighbors", neighborSnapshot.size());
    }

    /**
     * Send a LEAVE notification to all neighbors.
     * <p>
     * Called during graceful shutdown.
     */
    public void broadcastLeave() {
        var leaveMsg = factory.createLeave(id());

        // Create snapshot to avoid ConcurrentModificationException during iteration
        var neighborSnapshot = new ArrayList<>(neighbors());
        for (UUID neighborId : neighborSnapshot) {
            try {
                transport.sendToNeighbor(neighborId, leaveMsg);
            } catch (Transport.TransportException e) {
                log.warn("Failed to send LEAVE to neighbor {}: {}", neighborId, e.getMessage());
            }
        }

        log.debug("Broadcast LEAVE to {} neighbors", neighborSnapshot.size());
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
            var joinRequest = factory.createJoinRequest(id(), targetPosition, bounds());
            transport.sendToNeighbor(acceptor.nodeId(), joinRequest);

            log.debug("Sent JOIN request to {} for position {}", acceptor.nodeId(), targetPosition);
            return true;

        } catch (Transport.TransportException e) {
            log.error("JOIN failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the transport for this bubble.
     *
     * @return P2P transport
     */
    public Transport getTransport() {
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
     * Create a Move message for this bubble using the configured factory.
     * <p>
     * Primarily used for testing clock injection - verifies that the factory
     * uses the correct clock for timestamps.
     *
     * @return Move message with current position and bounds
     */
    public Message.Move createMoveMessage() {
        return factory.createMove(id(), position(), bounds());
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
        log.debug("Bubble {} closed", id());
    }

    // ========== Private Methods ==========

    /**
     * Handle incoming P2P messages.
     */
    private void handleMessage(Message message) {
        switch (message) {
            case Message.JoinRequest req -> handleJoinRequest(req);
            case Message.JoinResponse resp -> handleJoinResponse(resp);
            case Message.Move move -> handleMove(move);
            case Message.Leave leave -> handleLeave(leave);
            case Message.GhostSync sync -> handleGhostSync(sync);
            case Message.Ack ack -> handleAck(ack);
            case Message.Query query -> handleQuery(query);
            case Message.QueryResponse resp -> {}  // Handled by RemoteBubbleProxy
            default -> log.warn("Unhandled message type: {}", message.getClass().getSimpleName());
        }
    }

    private void handleJoinRequest(Message.JoinRequest req) {
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
        // Create snapshot to avoid ConcurrentModificationException
        var neighborInfos = new HashSet<Message.NeighborInfo>();
        var statesSnapshot = new HashMap<>(neighborStates);
        for (var entry : statesSnapshot.entrySet()) {
            if (!entry.getKey().equals(req.joinerId())) {
                var state = entry.getValue();
                neighborInfos.add(new Message.NeighborInfo(
                    state.nodeId(),
                    state.position(),
                    state.bounds()
                ));
            }
        }
        // Include ourselves
        neighborInfos.add(new Message.NeighborInfo(id(), position(), bounds()));

        var response = factory.createJoinResponse(id(), neighborInfos);
        try {
            transport.sendToNeighbor(req.joinerId(), response);
        } catch (Transport.TransportException e) {
            log.warn("Failed to send JOIN response to {}: {}", req.joinerId(), e.getMessage());
        }

        emitEvent(new Event.Join(req.joinerId(), req.position()));
    }

    private void handleJoinResponse(Message.JoinResponse resp) {
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

        // Send ACK to acceptor
        try {
            transport.sendToNeighbor(resp.acceptorId(), factory.createAck(resp.acceptorId(), id()));
        } catch (Transport.TransportException e) {
            log.warn("Failed to send ACK to {}: {}", resp.acceptorId(), e.getMessage());
        }

        // Notify other learned neighbors of our presence (establish bidirectional relationship)
        // Skip the acceptor (already knows about us from JoinRequest)
        // Also skip neighbors we've already introduced ourselves to (prevents message loops)
        for (var neighborInfo : resp.neighbors()) {
            UUID neighborId = neighborInfo.nodeId();
            if (!neighborId.equals(resp.acceptorId()) && !introducedTo.contains(neighborId)) {
                introducedTo.add(neighborId);  // Mark as introduced before sending
                try {
                    var introRequest = factory.createJoinRequest(id(), position(), bounds());
                    transport.sendToNeighbor(neighborId, introRequest);
                    log.trace("Sent introduction to neighbor {} from JoinResponse", neighborId);
                } catch (Transport.TransportException e) {
                    log.warn("Failed to introduce to neighbor {}: {}", neighborId, e.getMessage());
                }
            }
        }
    }

    private void handleMove(Message.Move move) {
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

    private void handleLeave(Message.Leave leave) {
        var state = neighborStates.get(leave.nodeId());
        if (state != null) {
            removeNeighbor(leave.nodeId());
            emitEvent(new Event.Leave(leave.nodeId(), state.position()));
            log.debug("Neighbor {} left", leave.nodeId());
        }
    }

    private void handleGhostSync(Message.GhostSync sync) {
        log.trace("Received GHOST_SYNC from {} with {} ghosts at bucket {}",
                  sync.sourceBubbleId(), sync.ghosts().size(), sync.bucket());
        // Ghost handling is delegated to external ghost manager
        // Emit event for external processing
        emitEvent(new Event.GhostSync(sync.sourceBubbleId(), sync.ghosts(), sync.bucket()));
    }

    private void handleAck(Message.Ack ack) {
        log.trace("Received ACK from {} for {}", ack.senderId(), ack.ackFor());
        // ACKs are handled by transport layer for async operations
    }

    private void handleQuery(Message.Query query) {
        log.debug("Received Query from {} for '{}' (queryId: {})",
            query.senderId(), query.queryType(), query.queryId());

        String responseData;
        try {
            responseData = switch (query.queryType()) {
                case "position" -> {
                    var pos = position();
                    yield String.format("%f,%f,%f", pos.getX(), pos.getY(), pos.getZ());
                }
                case "neighbors" -> {
                    if (neighbors().isEmpty()) {
                        yield "";
                    }
                    yield String.join(",", neighbors().stream()
                        .map(UUID::toString)
                        .toList());
                }
                default -> {
                    log.warn("Unknown query type: {}", query.queryType());
                    yield "error:unknown_query_type";
                }
            };

            // Send QueryResponse back to querier
            var response = factory.createQueryResponse(query.queryId(), id(), responseData);
            transport.sendToNeighbor(query.senderId(), response);
            log.trace("Sent QueryResponse to {} (queryId: {})", query.senderId(), query.queryId());

        } catch (Exception e) {
            log.error("Error handling query from {}: {}", query.senderId(), e.getMessage(), e);
            // Send error response
            try {
                var errorResponse = factory.createQueryResponse(
                    query.queryId(), id(), "error:" + e.getMessage());
                transport.sendToNeighbor(query.senderId(), errorResponse);
            } catch (Exception e2) {
                log.error("Failed to send error response: {}", e2.getMessage());
            }
        }
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

    private Transport.MemberInfo findAcceptorForPosition(Point3D position) {
        // Use transport's routing to find the member responsible for this position
        // For now, use a simple approach: get any available member
        // In production, this would use TetreeKey routing
        try {
            var bounds = this.bounds();
            if (bounds != null && bounds.rootKey() != null) {
                return transport.routeToKey(bounds.rootKey());
            }
        } catch (Transport.TransportException e) {
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
