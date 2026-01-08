/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test GridBubbleFactory - bubble creation with spatial bounds.
 *
 * @author hal.hildebrand
 */
class GridBubbleFactoryTest {

    @Test
    void testCreate2x2Grid() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        assertNotNull(grid);
        assertEquals(4, countBubbles(grid, config));

        // Verify each bubble is initialized
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = grid.getBubble(coord);
                assertNotNull(bubble, "Bubble at " + coord + " should exist");
                assertEquals(0, bubble.entityCount(), "Bubble should be empty initially");
            }
        }
    }

    @Test
    void testCreate3x3Grid() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        assertEquals(9, countBubbles(grid, config));

        // Verify bubbles are created with correct configuration
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = grid.getBubble(coord);
                assertNotNull(bubble);
                assertNotNull(bubble.id());
            }
        }
    }

    @Test
    void testCreate1x1Grid() {
        var config = GridConfiguration.square(1, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        assertEquals(1, countBubbles(grid, config));

        var bubble = grid.getBubble(new BubbleCoordinate(0, 0));
        assertNotNull(bubble);
        assertTrue(bubble.getVonNeighbors().isEmpty(), "Single bubble should have no neighbors");
    }

    @Test
    void testCreate4x4Grid() {
        var config = GridConfiguration.square(4, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        assertEquals(16, countBubbles(grid, config));
    }

    @Test
    void testNeighborSetup2x2() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        // Corner (0,0): expects 3 neighbors
        var bubble00 = grid.getBubble(new BubbleCoordinate(0, 0));
        assertEquals(3, bubble00.getVonNeighbors().size(), "Corner bubble should have 3 neighbors");

        // Corner (1,1): expects 3 neighbors
        var bubble11 = grid.getBubble(new BubbleCoordinate(1, 1));
        assertEquals(3, bubble11.getVonNeighbors().size(), "Corner bubble should have 3 neighbors");

        // All bubbles in 2x2 are corners, each has 3 neighbors
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                var bubble = grid.getBubble(new BubbleCoordinate(row, col));
                assertEquals(3, bubble.getVonNeighbors().size());
            }
        }
    }

    @Test
    void testNeighborSetup3x3() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        // Corner (0,0): 3 neighbors
        var corner = grid.getBubble(new BubbleCoordinate(0, 0));
        assertEquals(3, corner.getVonNeighbors().size(), "Corner should have 3 neighbors");

        // Edge (0,1): 5 neighbors
        var edge = grid.getBubble(new BubbleCoordinate(0, 1));
        assertEquals(5, edge.getVonNeighbors().size(), "Edge should have 5 neighbors");

        // Interior (1,1): 8 neighbors
        var interior = grid.getBubble(new BubbleCoordinate(1, 1));
        assertEquals(8, interior.getVonNeighbors().size(), "Interior should have 8 neighbors");
    }

    @Test
    void testNeighborSymmetry() {
        // If A is neighbor of B, then B is neighbor of A
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var bubble00 = grid.getBubble(new BubbleCoordinate(0, 0));
        var bubble01 = grid.getBubble(new BubbleCoordinate(0, 1));

        assertTrue(bubble00.getVonNeighbors().contains(bubble01.id()),
                   "Bubble (0,0) should have (0,1) as neighbor");
        assertTrue(bubble01.getVonNeighbors().contains(bubble00.id()),
                   "Bubble (0,1) should have (0,0) as neighbor");
    }

    @Test
    void testUniqueBubbleIds() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var ids = new java.util.HashSet<java.util.UUID>();
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                var bubble = grid.getBubble(new BubbleCoordinate(row, col));
                assertTrue(ids.add(bubble.id()), "Bubble IDs should be unique");
            }
        }

        assertEquals(9, ids.size());
    }

    @Test
    void testCustomSpatialLevel() {
        var config = GridConfiguration.DEFAULT_2X2;
        byte spatialLevel = 12;
        var grid = GridBubbleFactory.createBubbles(config, spatialLevel, 16L);

        // Verify bubbles are created (spatial level is internal to EnhancedBubble)
        assertNotNull(grid.getBubble(new BubbleCoordinate(0, 0)));
    }

    @Test
    void testCustomTargetFrameMs() {
        var config = GridConfiguration.DEFAULT_2X2;
        long targetFrameMs = 33L; // 30fps
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, targetFrameMs);

        // Verify bubbles are created
        assertNotNull(grid.getBubble(new BubbleCoordinate(0, 0)));
    }

    /**
     * Count non-null bubbles in the grid.
     */
    private int countBubbles(BubbleGrid<EnhancedBubble> grid, GridConfiguration config) {
        int count = 0;
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                if (grid.getBubble(new BubbleCoordinate(row, col)) != null) {
                    count++;
                }
            }
        }
        return count;
    }
}
