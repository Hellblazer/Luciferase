/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleCoordinate;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;

import java.util.UUID;

/**
 * Factory for creating bubble instances in a grid topology.
 * <p>
 * Creates EnhancedBubble instances for each cell in the grid configuration.
 * Each bubble is assigned a unique ID and configured with the specified
 * spatial level and target frame time.
 * <p>
 * This factory does NOT:
 * - Establish neighbor relationships (that's MultiBubbleSimulation's job)
 * - Add entities (use InitialDistribution for that)
 * - Start simulation loops
 *
 * @author hal.hildebrand
 */
public class GridBubbleFactory {

    /**
     * Create bubbles for all cells in the grid.
     * <p>
     * Each bubble is created with a unique UUID and the specified parameters.
     * Neighbor relationships are NOT established by this factory.
     *
     * @param config        Grid configuration
     * @param spatialLevel  Tetree refinement level for each bubble's spatial index
     * @param targetFrameMs Target frame time budget in milliseconds
     * @return BubbleGrid with all bubbles created
     */
    public static BubbleGrid<EnhancedBubble> createBubbles(
        GridConfiguration config,
        byte spatialLevel,
        long targetFrameMs
    ) {
        var grid = BubbleGrid.<EnhancedBubble>createEmpty(config);

        // Create a bubble for each grid cell
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = createBubble(spatialLevel, targetFrameMs);
                grid.setBubble(coord, bubble);
            }
        }

        return grid;
    }

    /**
     * Create a single bubble with unique ID.
     *
     * @param spatialLevel  Tetree refinement level
     * @param targetFrameMs Target frame time budget
     * @return New EnhancedBubble instance
     */
    private static EnhancedBubble createBubble(byte spatialLevel, long targetFrameMs) {
        return new EnhancedBubble(UUID.randomUUID(), spatialLevel, targetFrameMs);
    }
}
