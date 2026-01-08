/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import javax.vecmath.Point3f;

/**
 * Configuration for a 2D grid topology of bubbles.
 * <p>
 * The grid partitions the XY plane into cells. Each cell is an infinite
 * column in the Z direction. Ghost sync uses AOI radius for Z-axis proximity.
 *
 * @param rows       Number of rows (Y direction)
 * @param columns    Number of columns (X direction)
 * @param cellWidth  Width of each cell (X extent)
 * @param cellHeight Height of each cell (Y extent)
 * @param originX    X coordinate of grid origin (bottom-left corner)
 * @param originY    Y coordinate of grid origin (bottom-left corner)
 * @author hal.hildebrand
 */
public record GridConfiguration(
    int rows,
    int columns,
    float cellWidth,
    float cellHeight,
    float originX,
    float originY
) {
    /**
     * Default configuration for testing: 2x2 grid with 100-unit cells.
     */
    public static final GridConfiguration DEFAULT_2X2 = new GridConfiguration(2, 2, 100f, 100f, 0f, 0f);

    /**
     * 3x3 grid with 100-unit cells (typical for multi-bubble testing).
     */
    public static final GridConfiguration DEFAULT_3X3 = new GridConfiguration(3, 3, 100f, 100f, 0f, 0f);

    /**
     * Validate configuration parameters.
     */
    public GridConfiguration {
        if (rows < 1) {
            throw new IllegalArgumentException("Rows must be at least 1: " + rows);
        }
        if (columns < 1) {
            throw new IllegalArgumentException("Columns must be at least 1: " + columns);
        }
        if (cellWidth <= 0) {
            throw new IllegalArgumentException("Cell width must be positive: " + cellWidth);
        }
        if (cellHeight <= 0) {
            throw new IllegalArgumentException("Cell height must be positive: " + cellHeight);
        }
    }

    /**
     * Create a square grid configuration.
     *
     * @param size     Grid size (size x size)
     * @param cellSize Cell dimension (same for width and height)
     * @return Configuration for square grid
     */
    public static GridConfiguration square(int size, float cellSize) {
        return new GridConfiguration(size, size, cellSize, cellSize, 0f, 0f);
    }

    /**
     * Create a grid configuration with origin at (0,0).
     *
     * @param rows       Number of rows
     * @param columns    Number of columns
     * @param cellWidth  Width of each cell
     * @param cellHeight Height of each cell
     * @return Configuration
     */
    public static GridConfiguration of(int rows, int columns, float cellWidth, float cellHeight) {
        return new GridConfiguration(rows, columns, cellWidth, cellHeight, 0f, 0f);
    }

    /**
     * Total number of bubbles in the grid.
     *
     * @return rows * columns
     */
    public int bubbleCount() {
        return rows * columns;
    }

    /**
     * Total width of the grid (all columns).
     *
     * @return columns * cellWidth
     */
    public float totalWidth() {
        return columns * cellWidth;
    }

    /**
     * Total height of the grid (all rows).
     *
     * @return rows * cellHeight
     */
    public float totalHeight() {
        return rows * cellHeight;
    }

    /**
     * Get the coordinate for a position in the grid.
     * Returns null if position is outside grid bounds.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return BubbleCoordinate or null if out of bounds
     */
    public BubbleCoordinate coordinateFor(float x, float y) {
        // Normalize to grid space
        float localX = x - originX;
        float localY = y - originY;

        // Check bounds
        if (localX < 0 || localX >= totalWidth() || localY < 0 || localY >= totalHeight()) {
            return null;
        }

        int col = (int) (localX / cellWidth);
        int row = (int) (localY / cellHeight);

        // Clamp to valid range (handles edge case of x/y exactly at max boundary)
        col = Math.min(col, columns - 1);
        row = Math.min(row, rows - 1);

        return new BubbleCoordinate(row, col);
    }

    /**
     * Get the coordinate for a 3D position (Z is ignored for grid assignment).
     *
     * @param position 3D position
     * @return BubbleCoordinate or null if out of bounds
     */
    public BubbleCoordinate coordinateFor(Point3f position) {
        return coordinateFor(position.x, position.y);
    }

    /**
     * Get the center point of a cell.
     *
     * @param coord Cell coordinate
     * @return Center point (Z = 0)
     */
    public Point3f cellCenter(BubbleCoordinate coord) {
        float x = originX + coord.column() * cellWidth + cellWidth / 2f;
        float y = originY + coord.row() * cellHeight + cellHeight / 2f;
        return new Point3f(x, y, 0f);
    }

    /**
     * Get the minimum corner of a cell.
     *
     * @param coord Cell coordinate
     * @return Minimum corner (Z = 0)
     */
    public Point3f cellMin(BubbleCoordinate coord) {
        float x = originX + coord.column() * cellWidth;
        float y = originY + coord.row() * cellHeight;
        return new Point3f(x, y, 0f);
    }

    /**
     * Get the maximum corner of a cell.
     *
     * @param coord Cell coordinate
     * @return Maximum corner (Z = 0)
     */
    public Point3f cellMax(BubbleCoordinate coord) {
        float x = originX + (coord.column() + 1) * cellWidth;
        float y = originY + (coord.row() + 1) * cellHeight;
        return new Point3f(x, y, 0f);
    }

    /**
     * Check if a coordinate is valid within this grid.
     *
     * @param coord Coordinate to check
     * @return true if within grid bounds
     */
    public boolean isValid(BubbleCoordinate coord) {
        return coord.row() >= 0 && coord.row() < rows
               && coord.column() >= 0 && coord.column() < columns;
    }

    /**
     * Convert coordinate to linear index (row-major order).
     *
     * @param coord Grid coordinate
     * @return Linear index
     */
    public int toIndex(BubbleCoordinate coord) {
        return coord.row() * columns + coord.column();
    }

    /**
     * Convert linear index to coordinate.
     *
     * @param index Linear index
     * @return Grid coordinate
     */
    public BubbleCoordinate fromIndex(int index) {
        if (index < 0 || index >= bubbleCount()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }
        int row = index / columns;
        int col = index % columns;
        return new BubbleCoordinate(row, col);
    }

    @Override
    public String toString() {
        return "GridConfiguration[" + rows + "x" + columns + ", cell=" + cellWidth + "x" + cellHeight + "]";
    }
}
