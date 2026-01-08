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
 * Direction enum for 8-way entity migration in grid topology.
 * <p>
 * Each direction has a delta (dRow, dColumn) that transforms
 * a source coordinate to a target coordinate.
 * <p>
 * Cardinal directions (N, S, E, W) have a single axis delta.
 * Diagonal directions (NE, NW, SE, SW) have both axes deltas.
 *
 * @author hal.hildebrand
 */
public enum MigrationDirection {
    NORTH(1, 0),        // +Y (increase row)
    SOUTH(-1, 0),       // -Y (decrease row)
    EAST(0, 1),         // +X (increase column)
    WEST(0, -1),        // -X (decrease column)
    NORTH_EAST(1, 1),   // +Y +X
    NORTH_WEST(1, -1),  // +Y -X
    SOUTH_EAST(-1, 1),  // -Y +X
    SOUTH_WEST(-1, -1); // -Y -X

    private final int dRow;
    private final int dColumn;

    MigrationDirection(int dRow, int dColumn) {
        this.dRow = dRow;
        this.dColumn = dColumn;
    }

    /**
     * Apply this direction to a coordinate to get the target coordinate.
     *
     * @param from Source coordinate
     * @return Target coordinate (from + delta)
     */
    public BubbleCoordinate apply(BubbleCoordinate from) {
        return new BubbleCoordinate(from.row() + dRow, from.column() + dColumn);
    }

    /**
     * Get the row delta for this direction.
     *
     * @return Change in row (-1, 0, or 1)
     */
    public int deltaRow() {
        return dRow;
    }

    /**
     * Get the column delta for this direction.
     *
     * @return Change in column (-1, 0, or 1)
     */
    public int deltaColumn() {
        return dColumn;
    }

    /**
     * Check if this is a diagonal direction.
     *
     * @return true for NE, NW, SE, SW
     */
    public boolean isDiagonal() {
        return dRow != 0 && dColumn != 0;
    }

    /**
     * Check if this is a cardinal direction.
     *
     * @return true for N, S, E, W
     */
    public boolean isCardinal() {
        return !isDiagonal();
    }
}
