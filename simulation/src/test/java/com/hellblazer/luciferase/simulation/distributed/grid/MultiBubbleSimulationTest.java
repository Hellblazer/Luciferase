/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test MultiBubbleSimulation - N-bubble coordinator.
 *
 * @author hal.hildebrand
 */
class MultiBubbleSimulationTest {

    @Test
    void testCreate2x2Simulation() {
        var config = GridConfiguration.DEFAULT_2X2;
        var sim = new MultiBubbleSimulation(config, 100, WorldBounds.DEFAULT);

        assertNotNull(sim);
        assertEquals(config, sim.getGridConfiguration());
        assertEquals(WorldBounds.DEFAULT, sim.getWorldBounds());
        assertFalse(sim.isRunning());
        assertEquals(0, sim.getTickCount());

        sim.close();
    }

    @Test
    void testCreate3x3Simulation() {
        var config = GridConfiguration.DEFAULT_3X3;
        var sim = new MultiBubbleSimulation(config, 200, WorldBounds.DEFAULT);

        assertNotNull(sim);
        assertEquals(config, sim.getGridConfiguration());

        sim.close();
    }

    @Test
    void testCreate1x1Simulation() {
        var config = GridConfiguration.square(1, 100f);
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT);

        assertNotNull(sim);
        assertEquals(1, sim.getGridConfiguration().bubbleCount());

        sim.close();
    }

    @Test
    void testCreate4x4Simulation() {
        var config = GridConfiguration.square(4, 100f);
        var sim = new MultiBubbleSimulation(config, 300, WorldBounds.DEFAULT);

        assertEquals(16, sim.getGridConfiguration().bubbleCount());

        sim.close();
    }

    @Test
    void testInitialEntityDistribution() {
        var config = GridConfiguration.DEFAULT_2X2;
        var sim = new MultiBubbleSimulation(config, 100, WorldBounds.DEFAULT);

        // Entities should be distributed across bubbles
        int totalEntities = sim.getAllEntities().size();
        assertEquals(100, totalEntities);

        sim.close();
    }

    @Test
    void testGetAllEntitiesEmpty() {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 0, WorldBounds.DEFAULT);

        var entities = sim.getAllEntities();
        assertTrue(entities.isEmpty());

        sim.close();
    }

    @Test
    void testGetBubbleAtCoordinate() {
        var config = GridConfiguration.DEFAULT_2X2;
        var sim = new MultiBubbleSimulation(config, 100, WorldBounds.DEFAULT);

        var bubble00 = sim.getBubble(new BubbleCoordinate(0, 0));
        assertNotNull(bubble00);

        var bubble11 = sim.getBubble(new BubbleCoordinate(1, 1));
        assertNotNull(bubble11);

        assertNotEquals(bubble00.id(), bubble11.id());

        sim.close();
    }

    @Test
    void testStartStop() {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT);

        assertFalse(sim.isRunning());

        sim.start();
        assertTrue(sim.isRunning());

        sim.stop();
        assertFalse(sim.isRunning());

        sim.close();
    }

    @Test
    void testTickProgression() throws InterruptedException {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT);

        sim.start();
        Thread.sleep(100); // Let a few ticks happen

        assertTrue(sim.getTickCount() > 0, "Tick count should increase");

        sim.stop();
        sim.close();
    }

    @Test
    void testDoubleStart() {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT);

        sim.start();
        assertTrue(sim.isRunning());

        // Second start should be no-op
        sim.start();
        assertTrue(sim.isRunning());

        sim.stop();
        sim.close();
    }

    @Test
    void testDoubleStop() {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT);

        sim.start();
        sim.stop();
        assertFalse(sim.isRunning());

        // Second stop should be no-op
        sim.stop();
        assertFalse(sim.isRunning());

        sim.close();
    }

    @Test
    void testCloseWhileRunning() {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT);

        sim.start();
        assertTrue(sim.isRunning());

        sim.close();
        assertFalse(sim.isRunning());
    }

    @Test
    void testGetMetrics() {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT);

        var metrics = sim.getMetrics();
        assertNotNull(metrics);

        sim.close();
    }

    @Test
    void testCustomBehavior() {
        var config = GridConfiguration.square(2, 100f);
        var behavior = new FlockingBehavior();
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT, behavior);

        assertNotNull(sim);

        sim.close();
    }

    @Test
    void testRunFor100Ticks() throws InterruptedException {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 50, WorldBounds.DEFAULT);

        sim.start();

        // Wait for ~100 ticks (16ms * 100 = 1600ms)
        Thread.sleep(1700);

        long tickCount = sim.getTickCount();
        assertTrue(tickCount >= 90, "Should have at least 90 ticks, got " + tickCount);

        sim.stop();
        sim.close();
    }

    @Test
    void testEntityCountStability() throws InterruptedException {
        var config = GridConfiguration.square(2, 100f);
        var sim = new MultiBubbleSimulation(config, 100, WorldBounds.DEFAULT);

        int initialCount = sim.getAllEntities().size();
        assertEquals(100, initialCount);

        sim.start();
        Thread.sleep(200);
        sim.stop();

        int finalCount = sim.getAllEntities().size();
        assertEquals(initialCount, finalCount, "Entity count should remain stable");

        sim.close();
    }
}
