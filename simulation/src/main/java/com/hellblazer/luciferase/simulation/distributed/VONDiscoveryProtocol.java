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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.Event;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * VON neighbor discovery protocol for distributed environments.
 * <p>
 * Responsibilities:
 * - Handle JOIN/MOVE/LEAVE events
 * - Update topology on neighbor changes
 * - Cross-process communication via ProcessCoordinator
 * - Event emission for subscribers
 * - Message ordering validation
 * <p>
 * Protocol Flow:
 * 1. JOIN: New bubble registers, neighbors updated
 * 2. MOVE: Bubble moves, neighbor list may change
 * 3. LEAVE: Bubble departs, removed from neighbor lists
 * <p>
 * Message Ordering:
 * - Respects CoordinatorMessage sequence numbers
 * - Rejects out-of-order messages
 * - Detects and logs duplicates
 * <p>
 * Architecture Decisions:
 * - D6B.3: Neighbor Lookups (lazy discovery with caching)
 * - D6B.4: Remote Proxies (uniform local/remote interface)
 * - D6B.6: Message Sequence Numbers (ordering guarantees)
 *
 * @author hal.hildebrand
 */
public class VONDiscoveryProtocol {

    private static final Logger log = LoggerFactory.getLogger(VONDiscoveryProtocol.class);

    private final ProcessCoordinator coordinator;
    private final MessageOrderValidator validator;
    private final CrossProcessNeighborIndex neighborIndex;

    // Bubble state tracking
    private final ConcurrentHashMap<UUID, BubbleState> bubbleStates;

    // Event subscribers
    private final List<Consumer<Event>> eventSubscribers;

    /**
     * Create a VONDiscoveryProtocol.
     *
     * @param coordinator ProcessCoordinator for topology authority
     * @param validator   MessageOrderValidator for sequence validation
     */
    public VONDiscoveryProtocol(ProcessCoordinator coordinator, MessageOrderValidator validator) {
        this.coordinator = Objects.requireNonNull(coordinator, "ProcessCoordinator cannot be null");
        this.validator = Objects.requireNonNull(validator, "MessageOrderValidator cannot be null");
        this.neighborIndex = new CrossProcessNeighborIndex(coordinator.getRegistry());
        this.bubbleStates = new ConcurrentHashMap<>();
        this.eventSubscribers = new CopyOnWriteArrayList<>();
    }

    /**
     * Subscribe to VON discovery events.
     *
     * @param subscriber Consumer to receive events
     */
    public void subscribeToEvents(Consumer<Event> subscriber) {
        Objects.requireNonNull(subscriber, "Subscriber cannot be null");
        eventSubscribers.add(subscriber);
        log.debug("Added event subscriber");
    }

    /**
     * Unsubscribe from VON discovery events.
     *
     * @param subscriber Consumer to remove
     */
    public void unsubscribeFromEvents(Consumer<Event> subscriber) {
        eventSubscribers.remove(subscriber);
        log.debug("Removed event subscriber");
    }

    /**
     * Handle a bubble JOIN event.
     *
     * @param bubbleId UUID of joining bubble
     * @param position Initial position
     */
    public void handleJoin(UUID bubbleId, Point3D position) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");
        Objects.requireNonNull(position, "Position cannot be null");

        log.debug("Handling JOIN for bubble {} at {}", bubbleId, position);

        // Update state
        bubbleStates.put(bubbleId, new BubbleState(bubbleId, position, System.currentTimeMillis()));

        // Emit event
        emitEvent(new Event.Join(bubbleId, position));

        // Invalidate neighbor cache
        neighborIndex.invalidateCache(bubbleId);
    }

    /**
     * Handle a bubble MOVE event.
     *
     * @param bubbleId    UUID of moving bubble
     * @param newPosition New position
     */
    public void handleMove(UUID bubbleId, Point3D newPosition) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");
        Objects.requireNonNull(newPosition, "New position cannot be null");

        var state = bubbleStates.get(bubbleId);
        if (state == null) {
            log.warn("MOVE event for unknown bubble {}, treating as JOIN", bubbleId);
            handleJoin(bubbleId, newPosition);
            return;
        }

        log.debug("Handling MOVE for bubble {} to {}", bubbleId, newPosition);

        // Update state
        bubbleStates.put(bubbleId, new BubbleState(bubbleId, newPosition, System.currentTimeMillis()));

        // Emit event
        emitEvent(new Event.Move(bubbleId, newPosition, null));

        // Invalidate neighbor cache (neighbors may change with movement)
        neighborIndex.invalidateCache(bubbleId);
    }

    /**
     * Handle a bubble LEAVE event.
     *
     * @param bubbleId UUID of leaving bubble
     */
    public void handleLeave(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");

        var state = bubbleStates.remove(bubbleId);
        if (state == null) {
            log.warn("LEAVE event for unknown bubble {}", bubbleId);
            return;
        }

        log.debug("Handling LEAVE for bubble {}", bubbleId);

        // Emit event
        emitEvent(new Event.Leave(bubbleId, state.position));

        // Invalidate neighbor cache
        neighborIndex.invalidateCache(bubbleId);
    }

    /**
     * Process a topology update message.
     * <p>
     * Validates message ordering before processing.
     *
     * @param message TopologyUpdateMessage to process
     */
    public void processTopologyUpdate(TopologyUpdateMessage message) {
        Objects.requireNonNull(message, "Message cannot be null");

        // Validate message ordering
        var result = validator.validateMessage(message);

        if (!result.isValid()) {
            if (result.isReordered()) {
                log.warn("Rejecting out-of-order message: seq {} (last seen: {})",
                        message.sequenceNumber(), result.lastSeenSequence());
            } else if (result.isDropped()) {
                log.warn("Gap detected in message sequence: {} messages dropped",
                        result.messageGap());
            }
            return;
        }

        log.trace("Processing topology update: seq {}, {} bubbles",
                message.sequenceNumber(), message.topology().size());

        // Update topology state based on message
        // In Phase 6B3, this is minimal - just log
        // Phase 6B4+ will add full topology synchronization
    }

    /**
     * Get neighbors for a bubble (lazy resolution).
     *
     * @param bubbleId UUID of bubble
     * @return Set of neighbor UUIDs (empty if bubble unknown)
     */
    public Set<UUID> getNeighbors(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");

        // Check if bubble exists in our state
        if (!bubbleStates.containsKey(bubbleId)) {
            log.debug("No state for bubble {}, returning empty neighbors", bubbleId);
            return Set.of();
        }

        // Lazy neighbor resolution would happen here
        // For now, return empty set
        return Set.of();
    }

    /**
     * Check if a bubble is registered (has state in the discovery protocol).
     *
     * @param bubbleId UUID of the bubble to check
     * @return true if bubble is registered, false otherwise
     */
    public boolean hasBubble(UUID bubbleId) {
        return bubbleStates.containsKey(bubbleId);
    }

    /**
     * Shutdown the protocol.
     * <p>
     * Clears all state and unsubscribes event listeners.
     */
    public void shutdown() {
        log.debug("Shutting down VONDiscoveryProtocol");
        bubbleStates.clear();
        eventSubscribers.clear();
    }

    /**
     * Emit an event to all subscribers.
     *
     * @param event Event to emit
     */
    private void emitEvent(Event event) {
        for (var subscriber : eventSubscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.warn("Error in event subscriber: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Get the neighbor index.
     *
     * @return CrossProcessNeighborIndex
     */
    public CrossProcessNeighborIndex getNeighborIndex() {
        return neighborIndex;
    }

    /**
     * Bubble state tracking.
     */
    private static class BubbleState {
        final UUID bubbleId;
        final Point3D position;
        final long lastUpdate;

        BubbleState(UUID bubbleId, Point3D position, long lastUpdate) {
            this.bubbleId = bubbleId;
            this.position = position;
            this.lastUpdate = lastUpdate;
        }
    }
}
