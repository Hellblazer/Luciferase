/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleCoordinate;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test multi-bubble simulation with grid topology.
 * <p>
 * MultiBubbleSimulation coordinates N bubbles in a 2D grid, managing tick
 * execution, entity distribution, and neighbor relationships.
 *
 * @author hal.hildebrand
 */
class MultiBubbleSimulationTest {

    private MultiBubbleSimulation simulation;

    @AfterEach
    void cleanup() {
        if (simulation != null) {
            simulation.close();
        }
    }

    @Test
    void testCreateSingleBubbleSimulation() {
        var config = GridConfiguration.of(1, 1, 100f, 100f);
        simulation = new MultiBubbleSimulation(config, 10);

        assertNotNull(simulation);
        assertEquals(config, simulation.getGridConfiguration());
        assertEquals(10, simulation.getTotalEntityCount());
        assertFalse(simulation.isRunning());
    }

    @Test
    void testCreate2x2Simulation() {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 100);

        assertNotNull(simulation);
        assertEquals(config, simulation.getGridConfiguration());
        assertEquals(100, simulation.getTotalEntityCount());
        assertEquals(4, simulation.getBubbleCount());
    }

    @Test
    void testCreate3x3Simulation() {
        var config = GridConfiguration.DEFAULT_3X3;
        simulation = new MultiBubbleSimulation(config, 200);

        assertNotNull(simulation);
        assertEquals(9, simulation.getBubbleCount());
        assertEquals(200, simulation.getTotalEntityCount());
    }

    @Test
    void testEntityDistribution() {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 100);

        // Entities should be distributed across bubbles
        int totalEntities = 0;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                var bubble = simulation.getBubble(new BubbleCoordinate(row, col));
                totalEntities += bubble.entityCount();
            }
        }

        assertEquals(100, totalEntities);
    }

    @Test
    void testNeighborSetup() {
        var config = GridConfiguration.DEFAULT_3X3;
        simulation = new MultiBubbleSimulation(config, 50);

        // Check corner bubble (0,0) - should have 3 neighbors
        var corner = simulation.getBubble(new BubbleCoordinate(0, 0));
        assertEquals(3, corner.getVonNeighbors().size());

        // Check edge bubble (0,1) - should have 5 neighbors
        var edge = simulation.getBubble(new BubbleCoordinate(0, 1));
        assertEquals(5, edge.getVonNeighbors().size());

        // Check interior bubble (1,1) - should have 8 neighbors
        var interior = simulation.getBubble(new BubbleCoordinate(1, 1));
        assertEquals(8, interior.getVonNeighbors().size());
    }

    @Test
    void testStartStop() {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 50);

        assertFalse(simulation.isRunning());

        simulation.start();
        assertTrue(simulation.isRunning());

        simulation.stop();
        assertFalse(simulation.isRunning());
    }

    @Test
    void testTickExecution() throws InterruptedException {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 50);

        simulation.start();

        // Wait for a few ticks
        Thread.sleep(100);

        long tickCount = simulation.getTickCount();
        assertTrue(tickCount > 0, "Ticks should have executed");

        simulation.stop();
    }

    @Test
    void testGetAllEntities() {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 100);

        var entities = simulation.getAllEntities();
        assertNotNull(entities);
        assertEquals(100, entities.size());
    }

    @Test
    void testGetBubble() {
        var config = GridConfiguration.DEFAULT_3X3;
        simulation = new MultiBubbleSimulation(config, 50);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = simulation.getBubble(coord);
                assertNotNull(bubble, "Bubble at " + coord + " should exist");
            }
        }
    }

    @Test
    void testGetBubbleInvalidCoordinate() {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 50);

        assertThrows(IllegalArgumentException.class, () -> {
            simulation.getBubble(new BubbleCoordinate(5, 5));
        });
    }

    @Test
    void testCustomBehavior() {
        var config = GridConfiguration.DEFAULT_2X2;
        var behavior = new FlockingBehavior();
        simulation = new MultiBubbleSimulation(config, 50, behavior);

        assertNotNull(simulation);
        assertEquals(50, simulation.getTotalEntityCount());
    }

    @Test
    void testZeroEntities() {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 0);

        assertEquals(0, simulation.getTotalEntityCount());
        var entities = simulation.getAllEntities();
        assertTrue(entities.isEmpty());
    }

    @Test
    void testMetrics() throws InterruptedException {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 50);

        simulation.start();
        Thread.sleep(100);
        simulation.stop();

        var metrics = simulation.getMetrics();
        assertNotNull(metrics);
        assertTrue(metrics.getTotalTicks() > 0);
    }

    @Test
    void testSingleRowGrid() {
        var config = GridConfiguration.of(1, 4, 100f, 100f);
        simulation = new MultiBubbleSimulation(config, 100);

        assertEquals(4, simulation.getBubbleCount());

        // Check neighbor counts
        assertEquals(1, simulation.getBubble(new BubbleCoordinate(0, 0)).getVonNeighbors().size());
        assertEquals(2, simulation.getBubble(new BubbleCoordinate(0, 1)).getVonNeighbors().size());
        assertEquals(2, simulation.getBubble(new BubbleCoordinate(0, 2)).getVonNeighbors().size());
        assertEquals(1, simulation.getBubble(new BubbleCoordinate(0, 3)).getVonNeighbors().size());
    }

    @Test
    void testSingleColumnGrid() {
        var config = GridConfiguration.of(4, 1, 100f, 100f);
        simulation = new MultiBubbleSimulation(config, 100);

        assertEquals(4, simulation.getBubbleCount());

        // Check neighbor counts
        assertEquals(1, simulation.getBubble(new BubbleCoordinate(0, 0)).getVonNeighbors().size());
        assertEquals(2, simulation.getBubble(new BubbleCoordinate(1, 0)).getVonNeighbors().size());
        assertEquals(2, simulation.getBubble(new BubbleCoordinate(2, 0)).getVonNeighbors().size());
        assertEquals(1, simulation.getBubble(new BubbleCoordinate(3, 0)).getVonNeighbors().size());
    }

    @Test
    void testCloseIdempotent() {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 50);

        simulation.close();
        simulation.close(); // Should not throw
    }

    @Test
    void testGetDebugState() {
        var config = GridConfiguration.DEFAULT_2X2;
        simulation = new MultiBubbleSimulation(config, 50);

        var debug = simulation.getDebugState();
        assertNotNull(debug);
        assertEquals(0, debug.tickCount());
        assertEquals(50, debug.totalEntityCount());
        assertEquals(4, debug.bubbleCount());
    }
}
