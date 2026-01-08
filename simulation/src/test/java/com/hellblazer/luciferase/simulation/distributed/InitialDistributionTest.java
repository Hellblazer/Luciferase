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
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test entity distribution to bubbles based on spatial position.
 * <p>
 * InitialDistribution assigns entities to grid cells based on XY position only.
 * Z coordinate is ignored for distribution (bubbles are infinite Z columns).
 * Distribution is deterministic and does not consider load balancing.
 *
 * @author hal.hildebrand
 */
class InitialDistributionTest {

    @Test
    void testDistributeToSingleCell() {
        var config = GridConfiguration.of(1, 1, 100f, 100f);
        var entities = List.of(
            new Point3f(50f, 50f, 0f),
            new Point3f(25f, 75f, 10f),
            new Point3f(75f, 25f, 20f)
        );

        var distribution = InitialDistribution.distribute(entities, config);

        assertEquals(1, distribution.size());
        assertTrue(distribution.containsKey(new BubbleCoordinate(0, 0)));
        assertEquals(3, distribution.get(new BubbleCoordinate(0, 0)).size());
    }

    @Test
    void testDistributeTo2x2Grid() {
        var config = GridConfiguration.DEFAULT_2X2;
        var entities = List.of(
            new Point3f(25f, 25f, 0f),   // Cell (0,0)
            new Point3f(125f, 25f, 0f),  // Cell (0,1)
            new Point3f(25f, 125f, 0f),  // Cell (1,0)
            new Point3f(125f, 125f, 0f)  // Cell (1,1)
        );

        var distribution = InitialDistribution.distribute(entities, config);

        assertEquals(4, distribution.size());
        assertEquals(1, distribution.get(new BubbleCoordinate(0, 0)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(0, 1)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(1, 0)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(1, 1)).size());
    }

    @Test
    void testDistributeTo3x3Grid() {
        var config = GridConfiguration.DEFAULT_3X3;
        var entities = new ArrayList<Point3f>();

        // Place 2 entities in each cell
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                var center = config.cellCenter(new BubbleCoordinate(row, col));
                entities.add(new Point3f(center.x + 10f, center.y + 10f, 0f));
                entities.add(new Point3f(center.x - 10f, center.y - 10f, 5f));
            }
        }

        var distribution = InitialDistribution.distribute(entities, config);

        assertEquals(9, distribution.size());
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                var coord = new BubbleCoordinate(row, col);
                assertEquals(2, distribution.get(coord).size(),
                    "Cell " + coord + " should have 2 entities");
            }
        }
    }

    @Test
    void testZCoordinateIgnored() {
        var config = GridConfiguration.DEFAULT_2X2;
        var entities = List.of(
            new Point3f(50f, 50f, 0f),
            new Point3f(50f, 50f, 100f),
            new Point3f(50f, 50f, 1000f)
        );

        var distribution = InitialDistribution.distribute(entities, config);

        // All entities should be in same cell despite different Z
        assertEquals(1, distribution.size());
        assertEquals(3, distribution.get(new BubbleCoordinate(0, 0)).size());
    }

    @Test
    void testEntityAtBoundary() {
        var config = GridConfiguration.DEFAULT_2X2;
        var entities = List.of(
            new Point3f(100f, 100f, 0f)  // Exactly at cell boundary
        );

        var distribution = InitialDistribution.distribute(entities, config);

        // Should be in cell (1,1) based on implementation
        assertEquals(1, distribution.size());
        assertTrue(distribution.containsKey(new BubbleCoordinate(1, 1)));
    }

    @Test
    void testEntityOutOfBounds() {
        var config = GridConfiguration.DEFAULT_2X2;
        var entities = List.of(
            new Point3f(-10f, 50f, 0f),   // Out of bounds (negative X)
            new Point3f(50f, -10f, 0f),   // Out of bounds (negative Y)
            new Point3f(250f, 50f, 0f),   // Out of bounds (X too large)
            new Point3f(50f, 250f, 0f)    // Out of bounds (Y too large)
        );

        var distribution = InitialDistribution.distribute(entities, config);

        // All out-of-bounds entities should be filtered out
        assertTrue(distribution.isEmpty());
    }

    @Test
    void testEmptyEntityList() {
        var config = GridConfiguration.DEFAULT_2X2;
        var entities = List.<Point3f>of();

        var distribution = InitialDistribution.distribute(entities, config);

        assertTrue(distribution.isEmpty());
    }

    @Test
    void testSingleRowGrid() {
        var config = GridConfiguration.of(1, 4, 100f, 100f);
        var entities = List.of(
            new Point3f(50f, 50f, 0f),   // Cell (0,0)
            new Point3f(150f, 50f, 0f),  // Cell (0,1)
            new Point3f(250f, 50f, 0f),  // Cell (0,2)
            new Point3f(350f, 50f, 0f)   // Cell (0,3)
        );

        var distribution = InitialDistribution.distribute(entities, config);

        assertEquals(4, distribution.size());
        assertEquals(1, distribution.get(new BubbleCoordinate(0, 0)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(0, 1)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(0, 2)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(0, 3)).size());
    }

    @Test
    void testSingleColumnGrid() {
        var config = GridConfiguration.of(4, 1, 100f, 100f);
        var entities = List.of(
            new Point3f(50f, 50f, 0f),   // Cell (0,0)
            new Point3f(50f, 150f, 0f),  // Cell (1,0)
            new Point3f(50f, 250f, 0f),  // Cell (2,0)
            new Point3f(50f, 350f, 0f)   // Cell (3,0)
        );

        var distribution = InitialDistribution.distribute(entities, config);

        assertEquals(4, distribution.size());
        assertEquals(1, distribution.get(new BubbleCoordinate(0, 0)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(1, 0)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(2, 0)).size());
        assertEquals(1, distribution.get(new BubbleCoordinate(3, 0)).size());
    }

    @Test
    void testNonSquareCells() {
        var config = GridConfiguration.of(2, 3, 150f, 100f);
        var entities = List.of(
            new Point3f(75f, 50f, 0f),    // Cell (0,0)
            new Point3f(225f, 50f, 0f),   // Cell (0,1)
            new Point3f(375f, 50f, 0f),   // Cell (0,2)
            new Point3f(75f, 150f, 0f),   // Cell (1,0)
            new Point3f(225f, 150f, 0f),  // Cell (1,1)
            new Point3f(375f, 150f, 0f)   // Cell (1,2)
        );

        var distribution = InitialDistribution.distribute(entities, config);

        assertEquals(6, distribution.size());
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                var coord = new BubbleCoordinate(row, col);
                assertEquals(1, distribution.get(coord).size());
            }
        }
    }
}
