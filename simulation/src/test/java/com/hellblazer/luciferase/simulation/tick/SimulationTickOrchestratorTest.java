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
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test SimulationTickOrchestrator using TDD methodology.
 * <p>
 * Tests verify tick orchestration logic:
 * - Velocity buffer swapping
 * - Grid iteration
 * - Metrics recording
 * - Periodic logging
 * - Exception handling
 *
 * @author hal.hildebrand
 */
class SimulationTickOrchestratorTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TICK_INTERVAL_MS = 16L;

    private TestClock testClock;
    private SimulationMetrics metrics;
    private FlockingBehavior behavior;
    private BubbleGrid<EnhancedBubble> bubbleGrid;
    private GridConfiguration gridConfig;

    @BeforeEach
    void setUp() {
        testClock = new TestClock(1000L); // Initialize with absolute time

        metrics = new SimulationMetrics();
        behavior = new FlockingBehavior();

        // Create 2x2 grid for testing
        gridConfig = GridConfiguration.of(2, 2, 100f, 100f);
        bubbleGrid = BubbleGrid.createEmpty(gridConfig);

        // Populate grid with bubbles
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = new EnhancedBubble(java.util.UUID.randomUUID(), SPATIAL_LEVEL, TICK_INTERVAL_MS);
                bubbleGrid.setBubble(coord, bubble);
            }
        }
    }

    @Test
    void testVelocityBufferSwapping() {
        // Given: FlockingBehavior that supports buffer swapping
        var orchestrator = SimulationTickOrchestrator.create(
            testClock,
            gridConfig,
            bubbleGrid,
            behavior,
            metrics,
            (bubble, deltaTime) -> {} // No-op entity update
        );

        // Verify initial state
        assertNotNull(behavior);

        // When: Execute tick
        orchestrator.executeTick();

        // Then: Buffer swap was called (verify no exception thrown)
        // FlockingBehavior.swapVelocityBuffers() creates new ConcurrentHashMap
        // We can't directly verify internal state, but no exception means success
    }

    @Test
    void testVelocityBufferSwappingWithNonFlockingBehavior() {
        // Given: Non-flocking behavior (no buffer swapping)
        var simpleBehavior = new EntityBehavior() {
            @Override
            public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                          EnhancedBubble bubble, float deltaTime) {
                return velocity;
            }

            @Override
            public float getAoiRadius() {
                return 10f;
            }

            @Override
            public float getMaxSpeed() {
                return 5f;
            }
        };

        var orchestrator = SimulationTickOrchestrator.create(
            testClock,
            gridConfig,
            bubbleGrid,
            simpleBehavior,
            metrics,
            (bubble, deltaTime) -> {}
        );

        // When: Execute tick
        orchestrator.executeTick();

        // Then: No exception thrown (behavior doesn't support buffer swapping)
    }

    @Test
    void testGridIteration() {
        // Given: Counter to track bubble updates
        var updateCount = new int[1];
        var orchestrator = SimulationTickOrchestrator.create(
            testClock,
            gridConfig,
            bubbleGrid,
            behavior,
            metrics,
            (bubble, deltaTime) -> updateCount[0]++
        );

        // When: Execute tick
        orchestrator.executeTick();

        // Then: All 4 bubbles were updated (2x2 grid)
        assertEquals(4, updateCount[0], "Should update all 4 bubbles in 2x2 grid");
    }

    @Test
    void testMetricsRecording() {
        // Given: Empty metrics
        assertEquals(0L, metrics.getTotalTicks());

        var orchestrator = SimulationTickOrchestrator.create(
            testClock,
            gridConfig,
            bubbleGrid,
            behavior,
            metrics,
            (bubble, deltaTime) -> {}
        );

        // Advance clock for frame time measurement
        testClock.setTime(1000L);

        // When: Execute tick
        orchestrator.executeTick();

        // Then: Metrics were recorded
        assertEquals(1L, metrics.getTotalTicks(), "Should record 1 tick");
        assertTrue(metrics.getLastFrameTimeMs() >= 0, "Frame time should be non-negative");
    }

    @Test
    void testPeriodicLogging() {
        // Given: Orchestrator with tick counter
        var tickCounter = new long[1];
        var orchestrator = SimulationTickOrchestrator.create(
            testClock,
            gridConfig,
            bubbleGrid,
            behavior,
            metrics,
            (bubble, deltaTime) -> {}
        );

        // When: Execute 600 ticks (logging threshold)
        for (int i = 0; i < 600; i++) {
            testClock.advance(16L); // Advance 16ms
            orchestrator.executeTick();
            tickCounter[0]++;
        }

        // Then: All ticks executed without exception
        assertEquals(600L, tickCounter[0]);
        assertEquals(600L, metrics.getTotalTicks());
    }

    @Test
    void testFullSequence() {
        // Given: Complete orchestrator setup with entity counting
        var totalEntitiesProcessed = new int[1];
        Function<EnhancedBubble, Integer> entityCounter = bubble -> {
            int count = bubble.entityCount();
            totalEntitiesProcessed[0] += count;
            return count;
        };

        var orchestrator = SimulationTickOrchestrator.create(
            testClock,
            gridConfig,
            bubbleGrid,
            behavior,
            metrics,
            (bubble, deltaTime) -> entityCounter.apply(bubble)
        );

        // Add entities to bubbles
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var bubble = bubbleGrid.getBubble(new BubbleCoordinate(row, col));
                bubble.addEntity("entity-" + row + "-" + col, new Point3f(0, 0, 0), null);
            }
        }

        // When: Execute tick
        testClock.setTime(1000L);
        orchestrator.executeTick();
        testClock.advance(100L); // 100ms later

        // Then: Full sequence executed
        assertEquals(1L, metrics.getTotalTicks());
        assertEquals(4, totalEntitiesProcessed[0], "Should process 4 entities (one per bubble)");
        assertTrue(metrics.getLastFrameTimeMs() >= 0);
    }

    @Test
    void testExceptionHandling() {
        // Given: Entity update function that throws exception
        var orchestrator = SimulationTickOrchestrator.create(
            testClock,
            gridConfig,
            bubbleGrid,
            behavior,
            metrics,
            (bubble, deltaTime) -> {
                throw new RuntimeException("Simulated entity update failure");
            }
        );

        // When: Execute tick with exception
        orchestrator.executeTick();

        // Then: Exception was caught and logged, tick completed
        // Metrics may not be fully recorded if exception occurs before metrics
        // But orchestrator should not propagate exception
    }

    @Test
    void testDeltaTimeCalculation() {
        // Given: Known tick interval
        var capturedDeltaTime = new float[1];
        var orchestrator = SimulationTickOrchestrator.create(
            testClock,
            gridConfig,
            bubbleGrid,
            behavior,
            metrics,
            (bubble, deltaTime) -> capturedDeltaTime[0] = deltaTime
        );

        // When: Execute tick
        orchestrator.executeTick();

        // Then: Delta time matches expected value (16ms = 0.016s)
        assertEquals(TICK_INTERVAL_MS / 1000.0f, capturedDeltaTime[0], 0.001f,
                    "Delta time should be " + TICK_INTERVAL_MS + "ms in seconds");
    }
}
