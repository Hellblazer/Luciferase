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
import java.util.concurrent.*;
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

    /**
     * Immutable holder for Clock and MessageFactory to guarantee atomic reads.
     * <p>
     * Fix for Luciferase-rpid: prevents race condition where clock and factory
     * become inconsistent when setClock() is called concurrently with message creation.
     */
    private static final class ClockContext {
        final Clock clock;
        final MessageFactory factory;

        ClockContext(Clock clock) {
            this.clock = clock;
            this.factory = new MessageFactory(clock);
        }
    }

    private final Transport transport;
    private final Map<UUID, NeighborState> neighborStates;
    private final Set<UUID> introducedTo;  // Track neighbors we've introduced ourselves to
    private final List<Consumer<Event>> eventListeners;
    private final Consumer<Message> messageHandler;
    private volatile ClockContext clockContext = new ClockContext(Clock.system());
    private volatile boolean closed = false;  // Track if close() has been called (for idempotency)

    // JOIN response retry management
    private final Map<UUID, PendingJoinResponse> pendingJoinResponses = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryScheduler;
    private static final int MAX_JOIN_RETRIES = 5;
    private static final long INITIAL_RETRY_DELAY_MS = 50;

    /**
     * Set the clock for deterministic testing.
     * <p>
     * Updates both clock and factory atomically by creating a new ClockContext.
     * No synchronization needed - single volatile write guarantees atomicity.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clockContext = new ClockContext(clock);  // Atomic swap
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
        this.neighborStates = new ConcurrentHashMap<>();
        this.introducedTo = ConcurrentHashMap.newKeySet();
        this.eventListeners = new CopyOnWriteArrayList<>();
        this.retryScheduler = Executors.newScheduledThreadPool(1, r -> {
            var t = new Thread(r, "join-retry-" + id);
            t.setDaemon(true);
            return t;
        });

        // Schedule periodic sweep to clean up orphaned JOIN response entries
        // (Fix for Luciferase-ziyl: prevent memory leak from failed JOINs)
        retryScheduler.scheduleAtFixedRate(() -> {
            try {
                var ctx = clockContext;  // Single volatile read
                var now = ctx.clock.currentTimeMillis();
                var removed = 0;

                // Remove entries older than 60 seconds
                var iterator = pendingJoinResponses.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (now - entry.getValue().firstAttemptTime() > 60_000) {
                        // Cancel the scheduled future to prevent further retries
                        var future = entry.getValue().retryFuture();
                        if (future != null && !future.isDone()) {
                            future.cancel(false);
                        }
                        iterator.remove();
                        removed++;
                    }
                }

                if (removed > 0) {
                    log.debug("Swept {} orphaned JOIN response entries", removed);
                }
            } catch (Exception e) {
                log.error("Error during JOIN response sweep: {}", e.getMessage(), e);
            }
        }, 60, 60, TimeUnit.SECONDS);

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
            var ctx = clockContext;  // Single volatile read
            neighborStates.put(neighbor.id(), new NeighborState(
                neighbor.id(),
                neighbor.position(),
                neighbor.bounds(),
                ctx.clock.currentTimeMillis()
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
        var ctx = clockContext;  // Single volatile read
        neighborStates.put(neighbor.id(), new NeighborState(
            neighbor.id(),
            neighbor.position(),
            neighbor.bounds(),
            ctx.clock.currentTimeMillis()
        ));
        emitEvent(new Event.Join(neighbor.id(), neighbor.position()));
        log.debug("Neighbor {} joined at {}", neighbor.id(), neighbor.position());
    }

    @Override
    public void addNeighbor(UUID neighborId) {
        super.addVonNeighbor(neighborId);
        if (!neighborStates.containsKey(neighborId)) {
            // Initialize with unknown state - will be updated on first message
            var ctx = clockContext;  // Single volatile read
            neighborStates.put(neighborId, new NeighborState(
                neighborId,
                new Point3D(0, 0, 0),
                null,
                ctx.clock.currentTimeMillis()
            ));
        }
    }

    @Override
    public void removeNeighbor(UUID neighborId) {
        super.removeVonNeighbor(neighborId);
        neighborStates.remove(neighborId);
        introducedTo.remove(neighborId);  // Clean up introducedTo to prevent memory leak
    }

    // ========== P2P Transport Methods ==========

    /**
     * Send a MOVE notification to all neighbors.
     * <p>
     * Called when this bubble's position or bounds change significantly.
     */
    public void broadcastMove() {
        var ctx = clockContext;  // Single volatile read
        var moveMsg = ctx.factory.createMove(id(), position(), bounds());

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
        var ctx = clockContext;  // Single volatile read
        var leaveMsg = ctx.factory.createLeave(id());

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
            var ctx = clockContext;  // Single volatile read
            var joinRequest = ctx.factory.createJoinRequest(id(), targetPosition, bounds());
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
        var ctx = clockContext;  // Single volatile read
        return ctx.factory.createMove(id(), position(), bounds());
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
     * Unregisters message handler. Note: broadcastLeave() is handled by
     * LifecycleCoordinator during shutdown to prevent duplicate calls.
     */
    public void close() {
        // Idempotent: return immediately if already closed
        if (closed) {
            log.debug("Bubble {} already closed - idempotent no-op", id());
            return;
        }
        closed = true;

        // Broadcast LEAVE to neighbors before cleanup (graceful departure)
        // This ensures neighbors are notified while transport is still available
        broadcastLeave();

        transport.removeMessageHandler(messageHandler);

        // Cancel all pending retries
        pendingJoinResponses.values().forEach(pending -> {
            if (pending.retryFuture != null && !pending.retryFuture.isDone()) {
                pending.retryFuture.cancel(false);
            }
        });
        pendingJoinResponses.clear();

        retryScheduler.shutdown();
        try {
            if (!retryScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                retryScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        neighborStates.clear();
        introducedTo.clear();  // Clean up introducedTo to prevent memory leak
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

        var ctx = clockContext;  // Single volatile read
        var response = ctx.factory.createJoinResponse(id(), neighborInfos);
        sendJoinResponseWithRetry(req.joinerId(), response, 0);

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
        var ctx = clockContext;  // Single volatile read
        try {
            transport.sendToNeighbor(resp.acceptorId(), ctx.factory.createAck(resp.acceptorId(), id()));
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
                    var introRequest = ctx.factory.createJoinRequest(id(), position(), bounds());
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

        var ctx = clockContext;  // Single volatile read
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
            var response = ctx.factory.createQueryResponse(query.queryId(), id(), responseData);
            transport.sendToNeighbor(query.senderId(), response);
            log.trace("Sent QueryResponse to {} (queryId: {})", query.senderId(), query.queryId());

        } catch (Exception e) {
            log.error("Error handling query from {}: {}", query.senderId(), e.getMessage(), e);
            // Send error response
            try {
                var errorResponse = ctx.factory.createQueryResponse(
                    query.queryId(), id(), "error:" + e.getMessage());
                transport.sendToNeighbor(query.senderId(), errorResponse);
            } catch (Exception e2) {
                log.error("Failed to send error response: {}", e2.getMessage());
            }
        }
    }

    /**
     * Send JOIN response with exponential backoff retry.
     * <p>
     * Retry intervals: 50ms, 100ms, 200ms, 400ms, 800ms
     * After max retries (5), performs compensation (removes neighbor).
     *
     * @param joinerId      UUID of the joining node
     * @param response      JoinResponse message
     * @param attemptCount  Current attempt number (0-based)
     */
    private void sendJoinResponseWithRetry(UUID joinerId, Message.JoinResponse response, int attemptCount) {
        try {
            transport.sendToNeighbor(joinerId, response);

            // Success - remove from pending if present
            pendingJoinResponses.remove(joinerId);
            log.debug("Successfully sent JOIN response to {} after {} attempts", joinerId, attemptCount + 1);

        } catch (Transport.TransportException e) {
            if (attemptCount >= MAX_JOIN_RETRIES - 1) {
                // Max retries exceeded - perform compensation
                log.error("Failed to send JOIN response to {} after {} attempts: {}. Removing neighbor.",
                         joinerId, attemptCount + 1, e.getMessage());
                compensateFailedJoin(joinerId);
            } else {
                // Schedule retry with exponential backoff
                var nextAttempt = attemptCount + 1;
                var delayMs = INITIAL_RETRY_DELAY_MS * (1L << attemptCount);  // 50, 100, 200, 400, 800

                log.warn("Failed to send JOIN response to {} (attempt {}/{}): {}. Retrying in {}ms",
                        joinerId, attemptCount + 1, MAX_JOIN_RETRIES, e.getMessage(), delayMs);

                var retryFuture = retryScheduler.schedule(
                    () -> sendJoinResponseWithRetry(joinerId, response, nextAttempt),
                    delayMs,
                    TimeUnit.MILLISECONDS
                );

                // Track pending retry - preserve firstAttemptTime from previous entry or use current time
                var ctx = clockContext;  // Single volatile read
                var existingEntry = pendingJoinResponses.get(joinerId);
                var firstAttemptTime = existingEntry != null ? existingEntry.firstAttemptTime() : ctx.clock.currentTimeMillis();

                pendingJoinResponses.put(joinerId, new PendingJoinResponse(
                    joinerId, response, nextAttempt, retryFuture, firstAttemptTime
                ));
            }
        }
    }

    /**
     * Compensation logic for failed JOIN response.
     * <p>
     * Removes the neighbor and cleans up state to prevent asymmetric relationships.
     *
     * @param joinerId UUID of the joiner that couldn't receive the response
     */
    private void compensateFailedJoin(UUID joinerId) {
        removeNeighbor(joinerId);
        pendingJoinResponses.remove(joinerId);
        log.debug("Compensated failed JOIN for {}: removed neighbor and cleaned up state", joinerId);
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

    /**
     * Tracks pending JOIN response retry state.
     *
     * @param joinerId         UUID of the joining node
     * @param response         JoinResponse message to send
     * @param attemptCount     Number of send attempts so far
     * @param retryFuture      Scheduled future for the next retry
     * @param firstAttemptTime Timestamp of the first send attempt (for orphan detection)
     */
    private record PendingJoinResponse(
        UUID joinerId,
        Message.JoinResponse response,
        int attemptCount,
        ScheduledFuture<?> retryFuture,
        long firstAttemptTime
    ) {
    }
}
