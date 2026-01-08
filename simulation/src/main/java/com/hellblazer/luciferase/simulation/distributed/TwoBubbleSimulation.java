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

    private final Map<String, GhostEntry> ghostsInBubble1 = new ConcurrentHashMap<>();
    private final Map<String, GhostEntry> ghostsInBubble2 = new ConcurrentHashMap<>();
    private final Map<String, javax.vecmath.Vector3f> velocities1 = new ConcurrentHashMap<>();
    private final Map<String, javax.vecmath.Vector3f> velocities2 = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCount = new AtomicLong(0);
    private final SimulationMetrics metrics = new SimulationMetrics();

    // Migration metrics
    private final AtomicLong migrationsTo1 = new AtomicLong(0);
    private final AtomicLong migrationsTo2 = new AtomicLong(0);
    private final AtomicLong migrationFailures = new AtomicLong(0);

    // Migration cooldowns: entityId -> tick when cooldown expires
    private final Map<String, Long> migrationCooldowns = new ConcurrentHashMap<>();

    private ScheduledFuture<?> tickTask;

    /**
     * Ghost entry with position, velocity, and expiration.
     */
    private record GhostEntry(String id, Point3f position, javax.vecmath.Vector3f velocity, long expirationTick) {}

    /**
     * Migration direction enum.
     */
    public enum MigrationDirection {
        TO_BUBBLE_1,
        TO_BUBBLE_2
    }

    /**
     * Migration intent: captures entity state and validates migration is safe.
     * Created during PREPARE phase, consumed during COMMIT phase.
     */
    public record MigrationIntent(
        String entityId,
        Point3f position,
        Object content,
        javax.vecmath.Vector3f velocity,
        MigrationDirection direction,
        long preparedAtTick
    ) {}

    /**
     * Migration result: outcome of a migration attempt.
     */
    public record MigrationResult(
        String entityId,
        MigrationDirection direction,
        boolean success,
        String message
    ) {
        public static MigrationResult success(String entityId, MigrationDirection direction) {
            return new MigrationResult(entityId, direction, true, "Success");
        }

        public static MigrationResult failure(String entityId, MigrationDirection direction, String message) {
            return new MigrationResult(entityId, direction, false, message);
        }
    }

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

        // Clear ghost, velocity, and cooldown maps to prevent memory leaks
        ghostsInBubble1.clear();
        ghostsInBubble2.clear();
        velocities1.clear();
        velocities2.clear();
        migrationCooldowns.clear();

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

    /**
     * Get total migrations to bubble 1.
     */
    public long getMigrationsTo1() {
        return migrationsTo1.get();
    }

    /**
     * Get total migrations to bubble 2.
     */
    public long getMigrationsTo2() {
        return migrationsTo2.get();
    }

    /**
     * Get detailed debug state for visualization and debugging.
     */
    public DebugState getDebugState() {
        return new DebugState(
            tickCount.get(),
            bubble1.entityCount(),
            bubble2.entityCount(),
            ghostsInBubble1.size(),
            ghostsInBubble2.size(),
            migrationsTo1.get(),
            migrationsTo2.get(),
            migrationFailures.get(),
            migrationCooldowns.size(),
            metrics
        );
    }

    /**
     * Get count of migration failures.
     *
     * @return Number of failed migration attempts
     */
    public long getMigrationFailures() {
        return migrationFailures.get();
    }

    /**
     * Get count of entities currently in migration cooldown.
     *
     * @return Number of entities that cannot migrate yet
     */
    public int getCooldownsActive() {
        long currentTick = tickCount.get();
        return (int) migrationCooldowns.entrySet().stream()
            .filter(e -> e.getValue() > currentTick)
            .count();
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

    /**
     * Debug state snapshot for monitoring and debugging.
     */
    public record DebugState(
        long tickCount,
        int bubble1EntityCount,
        int bubble2EntityCount,
        int bubble1GhostCount,
        int bubble2GhostCount,
        long migrationsTo1,
        long migrationsTo2,
        long migrationFailures,
        int cooldownsActive,
        SimulationMetrics metrics
    ) {}

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

            // Periodic cleanup to remove orphaned entries
            if (currentTick > 0 && currentTick % VELOCITY_CLEANUP_INTERVAL_TICKS == 0) {
                cleanupOrphanedVelocities();
                cleanupExpiredCooldowns();
            }

            // Log periodically
            if (currentTick > 0 && currentTick % 600 == 0) {
                log.debug("Tick {}: bubble1={}, bubble2={}, ghosts1={}, ghosts2={}, " +
                          "migrations(to1={}, to2={}, failures={}), cooldowns={}, {}",
                          currentTick, bubble1.entityCount(), bubble2.entityCount(),
                          ghostsInBubble1.size(), ghostsInBubble2.size(),
                          migrationsTo1.get(), migrationsTo2.get(), migrationFailures.get(),
                          migrationCooldowns.size(), metrics);
            }

        } catch (Exception e) {
            log.error("Error in simulation tick: {}", e.getMessage(), e);
        }
    }

    private void updateBubbleEntities(EnhancedBubble bubble, EntityBehavior behavior,
                                       Map<String, javax.vecmath.Vector3f> velocities,
                                       float deltaTime, float minX, float maxX) {
        for (var entity : bubble.getAllEntityRecords()) {
            try {
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
            } catch (Exception e) {
                log.error("Failed to update entity {}: {}", entity.id(), e.getMessage());
            }
        }
    }

    /**
     * Clean up velocity entries for entities that no longer exist.
     * Prevents memory leaks from orphaned velocity entries.
     */
    private void cleanupOrphanedVelocities() {
        var bubble1Ids = bubble1.getAllEntityRecords().stream()
            .map(EnhancedBubble.EntityRecord::id)
            .collect(java.util.stream.Collectors.toSet());
        int removed1 = velocities1.size();
        velocities1.keySet().retainAll(bubble1Ids);
        removed1 -= velocities1.size();

        var bubble2Ids = bubble2.getAllEntityRecords().stream()
            .map(EnhancedBubble.EntityRecord::id)
            .collect(java.util.stream.Collectors.toSet());
        int removed2 = velocities2.size();
        velocities2.keySet().retainAll(bubble2Ids);
        removed2 -= velocities2.size();

        if (removed1 > 0 || removed2 > 0) {
            log.debug("Velocity cleanup: removed {} from bubble1, {} from bubble2", removed1, removed2);
        }
    }

    /**
     * Clean up expired cooldown entries.
     * Prevents memory growth from accumulated cooldown entries in long-running simulations.
     */
    private void cleanupExpiredCooldowns() {
        long currentTick = tickCount.get();
        int sizeBefore = migrationCooldowns.size();
        migrationCooldowns.entrySet().removeIf(e -> e.getValue() <= currentTick);
        int removed = sizeBefore - migrationCooldowns.size();

        if (removed > 0) {
            log.debug("Cooldown cleanup: removed {} expired entries", removed);
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

    /**
     * Check for entities that need migration using two-phase commit protocol.
     * <p>
     * Phase 1 (PREPARE): Validate migration is safe, create intent
     * Phase 2 (COMMIT): Execute migration atomically with rollback on failure
     */
    private void checkMigration() {
        long currentTick = tickCount.get();

        // Snapshot entities atomically - use List.copyOf() to prevent modifications during iteration
        var bubble1Snapshot = List.copyOf(bubble1.getAllEntityRecords());
        var bubble2Snapshot = List.copyOf(bubble2.getAllEntityRecords());

        // Phase 1: PREPARE - identify migration candidates with hysteresis and cooldown checks
        var intentsTo2 = new ArrayList<MigrationIntent>();
        for (var entity : bubble1Snapshot) {
            if (shouldMigrate(entity, MigrationDirection.TO_BUBBLE_2, currentTick)) {
                var intent = prepareMigration(entity, MigrationDirection.TO_BUBBLE_2, currentTick);
                if (intent != null) {
                    intentsTo2.add(intent);
                }
            }
        }

        var intentsTo1 = new ArrayList<MigrationIntent>();
        for (var entity : bubble2Snapshot) {
            if (shouldMigrate(entity, MigrationDirection.TO_BUBBLE_1, currentTick)) {
                var intent = prepareMigration(entity, MigrationDirection.TO_BUBBLE_1, currentTick);
                if (intent != null) {
                    intentsTo1.add(intent);
                }
            }
        }

        // Phase 2: COMMIT - execute migrations with rollback on failure
        for (var intent : intentsTo2) {
            var result = commitMigration(intent, currentTick);
            if (!result.success()) {
                log.warn("Migration failed: {}", result.message());
            }
        }

        for (var intent : intentsTo1) {
            var result = commitMigration(intent, currentTick);
            if (!result.success()) {
                log.warn("Migration failed: {}", result.message());
            }
        }
    }

    /**
     * Check if entity should migrate based on hysteresis and cooldown.
     *
     * @param entity    Entity to check
     * @param direction Target direction
     * @param currentTick Current simulation tick
     * @return true if entity should migrate
     */
    private boolean shouldMigrate(EnhancedBubble.EntityRecord entity, MigrationDirection direction, long currentTick) {
        // Check cooldown first (cheaper check)
        if (isInCooldown(entity.id(), currentTick)) {
            return false;
        }

        // Apply hysteresis: entity must be past boundary by HYSTERESIS_DISTANCE
        float x = entity.position().x;
        if (direction == MigrationDirection.TO_BUBBLE_2) {
            // Entity in bubble1 needs to be HYSTERESIS_DISTANCE past boundary into bubble2's region
            return x >= (boundaryX + HYSTERESIS_DISTANCE);
        } else {
            // Entity in bubble2 needs to be HYSTERESIS_DISTANCE past boundary into bubble1's region
            return x < (boundaryX - HYSTERESIS_DISTANCE);
        }
    }

    /**
     * Check if entity is in migration cooldown.
     *
     * @param entityId Entity ID to check
     * @param currentTick Current simulation tick
     * @return true if entity cannot migrate yet
     */
    private boolean isInCooldown(String entityId, long currentTick) {
        var cooldownExpires = migrationCooldowns.get(entityId);
        if (cooldownExpires == null) {
            return false;
        }
        return currentTick < cooldownExpires;
    }

    /**
     * PREPARE phase: Validate migration and create intent.
     *
     * @param entity    Entity to migrate
     * @param direction Target direction
     * @param currentTick Current simulation tick
     * @return MigrationIntent if valid, null if migration should not proceed
     */
    private MigrationIntent prepareMigration(EnhancedBubble.EntityRecord entity, MigrationDirection direction, long currentTick) {
        String entityId = entity.id();

        // Validate entity exists in source bubble
        var sourceBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble1 : bubble2;
        var targetBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble2 : bubble1;

        if (!sourceBubble.getEntities().contains(entityId)) {
            log.trace("Prepare failed: entity {} not in source bubble", entityId);
            return null;
        }

        // Validate entity NOT in target bubble (prevents duplicates)
        if (targetBubble.getEntities().contains(entityId)) {
            log.warn("Prepare failed: entity {} already in target bubble", entityId);
            return null;
        }

        // Get velocity from source bubble's velocity map
        var velocityMap = (direction == MigrationDirection.TO_BUBBLE_2) ? velocities1 : velocities2;
        var velocity = velocityMap.get(entityId);

        return new MigrationIntent(
            entityId,
            new Point3f(entity.position()),  // Copy position
            entity.content(),
            velocity != null ? new javax.vecmath.Vector3f(velocity) : null,
            direction,
            currentTick
        );
    }

    /**
     * COMMIT phase: Execute migration atomically with rollback on failure.
     * <p>
     * ATOMICITY GUARANTEE: Add-before-remove prevents entity loss.
     * Alternative remove-before-add would risk entity loss if add fails.
     * <p>
     * Order of operations for atomicity:
     * <ol>
     *   <li>Remove ghost from target (if entity was ghosted there)</li>
     *   <li>Add entity to target bubble (target now owns entity)</li>
     *   <li>Add velocity to target (don't remove from source yet)</li>
     *   <li>Remove entity from source bubble</li>
     *   <li>Remove velocity from source (only after source removal succeeds)</li>
     *   <li>Update metrics and cooldown</li>
     * </ol>
     * <p>
     * Rollback scenarios:
     * <ul>
     *   <li>Step 2 fails: no rollback needed, entity stays in source</li>
     *   <li>Step 4 fails: rollback removes from target, velocity stays in source</li>
     * </ul>
     *
     * @param intent Migration intent from prepare phase
     * @param currentTick Current simulation tick
     * @return MigrationResult indicating success or failure
     */
    private MigrationResult commitMigration(MigrationIntent intent, long currentTick) {
        String entityId = intent.entityId();
        var direction = intent.direction();

        var sourceBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble1 : bubble2;
        var targetBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble2 : bubble1;
        var sourceVelocities = (direction == MigrationDirection.TO_BUBBLE_2) ? velocities1 : velocities2;
        var targetVelocities = (direction == MigrationDirection.TO_BUBBLE_2) ? velocities2 : velocities1;
        var targetGhosts = (direction == MigrationDirection.TO_BUBBLE_2) ? ghostsInBubble2 : ghostsInBubble1;
        var migrationCounter = (direction == MigrationDirection.TO_BUBBLE_2) ? migrationsTo2 : migrationsTo1;

        // Step 1: Remove ghost from target FIRST (before adding as real entity)
        // This prevents conflicts if entity was already ghosted in target
        targetGhosts.remove(entityId);

        try {
            // Step 2: Add entity to target bubble (target now owns entity)
            targetBubble.addEntity(entityId, intent.position(), intent.content());
        } catch (Exception e) {
            // Add failed - no rollback needed, entity stays in source
            migrationFailures.incrementAndGet();
            return MigrationResult.failure(entityId, direction, "Failed to add to target: " + e.getMessage());
        }

        try {
            // Step 3: Add velocity to target (but don't remove from source yet)
            if (intent.velocity() != null) {
                targetVelocities.put(entityId, intent.velocity());
            }

            // Step 4: Remove entity from source bubble
            sourceBubble.removeEntity(entityId);

            // Step 5: Now safe to remove velocity from source (source removal succeeded)
            sourceVelocities.remove(entityId);

            // Step 6: Update metrics and cooldown
            migrationCounter.incrementAndGet();
            migrationCooldowns.put(entityId, currentTick + MIGRATION_COOLDOWN_TICKS);

            log.debug("Migrated {} from bubble{} to bubble{}", entityId,
                      direction == MigrationDirection.TO_BUBBLE_2 ? 1 : 2,
                      direction == MigrationDirection.TO_BUBBLE_2 ? 2 : 1);
            return MigrationResult.success(entityId, direction);

        } catch (Exception e) {
            // Rollback: remove from target since it was added but migration failed
            // Note: velocity was NOT removed from source, so no need to restore it
            rollbackMigration(intent, e);
            migrationFailures.incrementAndGet();
            return MigrationResult.failure(entityId, direction, "Rollback after failure: " + e.getMessage());
        }
    }

    /**
     * Rollback a failed migration by removing entity from target and restoring velocity.
     * <p>
     * Called when migration fails after entity was added to target but before
     * source removal completed. Ensures entity stays in source with velocity intact.
     *
     * @param intent Original migration intent
     * @param cause Exception that caused the failure
     */
    private void rollbackMigration(MigrationIntent intent, Exception cause) {
        String entityId = intent.entityId();
        var direction = intent.direction();
        var targetBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble2 : bubble1;
        var targetVelocities = (direction == MigrationDirection.TO_BUBBLE_2) ? velocities2 : velocities1;
        var sourceVelocities = (direction == MigrationDirection.TO_BUBBLE_2) ? velocities1 : velocities2;

        try {
            // Remove entity from target bubble
            targetBubble.removeEntity(entityId);

            // Remove velocity from target (it was copied there)
            targetVelocities.remove(entityId);

            // Restore velocity to source if it was transferred
            // (With new ordering, velocity is NOT removed from source until after
            // source removal succeeds, so this is just a safety net)
            if (intent.velocity() != null && !sourceVelocities.containsKey(entityId)) {
                sourceVelocities.put(entityId, intent.velocity());
            }

            log.warn("Rolled back migration of {} from bubble{} to bubble{}: {}",
                     entityId,
                     direction == MigrationDirection.TO_BUBBLE_2 ? 1 : 2,
                     direction == MigrationDirection.TO_BUBBLE_2 ? 2 : 1,
                     cause.getMessage());
        } catch (Exception rollbackError) {
            log.error("Rollback failed for {}: original error={}, rollback error={}",
                      entityId, cause.getMessage(), rollbackError.getMessage());
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
