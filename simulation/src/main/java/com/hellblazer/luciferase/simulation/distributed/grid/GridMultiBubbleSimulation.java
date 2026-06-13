/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EntityPhysicsManager;
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-bubble distributed simulation with N bubbles in a 2D grid topology.
 * <p>
 * Generalizes TwoBubbleSimulation to support arbitrary NxM grids:
 * <ul>
 *   <li>2D grid topology (XY plane, Z unrestricted within cells)</li>
 *   <li>Neighbor count varies: 3 (corner), 5 (edge), 8 (interior)</li>
 *   <li>Spatial entity distribution based on XY position</li>
 *   <li>Synchronized tick execution across all bubbles</li>
 * </ul>
 * <p>
 * Future increments will add:
 * - Ghost layer synchronization (Inc 5C)
 * - Multi-directional migration (Inc 5D)
 * - Performance optimizations (Inc 5E)
 *
 * @author hal.hildebrand
 */
public class GridMultiBubbleSimulation implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GridMultiBubbleSimulation.class);

    /**
     * Default tick interval: 60fps (16.67ms).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    /**
     * Deterministic base seed for initial velocity generation (Luciferase-0frcy.98). XORed with
     * {@code gridConfig.hashCode()} so distinct grids still get distinct (but reproducible) streams.
     */
    private static final long VELOCITY_SEED = 0x5DEECE66DL;

    private final GridConfiguration gridConfig;
    private final WorldBounds worldBounds;
    private final BubbleGrid<EnhancedBubble> bubbleGrid;
    private final EntityBehavior behavior;
    private final GridGhostSyncAdapter ghostSyncAdapter;
    private final MultiDirectionalMigration migration;
    /** Physics manager: single source of truth for entity velocities (Luciferase-chmxx Finding 1). */
    private final EntityPhysicsManager physicsManager;

    // Serializes the cross-bubble migration commit phase against cross-bubble entity snapshots
    // (getAllEntities). A migration moves an entity between two bubbles (add to target, then remove
    // from source); getAllEntities iterates the bubbles row-major and non-atomically, so without
    // this lock a snapshot could read the target before the add and the source after the remove
    // (transient under-count) — or both before either step (over-count). Holding this lock around
    // the whole commit phase and around the snapshot makes the count a consistent, conserved
    // observation (Luciferase-jar).
    private final Object snapshotLock = new Object();

    /**
     * Number of <em>consecutive</em> failing ticks after which the simulation circuit-breaks: it logs a
     * fatal, cancels the scheduled tick task, and transitions to a terminal {@code failed} state instead of
     * silently hot-looping a deterministically-broken tick every {@link #DEFAULT_TICK_INTERVAL_MS} ms
     * forever (Luciferase-a57pj). A transient failure self-heals — a single successful tick resets the
     * consecutive counter, so only a sustained streak trips the breaker.
     */
    static final int MAX_CONSECUTIVE_TICK_FAILURES = 10;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCount = new AtomicLong(0);
    private final AtomicLong currentBucket = new AtomicLong(0);
    // Tick-failure observability + circuit-break state (Luciferase-a57pj). tickFailureCount is a lifetime
    // counter (cumulative across restarts); consecutiveTickFailures resets to 0 on any successful tick and
    // drives the circuit-breaker; failed is the terminal state set when the breaker trips.
    private final AtomicLong tickFailureCount = new AtomicLong(0);
    private final AtomicLong consecutiveTickFailures = new AtomicLong(0);
    private volatile boolean failed = false;
    private final SimulationMetrics metrics = new SimulationMetrics();
    private volatile Clock clock = Clock.system();

    // volatile: written by the caller thread in start()/stop(), read by the scheduler thread in tick()'s
    // circuit-break path. Without it the first tick (initialDelay=0) could read a stale null and skip the
    // cancel, leaving the task hot-looping despite the breaker tripping (Luciferase-a57pj review).
    private volatile ScheduledFuture<?> tickTask;

    /**
     * Create a multi-bubble simulation with default behavior.
     *
     * @param gridConfig  Grid configuration (NxM bubbles)
     * @param entityCount Number of entities (spatially distributed)
     * @param worldBounds World boundary configuration
     */
    public GridMultiBubbleSimulation(GridConfiguration gridConfig, int entityCount, WorldBounds worldBounds) {
        this(gridConfig, entityCount, worldBounds, new FlockingBehavior());
    }

    /**
     * Create a multi-bubble simulation with custom behavior.
     *
     * @param gridConfig  Grid configuration (NxM bubbles)
     * @param entityCount Number of entities (spatially distributed)
     * @param worldBounds World boundary configuration
     * @param behavior    Entity behavior for all bubbles
     */
    public GridMultiBubbleSimulation(
        GridConfiguration gridConfig,
        int entityCount,
        WorldBounds worldBounds,
        EntityBehavior behavior
    ) {
        this.gridConfig = gridConfig;
        this.worldBounds = worldBounds;
        this.behavior = behavior;

        // Create bubbles in grid topology
        this.bubbleGrid = GridBubbleFactory.createBubbles(gridConfig, (byte) 10, DEFAULT_TICK_INTERVAL_MS);

        // Create ghost sync adapter (Inc 5C integration)
        this.ghostSyncAdapter = new GridGhostSyncAdapter(gridConfig, bubbleGrid);

        // Physics manager: owns all entity velocities; wired into ghostSyncAdapter so outbound
        // ghosts carry real velocity for dead-reckoning (Luciferase-chmxx Finding 1).
        this.physicsManager = new EntityPhysicsManager(behavior, worldBounds);
        ghostSyncAdapter.setPhysicsManager(this.physicsManager);

        // Create multi-directional migration (Inc 5D integration)
        this.migration = new MultiDirectionalMigration(gridConfig, bubbleGrid, physicsManager.getVelocities());

        // Distribute entities spatially
        populateEntities(entityCount);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "GridMultiBubbleSimulation-" + gridConfig.rows() + "x" + gridConfig.columns());
            t.setDaemon(true);
            return t;
        });

        log.info("GridMultiBubbleSimulation created: {} bubbles ({}x{}), {} entities",
                 gridConfig.bubbleCount(), gridConfig.rows(), gridConfig.columns(), entityCount);
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
            // Clear circuit-break state so a restart begins healthy (lifetime tickFailureCount is retained).
            failed = false;
            consecutiveTickFailures.set(0);
            initializeVelocities();

            tickTask = scheduler.scheduleAtFixedRate(
                this::tick,
                0,
                DEFAULT_TICK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );

            log.info("GridMultiBubbleSimulation started: {} bubbles", gridConfig.bubbleCount());
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
            log.info("GridMultiBubbleSimulation stopped after {} ticks. {}", tickCount.get(), metrics);
        }
    }

    @Override
    public void close() {
        stop();

        physicsManager.getVelocities().clear();

        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate within 1 second");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for scheduler termination");
        }

        log.debug("GridMultiBubbleSimulation closed");
    }

    /**
     * Check if simulation is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get the number of <em>successful</em> ticks completed.
     * <p>
     * This counts ticks that ran to completion, NOT scheduler invocations: a tick that throws is counted by
     * {@link #getTickFailureCount()} instead, so under failures this value diverges (downward) from the
     * number of times the scheduler fired (Luciferase-a57pj).
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Get the lifetime count of failing ticks (cumulative across restarts). A failing tick is one whose
     * body threw; the exception is swallowed to keep the scheduled task alive, but counted here so the
     * failure is observable. See {@link #getConsecutiveTickFailures()} and {@link #isFailed()}.
     */
    public long getTickFailureCount() {
        return tickFailureCount.get();
    }

    /**
     * Get the current run of consecutive failing ticks. Reset to 0 by any successful tick (so transient
     * failures self-heal); reaching {@link #MAX_CONSECUTIVE_TICK_FAILURES} trips the circuit-breaker
     * ({@link #isFailed()}).
     */
    public long getConsecutiveTickFailures() {
        return consecutiveTickFailures.get();
    }

    /**
     * Whether the simulation has circuit-broken: {@link #MAX_CONSECUTIVE_TICK_FAILURES} consecutive ticks
     * failed, so the scheduled task was cancelled and the simulation halted in a terminal FAILED state.
     * A subsequent {@link #start()} clears this.
     */
    public boolean isFailed() {
        return failed;
    }

    /**
     * Whether the simulation is running and has not circuit-broken. A supervisor wanting a finer signal can
     * additionally inspect {@link #getConsecutiveTickFailures()} (running with a non-zero streak = degraded).
     */
    public boolean isHealthy() {
        return running.get() && !failed;
    }

    /**
     * Get simulation metrics.
     */
    public SimulationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Get migration metrics.
     */
    public MigrationMetrics getMigrationMetrics() {
        return migration.getMetrics();
    }

    /**
     * Get the grid configuration.
     */
    public GridConfiguration getGridConfiguration() {
        return gridConfig;
    }

    /**
     * Get world bounds.
     */
    public WorldBounds getWorldBounds() {
        return worldBounds;
    }

    /**
     * Get bubble at a specific grid coordinate.
     *
     * @param coord Grid coordinate
     * @return Bubble at that coordinate
     */
    public EnhancedBubble getBubble(BubbleCoordinate coord) {
        return bubbleGrid.getBubble(coord);
    }

    /**
     * Get all entities from all bubbles (for visualization).
     * Includes both real entities and ghosts.
     *
     * @return List of all entities with their positions and bubble coordinates
     */
    public List<EntitySnapshot> getAllEntities() {
        var entities = new ArrayList<EntitySnapshot>();

        // Snapshot under snapshotLock so the cross-bubble read is atomic w.r.t. the migration commit
        // phase — a migrating entity is seen in exactly one bubble (its source pre-commit or its
        // target post-commit), never zero or two, so the entity count is conserved (Luciferase-jar).
        synchronized (snapshotLock) {
            for (int row = 0; row < gridConfig.rows(); row++) {
                for (int col = 0; col < gridConfig.columns(); col++) {
                    var coord = new BubbleCoordinate(row, col);
                    var bubble = bubbleGrid.getBubble(coord);

                    // Add real entities
                    for (var record : bubble.getAllEntityRecords()) {
                        entities.add(new EntitySnapshot(
                            record.id(),
                            record.position(),
                            coord,
                            false // Real entity
                        ));
                    }

                    // Add ghost entities (Inc 5C)
                    var ghosts = ghostSyncAdapter.getGhostsForBubble(bubble.id());
                    for (var ghost : ghosts) {
                        entities.add(new EntitySnapshot(
                            ghost.entityId().toString(),
                            ghost.position(),
                            coord,
                            true // Ghost entity
                        ));
                    }
                }
            }
        }

        return entities;
    }

    /**
     * Get total ghost count across all bubbles (for testing).
     *
     * @return Total number of ghost entities
     */
    public int getGhostCount() {
        return ghostSyncAdapter.getTotalGhostCount();
    }

    /**
     * Get only real entities (excludes ghosts).
     *
     * @return List of real entities (isGhost = false)
     */
    public List<EntitySnapshot> getRealEntities() {
        return getAllEntities().stream()
            .filter(e -> !e.isGhost())
            .toList();
    }

    // ========== Records for Visualization ==========

    /**
     * Snapshot of an entity for visualization.
     *
     * @param id            Entity ID
     * @param position      Current position
     * @param bubbleCoord   Which bubble (grid coordinate)
     * @param isGhost       True if this is a ghost copy (Inc 5C)
     */
    public record EntitySnapshot(String id, Point3f position, BubbleCoordinate bubbleCoord, boolean isGhost) {}

    // ========== Private Methods ==========

    private void populateEntities(int entityCount) {
        var random = new Random(42);
        var margin = 20f;

        var entities = new ArrayList<InitialDistribution.EntitySpec>();

        for (int i = 0; i < entityCount; i++) {
            // Use grid's actual bounds (not worldBounds which might be larger)
            float x = gridConfig.originX() + margin + random.nextFloat() * (gridConfig.totalWidth() - 2 * margin);
            float y = gridConfig.originY() + margin + random.nextFloat() * (gridConfig.totalHeight() - 2 * margin);
            float z = worldBounds.min() + margin + random.nextFloat() * (worldBounds.size() - 2 * margin);

            var position = new Point3f(x, y, z);
            entities.add(new InitialDistribution.EntitySpec("entity-" + i, position, null));
        }

        // Distribute entities spatially to bubbles
        InitialDistribution.distribute(entities, bubbleGrid, gridConfig);
    }

    private void initializeVelocities() {
        physicsManager.getVelocities().clear();
        // Luciferase-0frcy.98: seed deterministically so velocity-dependent behaviour (migration
        // counts, boundary crossings) is reproducible across runs, matching populateEntities()'s
        // seeded placement. Derive from gridConfig so different grids still get distinct streams.
        var random = new Random(VELOCITY_SEED ^ gridConfig.hashCode());

        // Initialize velocities for all entities in all bubbles
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                for (var entity : bubble.getAllEntityRecords()) {
                    physicsManager.setVelocity(entity.id(), randomVelocity(random, behavior.getMaxSpeed()));
                }
            }
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
            long startNs = clock.nanoTime();
            float deltaTime = DEFAULT_TICK_INTERVAL_MS / 1000.0f;
            long bucket = currentBucket.get();

            // Swap velocity buffers for FlockingBehavior
            if (behavior instanceof FlockingBehavior fb) {
                fb.swapVelocityBuffers();
            }

            // Update all bubbles
            int totalEntities = 0;
            for (int row = 0; row < gridConfig.rows(); row++) {
                for (int col = 0; col < gridConfig.columns(); col++) {
                    var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                    updateBubbleEntities(bubble, deltaTime, gridConfig.cellMin(new BubbleCoordinate(row, col)),
                                         gridConfig.cellMax(new BubbleCoordinate(row, col)));
                    totalEntities += bubble.entityCount();
                }
            }

            // Migration commit + ghost sync, both under snapshotLock so a concurrent getAllEntities()
            // observes a single logically-consistent tick: no migration seen mid-move across two
            // bubbles (Luciferase-jar), AND the ghost snapshot read in getAllEntities() is from the
            // same locked region as the ghost writes here (Luciferase-0frcy.64 / .97). Previously the
            // ghost writes ran outside the lock, so getAllEntities() could observe partially-written
            // ghost state or a ghost snapshot from a different tick phase than the real-entity snapshot.
            synchronized (snapshotLock) {
                migration.checkMigrations(tickCount.get());

                // Ghost sync: detect boundary entities and create ghosts (Inc 5C)
                ghostSyncAdapter.processBoundaryEntities(bucket);
                ghostSyncAdapter.onBucketComplete(bucket);
            }

            // Record metrics
            long frameTimeNs = clock.nanoTime() - startNs;
            metrics.recordTick(frameTimeNs, totalEntities);

            tickCount.incrementAndGet();
            currentBucket.incrementAndGet();
            // Successful tick — clear any in-progress failure streak so transient failures self-heal.
            consecutiveTickFailures.set(0);

            // Log periodically
            long currentTick = tickCount.get();
            if (currentTick > 0 && currentTick % 600 == 0) {
                log.debug("Tick {}: {} bubbles, {} entities, {} ghosts, {}",
                          currentTick, gridConfig.bubbleCount(), totalEntities,
                          ghostSyncAdapter.getTotalGhostCount(), metrics);
            }

        } catch (Exception e) {
            // RDR / Luciferase-a57pj: scheduleAtFixedRate keeps the task alive only because we swallow here;
            // without a failure surface a deterministically-broken tick would hot-loop every 16ms forever and
            // tickCount (a success counter) would silently diverge from scheduler invocations. Count the
            // failure, and circuit-break after a sustained streak so the breakage is loud and bounded.
            long total = tickFailureCount.incrementAndGet();
            long consecutive = consecutiveTickFailures.incrementAndGet();
            log.error("Error in simulation tick (lifetime failures={}, consecutive={}/{}): {}",
                      total, consecutive, MAX_CONSECUTIVE_TICK_FAILURES, e.getMessage(), e);

            if (consecutive >= MAX_CONSECUTIVE_TICK_FAILURES) {
                // Order matters for observers polling both flags: clear running first, then set failed, so a
                // reader never sees the inconsistent (running && failed) pair — only ever (running && !failed),
                // (!running && !failed) transiently, or the terminal (!running && failed).
                running.set(false);
                failed = true;
                var task = tickTask;
                if (task != null) {
                    task.cancel(false);  // safe from the scheduler thread; false = don't self-interrupt
                }
                log.error("GridMultiBubbleSimulation circuit-break: {} consecutive tick failures — halting "
                          + "(terminal FAILED state). Last error: {}", consecutive, e.getMessage());
            }
        }
    }

    private void updateBubbleEntities(
        EnhancedBubble bubble,
        float deltaTime,
        Point3f cellMin,
        Point3f cellMax
    ) {
        for (var entity : bubble.getAllEntityRecords()) {
            try {
                var existing = physicsManager.getVelocity(entity.id());
                var velocity = existing != null ? existing : new javax.vecmath.Vector3f();

                var newVelocity = behavior.computeVelocity(
                    entity.id(),
                    entity.position(),
                    velocity,
                    bubble,
                    deltaTime
                );

                physicsManager.setVelocity(entity.id(), newVelocity);

                var newPosition = new Point3f(entity.position());
                newPosition.x += newVelocity.x * deltaTime;
                newPosition.y += newVelocity.y * deltaTime;
                newPosition.z += newVelocity.z * deltaTime;

                // Clamp to cell bounds (XY) and world bounds (Z)
                newPosition.x = Math.max(cellMin.x, Math.min(cellMax.x, newPosition.x));
                newPosition.y = Math.max(cellMin.y, Math.min(cellMax.y, newPosition.y));
                newPosition.z = worldBounds.clamp(newPosition.z);

                bubble.updateEntityPosition(entity.id(), newPosition);
            } catch (Exception e) {
                log.error("Failed to update entity {}: {}", entity.id(), e.getMessage());
            }
        }
    }
}
