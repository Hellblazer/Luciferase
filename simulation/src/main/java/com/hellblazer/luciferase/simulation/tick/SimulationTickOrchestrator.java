/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tick;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleCoordinate;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates simulation tick execution across a bubble grid.
 * <p>
 * Extracted from MultiBubbleSimulation.tick() to reduce complexity.
 * Handles:
 * - Frame preparation (velocity buffer swapping)
 * - Grid iteration and entity updates
 * - Metrics recording
 * - Periodic logging
 * - Exception handling
 * <p>
 * Design Pattern: Command pattern with injected entity update logic.
 *
 * @author hal.hildebrand
 */
public class SimulationTickOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SimulationTickOrchestrator.class);

    /**
     * Default tick interval: 60fps (16.67ms).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    private final Clock clock;
    private final GridConfiguration gridConfig;
    private final BubbleGrid<EnhancedBubble> bubbleGrid;
    private final EntityBehavior behavior;
    private final SimulationMetrics metrics;
    private final BubbleEntityUpdater entityUpdater;
    private final AtomicLong tickCount;

    /**
     * Functional interface for updating entities in a bubble.
     */
    @FunctionalInterface
    public interface BubbleEntityUpdater {
        /**
         * Update all entities in the given bubble.
         *
         * @param bubble    Bubble containing entities to update
         * @param deltaTime Time step in seconds
         */
        void updateEntities(EnhancedBubble bubble, float deltaTime);
    }

    private SimulationTickOrchestrator(Clock clock,
                                      GridConfiguration gridConfig,
                                      BubbleGrid<EnhancedBubble> bubbleGrid,
                                      EntityBehavior behavior,
                                      SimulationMetrics metrics,
                                      BubbleEntityUpdater entityUpdater) {
        this.clock = clock;
        this.gridConfig = gridConfig;
        this.bubbleGrid = bubbleGrid;
        this.behavior = behavior;
        this.metrics = metrics;
        this.entityUpdater = entityUpdater;
        this.tickCount = new AtomicLong(0);
    }

    /**
     * Create a new tick orchestrator.
     *
     * @param clock         Clock for time measurement
     * @param gridConfig    Grid configuration
     * @param bubbleGrid    Grid of bubbles to orchestrate
     * @param behavior      Entity behavior (for buffer swapping)
     * @param metrics       Metrics recorder
     * @param entityUpdater Function to update entities in a bubble
     * @return New orchestrator instance
     */
    public static SimulationTickOrchestrator create(Clock clock,
                                                   GridConfiguration gridConfig,
                                                   BubbleGrid<EnhancedBubble> bubbleGrid,
                                                   EntityBehavior behavior,
                                                   SimulationMetrics metrics,
                                                   BubbleEntityUpdater entityUpdater) {
        return new SimulationTickOrchestrator(clock, gridConfig, bubbleGrid, behavior, metrics, entityUpdater);
    }

    /**
     * Execute one simulation tick.
     * <p>
     * Sequence:
     * 1. Prepare frame (swap velocity buffers if needed)
     * 2. Process all bubbles in grid
     * 3. Record metrics
     * 4. Log periodically (every 600 ticks)
     * 5. Handle exceptions
     */
    public void executeTick() {
        try {
            long startNs = clock.nanoTime();
            float deltaTime = DEFAULT_TICK_INTERVAL_MS / 1000.0f;

            // Swap velocity buffers for FlockingBehavior
            if (behavior instanceof FlockingBehavior fb) {
                fb.swapVelocityBuffers();
            }

            // Update all bubbles
            for (int row = 0; row < gridConfig.rows(); row++) {
                for (int col = 0; col < gridConfig.columns(); col++) {
                    var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                    entityUpdater.updateEntities(bubble, deltaTime);
                }
            }

            // Record metrics
            long frameTimeNs = clock.nanoTime() - startNs;
            int totalEntities = getTotalEntityCount();
            metrics.recordTick(frameTimeNs, totalEntities);

            tickCount.incrementAndGet();

            // Log periodically
            long currentTick = tickCount.get();
            if (currentTick > 0 && currentTick % 600 == 0) {
                log.debug("Tick {}: {} entities across {} bubbles, {}",
                         currentTick, totalEntities, gridConfig.bubbleCount(), metrics);
            }

        } catch (Exception e) {
            log.error("Error in simulation tick: {}", e.getMessage(), e);
        }
    }

    /**
     * Get total entity count across all bubbles.
     */
    private int getTotalEntityCount() {
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
     * Get current tick count.
     */
    public long getTickCount() {
        return tickCount.get();
    }
}
