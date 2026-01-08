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

    private final WorldBounds worldBounds;
    private final float boundaryX;  // X coordinate that divides the bubbles

    private final LocalServerTransport.Registry transportRegistry;
    private final VonBubble bubble1;
    private final VonBubble bubble2;
    private final EntityBehavior behavior1;
    private final EntityBehavior behavior2;

    private final Map<String, GhostEntry> ghostsInBubble1 = new ConcurrentHashMap<>();
    private final Map<String, GhostEntry> ghostsInBubble2 = new ConcurrentHashMap<>();
    private final Map<String, javax.vecmath.Vector3f> velocities1 = new ConcurrentHashMap<>();
    private final Map<String, javax.vecmath.Vector3f> velocities2 = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCount = new AtomicLong(0);
    private final SimulationMetrics metrics = new SimulationMetrics();

    private ScheduledFuture<?> tickTask;

    /**
     * Ghost entry with position, velocity, and expiration.
     */
    private record GhostEntry(String id, Point3f position, javax.vecmath.Vector3f velocity, long expirationTick) {}

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

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "TwoBubbleSimulation");
            t.setDaemon(true);
            return t;
        });

        log.info("TwoBubbleSimulation created: {} entities in bubble1, {} in bubble2, boundary at x={}",
                 bubble1.entityCount(), bubble2.entityCount(), boundaryX);
    }

    /**
     * Start the simulation.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            initializeVelocities();

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
            log.info("TwoBubbleSimulation stopped after {} ticks. {}", tickCount.get(), metrics);
        }
    }

    @Override
    public void close() {
        stop();

        // Clear ghost and velocity maps to prevent memory leaks
        ghostsInBubble1.clear();
        ghostsInBubble2.clear();
        velocities1.clear();
        velocities2.clear();

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
        return metrics;
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

        // Include active ghosts for visualization
        long currentTick = tickCount.get();
        for (var ghost : ghostsInBubble1.values()) {
            if (ghost.expirationTick > currentTick) {
                entities.add(new EntitySnapshot(ghost.id + "_ghost", ghost.position, 1, true));
            }
        }
        for (var ghost : ghostsInBubble2.values()) {
            if (ghost.expirationTick > currentTick) {
                entities.add(new EntitySnapshot(ghost.id + "_ghost", ghost.position, 2, true));
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

    // ========== Entity Snapshot for Visualization ==========

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

    private void initializeVelocities() {
        velocities1.clear();
        velocities2.clear();
        var random = new Random();

        for (var entity : bubble1.getAllEntityRecords()) {
            velocities1.put(entity.id(), randomVelocity(random, behavior1.getMaxSpeed()));
        }
        for (var entity : bubble2.getAllEntityRecords()) {
            velocities2.put(entity.id(), randomVelocity(random, behavior2.getMaxSpeed()));
        }
    }

    private javax.vecmath.Vector3f randomVelocity(Random random, float maxSpeed) {
        return new javax.vecmath.Vector3f(
            (random.nextFloat() - 0.5f) * 2 * maxSpeed,
            (random.nextFloat() - 0.5f) * 2 * maxSpeed,
            (random.nextFloat() - 0.5f) * 2 * maxSpeed
        );
    }

    private void tick() {
        try {
            long startNs = System.nanoTime();
            long currentTick = tickCount.get();
            float deltaTime = DEFAULT_TICK_INTERVAL_MS / 1000.0f;

            // Swap velocity buffers for FlockingBehavior
            if (behavior1 instanceof FlockingBehavior fb1) {
                fb1.swapVelocityBuffers();
            }
            if (behavior2 instanceof FlockingBehavior fb2) {
                fb2.swapVelocityBuffers();
            }

            // Update bubble 1 entities
            updateBubbleEntities(bubble1, behavior1, velocities1, deltaTime, worldBounds.min(), boundaryX);

            // Update bubble 2 entities
            updateBubbleEntities(bubble2, behavior2, velocities2, deltaTime, boundaryX, worldBounds.max());

            // Sync ghosts periodically
            if (currentTick % GHOST_SYNC_INTERVAL_TICKS == 0) {
                syncGhosts(currentTick);
            }

            // Expire old ghosts
            expireGhosts(currentTick);

            // Check for entity migration (entity crossed boundary)
            checkMigration();

            // Record metrics
            long frameTimeNs = System.nanoTime() - startNs;
            int totalEntities = bubble1.entityCount() + bubble2.entityCount();
            metrics.recordTick(frameTimeNs, totalEntities);

            tickCount.incrementAndGet();

            // Log periodically
            if (currentTick > 0 && currentTick % 600 == 0) {
                log.debug("Tick {}: bubble1={}, bubble2={}, ghosts1={}, ghosts2={}, {}",
                          currentTick, bubble1.entityCount(), bubble2.entityCount(),
                          ghostsInBubble1.size(), ghostsInBubble2.size(), metrics);
            }

        } catch (Exception e) {
            log.error("Error in simulation tick: {}", e.getMessage(), e);
        }
    }

    private void updateBubbleEntities(EnhancedBubble bubble, EntityBehavior behavior,
                                       Map<String, javax.vecmath.Vector3f> velocities,
                                       float deltaTime, float minX, float maxX) {
        for (var entity : bubble.getAllEntityRecords()) {
            var velocity = velocities.computeIfAbsent(entity.id(), k -> new javax.vecmath.Vector3f());

            var newVelocity = behavior.computeVelocity(
                entity.id(),
                entity.position(),
                velocity,
                bubble,
                deltaTime
            );

            velocities.put(entity.id(), newVelocity);

            var newPosition = new Point3f(entity.position());
            newPosition.x += newVelocity.x * deltaTime;
            newPosition.y += newVelocity.y * deltaTime;
            newPosition.z += newVelocity.z * deltaTime;

            // Clamp to world bounds (y, z) and bubble region (x)
            newPosition.x = Math.max(minX, Math.min(maxX, newPosition.x));
            newPosition.y = worldBounds.clamp(newPosition.y);
            newPosition.z = worldBounds.clamp(newPosition.z);

            bubble.updateEntityPosition(entity.id(), newPosition);
        }
    }

    private void syncGhosts(long currentTick) {
        long expirationTick = currentTick + GHOST_TTL_TICKS;

        // Find entities near boundary in bubble1, ghost them to bubble2
        for (var entity : bubble1.getAllEntityRecords()) {
            float distFromBoundary = boundaryX - entity.position().x;
            if (distFromBoundary >= 0 && distFromBoundary < GHOST_BOUNDARY_WIDTH) {
                var velocity = velocities1.get(entity.id());
                var ghost = new GhostEntry(
                    entity.id(),
                    new Point3f(entity.position()),
                    velocity != null ? new javax.vecmath.Vector3f(velocity) : new javax.vecmath.Vector3f(),
                    expirationTick
                );
                ghostsInBubble2.put(entity.id(), ghost);
            }
        }

        // Find entities near boundary in bubble2, ghost them to bubble1
        for (var entity : bubble2.getAllEntityRecords()) {
            float distFromBoundary = entity.position().x - boundaryX;
            if (distFromBoundary >= 0 && distFromBoundary < GHOST_BOUNDARY_WIDTH) {
                var velocity = velocities2.get(entity.id());
                var ghost = new GhostEntry(
                    entity.id(),
                    new Point3f(entity.position()),
                    velocity != null ? new javax.vecmath.Vector3f(velocity) : new javax.vecmath.Vector3f(),
                    expirationTick
                );
                ghostsInBubble1.put(entity.id(), ghost);
            }
        }

        log.trace("Ghost sync: {} ghosts in bubble1, {} in bubble2",
                  ghostsInBubble1.size(), ghostsInBubble2.size());
    }

    private void expireGhosts(long currentTick) {
        ghostsInBubble1.entrySet().removeIf(e -> e.getValue().expirationTick <= currentTick);
        ghostsInBubble2.entrySet().removeIf(e -> e.getValue().expirationTick <= currentTick);
    }

    private void checkMigration() {
        // Snapshot entities atomically - use List.copyOf() to prevent modifications during iteration
        var bubble1Snapshot = List.copyOf(bubble1.getAllEntityRecords());
        var bubble2Snapshot = List.copyOf(bubble2.getAllEntityRecords());

        // Check bubble1 entities that crossed into bubble2's region
        var toMigrateTo2 = new ArrayList<EnhancedBubble.EntityRecord>();
        for (var entity : bubble1Snapshot) {
            if (entity.position().x >= boundaryX) {
                toMigrateTo2.add(entity);
            }
        }

        // Check bubble2 entities that crossed into bubble1's region
        var toMigrateTo1 = new ArrayList<EnhancedBubble.EntityRecord>();
        for (var entity : bubble2Snapshot) {
            if (entity.position().x < boundaryX) {
                toMigrateTo1.add(entity);
            }
        }

        // Perform migrations with error handling
        for (var record : toMigrateTo2) {
            try {
                bubble1.removeEntity(record.id());
                bubble2.addEntity(record.id(), record.position(), record.content());
                var velocity = velocities1.remove(record.id());
                if (velocity != null) {
                    velocities2.put(record.id(), velocity);
                }
                ghostsInBubble2.remove(record.id());  // No longer a ghost if it's real
                log.debug("Migrated {} from bubble1 to bubble2", record.id());
            } catch (Exception e) {
                log.error("Failed to migrate {} from bubble1 to bubble2: {}", record.id(), e.getMessage());
            }
        }

        for (var record : toMigrateTo1) {
            try {
                bubble2.removeEntity(record.id());
                bubble1.addEntity(record.id(), record.position(), record.content());
                var velocity = velocities2.remove(record.id());
                if (velocity != null) {
                    velocities1.put(record.id(), velocity);
                }
                ghostsInBubble1.remove(record.id());  // No longer a ghost if it's real
                log.debug("Migrated {} from bubble2 to bubble1", record.id());
            } catch (Exception e) {
                log.error("Failed to migrate {} from bubble2 to bubble1: {}", record.id(), e.getMessage());
            }
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
