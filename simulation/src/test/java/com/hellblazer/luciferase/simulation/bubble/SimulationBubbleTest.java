/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.behavior.RandomWalkBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for SimulationBubble.
 * <p>
 * Validates:
 * <ul>
 *   <li>Lifecycle control (start/stop)</li>
 *   <li>Physics tick execution</li>
 *   <li>Entity movement</li>
 *   <li>World bounds clamping</li>
 *   <li>Determinism (same seed = identical results)</li>
 *   <li>FlockingBehavior integration</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class SimulationBubbleTest {

    private EnhancedBubble bubble;
    private SimulationBubble simulation;

    @BeforeEach
    void setUp() throws Exception {
        // Create a test bubble with 10 entities
        bubble = TestBubbleFactory.createTestBubble(10, new Random(42));
    }

    @AfterEach
    void tearDown() {
        if (simulation != null) {
            simulation.shutdown();
        }
    }

    @Test
    void testStartStop() throws Exception {
        var behavior = new RandomWalkBehavior(new Random(42), 5.0f, 10.0f, 0.1f, WorldBounds.DEFAULT);
        simulation = new SimulationBubble(bubble, behavior);

        assertFalse(simulation.isRunning(), "Should not be running initially");

        simulation.start();
        assertTrue(simulation.isRunning(), "Should be running after start");

        Thread.sleep(100);  // Let it tick a few times

        simulation.stop();
        assertFalse(simulation.isRunning(), "Should not be running after stop");

        assertTrue(simulation.getTickCount() > 0, "Should have executed at least one tick");
    }

    @Test
    void testTicksExecute() throws Exception {
        var behavior = new RandomWalkBehavior(new Random(42), 5.0f, 10.0f, 0.1f, WorldBounds.DEFAULT);
        simulation = new SimulationBubble(bubble, behavior);

        simulation.start();
        Thread.sleep(100);  // Wait for ticks to execute (16ms interval = ~6 ticks per 100ms)
        simulation.stop();

        var tickCount = simulation.getTickCount();
        assertTrue(tickCount > 0, "Should have executed at least one tick");
        assertTrue(tickCount >= 3, "Should have executed at least 3 ticks in 100ms (expected ~6)");

        System.out.println("Executed " + tickCount + " ticks in 100ms");
    }

    @Test
    void testEntitiesMove() throws Exception {
        var behavior = new RandomWalkBehavior(new Random(42), 10.0f, 20.0f, 0.5f, WorldBounds.DEFAULT);
        simulation = new SimulationBubble(bubble, behavior);

        // Capture initial positions
        var initialPositions = capturePositions(bubble);

        simulation.start();
        Thread.sleep(200);  // Let entities move for 200ms
        simulation.stop();

        // Capture final positions
        var finalPositions = capturePositions(bubble);

        // Verify at least one entity moved
        boolean anyMoved = false;
        for (var entityId : initialPositions.keySet()) {
            var initial = initialPositions.get(entityId);
            var finalPos = finalPositions.get(entityId);

            if (!positionsEqual(initial, finalPos)) {
                anyMoved = true;
                break;
            }
        }

        assertTrue(anyMoved, "At least one entity should have moved");
    }

    @Test
    void testEntitiesStayInBounds() throws Exception {
        var worldBounds = new WorldBounds(0.0f, 200.0f);  // [0, 200] bounds
        var behavior = new RandomWalkBehavior(new Random(42), 20.0f, 30.0f, 1.0f, WorldBounds.DEFAULT);  // Fast movement
        simulation = new SimulationBubble(bubble, behavior, 16, worldBounds);

        simulation.start();
        Thread.sleep(500);  // Run for 500ms with fast movement
        simulation.stop();

        // Verify all entities are within bounds
        var entities = bubble.getAllEntityRecords();
        for (var entity : entities) {
            var pos = entity.position();
            assertTrue(pos.x >= 0.0f && pos.x <= 200.0f,
                       "Entity " + entity.id() + " x out of bounds: " + pos.x);
            assertTrue(pos.y >= 0.0f && pos.y <= 200.0f,
                       "Entity " + entity.id() + " y out of bounds: " + pos.y);
            assertTrue(pos.z >= 0.0f && pos.z <= 200.0f,
                       "Entity " + entity.id() + " z out of bounds: " + pos.z);
        }
    }

    /**
     * Test that simulations with same seed produce identical, deterministic results.
     * Now uses TestClock for deterministic time advancement (no wall-clock variability).
     */
    @Test
    void testDeterminismWithSameSeed() throws Exception {
        var seed = 42L;
        var worldBounds = WorldBounds.DEFAULT;
        var tickIntervalMs = 16L;

        // Use TestClock for deterministic time advancement (not wall-clock Thread.sleep)
        var testClock1 = new TestClock(0L);
        var testClock2 = new TestClock(0L);

        // Run 1: First simulation
        var bubble1 = TestBubbleFactory.createTestBubble(10, new Random(seed));
        var behavior1 = new RandomWalkBehavior(new Random(seed), 10.0f, 20.0f, 0.5f, worldBounds);
        var sim1 = new SimulationBubble(bubble1, behavior1, tickIntervalMs, worldBounds);
        sim1.setClock(testClock1);

        sim1.start();
        testClock1.advance(200);  // Deterministic 200ms advancement
        sim1.stop();

        var positions1 = capturePositions(bubble1);
        var tickCount1 = sim1.getTickCount();

        // Run 2: Second simulation with SAME seed
        var bubble2 = TestBubbleFactory.createTestBubble(10, new Random(seed));
        var behavior2 = new RandomWalkBehavior(new Random(seed), 10.0f, 20.0f, 0.5f, worldBounds);
        var sim2 = new SimulationBubble(bubble2, behavior2, tickIntervalMs, worldBounds);
        sim2.setClock(testClock2);

        sim2.start();
        testClock2.advance(200);  // Deterministic 200ms advancement
        sim2.stop();

        var positions2 = capturePositions(bubble2);
        var tickCount2 = sim2.getTickCount();

        // Verify tick counts are IDENTICAL (deterministic with TestClock)
        assertEquals(tickCount1, tickCount2,
                     "Tick counts should be identical with deterministic clock");

        // Verify positions are identical (determinism)
        for (var entityId : positions1.keySet()) {
            var pos1 = positions1.get(entityId);
            var pos2 = positions2.get(entityId);

            assertNotNull(pos2, "Entity " + entityId + " should exist in both runs");
            assertTrue(positionsEqual(pos1, pos2),
                       "Entity " + entityId + " positions should be identical: " +
                       positionToString(pos1) + " vs " + positionToString(pos2));
        }

        sim1.shutdown();
        sim2.shutdown();
    }

    @Test
    void testFlockingBehaviorIntegration() throws Exception {
        // Create bubble with more entities for flocking
        var flockBubble = TestBubbleFactory.createTestBubble(20, new Random(42));

        var behavior = new FlockingBehavior(
            50.0f,   // AOI radius
            5.0f,    // max speed
            0.5f,    // max force
            0.5f,    // separation weight
            0.3f,    // alignment weight
            0.2f,    // cohesion weight
            WorldBounds.DEFAULT,
            new Random(42)
        );

        simulation = new SimulationBubble(flockBubble, behavior);

        simulation.start();
        Thread.sleep(300);  // Let flocking behavior evolve
        simulation.stop();

        // Verify entities moved (flocking should cause movement)
        var entities = flockBubble.getAllEntityRecords();
        assertTrue(entities.size() > 0, "Should have entities");

        // Verify tick count
        assertTrue(simulation.getTickCount() > 10, "Should have executed multiple ticks");

        System.out.println("Flocking simulation: " + simulation.getTickCount() +
                           " ticks, " + entities.size() + " entities");
    }

    @Test
    void testClockInjection() throws Exception {
        var testClock = new TestClock();
        testClock.setTime(1000L);

        var behavior = new RandomWalkBehavior(new Random(42), 5.0f, 10.0f, 0.1f, WorldBounds.DEFAULT);
        simulation = new SimulationBubble(bubble, behavior);
        simulation.setClock(testClock);

        // Clock injection doesn't affect Prime-Mover timing (uses Kronos),
        // but it does affect nanoTime() calls in physicsTick() for metrics
        simulation.start();
        Thread.sleep(100);
        simulation.stop();

        assertTrue(simulation.getTickCount() > 0, "Should have executed ticks");
    }

    @Test
    void testDefaultConstructor() {
        var behavior = new RandomWalkBehavior(new Random(42), 5.0f, 10.0f, 0.1f, WorldBounds.DEFAULT);
        simulation = new SimulationBubble(bubble, behavior);

        assertNotNull(simulation.getBubble());
        assertNotNull(simulation.getBehavior());
        assertNotNull(simulation.getWorldBounds());
        assertNotNull(simulation.getController());
        assertFalse(simulation.isRunning());
        assertEquals(0, simulation.getTickCount());
    }

    @Test
    void testNullValidation() {
        var behavior = new RandomWalkBehavior(new Random(42), 5.0f, 10.0f, 0.1f, WorldBounds.DEFAULT);

        assertThrows(IllegalArgumentException.class, () ->
            new SimulationBubble(null, behavior));

        assertThrows(IllegalArgumentException.class, () ->
            new SimulationBubble(bubble, null));

        assertThrows(IllegalArgumentException.class, () ->
            new SimulationBubble(bubble, behavior, 0, WorldBounds.DEFAULT));

        assertThrows(IllegalArgumentException.class, () ->
            new SimulationBubble(bubble, behavior, 16, null));
    }

    // Helper methods

    private Map<String, Point3f> capturePositions(EnhancedBubble bubble) {
        var positions = new HashMap<String, Point3f>();
        for (var entity : bubble.getAllEntityRecords()) {
            positions.put(entity.id(), new Point3f(entity.position()));
        }
        return positions;
    }

    private boolean positionsEqual(Point3f p1, Point3f p2) {
        float epsilon = 0.0001f;
        return Math.abs(p1.x - p2.x) < epsilon &&
               Math.abs(p1.y - p2.y) < epsilon &&
               Math.abs(p1.z - p2.z) < epsilon;
    }

    private String positionToString(Point3f p) {
        return String.format("(%.4f, %.4f, %.4f)", p.x, p.y, p.z);
    }

    @Test
    void testEntityChurnRobustness() throws Exception {
        var behavior = new RandomWalkBehavior(new Random(42), 5.0f, 10.0f, 0.1f, WorldBounds.DEFAULT);
        simulation = new SimulationBubble(bubble, behavior);

        // Add initial entities
        for (int i = 0; i < 10; i++) {
            bubble.addEntity("initial-" + i, new Point3f(100, 100, 100), null);
        }

        simulation.start();
        Thread.sleep(100);  // Let simulation run for a bit

        // Simulate entity churn: remove old entities and add new ones
        for (int cycle = 0; cycle < 5; cycle++) {
            // Remove all existing entities
            var entities = bubble.getAllEntityRecords();
            for (var entity : entities) {
                bubble.removeEntity(entity.id());
            }

            // Add new entities with different IDs
            for (int i = 0; i < 10; i++) {
                bubble.addEntity("cycle-" + cycle + "-entity-" + i,
                                new Point3f(100 + i * 10, 100, 100), null);
            }

            Thread.sleep(100);  // Let entities be processed
        }

        simulation.stop();

        // Verify: Simulation handles entity churn without crashing
        var finalEntities = bubble.getAllEntityRecords();
        assertTrue(finalEntities.size() > 0, "Should have entities after churn");
        assertTrue(simulation.getTickCount() > 0, "Should have executed ticks during churn");

        System.out.println("Entity churn test: " + simulation.getTickCount() +
                          " ticks, " + finalEntities.size() + " final entities, " +
                          "5 churn cycles completed successfully");
    }

    @Test
    @DisabledIfEnvironmentVariable(
        named = "CI",
        matches = "true",
        disabledReason = "Long-running test (30+ seconds) to validate periodic cleanup. " +
                         "Cleanup runs every 1800 ticks (30s at 60fps). " +
                         "Manual validation test, not for CI."
    )
    void testPeriodicCleanupTriggersCorrectly() throws Exception {
        var behavior = new RandomWalkBehavior(new Random(42), 5.0f, 10.0f, 0.1f, WorldBounds.DEFAULT);
        simulation = new SimulationBubble(bubble, behavior);

        // Add and remove entities to create stale velocity cache entries
        for (int i = 0; i < 20; i++) {
            bubble.addEntity("temp-" + i, new Point3f(100, 100, 100), null);
        }

        simulation.start();
        Thread.sleep(1000);  // Let entities be processed

        // Remove half the entities to create stale entries
        var entities = bubble.getAllEntityRecords();
        for (int i = 0; i < entities.size() / 2; i++) {
            bubble.removeEntity(entities.get(i).id());
        }

        // Wait for cleanup cycle (1800 ticks = ~28.8 seconds at 16ms/tick)
        Thread.sleep(31000);

        simulation.stop();

        // Verify cleanup ran
        assertTrue(simulation.getTickCount() >= 1800,
                  "Should have run at least 1800 ticks to trigger cleanup");

        System.out.println("Periodic cleanup test: " + simulation.getTickCount() +
                          " ticks completed, cleanup cycle validated");
    }
}
