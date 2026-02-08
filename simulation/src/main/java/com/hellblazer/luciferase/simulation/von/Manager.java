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

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.lifecycle.EnhancedBubbleAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.LifecycleCoordinator;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manager for coordinating Bubbles with P2P transport.
 * <p>
 * Manager provides a high-level API for VON operations in a distributed setting:
 * <ul>
 *   <li>Create and manage Bubbles with P2P transport</li>
 *   <li>Coordinate JOIN via Fireflies member discovery</li>
 *   <li>Track MOVE and LEAVE across the network</li>
 *   <li>Monitor neighbor consistency (NC) metric</li>
 * </ul>
 * <p>
 * In v4.0 architecture:
 * <ul>
 *   <li>VON IS the distributed spatial index (no separate ReplicatedForest)</li>
 *   <li>Point-to-point communication after JOIN (no broadcast)</li>
 *   <li>Fireflies for initial contact only, then P2P</li>
 * </ul>
 * <p>
 * Thread-safe for concurrent bubble operations.
 *
 * @author hal.hildebrand
 */
public class Manager {

    private static final Logger log = LoggerFactory.getLogger(Manager.class);

    private final Map<UUID, Bubble> bubbles;
    private final LocalServerTransport.Registry transportRegistry;
    private volatile MessageFactory factory;
    private final List<Consumer<Event>> eventListeners;
    private final byte spatialLevel;
    private final long targetFrameMs;
    private final float aoiRadius;
    private volatile Clock clock;

    /**
     * Create a Manager with default configuration.
     *
     * @param transportRegistry Transport registry for P2P communication
     */
    public Manager(LocalServerTransport.Registry transportRegistry) {
        this(transportRegistry, (byte) 10, 16L, 50.0f);
    }

    /**
     * Create a Manager with custom configuration using system clock.
     *
     * @param transportRegistry Transport registry for P2P communication
     * @param spatialLevel      Tetree refinement level for bubbles
     * @param targetFrameMs     Target frame time for simulation
     * @param aoiRadius         Area of Interest radius for neighbor detection
     */
    public Manager(LocalServerTransport.Registry transportRegistry,
                      byte spatialLevel, long targetFrameMs, float aoiRadius) {
        this(transportRegistry, spatialLevel, targetFrameMs, aoiRadius, Clock.system());
    }

    /**
     * Create a Manager with custom configuration and injected clock.
     * <p>
     * Use this constructor for deterministic testing with a TestClock.
     *
     * @param transportRegistry Transport registry for P2P communication
     * @param spatialLevel      Tetree refinement level for bubbles
     * @param targetFrameMs     Target frame time for simulation
     * @param aoiRadius         Area of Interest radius for neighbor detection
     * @param clock             Clock for timestamps (use TestClock for testing)
     */
    public Manager(LocalServerTransport.Registry transportRegistry,
                      byte spatialLevel, long targetFrameMs, float aoiRadius, Clock clock) {
        this.transportRegistry = Objects.requireNonNull(transportRegistry, "transportRegistry cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.factory = new MessageFactory(clock);
        this.bubbles = new ConcurrentHashMap<>();
        this.eventListeners = new CopyOnWriteArrayList<>();
        this.spatialLevel = spatialLevel;
        this.targetFrameMs = targetFrameMs;
        this.aoiRadius = aoiRadius;

        log.info("Manager created: spatialLevel={}, targetFrameMs={}, aoiRadius={}",
                spatialLevel, targetFrameMs, aoiRadius);
    }

    /**
     * Set the clock for deterministic testing.
     * <p>
     * Updates the manager's factory and propagates the clock to all existing bubbles.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.factory = new MessageFactory(clock);

        // Propagate to all existing bubbles
        for (var bubble : bubbles.values()) {
            bubble.setClock(clock);
        }

        log.debug("Clock updated and propagated to {} bubbles", bubbles.size());
    }

    /**
     * Create a new Bubble and register it with the manager.
     * <p>
     * The new bubble inherits the manager's clock for deterministic timestamps.
     *
     * @return The newly created Bubble
     */
    public Bubble createBubble() {
        var id = UUID.randomUUID();
        var transport = transportRegistry.register(id);
        var bubble = new Bubble(id, spatialLevel, targetFrameMs, transport);

        // Propagate clock to new bubble
        bubble.setClock(clock);

        // Forward events to manager listeners
        bubble.addEventListener(this::dispatchEvent);

        bubbles.put(id, bubble);
        log.debug("Created bubble: {}", id);

        return bubble;
    }

    /**
     * Create a new Bubble with a specific ID.
     * <p>
     * The new bubble inherits the manager's clock for deterministic timestamps.
     *
     * @param id The UUID for the bubble
     * @return The newly created Bubble
     */
    public Bubble createBubble(UUID id) {
        var transport = transportRegistry.register(id);
        var bubble = new Bubble(id, spatialLevel, targetFrameMs, transport);

        // Propagate clock to new bubble
        bubble.setClock(clock);

        // Forward events to manager listeners
        bubble.addEventListener(this::dispatchEvent);

        bubbles.put(id, bubble);
        log.debug("Created bubble with ID: {}", id);

        return bubble;
    }

    /**
     * Join a bubble to the VON at a specific position.
     * <p>
     * If there are no other bubbles (first join), the bubble becomes solo.
     * Otherwise, it contacts an existing bubble and receives neighbor list.
     *
     * @param bubble   The bubble to join
     * @param position Target position in the VON
     * @return true if join succeeded, false otherwise
     */
    public boolean joinAt(Bubble bubble, Point3D position) {
        Objects.requireNonNull(bubble, "bubble cannot be null");
        Objects.requireNonNull(position, "position cannot be null");

        if (bubbles.size() == 1 && bubbles.containsKey(bubble.id())) {
            // First bubble - solo join (no neighbors to contact)
            log.info("Solo join for bubble {}", bubble.id());
            return true;
        }

        // Find an existing bubble to join via
        var entryPoint = findEntryPoint(bubble.id());
        if (entryPoint == null) {
            log.warn("No entry point found for join");
            return false;
        }

        // Send JoinRequest to entry point
        try {
            var joinRequest = factory.createJoinRequest(bubble.id(), position, bubble.bounds());
            bubble.getTransport().sendToNeighbor(entryPoint.id(), joinRequest);
            log.debug("Sent JOIN request from {} to entry point {}", bubble.id(), entryPoint.id());
            return true;
        } catch (Transport.TransportException e) {
            log.error("Failed to send JOIN request: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Join a bubble and wait for neighbors to be established.
     *
     * @param bubble         The bubble to join
     * @param position       Target position
     * @param timeoutMs      Maximum time to wait for join completion
     * @return true if join completed with at least one neighbor, false otherwise
     */
    public boolean joinAndWait(Bubble bubble, Point3D position, long timeoutMs) {
        if (bubbles.size() == 1 && bubbles.containsKey(bubble.id())) {
            // Solo join - immediate success
            return true;
        }

        var neighborReceived = new CountDownLatch(1);
        Consumer<Event> joinListener = event -> {
            if (event instanceof Event.Join join && join.nodeId().equals(bubble.id())) {
                // This bubble was acknowledged
                neighborReceived.countDown();
            }
        };

        bubble.addEventListener(joinListener);

        try {
            if (!joinAt(bubble, position)) {
                return false;
            }

            // Wait for join confirmation (neighbor list received)
            return neighborReceived.await(timeoutMs, TimeUnit.MILLISECONDS) ||
                   !bubble.neighbors().isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            bubble.removeEventListener(joinListener);
        }
    }

    /**
     * Move a bubble to a new position and notify neighbors.
     *
     * @param bubble      The bubble to move
     * @param newPosition New position
     */
    public void move(Bubble bubble, Point3D newPosition) {
        Objects.requireNonNull(bubble, "bubble cannot be null");
        Objects.requireNonNull(newPosition, "newPosition cannot be null");

        // Update bubble entities (caller should have already done this)
        // This triggers internal position recalculation

        // Broadcast move to all P2P neighbors
        bubble.broadcastMove();

        log.trace("Bubble {} moved to {}", bubble.id(), newPosition);
    }

    /**
     * Remove a bubble from the VON gracefully.
     * <p>
     * Phase 5: Uses EnhancedBubbleAdapter for single-bubble graceful shutdown.
     * The adapter ensures broadcastLeave() is called exactly once during stop().
     *
     * @param bubble The bubble to remove
     */
    public void leave(Bubble bubble) {
        Objects.requireNonNull(bubble, "bubble cannot be null");

        // Phase 5: Use adapter for graceful shutdown
        // EnhancedBubbleAdapter handles broadcastLeave() during stop()
        if (bubble instanceof EnhancedBubble enhanced) {
            try {
                var adapter = new EnhancedBubbleAdapter(enhanced, enhanced.getRealTimeController());

                // Use a single-bubble coordinator for proper lifecycle management
                var coordinator = new LifecycleCoordinator();
                coordinator.register(adapter);
                coordinator.start(); // Idempotent - required before stop
                coordinator.stop(5000); // 5 second timeout

                log.debug("Bubble {} gracefully departed via lifecycle adapter", bubble.id());
            } catch (Exception e) {
                log.warn("Error during graceful leave for bubble {}: {}", bubble.id(), e.getMessage());
                // Fallback to direct close
                bubble.close();
            }
        } else {
            // Plain bubble - just close directly
            bubble.close();
        }

        // Remove from manager
        bubbles.remove(bubble.id());

        log.debug("Bubble {} left the VON", bubble.id());
    }

    /**
     * Get a bubble by ID.
     *
     * @param id Bubble UUID
     * @return Bubble or null if not found
     */
    public Bubble getBubble(UUID id) {
        return bubbles.get(id);
    }

    /**
     * Get all managed bubbles.
     *
     * @return Unmodifiable collection of bubbles
     */
    public Collection<Bubble> getAllBubbles() {
        return Collections.unmodifiableCollection(bubbles.values());
    }

    /**
     * Get the number of managed bubbles.
     *
     * @return Bubble count
     */
    public int size() {
        return bubbles.size();
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
     * Calculate Neighbor Consistency (NC) for a bubble.
     * <p>
     * NC = known_neighbors / actual_neighbors_in_aoi
     * <p>
     * This is the local view - in P2P mode, we can only compare against
     * other bubbles we know about through the manager.
     *
     * @param bubble Bubble to calculate NC for
     * @return NC value (0.0 to 1.0)
     */
    public float calculateNC(Bubble bubble) {
        if (!bubbles.containsKey(bubble.id())) {
            return 0.0f;
        }

        int knownNeighbors = bubble.neighbors().size();

        // Count bubbles within AOI radius (excluding self)
        int actualNeighbors = 0;
        for (Bubble other : bubbles.values()) {
            if (!other.id().equals(bubble.id())) {
                double dist = bubble.position().distance(other.position());
                if (dist <= aoiRadius) {
                    actualNeighbors++;
                }
            }
        }

        if (actualNeighbors == 0) {
            return 1.0f;  // Solo bubble - perfect NC
        }

        return (float) knownNeighbors / actualNeighbors;
    }

    /**
     * Get the AOI radius.
     *
     * @return Area of Interest radius
     */
    public float getAoiRadius() {
        return aoiRadius;
    }

    /**
     * Close all bubbles and release resources.
     * <p>
     * Uses LifecycleCoordinator for ordered shutdown when bubbles are EnhancedBubbles.
     * This ensures components stop in proper dependency order and broadcastLeave()
     * is called exactly once per bubble.
     */
    public void close() {
        // Phase 5: Use LifecycleCoordinator for ordered shutdown
        var coordinator = new LifecycleCoordinator();
        var adapters = new ArrayList<EnhancedBubbleAdapter>();

        // Register all bubbles as lifecycle components
        for (Bubble bubble : bubbles.values()) {
            // Bubble extends EnhancedBubble, so this cast is safe
            if (bubble instanceof EnhancedBubble enhanced) {
                try {
                    var adapter = new EnhancedBubbleAdapter(enhanced, enhanced.getRealTimeController());
                    coordinator.register(adapter);
                    adapters.add(adapter);
                    log.debug("Registered bubble {} for lifecycle shutdown", bubble.id());
                } catch (Exception e) {
                    log.warn("Failed to register bubble {} for lifecycle: {}", bubble.id(), e.getMessage());
                }
            }
        }

        // Perform ordered shutdown via coordinator
        try {
            if (!adapters.isEmpty()) {
                log.info("Starting coordinated shutdown of {} bubbles", adapters.size());
                coordinator.start(); // Idempotent - allows us to stop
                coordinator.stop(5000); // 5 second timeout for shutdown
                log.info("Coordinated shutdown completed");
            }
        } catch (Exception e) {
            log.warn("Error during coordinated shutdown: {}", e.getMessage());
        }

        // Clean up remaining bubbles that weren't coordinated
        for (Bubble bubble : bubbles.values()) {
            try {
                bubble.close();
            } catch (Exception e) {
                log.warn("Error closing bubble {}: {}", bubble.id(), e.getMessage());
            }
        }

        bubbles.clear();
        eventListeners.clear();
        log.info("Manager closed");
    }

    // ========== Private Methods ==========

    /**
     * Find an entry point for joining (any existing bubble except the joiner).
     */
    private Bubble findEntryPoint(UUID excludeId) {
        return bubbles.values().stream()
            .filter(b -> !b.id().equals(excludeId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Dispatch event to all listeners.
     * <p>
     * Package-private to allow VONRecoveryIntegration to emit events.
     */
    void dispatchEvent(Event event) {
        for (var listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("Event listener error: {}", e.getMessage());
            }
        }
    }
}
