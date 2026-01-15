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
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleCoordinate;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.tick.EntityUpdateExecutor;
import com.hellblazer.luciferase.simulation.tick.SimulationTickOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-bubble distributed simulation with N bubbles in grid topology.
 * <p>
 * Generalizes TwoBubbleSimulation to support arbitrary NxM grids. Each bubble
 * runs in the same process with coordinated tick execution.
 * <p>
 * Features:
 * - Grid topology with automatic neighbor setup
 * - Spatial entity distribution based on position
 * - Synchronized tick execution across all bubbles
 * - Unified visualization endpoint
 * - Metrics aggregation
 * <p>
 * Architecture:
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                   MultiBubbleSimulation                         │
 * │                                                                 │
 * │  ┌──────┐  ┌──────┐  ┌──────┐                                  │
 * │  │ B(0,0)│──│ B(0,1)│──│ B(0,2)│  Grid topology                  │
 * │  └──┬───┘  └──┬───┘  └──┬───┘   with neighbor sync            │
 * │     │         │         │                                       │
 * │  ┌──┴───┐  ┌──┴───┐  ┌──┴───┐                                  │
 * │  │ B(1,0)│──│ B(1,1)│──│ B(1,2)│                                │
 * │  └──────┘  └──────┘  └──────┘                                  │
 * │                                                                 │
 * │           Coordinated Tick Execution                            │
 * │           Visualization (all entities)                          │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @author hal.hildebrand
 */
public class MultiBubbleSimulation implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MultiBubbleSimulation.class);

    /**
     * Default tick interval: 60fps (16.67ms).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    /**
     * Default spatial level for bubble spatial indices.
     */
    public static final byte DEFAULT_SPATIAL_LEVEL = 10;

    private final GridConfiguration gridConfig;
    private final BubbleGrid<EnhancedBubble> bubbleGrid;
    private final EntityBehavior behavior;
    private final Map<UUID, Vector3f> velocities = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCount = new AtomicLong(0);
    private final SimulationMetrics metrics = new SimulationMetrics();
    private volatile Clock clock = Clock.system();
    private final SimulationTickOrchestrator tickOrchestrator;

    private ScheduledFuture<?> tickTask;

    /**
     * Create a multi-bubble simulation with default behavior.
     *
     * @param gridConfig  Grid configuration
     * @param entityCount Total number of entities (distributed spatially)
     */
    public MultiBubbleSimulation(GridConfiguration gridConfig, int entityCount) {
        this(gridConfig, entityCount, new FlockingBehavior());
    }

    /**
     * Create a multi-bubble simulation with custom behavior.
     *
     * @param gridConfig  Grid configuration
     * @param entityCount Total number of entities
     * @param behavior    Entity behavior for all bubbles
     */
    public MultiBubbleSimulation(GridConfiguration gridConfig, int entityCount, EntityBehavior behavior) {
        this.gridConfig = gridConfig;
        this.behavior = behavior;

        // Create bubbles for all grid cells
        this.bubbleGrid = GridBubbleFactory.createBubbles(
            gridConfig,
            DEFAULT_SPATIAL_LEVEL,
            DEFAULT_TICK_INTERVAL_MS
        );

        // Set up neighbor relationships
        setupNeighborRelationships();

        // Populate entities with spatial distribution
        populateEntities(entityCount);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "MultiBubbleSimulation");
            t.setDaemon(true);
            return t;
        });

        // Create entity update executor
        var entityUpdateExecutor = EntityUpdateExecutor.create(behavior, velocities, gridConfig);

        // Create tick orchestrator
        this.tickOrchestrator = SimulationTickOrchestrator.create(
            clock,
            gridConfig,
            bubbleGrid,
            behavior,
            metrics,
            entityUpdateExecutor::updateEntities
        );

        log.info("MultiBubbleSimulation created: {} bubbles ({}), {} total entities",
                 gridConfig.bubbleCount(), gridConfig, getTotalEntityCount());
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
            initializeVelocities();

            tickTask = scheduler.scheduleAtFixedRate(
                this::tick,
                0,
                DEFAULT_TICK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );

            log.info("MultiBubbleSimulation started");
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

        // Close all bubbles
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                if (bubble != null) {
                    // EnhancedBubble doesn't have close(), nothing to clean up
                }
            }
        }
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
     * Get grid configuration.
     */
    public GridConfiguration getGridConfiguration() {
        return gridConfig;
    }

    /**
     * Get number of bubbles in the grid.
     */
    public int getBubbleCount() {
        return gridConfig.bubbleCount();
    }

    /**
     * Get total entity count across all bubbles.
     */
    public int getTotalEntityCount() {
        int total = 0;
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                if (bubble != null) {
                    total += bubble.entityCount();
                }
            }
        }
        return total;
    }

    /**
     * Get bubble at specific coordinate.
     *
     * @param coord Grid coordinate
     * @return Bubble at coordinate
     * @throws IllegalArgumentException if coordinate is out of bounds
     */
    public EnhancedBubble getBubble(BubbleCoordinate coord) {
        if (!gridConfig.isValid(coord)) {
            throw new IllegalArgumentException("Invalid coordinate: " + coord);
        }
        return bubbleGrid.getBubble(coord);
    }

    /**
     * Get all entities from all bubbles (for visualization).
     */
    public List<EntitySnapshot> getAllEntities() {
        var entities = new ArrayList<EntitySnapshot>();

        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = bubbleGrid.getBubble(coord);
                if (bubble != null) {
                    for (var record : bubble.getAllEntityRecords()) {
                        entities.add(new EntitySnapshot(
                            record.id(),
                            record.position(),
                            coord,
                            false
                        ));
                    }
                }
            }
        }

        return entities;
    }

    /**
     * Get detailed debug state for visualization and debugging.
     */
    public DebugState getDebugState() {
        return new DebugState(
            tickCount.get(),
            getTotalEntityCount(),
            getBubbleCount(),
            metrics
        );
    }

    // ========== Records for Visualization and Debugging ==========

    /**
     * Snapshot of an entity for visualization.
     *
     * @param id       Entity ID
     * @param position Current position
     * @param coord    Grid coordinate of owning bubble
     * @param isGhost  True if this is a ghost copy
     */
    public record EntitySnapshot(String id, Point3f position, BubbleCoordinate coord, boolean isGhost) {}

    /**
     * Debug state snapshot for monitoring and debugging.
     */
    public record DebugState(
        long tickCount,
        int totalEntityCount,
        int bubbleCount,
        SimulationMetrics metrics
    ) {}

    // ========== Private Methods ==========

    /**
     * Set up neighbor relationships for all bubbles based on grid topology.
     */
    private void setupNeighborRelationships() {
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = bubbleGrid.getBubble(coord);
                var neighbors = bubbleGrid.getNeighbors(coord);

                for (var neighbor : neighbors) {
                    bubble.addVonNeighbor(neighbor.id());
                }
            }
        }
    }

    /**
     * Populate entities using spatial distribution.
     */
    private void populateEntities(int entityCount) {
        // Generate random positions within grid bounds
        var random = new Random(42);
        var margin = 10f;
        var positions = new ArrayList<Point3f>();

        for (int i = 0; i < entityCount; i++) {
            var x = gridConfig.originX() + margin + random.nextFloat() * (gridConfig.totalWidth() - 2 * margin);
            var y = gridConfig.originY() + margin + random.nextFloat() * (gridConfig.totalHeight() - 2 * margin);
            var z = margin + random.nextFloat() * (100f - 2 * margin);  // Z range: [margin, 100-margin]
            positions.add(new Point3f(x, y, z));
        }

        // Distribute entities to bubbles based on position
        var distribution = InitialDistribution.distribute(positions, gridConfig);

        int entityIndex = 0;
        for (var entry : distribution.entrySet()) {
            var coord = entry.getKey();
            var bubble = bubbleGrid.getBubble(coord);

            for (var position : entry.getValue()) {
                bubble.addEntity("entity-" + entityIndex++, position, null);
            }
        }
    }

    /**
     * Initialize velocities for all entities.
     */
    private void initializeVelocities() {
        velocities.clear();
        var random = new Random();

        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                for (var entity : bubble.getAllEntityRecords()) {
                    velocities.put(
                        UUID.nameUUIDFromBytes(entity.id().getBytes()),
                        randomVelocity(random, behavior.getMaxSpeed())
                    );
                }
            }
        }
    }

    /**
     * Generate random velocity vector.
     */
    private Vector3f randomVelocity(Random random, float maxSpeed) {
        return new Vector3f(
            (random.nextFloat() - 0.5f) * 2 * maxSpeed,
            (random.nextFloat() - 0.5f) * 2 * maxSpeed,
            (random.nextFloat() - 0.5f) * 2 * maxSpeed
        );
    }

    /**
     * Execute one simulation tick (delegates to orchestrator).
     */
    private void tick() {
        tickOrchestrator.executeTick();
        tickCount.set(tickOrchestrator.getTickCount());
    }

}
