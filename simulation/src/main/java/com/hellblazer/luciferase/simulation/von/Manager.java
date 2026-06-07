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
import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.lifecycle.EnhancedBubbleAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.LifecycleComponent;
import com.hellblazer.luciferase.simulation.lifecycle.LifecycleCoordinator;
import javax.vecmath.Point3d;
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
    private final LifecycleCoordinator coordinator;
    // RDR-017 P0 (Luciferase-vhhu0): lifecycle dependencies declared on every bubble adapter created
    // by this manager. Default empty = bubbles register at Layer 0 (pre-RDR-017 behavior). The node
    // bootstrap sets this to List.of("PersistenceManager") after registering the persistence adapter,
    // so bubbles register at Layer 1 and start only after persistence is up.
    private volatile List<String> bubbleDependencies = List.of();

    /**
     * Create a Manager with default configuration.
     * <p>
     * Defaults: {@code spatialLevel = }{@link SpatialLevelHeuristic#DEFAULT_SPATIAL_LEVEL}
     * (computed from {@code aoiRadius = 50}; currently {@code 18}),
     * {@code targetFrameMs = 16}, {@code aoiRadius = 50.0}.
     * <p>
     * <b>Behavior change (RDR-003 Phase 0 Step 0):</b> the default {@code spatialLevel}
     * previously was a hardcoded {@code 10} (cell-edge {@code 2048}), which collapsed the
     * default {@code 200}-unit VoN world into a single Tetree cell and made any spatial-
     * index query degenerate to a linear scan. It is now computed from the AoI radius via
     * {@link SpatialLevelHeuristic#computeDefault(float)} targeting
     * {@code r &approx; 8&middot;cell-edge}. Tests or callers that implicitly depended on
     * single-cell bucketing should use the explicit 4-arg or 5-arg constructor with a
     * deliberate {@code spatialLevel}.
     *
     * @param transportRegistry Transport registry for P2P communication
     */
    public Manager(LocalServerTransport.Registry transportRegistry) {
        this(transportRegistry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
             SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
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

        // Initialize persistent lifecycle coordinator
        this.coordinator = new LifecycleCoordinator();
        this.coordinator.start(); // Empty coordinator starts instantly (<50ms)

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
     * Register an infrastructure lifecycle component (e.g. {@code SocketConnectionManagerAdapter},
     * {@code PersistenceManagerAdapter}) with this manager's coordinator.
     * <p>
     * RDR-017 P0 (Luciferase-vhhu0): the node bootstrap calls this to register Layer-0 infrastructure
     * <b>before</b> any bubble is created, so that bubble adapters declaring a dependency on those
     * components (see {@link #setBubbleDependencies(List)}) resolve at registration time.
     *
     * @param component the infrastructure component to register and start
     */
    public void registerInfrastructure(LifecycleComponent component) {
        Objects.requireNonNull(component, "component cannot be null");
        coordinator.registerAndStart(component);
        log.info("Registered infrastructure component: {}", component.name());
    }

    /**
     * Set the lifecycle dependencies declared on every bubble adapter created by this manager.
     * <p>
     * RDR-017 P0: the node bootstrap sets this to {@code List.of("PersistenceManager")} after
     * {@link #registerInfrastructure(LifecycleComponent)} so bubbles register at Layer 1 and start
     * only after persistence. Each named dependency must already be registered (or be registered
     * before the next {@code createBubble}) or {@code createBubble} will fail to register the bubble.
     *
     * @param dependencies component names new bubbles depend on (defensively copied)
     */
    public void setBubbleDependencies(List<String> dependencies) {
        this.bubbleDependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies cannot be null"));
    }

    /**
     * @return the lifecycle dependencies declared on bubble adapters created by this manager
     */
    public List<String> getBubbleDependencies() {
        return bubbleDependencies;
    }

    /**
     * Package-private accessor to the lifecycle coordinator, for the node bootstrap and tests to
     * observe component state and layering. Not part of the public API.
     *
     * @return this manager's lifecycle coordinator
     */
    LifecycleCoordinator coordinator() {
        return coordinator;
    }

    /**
     * Create a new Bubble and register it with the manager.
     * <p>
     * The new bubble inherits the manager's clock for deterministic timestamps.
     * Registers bubble with lifecycle coordinator for graceful shutdown coordination.
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

        // Register with lifecycle coordinator for graceful shutdown
        // Non-fatal if registration fails - bubble still works without coordinator
        if (bubble instanceof EnhancedBubble enhanced) {
            try {
                var adapter = new EnhancedBubbleAdapter(enhanced, enhanced.getRealTimeController(),
                                                        bubbleDependencies);
                coordinator.registerAndStart(adapter);
                log.debug("Created and registered bubble: {}", id);
            } catch (Exception e) {
                log.warn("Failed to register bubble {} with lifecycle coordinator: {}", id, e.getMessage());
                // Continue - bubble still functional without coordinator
            }
        } else {
            log.debug("Created bubble: {}", id);
        }

        return bubble;
    }

    /**
     * Create a new Bubble with a specific ID.
     * <p>
     * The new bubble inherits the manager's clock for deterministic timestamps.
     * Registers bubble with lifecycle coordinator for graceful shutdown coordination.
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

        // Register with lifecycle coordinator for graceful shutdown
        // Non-fatal if registration fails - bubble still works without coordinator
        if (bubble instanceof EnhancedBubble enhanced) {
            try {
                var adapter = new EnhancedBubbleAdapter(enhanced, enhanced.getRealTimeController(),
                                                        bubbleDependencies);
                coordinator.registerAndStart(adapter);
                log.debug("Created and registered bubble with ID: {}", id);
            } catch (Exception e) {
                log.warn("Failed to register bubble {} with lifecycle coordinator: {}", id, e.getMessage());
                // Continue - bubble still functional without coordinator
            }
        } else {
            log.debug("Created bubble with ID: {}", id);
        }

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
    public boolean joinAt(Bubble bubble, Point3d position) {
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
    public boolean joinAndWait(Bubble bubble, Point3d position, long timeoutMs) {
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
    public void move(Bubble bubble, Point3d newPosition) {
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
     * Uses persistent lifecycle coordinator for single-bubble graceful shutdown.
     * The adapter ensures broadcastLeave() is called exactly once during stop().
     *
     * @param bubble The bubble to remove
     */
    public void leave(Bubble bubble) {
        Objects.requireNonNull(bubble, "bubble cannot be null");

        // Use persistent coordinator for graceful shutdown
        if (bubble instanceof EnhancedBubble enhanced) {
            var adapterName = "EnhancedBubble-" + bubble.id();
            try {
                coordinator.stopAndUnregister(adapterName);
                log.debug("Bubble {} gracefully departed via lifecycle coordinator", bubble.id());
            } catch (Exception e) {
                log.warn("Coordinator stop failed for bubble {}, falling back to direct close: {}",
                         bubble.id(), e.getMessage());
                // Fallback to direct close (idempotent)
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
     * Uses persistent lifecycle coordinator for ordered shutdown.
     * This ensures components stop in proper dependency order and broadcastLeave()
     * is called exactly once per bubble.
     */
    public void close() {
        log.info("Starting coordinated shutdown of {} bubbles", bubbles.size());

        // Use persistent coordinator for graceful shutdown of all registered bubbles
        try {
            coordinator.stop(5000); // 5 second timeout for shutdown
            log.info("Coordinated shutdown completed");
        } catch (Exception e) {
            log.warn("Error during coordinated shutdown: {}", e.getMessage());
        }

        // Safety net: Close any bubbles directly (idempotent)
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
