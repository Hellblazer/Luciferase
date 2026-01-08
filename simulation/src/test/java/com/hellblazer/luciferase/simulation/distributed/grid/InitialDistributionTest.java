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

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test InitialDistribution - spatial entity distribution algorithm.
 *
 * @author hal.hildebrand
 */
class InitialDistributionTest {

    @Test
    void testDistributeToSingleBubble() {
        var config = GridConfiguration.square(1, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var entities = new ArrayList<InitialDistribution.EntitySpec>();
        entities.add(new InitialDistribution.EntitySpec("entity-0", new Point3f(50f, 50f, 50f), null));
        entities.add(new InitialDistribution.EntitySpec("entity-1", new Point3f(25f, 75f, 10f), null));
        entities.add(new InitialDistribution.EntitySpec("entity-2", new Point3f(75f, 25f, 90f), null));

        InitialDistribution.distribute(entities, grid, config);

        var bubble = grid.getBubble(new BubbleCoordinate(0, 0));
        assertEquals(3, bubble.entityCount(), "All entities should go to single bubble");
    }

    @Test
    void testDistribute2x2Quadrants() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var entities = new ArrayList<InitialDistribution.EntitySpec>();
        // Bottom-left quadrant (0,0)
        entities.add(new InitialDistribution.EntitySpec("bl-0", new Point3f(25f, 25f, 0f), null));
        entities.add(new InitialDistribution.EntitySpec("bl-1", new Point3f(50f, 50f, 0f), null));

        // Bottom-right quadrant (0,1)
        entities.add(new InitialDistribution.EntitySpec("br-0", new Point3f(125f, 25f, 0f), null));
        entities.add(new InitialDistribution.EntitySpec("br-1", new Point3f(150f, 50f, 0f), null));

        // Top-left quadrant (1,0)
        entities.add(new InitialDistribution.EntitySpec("tl-0", new Point3f(25f, 125f, 0f), null));
        entities.add(new InitialDistribution.EntitySpec("tl-1", new Point3f(50f, 150f, 0f), null));

        // Top-right quadrant (1,1)
        entities.add(new InitialDistribution.EntitySpec("tr-0", new Point3f(125f, 125f, 0f), null));
        entities.add(new InitialDistribution.EntitySpec("tr-1", new Point3f(150f, 150f, 0f), null));

        InitialDistribution.distribute(entities, grid, config);

        assertEquals(2, grid.getBubble(new BubbleCoordinate(0, 0)).entityCount());
        assertEquals(2, grid.getBubble(new BubbleCoordinate(0, 1)).entityCount());
        assertEquals(2, grid.getBubble(new BubbleCoordinate(1, 0)).entityCount());
        assertEquals(2, grid.getBubble(new BubbleCoordinate(1, 1)).entityCount());
    }

    @Test
    void testDistribute3x3UniformRandom() {
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        // Create 90 entities randomly distributed (should average ~10 per bubble)
        var entities = createRandomEntities(90, config, 42L);

        InitialDistribution.distribute(entities, grid, config);

        // Verify all entities are distributed
        int totalEntities = 0;
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                int count = grid.getBubble(new BubbleCoordinate(row, col)).entityCount();
                totalEntities += count;
                assertTrue(count > 0, "Each bubble should have some entities with uniform random");
            }
        }

        assertEquals(90, totalEntities);
    }

    @Test
    void testDistributeWithZVariation() {
        // Z coordinate should not affect grid assignment (grid is 2D in XY plane)
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var entities = new ArrayList<InitialDistribution.EntitySpec>();
        // Same XY position, different Z values
        entities.add(new InitialDistribution.EntitySpec("z0", new Point3f(50f, 50f, 0f), null));
        entities.add(new InitialDistribution.EntitySpec("z50", new Point3f(50f, 50f, 50f), null));
        entities.add(new InitialDistribution.EntitySpec("z100", new Point3f(50f, 50f, 100f), null));

        InitialDistribution.distribute(entities, grid, config);

        // All should go to same bubble (0,0) since XY is same
        assertEquals(3, grid.getBubble(new BubbleCoordinate(0, 0)).entityCount());
        assertEquals(0, grid.getBubble(new BubbleCoordinate(0, 1)).entityCount());
        assertEquals(0, grid.getBubble(new BubbleCoordinate(1, 0)).entityCount());
        assertEquals(0, grid.getBubble(new BubbleCoordinate(1, 1)).entityCount());
    }

    @Test
    void testDistributeEmptyEntityList() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        InitialDistribution.distribute(List.of(), grid, config);

        // All bubbles should be empty
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                assertEquals(0, grid.getBubble(new BubbleCoordinate(row, col)).entityCount());
            }
        }
    }

    @Test
    void testDistributeOutOfBoundsEntity() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var entities = new ArrayList<InitialDistribution.EntitySpec>();
        // Inside bounds
        entities.add(new InitialDistribution.EntitySpec("inside", new Point3f(50f, 50f, 0f), null));
        // Outside bounds - should be skipped
        entities.add(new InitialDistribution.EntitySpec("outside-x", new Point3f(300f, 50f, 0f), null));
        entities.add(new InitialDistribution.EntitySpec("outside-y", new Point3f(50f, 300f, 0f), null));
        entities.add(new InitialDistribution.EntitySpec("outside-negative", new Point3f(-10f, 50f, 0f), null));

        InitialDistribution.distribute(entities, grid, config);

        // Only 1 entity should be distributed
        int totalEntities = 0;
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                totalEntities += grid.getBubble(new BubbleCoordinate(row, col)).entityCount();
            }
        }

        assertEquals(1, totalEntities, "Only in-bounds entities should be distributed");
    }

    @Test
    void testDistributeBoundaryEntities() {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var entities = new ArrayList<InitialDistribution.EntitySpec>();
        // Entity exactly on boundary at x=100 (boundary between columns)
        entities.add(new InitialDistribution.EntitySpec("boundary-x", new Point3f(100f, 50f, 0f), null));
        // Entity exactly on boundary at y=100 (boundary between rows)
        entities.add(new InitialDistribution.EntitySpec("boundary-y", new Point3f(50f, 100f, 0f), null));

        InitialDistribution.distribute(entities, grid, config);

        // Both should be distributed (specific bubble depends on implementation)
        int totalEntities = 0;
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                totalEntities += grid.getBubble(new BubbleCoordinate(row, col)).entityCount();
            }
        }

        assertEquals(2, totalEntities, "Boundary entities should be distributed");
    }

    @Test
    void testDistributeWithContent() {
        var config = GridConfiguration.square(1, 100f);
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var content1 = "some content";
        var content2 = 42;

        var entities = new ArrayList<InitialDistribution.EntitySpec>();
        entities.add(new InitialDistribution.EntitySpec("entity-1", new Point3f(50f, 50f, 0f), content1));
        entities.add(new InitialDistribution.EntitySpec("entity-2", new Point3f(25f, 75f, 0f), content2));

        InitialDistribution.distribute(entities, grid, config);

        var bubble = grid.getBubble(new BubbleCoordinate(0, 0));
        assertEquals(2, bubble.entityCount());

        // Verify entities can be queried (content is stored)
        var entityRecords = bubble.getAllEntityRecords();
        assertEquals(2, entityRecords.size());
    }

    @Test
    void testDistributeSkewedDistribution() {
        // All entities in one bubble, others empty
        var config = GridConfiguration.DEFAULT_3X3;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);

        var entities = new ArrayList<InitialDistribution.EntitySpec>();
        // All in center bubble (1,1)
        for (int i = 0; i < 100; i++) {
            float x = 100f + i * 0.5f; // Range: 100-150
            float y = 100f + i * 0.3f; // Range: 100-130
            entities.add(new InitialDistribution.EntitySpec("center-" + i, new Point3f(x, y, 0f), null));
        }

        InitialDistribution.distribute(entities, grid, config);

        // Center bubble should have all entities
        assertEquals(100, grid.getBubble(new BubbleCoordinate(1, 1)).entityCount());

        // Others should be empty or near-empty
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                if (row == 1 && col == 1) continue; // Skip center
                int count = grid.getBubble(new BubbleCoordinate(row, col)).entityCount();
                assertTrue(count < 10, "Non-center bubbles should have few entities");
            }
        }
    }

    /**
     * Create random entities uniformly distributed within grid bounds.
     */
    private List<InitialDistribution.EntitySpec> createRandomEntities(
        int count, GridConfiguration config, long seed
    ) {
        var random = new java.util.Random(seed);
        var entities = new ArrayList<InitialDistribution.EntitySpec>();

        float margin = 10f;
        for (int i = 0; i < count; i++) {
            float x = margin + random.nextFloat() * (config.totalWidth() - 2 * margin);
            float y = margin + random.nextFloat() * (config.totalHeight() - 2 * margin);
            float z = random.nextFloat() * 100f;

            entities.add(new InitialDistribution.EntitySpec(
                "random-" + i,
                new Point3f(x, y, z),
                null
            ));
        }

        return entities;
    }
}
