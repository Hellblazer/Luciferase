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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 2D grid topology for bubble placement with O(1) neighbor lookup.
 * <p>
 * The grid partitions the XY plane into cells. Each cell is an infinite
 * column in the Z direction. Neighbors are determined by adjacency in
 * the 2D grid (including diagonals).
 * <p>
 * Neighbor counts:
 * - Corner cells: 3 neighbors
 * - Edge cells: 5 neighbors
 * - Interior cells: 8 neighbors
 * <p>
 * Thread-safe for concurrent reads. Writes should be externally synchronized.
 *
 * @author hal.hildebrand
 */
public class BubbleGrid {

    private final GridConfiguration config;
    private final Bubble[][] grid;

    /**
     * Create a new bubble grid with the given configuration.
     *
     * @param config Grid configuration
     */
    private BubbleGrid(GridConfiguration config) {
        this.config = config;
        this.grid = new Bubble[config.rows()][config.columns()];
    }

    /**
     * Create an empty grid with all cells initialized to null.
     *
     * @param config Grid configuration
     * @return New empty grid
     */
    public static BubbleGrid createEmpty(GridConfiguration config) {
        return new BubbleGrid(config);
    }

    /**
     * Get the grid configuration.
     *
     * @return Grid configuration
     */
    public GridConfiguration getConfiguration() {
        return config;
    }

    /**
     * Get the bubble at a specific coordinate.
     * Returns null if the cell is empty.
     *
     * @param coord Grid coordinate
     * @return Bubble at the coordinate, or null if empty
     * @throws IllegalArgumentException if coordinate is out of bounds
     */
    public Bubble getBubble(BubbleCoordinate coord) {
        validateCoordinate(coord);
        return grid[coord.row()][coord.column()];
    }

    /**
     * Set the bubble at a specific coordinate.
     * Can be used to place a bubble or clear a cell (by passing null).
     *
     * @param coord  Grid coordinate
     * @param bubble Bubble to place, or null to clear the cell
     * @throws IllegalArgumentException if coordinate is out of bounds
     */
    public void setBubble(BubbleCoordinate coord, Bubble bubble) {
        validateCoordinate(coord);
        grid[coord.row()][coord.column()] = bubble;
    }

    /**
     * Get all neighbors of a cell (including diagonals).
     * Only returns non-null bubbles. The set is unmodifiable.
     * <p>
     * Neighbor count depends on position:
     * - Corner: 3 neighbors
     * - Edge: 5 neighbors
     * - Interior: 8 neighbors
     *
     * @param coord Grid coordinate
     * @return Unmodifiable set of neighboring bubbles (may be empty)
     * @throws IllegalArgumentException if coordinate is out of bounds
     */
    public Set<Bubble> getNeighbors(BubbleCoordinate coord) {
        validateCoordinate(coord);

        var neighbors = new HashSet<Bubble>();
        int row = coord.row();
        int col = coord.column();

        // Check all 8 possible neighbor positions (including diagonals)
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                // Skip self
                if (dr == 0 && dc == 0) {
                    continue;
                }

                int neighborRow = row + dr;
                int neighborCol = col + dc;

                // Check if neighbor is within grid bounds
                if (neighborRow >= 0 && neighborRow < config.rows()
                    && neighborCol >= 0 && neighborCol < config.columns()) {

                    var neighbor = grid[neighborRow][neighborCol];
                    if (neighbor != null) {
                        neighbors.add(neighbor);
                    }
                }
            }
        }

        return Collections.unmodifiableSet(neighbors);
    }

    /**
     * Validate that a coordinate is within grid bounds.
     *
     * @param coord Coordinate to validate
     * @throws IllegalArgumentException if coordinate is out of bounds
     */
    private void validateCoordinate(BubbleCoordinate coord) {
        if (!config.isValid(coord)) {
            throw new IllegalArgumentException(
                "Coordinate " + coord + " is out of bounds for grid " + config);
        }
    }

    @Override
    public String toString() {
        return "BubbleGrid[" + config + "]";
    }
}
