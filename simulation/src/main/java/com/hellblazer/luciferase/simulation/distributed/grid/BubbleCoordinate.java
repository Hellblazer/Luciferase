/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

/**
 * Grid position for a bubble in the 2D grid topology.
 * Uses row-major ordering (row, column) convention.
 * <p>
 * The grid is 2D (XY plane), with cells being infinite columns in Z.
 * Neighbor count varies by position:
 * - Corner: 3 neighbors
 * - Edge: 5 neighbors
 * - Interior: 8 neighbors
 *
 * @param row    Row index (0-based, increases in Y direction)
 * @param column Column index (0-based, increases in X direction)
 * @author hal.hildebrand
 */
public record BubbleCoordinate(int row, int column) {

    /**
     * Validate that coordinates are non-negative.
     */
    public BubbleCoordinate {
        if (row < 0) {
            throw new IllegalArgumentException("Row must be non-negative: " + row);
        }
        if (column < 0) {
            throw new IllegalArgumentException("Column must be non-negative: " + column);
        }
    }

    /**
     * Calculate Manhattan distance to another coordinate.
     *
     * @param other The other coordinate
     * @return Manhattan distance (|row1-row2| + |col1-col2|)
     */
    public int manhattanDistance(BubbleCoordinate other) {
        return Math.abs(row - other.row) + Math.abs(column - other.column);
    }

    /**
     * Calculate Chebyshev distance (max of row/column differences).
     * This is the "chess king" distance - 1 for adjacent (including diagonal).
     *
     * @param other The other coordinate
     * @return Chebyshev distance
     */
    public int chebyshevDistance(BubbleCoordinate other) {
        return Math.max(Math.abs(row - other.row), Math.abs(column - other.column));
    }

    /**
     * Check if this coordinate is adjacent to another (including diagonals).
     * Two coordinates are adjacent if their Chebyshev distance is 1.
     *
     * @param other The other coordinate
     * @return true if adjacent (including diagonal)
     */
    public boolean isAdjacentTo(BubbleCoordinate other) {
        return chebyshevDistance(other) == 1;
    }

    /**
     * Get the position type based on grid dimensions.
     *
     * @param rows Total rows in grid
     * @param cols Total columns in grid
     * @return CORNER, EDGE, or INTERIOR
     */
    public PositionType getPositionType(int rows, int cols) {
        boolean atRowEdge = (row == 0 || row == rows - 1);
        boolean atColEdge = (column == 0 || column == cols - 1);

        if (atRowEdge && atColEdge) {
            return PositionType.CORNER;
        } else if (atRowEdge || atColEdge) {
            return PositionType.EDGE;
        } else {
            return PositionType.INTERIOR;
        }
    }

    /**
     * Get expected neighbor count based on position type.
     * Handles degenerate grids (1xN, Nx1, 1x1).
     *
     * @param rows Total rows in grid
     * @param cols Total columns in grid
     * @return Expected neighbor count (0, 1, 2, 3, 5, or 8)
     */
    public int expectedNeighborCount(int rows, int cols) {
        // Handle degenerate cases
        if (rows == 1 && cols == 1) {
            return 0; // Single cell has no neighbors
        }

        if (rows == 1) {
            // Single row (1xN grid) - only horizontal neighbors
            return (column == 0 || column == cols - 1) ? 1 : 2;
        }

        if (cols == 1) {
            // Single column (Nx1 grid) - only vertical neighbors
            return (row == 0 || row == rows - 1) ? 1 : 2;
        }

        // Normal 2D grid
        return switch (getPositionType(rows, cols)) {
            case CORNER -> 3;
            case EDGE -> 5;
            case INTERIOR -> 8;
        };
    }

    @Override
    public String toString() {
        return "(" + row + "," + column + ")";
    }

    /**
     * Position type in the grid.
     */
    public enum PositionType {
        CORNER,   // 3 neighbors
        EDGE,     // 5 neighbors
        INTERIOR  // 8 neighbors
    }
}
