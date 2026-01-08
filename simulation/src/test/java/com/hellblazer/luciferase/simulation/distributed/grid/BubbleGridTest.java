/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.bubble.Bubble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BubbleGrid 2D grid topology with O(1) neighbor lookup.
 * Validates grid creation, neighbor detection, and boundary conditions.
 *
 * @author hal.hildebrand
 */
class BubbleGridTest {

    @Test
    void testCreateEmptyGrid2x2() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = BubbleGrid.createEmpty(config);

        assertNotNull(grid);
        assertEquals(config, grid.getConfiguration());

        // All cells should be null initially
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                assertNull(grid.getBubble(new BubbleCoordinate(row, col)));
            }
        }
    }

    @Test
    void testCreateEmptyGrid3x3() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = BubbleGrid.createEmpty(config);

        assertEquals(config, grid.getConfiguration());

        // All cells should be null
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                assertNull(grid.getBubble(new BubbleCoordinate(row, col)));
            }
        }
    }

    @Test
    void testSetAndGetBubble() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = BubbleGrid.createEmpty(config);

        var bubble = new Bubble(UUID.randomUUID());
        var coord = new BubbleCoordinate(0, 0);

        grid.setBubble(coord, bubble);

        assertEquals(bubble, grid.getBubble(coord));
        assertSame(bubble, grid.getBubble(coord));
    }

    @Test
    void testSetBubbleOutOfBounds() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = BubbleGrid.createEmpty(config);
        var bubble = new Bubble(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class,
            () -> grid.setBubble(new BubbleCoordinate(2, 0), bubble));
        assertThrows(IllegalArgumentException.class,
            () -> grid.setBubble(new BubbleCoordinate(0, 2), bubble));
        assertThrows(IllegalArgumentException.class,
            () -> grid.setBubble(new BubbleCoordinate(-1, 0), bubble));
    }

    @Test
    void testGetBubbleOutOfBounds() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = BubbleGrid.createEmpty(config);

        assertThrows(IllegalArgumentException.class,
            () -> grid.getBubble(new BubbleCoordinate(2, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> grid.getBubble(new BubbleCoordinate(0, 2)));
    }

    @Test
    void testCornerNeighborCount3Neighbors() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = createFullyPopulatedGrid(config);

        // Test all 4 corners
        assertEquals(3, grid.getNeighbors(new BubbleCoordinate(0, 0)).size(), "Top-left corner");
        assertEquals(3, grid.getNeighbors(new BubbleCoordinate(0, 2)).size(), "Top-right corner");
        assertEquals(3, grid.getNeighbors(new BubbleCoordinate(2, 0)).size(), "Bottom-left corner");
        assertEquals(3, grid.getNeighbors(new BubbleCoordinate(2, 2)).size(), "Bottom-right corner");
    }

    @Test
    void testEdgeNeighborCount5Neighbors() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = createFullyPopulatedGrid(config);

        // Test all 4 edge midpoints
        assertEquals(5, grid.getNeighbors(new BubbleCoordinate(0, 1)).size(), "Top edge");
        assertEquals(5, grid.getNeighbors(new BubbleCoordinate(2, 1)).size(), "Bottom edge");
        assertEquals(5, grid.getNeighbors(new BubbleCoordinate(1, 0)).size(), "Left edge");
        assertEquals(5, grid.getNeighbors(new BubbleCoordinate(1, 2)).size(), "Right edge");
    }

    @Test
    void testInteriorNeighborCount8Neighbors() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = createFullyPopulatedGrid(config);

        // Center cell has 8 neighbors
        assertEquals(8, grid.getNeighbors(new BubbleCoordinate(1, 1)).size(), "Center cell");
    }

    @Test
    void testTopLeftCornerNeighborsCorrect() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = createFullyPopulatedGrid(config);

        var neighbors = grid.getNeighbors(new BubbleCoordinate(0, 0));
        assertEquals(3, neighbors.size());

        // Top-left (0,0) should have neighbors: (0,1), (1,0), (1,1)
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(0, 1)), "Right");
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(1, 0)), "Below");
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(1, 1)), "Diagonal");
    }

    @Test
    void testCenterCellNeighborsCorrect() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = createFullyPopulatedGrid(config);

        var neighbors = grid.getNeighbors(new BubbleCoordinate(1, 1));
        assertEquals(8, neighbors.size());

        // Center (1,1) should have all 8 surrounding cells
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(0, 0)));
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(0, 1)));
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(0, 2)));
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(1, 0)));
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(1, 2)));
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(2, 0)));
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(2, 1)));
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(2, 2)));
    }

    @Test
    void testGetNeighborsSkipsNullCells() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = BubbleGrid.createEmpty(config);

        // Only populate center and one neighbor
        grid.setBubble(new BubbleCoordinate(1, 1), new Bubble(UUID.randomUUID()));
        grid.setBubble(new BubbleCoordinate(1, 2), new Bubble(UUID.randomUUID()));

        var neighbors = grid.getNeighbors(new BubbleCoordinate(1, 1));

        // Should only return the one non-null neighbor
        assertEquals(1, neighbors.size());
        assertTrue(containsBubbleAt(neighbors, grid, new BubbleCoordinate(1, 2)));
    }

    @Test
    void testGetNeighborsReturnsEmptyForIsolatedCell() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = BubbleGrid.createEmpty(config);

        // Only populate center, no neighbors
        grid.setBubble(new BubbleCoordinate(1, 1), new Bubble(UUID.randomUUID()));

        var neighbors = grid.getNeighbors(new BubbleCoordinate(1, 1));

        assertTrue(neighbors.isEmpty());
    }

    @Test
    void testGetNeighborsOutOfBounds() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = createFullyPopulatedGrid(config);

        assertThrows(IllegalArgumentException.class,
            () -> grid.getNeighbors(new BubbleCoordinate(2, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> grid.getNeighbors(new BubbleCoordinate(0, 2)));
    }

    @ParameterizedTest
    @CsvSource({
        "2, 2",  // 2x2 grid
        "3, 3",  // 3x3 grid
        "4, 4",  // 4x4 grid
        "1, 5",  // 1x5 grid
        "5, 1"   // 5x1 grid
    })
    void testVariousGridSizes(int rows, int cols) {
        var config = GridConfiguration.of(rows, cols, 100f, 100f);
        var grid = createFullyPopulatedGrid(config);

        // Verify every cell has correct neighbor count
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                var coord = new BubbleCoordinate(row, col);
                int expectedCount = coord.expectedNeighborCount(rows, cols);
                int actualCount = grid.getNeighbors(coord).size();

                assertEquals(expectedCount, actualCount,
                    "Cell (" + row + "," + col + ") in " + rows + "x" + cols + " grid");
            }
        }
    }

    @Test
    void test1x1GridHasNoNeighbors() {
        var config = GridConfiguration.square(1, 100f);
        var grid = createFullyPopulatedGrid(config);

        var neighbors = grid.getNeighbors(new BubbleCoordinate(0, 0));
        assertTrue(neighbors.isEmpty(), "Single cell grid has no neighbors");
    }

    @Test
    void testNeighborsAreUnmodifiable() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = createFullyPopulatedGrid(config);

        var neighbors = grid.getNeighbors(new BubbleCoordinate(1, 1));

        // Attempting to modify should throw
        assertThrows(UnsupportedOperationException.class,
            () -> neighbors.add(new Bubble(UUID.randomUUID())));
        assertThrows(UnsupportedOperationException.class,
            neighbors::clear);
    }

    @Test
    void testThreadSafeReads() throws InterruptedException {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = createFullyPopulatedGrid(config);

        // Multiple threads reading concurrently
        var threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    var coord = new BubbleCoordinate(j % 3, (j / 3) % 3);
                    assertNotNull(grid.getBubble(coord));
                    var neighbors = grid.getNeighbors(coord);
                    assertNotNull(neighbors);
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }
    }

    @Test
    void testReplaceBubble() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = BubbleGrid.createEmpty(config);

        var coord = new BubbleCoordinate(0, 0);
        var bubble1 = new Bubble(UUID.randomUUID());
        var bubble2 = new Bubble(UUID.randomUUID());

        grid.setBubble(coord, bubble1);
        assertEquals(bubble1, grid.getBubble(coord));

        grid.setBubble(coord, bubble2);
        assertEquals(bubble2, grid.getBubble(coord));
        assertNotEquals(bubble1, grid.getBubble(coord));
    }

    @Test
    void testSetNullBubble() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = BubbleGrid.createEmpty(config);

        var coord = new BubbleCoordinate(0, 0);
        var bubble = new Bubble(UUID.randomUUID());

        grid.setBubble(coord, bubble);
        assertNotNull(grid.getBubble(coord));

        grid.setBubble(coord, null);
        assertNull(grid.getBubble(coord));
    }

    // Helper methods

    /**
     * Create a grid with all cells populated by unique bubbles.
     */
    private BubbleGrid createFullyPopulatedGrid(GridConfiguration config) {
        var grid = BubbleGrid.createEmpty(config);
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                grid.setBubble(new BubbleCoordinate(row, col), new Bubble(UUID.randomUUID()));
            }
        }
        return grid;
    }

    /**
     * Check if a set of bubbles contains the bubble at a specific coordinate.
     */
    private boolean containsBubbleAt(Set<Bubble> neighbors, BubbleGrid grid, BubbleCoordinate coord) {
        var bubble = grid.getBubble(coord);
        return neighbors.contains(bubble);
    }
}
