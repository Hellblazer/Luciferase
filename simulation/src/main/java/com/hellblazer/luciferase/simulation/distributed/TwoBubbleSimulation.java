/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.loop.SimulationLoop;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import com.hellblazer.luciferase.simulation.von.VonMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two-bubble distributed simulation with ghost layer synchronization.
 * <p>
 * Creates two VonBubbles running in the same process with:
 * <ul>
 *   <li>LocalServerTransport for in-process P2P messaging</li>
 *   <li>Ghost layer synchronization for boundary entities</li>
 *   <li>Synchronized tick execution across both bubbles</li>
 *   <li>Unified visualization endpoint</li>
 * </ul>
 * <p>
 * Inc 3 Architecture:
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    TwoBubbleSimulation                          │
 * │                                                                 │
 * │  ┌──────────────────┐   Ghost Sync   ┌──────────────────┐      │
 * │  │    Bubble 1      │◄──────────────►│    Bubble 2      │      │
 * │  │  (x: 0-100)      │                │  (x: 100-200)    │      │
 * │  │  - Entities      │                │  - Entities      │      │
 * │  │  - Ghosts from 2 │                │  - Ghosts from 1 │      │
 * │  └──────────────────┘                └──────────────────┘      │
 * │           │                                    │                │
 * │           └──────────► Visualization ◄─────────┘                │
 * │                     (shows all entities)                        │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @author hal.hildebrand
 */
public class TwoBubbleSimulation implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TwoBubbleSimulation.class);

    /**
     * Default tick interval: 60fps (16.67ms).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    /**
     * Ghost sync interval: every 3 ticks (~50ms at 60fps).
     * More frequent than bucket boundaries for smoother cross-boundary flocking.
     */
    public static final int GHOST_SYNC_INTERVAL_TICKS = 3;

    /**
     * Ghost boundary width: entities within this distance of boundary are ghosted.
     */
    public static final float GHOST_BOUNDARY_WIDTH = 30.0f;

    /**
     * Ghost TTL: how many ticks ghosts remain valid.
     */
    public static final int GHOST_TTL_TICKS = 10;

    /**
     * Velocity cleanup interval: every 30 seconds at 60fps.
     */
    private static final long VELOCITY_CLEANUP_INTERVAL_TICKS = 1800;

    /**
     * Migration cooldown: minimum ticks between migrations for same entity.
     * Prevents boundary oscillation (ping-pong effect).
     */
    public static final int MIGRATION_COOLDOWN_TICKS = 30;  // 500ms at 60fps

    /**
     * Hysteresis distance: entity must be this far past boundary to trigger migration.
     * Prevents rapid toggling when entity hovers near boundary.
     */
    public static final float HYSTERESIS_DISTANCE = 2.0f;

    private final WorldBounds worldBounds;
    private final float boundaryX;  // X coordinate that divides the bubbles

    private final LocalServerTransport.Registry transportRegistry;
    private final VonBubble bubble1;
    private final VonBubble bubble2;
    private final EntityBehavior behavior1;
    private final EntityBehavior behavior2;

    private final GhostSyncCoordinator ghostSyncCoordinator;
    private final CrossBubbleMigrationManager migrationManager;
    private final VelocityTracker velocityTracker;
    private final BubbleEntityUpdater entityUpdater;
    private final SimulationTickMetrics tickMetrics;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCount = new AtomicLong(0);
    private volatile Clock clock = Clock.system();

    private ScheduledFuture<?> tickTask;


    /**
     * Create a two-bubble simulation with default parameters.
     *
     * @param entityCount Number of entities (split between bubbles)
     */
    public TwoBubbleSimulation(int entityCount) {
        this(entityCount, WorldBounds.DEFAULT, new FlockingBehavior(), new FlockingBehavior());
    }

    /**
     * Create a two-bubble simulation with custom behavior.
     *
     * @param entityCount Number of entities (split between bubbles)
     * @param worldBounds World boundary configuration
     * @param behavior1   Behavior for bubble 1
     * @param behavior2   Behavior for bubble 2
     */
    public TwoBubbleSimulation(int entityCount, WorldBounds worldBounds,
                                EntityBehavior behavior1, EntityBehavior behavior2) {
        this.worldBounds = worldBounds;
        this.boundaryX = worldBounds.center();  // Split at center
        this.behavior1 = behavior1;
        this.behavior2 = behavior2;

        // Create transport registry for in-process P2P
        this.transportRegistry = LocalServerTransport.Registry.create();

        // Create bubbles with transport
        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        var transport1 = transportRegistry.register(uuid1);
        var transport2 = transportRegistry.register(uuid2);

        this.bubble1 = new VonBubble(uuid1, (byte) 10, DEFAULT_TICK_INTERVAL_MS, transport1);
        this.bubble2 = new VonBubble(uuid2, (byte) 10, DEFAULT_TICK_INTERVAL_MS, transport2);

        // Establish neighbor relationship
        bubble1.addNeighbor(uuid2);
        bubble2.addNeighbor(uuid1);

        // Register event listeners for ghost sync
        bubble1.addEventListener(this::handleBubble1Event);
        bubble2.addEventListener(this::handleBubble2Event);

        // Populate entities (split between bubbles based on x position)
        populateEntities(entityCount);

        // Initialize velocity tracker BEFORE other components (they depend on velocity maps)
        this.velocityTracker = new VelocityTracker(bubble1, bubble2, new Random());
        velocityTracker.initializeVelocities(behavior1.getMaxSpeed(), behavior2.getMaxSpeed());

        // Initialize metrics tracker
        this.tickMetrics = new SimulationTickMetrics(bubble1, bubble2);

        // Initialize ghost synchronizer with strategy pattern
        var ghostStrategy = new TwoBubbleSyncStrategy(
            bubble1, bubble2, boundaryX,
            GHOST_BOUNDARY_WIDTH, GHOST_TTL_TICKS,
            velocityTracker.getVelocities1(), velocityTracker.getVelocities2()
        );
        this.ghostSyncCoordinator = new GhostSyncCoordinator(ghostStrategy);

        // Initialize migration manager
        this.migrationManager = new CrossBubbleMigrationManager(
            bubble1, bubble2, boundaryX,
            MIGRATION_COOLDOWN_TICKS, HYSTERESIS_DISTANCE,
            tickMetrics
        );

        // Wire up ghost sync callback to coordinate ghost layer with migrations
        var ghostCallback = new GhostSyncMigrationCallback(ghostSyncCoordinator, bubble1, bubble2);
        migrationManager.setLifecycleCallbacks(ghostCallback);

        // Initialize entity updater
        this.entityUpdater = new BubbleEntityUpdater(worldBounds);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "TwoBubbleSimulation");
            t.setDaemon(true);
            return t;
        });

        // Validate ghost sync parameters - warn if entities could cross boundary without being ghosted
        float maxDistancePerInterval = Math.max(behavior1.getMaxSpeed(), behavior2.getMaxSpeed())
            * (GHOST_SYNC_INTERVAL_TICKS * DEFAULT_TICK_INTERVAL_MS / 1000.0f);
        if (maxDistancePerInterval > GHOST_BOUNDARY_WIDTH) {
            log.warn("Entity max speed ({}) may cause boundary crossing without ghost sync. " +
                     "Max distance per sync interval: {}, ghost boundary width: {}. " +
                     "Consider increasing GHOST_BOUNDARY_WIDTH or reducing GHOST_SYNC_INTERVAL_TICKS.",
                     Math.max(behavior1.getMaxSpeed(), behavior2.getMaxSpeed()),
                     maxDistancePerInterval, GHOST_BOUNDARY_WIDTH);
        }

        log.info("TwoBubbleSimulation created: {} entities in bubble1, {} in bubble2, boundary at x={}",
                 bubble1.entityCount(), bubble2.entityCount(), boundaryX);
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Start the simulation.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Velocities initialized in constructor

            tickTask = scheduler.scheduleAtFixedRate(
                this::tick,
                0,
                DEFAULT_TICK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );

            log.info("TwoBubbleSimulation started");
        }
    }

    /**
     * Stop the simulation.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (tickTask != null) {
                tickTask.cancel(false);
                tickTask = null;
            }
            log.info("TwoBubbleSimulation stopped after {} ticks. {}", tickCount.get(), tickMetrics);
        }
    }

    @Override
    public void close() {
        stop();

        // Clear component state to prevent memory leaks
        ghostSyncCoordinator.clear();
        // Note: velocityTracker manages velocity maps internally
        // Note: migrationManager manages cooldown map internally

        // Shutdown scheduler with timeout
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate within 1 second");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for scheduler termination");
        }

        bubble1.close();
        bubble2.close();
        transportRegistry.close();
    }

    /**
     * Check if simulation is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get tick count.
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Get simulation metrics.
     */
    public SimulationMetrics getMetrics() {
        return tickMetrics.getMetrics();
    }

    /**
     * Get bubble 1.
     */
    public VonBubble getBubble1() {
        return bubble1;
    }

    /**
     * Get bubble 2.
     */
    public VonBubble getBubble2() {
        return bubble2;
    }

    /**
     * Get all entities from both bubbles (for visualization).
     */
    public List<EntitySnapshot> getAllEntities() {
        var entities = new ArrayList<EntitySnapshot>();

        for (var record : bubble1.getAllEntityRecords()) {
            entities.add(new EntitySnapshot(record.id(), record.position(), 1, false));
        }
        for (var record : bubble2.getAllEntityRecords()) {
            entities.add(new EntitySnapshot(record.id(), record.position(), 2, false));
        }

        // Include active ghosts for visualization (delegate to ghostSyncCoordinator)
        long currentTick = tickCount.get();
        for (var ghost : ghostSyncCoordinator.getGhostsInBubble1().values()) {
            if (ghost.expirationTick() > currentTick) {
                entities.add(new EntitySnapshot(ghost.id() + "_ghost", ghost.position(), 1, true));
            }
        }
        for (var ghost : ghostSyncCoordinator.getGhostsInBubble2().values()) {
            if (ghost.expirationTick() > currentTick) {
                entities.add(new EntitySnapshot(ghost.id() + "_ghost", ghost.position(), 2, true));
            }
        }

        return entities;
    }

    /**
     * Get the X coordinate that divides the bubbles.
     */
    public float getBoundaryX() {
        return boundaryX;
    }

    /**
     * Get world bounds.
     */
    public WorldBounds getWorldBounds() {
        return worldBounds;
    }

    /**
     * Get total migrations to bubble 1.
     */
    public long getMigrationsTo1() {
        return tickMetrics.getMigrationsTo1();
    }

    /**
     * Get total migrations to bubble 2.
     */
    public long getMigrationsTo2() {
        return tickMetrics.getMigrationsTo2();
    }

    /**
     * Get detailed debug state for visualization and debugging.
     */
    public SimulationTickMetrics.DebugState getDebugState() {
        long currentTick = tickCount.get();
        return tickMetrics.getDebugState(
            currentTick,
            ghostSyncCoordinator.getGhostsInBubble1().size(),
            ghostSyncCoordinator.getGhostsInBubble2().size(),
            migrationManager.getActiveCooldownCount(currentTick)
        );
    }

    /**
     * Get count of migration failures.
     *
     * @return Number of failed migration attempts
     */
    public long getMigrationFailures() {
        return tickMetrics.getMigrationFailures();
    }

    /**
     * Get count of entities currently in migration cooldown.
     *
     * @return Number of entities that cannot migrate yet
     */
    public int getCooldownsActive() {
        return migrationManager.getActiveCooldownCount(tickCount.get());
    }

    // ========== Records for Visualization and Debugging ==========

    /**
     * Snapshot of an entity for visualization.
     *
     * @param id       Entity ID
     * @param position Current position
     * @param bubbleId Which bubble (1 or 2)
     * @param isGhost  True if this is a ghost copy
     */
    public record EntitySnapshot(String id, Point3f position, int bubbleId, boolean isGhost) {}

    // ========== Private Methods ==========

    private void populateEntities(int entityCount) {
        var random = new Random(42);
        var margin = 20f;

        for (int i = 0; i < entityCount; i++) {
            var x = worldBounds.min() + margin + random.nextFloat() * (worldBounds.size() - 2 * margin);
            var y = worldBounds.min() + margin + random.nextFloat() * (worldBounds.size() - 2 * margin);
            var z = worldBounds.min() + margin + random.nextFloat() * (worldBounds.size() - 2 * margin);
            var position = new Point3f(x, y, z);

            // Assign to bubble based on x position
            if (x < boundaryX) {
                bubble1.addEntity("entity-" + i, position, null);
            } else {
                bubble2.addEntity("entity-" + i, position, null);
            }
        }
    }



    private void tick() {
        try {
            long startNs = clock.nanoTime();
            long currentTick = tickCount.get();
            float deltaTime = DEFAULT_TICK_INTERVAL_MS / 1000.0f;

            // Swap velocity buffers for FlockingBehavior
            if (behavior1 instanceof FlockingBehavior fb1) {
                fb1.swapVelocityBuffers();
            }
            if (behavior2 instanceof FlockingBehavior fb2) {
                fb2.swapVelocityBuffers();
            }

            // Update entities (delegate to entityUpdater)
            entityUpdater.updateBubbleEntities(bubble1, behavior1, velocityTracker.getVelocities1(), deltaTime, worldBounds.min(), boundaryX);
            entityUpdater.updateBubbleEntities(bubble2, behavior2, velocityTracker.getVelocities2(), deltaTime, boundaryX, worldBounds.max());

            // Sync ghosts periodically (delegate to ghostSyncCoordinator)
            if (currentTick % GHOST_SYNC_INTERVAL_TICKS == 0) {
                ghostSyncCoordinator.syncGhosts(currentTick);
            }
            ghostSyncCoordinator.expireGhosts(currentTick);

            // Check for entity migration (delegate to migrationManager)
            migrationManager.checkAndMigrate(currentTick, velocityTracker.getVelocities1(), velocityTracker.getVelocities2());

            // Record metrics (delegate to tickMetrics)
            long frameTimeNs = clock.nanoTime() - startNs;
            tickMetrics.recordTick(frameTimeNs);

            tickCount.incrementAndGet();

            // Periodic cleanup to remove orphaned entries
            if (currentTick > 0 && currentTick % VELOCITY_CLEANUP_INTERVAL_TICKS == 0) {
                velocityTracker.cleanupOrphanedVelocities();
                migrationManager.cleanupExpiredCooldowns(currentTick);
            }

            // Log periodically (delegate to tickMetrics)
            tickMetrics.logPeriodic(
                currentTick,
                ghostSyncCoordinator.getGhostsInBubble1().size(),
                ghostSyncCoordinator.getGhostsInBubble2().size(),
                migrationManager.getActiveCooldownCount(currentTick)
            );

        } catch (Exception e) {
            log.error("Error in simulation tick: {}", e.getMessage(), e);
        }
    }


    private void handleBubble1Event(com.hellblazer.luciferase.simulation.von.Event event) {
        // Handle VON events from bubble1
        log.trace("Bubble1 event: {}", event);
    }

    private void handleBubble2Event(com.hellblazer.luciferase.simulation.von.Event event) {
        // Handle VON events from bubble2
        log.trace("Bubble2 event: {}", event);
    }
}
