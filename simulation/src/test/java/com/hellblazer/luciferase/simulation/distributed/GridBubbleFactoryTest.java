/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.distributed.grid.BubbleCoordinate;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test bubble factory for creating bubble instances with grid positions.
 * <p>
 * GridBubbleFactory creates EnhancedBubble instances for each cell in the grid
 * and assigns spatial bounds based on the grid configuration.
 *
 * @author hal.hildebrand
 */
class GridBubbleFactoryTest {

    @Test
    void testCreateSingleBubble() {
        var config = GridConfiguration.of(1, 1, 100f, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        assertNotNull(grid);
        assertEquals(config, grid.getConfiguration());

        var bubble = grid.getBubble(new BubbleCoordinate(0, 0));
        assertNotNull(bubble);
        assertNotNull(bubble.id());
        assertEquals(0, bubble.entityCount());
    }

    @Test
    void testCreate2x2Grid() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        // Verify all 4 bubbles created
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = grid.getBubble(coord);
                assertNotNull(bubble, "Bubble at " + coord + " should exist");
                assertNotNull(bubble.id());
            }
        }
    }

    @Test
    void testCreate3x3Grid() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        // Verify all 9 bubbles created
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = grid.getBubble(coord);
                assertNotNull(bubble, "Bubble at " + coord + " should exist");
            }
        }
    }

    @Test
    void testBubbleUniqueIds() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var ids = new java.util.HashSet<java.util.UUID>();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                var bubble = grid.getBubble(new BubbleCoordinate(row, col));
                assertTrue(ids.add(bubble.id()), "Bubble IDs must be unique");
            }
        }

        assertEquals(4, ids.size());
    }

    @Test
    void testCustomSpatialLevel() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 12, 16L);

        var bubble = grid.getBubble(new BubbleCoordinate(0, 0));
        assertNotNull(bubble);
        // Spatial level is internal to EnhancedBubble, we just verify creation succeeded
    }

    @Test
    void testCustomTargetFrameMs() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 32L);

        var bubble = grid.getBubble(new BubbleCoordinate(0, 0));
        assertNotNull(bubble);
        // Target frame time is internal to EnhancedBubble, we just verify creation succeeded
    }

    @Test
    void testNeighborRelationships() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        // Verify neighbor relationships are NOT set up by factory
        // (That's MultiBubbleSimulation's job)
        var center = grid.getBubble(new BubbleCoordinate(1, 1));
        assertEquals(0, center.getVonNeighbors().size(),
            "Factory should not establish neighbor relationships");
    }

    @Test
    void testSingleRowGrid() {
        var config = GridConfiguration.of(1, 4, 100f, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        for (int col = 0; col < 4; col++) {
            var bubble = grid.getBubble(new BubbleCoordinate(0, col));
            assertNotNull(bubble);
        }
    }

    @Test
    void testSingleColumnGrid() {
        var config = GridConfiguration.of(4, 1, 100f, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        for (int row = 0; row < 4; row++) {
            var bubble = grid.getBubble(new BubbleCoordinate(row, 0));
            assertNotNull(bubble);
        }
    }

    @Test
    void testLargeGrid() {
        var config = GridConfiguration.square(10, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        // Spot check corners and center
        assertNotNull(grid.getBubble(new BubbleCoordinate(0, 0)));
        assertNotNull(grid.getBubble(new BubbleCoordinate(0, 9)));
        assertNotNull(grid.getBubble(new BubbleCoordinate(9, 0)));
        assertNotNull(grid.getBubble(new BubbleCoordinate(9, 9)));
        assertNotNull(grid.getBubble(new BubbleCoordinate(5, 5)));
    }

    @Test
    void testNonSquareCells() {
        var config = GridConfiguration.of(2, 3, 150f, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                var bubble = grid.getBubble(new BubbleCoordinate(row, col));
                assertNotNull(bubble);
            }
        }
    }
}
