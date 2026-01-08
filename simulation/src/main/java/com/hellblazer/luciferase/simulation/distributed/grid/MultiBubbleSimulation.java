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
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
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
public class MultiBubbleSimulation implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MultiBubbleSimulation.class);

    /**
     * Default tick interval: 60fps (16.67ms).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    private final GridConfiguration gridConfig;
    private final WorldBounds worldBounds;
    private final BubbleGrid<EnhancedBubble> bubbleGrid;
    private final EntityBehavior behavior;
    private final GridGhostSyncAdapter ghostSyncAdapter;

    private final Map<String, javax.vecmath.Vector3f> velocities = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCount = new AtomicLong(0);
    private final AtomicLong currentBucket = new AtomicLong(0);
    private final SimulationMetrics metrics = new SimulationMetrics();

    private ScheduledFuture<?> tickTask;

    /**
     * Create a multi-bubble simulation with default behavior.
     *
     * @param gridConfig  Grid configuration (NxM bubbles)
     * @param entityCount Number of entities (spatially distributed)
     * @param worldBounds World boundary configuration
     */
    public MultiBubbleSimulation(GridConfiguration gridConfig, int entityCount, WorldBounds worldBounds) {
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
    public MultiBubbleSimulation(
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

        // Distribute entities spatially
        populateEntities(entityCount);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "MultiBubbleSimulation-" + gridConfig.rows() + "x" + gridConfig.columns());
            t.setDaemon(true);
            return t;
        });

        log.info("MultiBubbleSimulation created: {} bubbles ({}x{}), {} entities",
                 gridConfig.bubbleCount(), gridConfig.rows(), gridConfig.columns(), entityCount);
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

            log.info("MultiBubbleSimulation started: {} bubbles", gridConfig.bubbleCount());
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
            log.info("MultiBubbleSimulation stopped after {} ticks. {}", tickCount.get(), metrics);
        }
    }

    @Override
    public void close() {
        stop();

        velocities.clear();

        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate within 1 second");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for scheduler termination");
        }

        log.debug("MultiBubbleSimulation closed");
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
        velocities.clear();
        var random = new Random();

        // Initialize velocities for all entities in all bubbles
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                for (var entity : bubble.getAllEntityRecords()) {
                    velocities.put(entity.id(), randomVelocity(random, behavior.getMaxSpeed()));
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
            long startNs = System.nanoTime();
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

            // Ghost sync: detect boundary entities and create ghosts (Inc 5C)
            ghostSyncAdapter.processBoundaryEntities(bucket);
            ghostSyncAdapter.onBucketComplete(bucket);

            // Record metrics
            long frameTimeNs = System.nanoTime() - startNs;
            metrics.recordTick(frameTimeNs, totalEntities);

            tickCount.incrementAndGet();
            currentBucket.incrementAndGet();

            // Log periodically
            long currentTick = tickCount.get();
            if (currentTick > 0 && currentTick % 600 == 0) {
                log.debug("Tick {}: {} bubbles, {} entities, {} ghosts, {}",
                          currentTick, gridConfig.bubbleCount(), totalEntities,
                          ghostSyncAdapter.getTotalGhostCount(), metrics);
            }

        } catch (Exception e) {
            log.error("Error in simulation tick: {}", e.getMessage(), e);
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
