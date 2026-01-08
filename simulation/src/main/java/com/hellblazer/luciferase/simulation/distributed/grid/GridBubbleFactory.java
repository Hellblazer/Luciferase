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

import java.util.UUID;

/**
 * Factory for creating bubbles in a grid topology with neighbor relationships.
 * <p>
 * Creates EnhancedBubble instances for each grid cell and establishes
 * VON neighbor relationships based on 2D adjacency (including diagonals).
 * <p>
 * The grid is 2D (XY plane), with cells being infinite columns in Z.
 * Neighbor count varies by position:
 * - Corner: 3 neighbors
 * - Edge: 5 neighbors
 * - Interior: 8 neighbors
 *
 * @author hal.hildebrand
 */
public class GridBubbleFactory {

    /**
     * Create a grid of bubbles with neighbor relationships.
     *
     * @param config        Grid configuration
     * @param spatialLevel  Tetree refinement level for spatial index
     * @param targetFrameMs Target frame time budget in milliseconds
     * @return BubbleGrid with all bubbles created and neighbors established
     */
    public static BubbleGrid<EnhancedBubble> createBubbles(
        GridConfiguration config,
        byte spatialLevel,
        long targetFrameMs
    ) {
        var grid = BubbleGrid.<EnhancedBubble>createEmpty(config);

        // Phase 1: Create all bubbles
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = new EnhancedBubble(UUID.randomUUID(), spatialLevel, targetFrameMs);
                grid.setBubble(coord, bubble);
            }
        }

        // Phase 2: Establish neighbor relationships
        for (int row = 0; row < config.rows(); row++) {
            for (int col = 0; col < config.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = grid.getBubble(coord);

                // Get neighbors from grid topology
                var neighbors = grid.getNeighbors(coord);

                // Add each neighbor's UUID to this bubble's VON neighbor set
                for (var neighbor : neighbors) {
                    bubble.addVonNeighbor(neighbor.id());
                }
            }
        }

        return grid;
    }
}
