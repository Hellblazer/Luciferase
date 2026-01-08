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
import java.util.*;

/**
 * Detects when entities are near grid cell boundaries for ghost synchronization.
 * <p>
 * Uses Area of Interest (AOI) radius to determine boundary proximity:
 * - Entity near boundary if distance to any cell edge < AOI radius
 * - Handles corner/edge/interior cells (3, 5, or 8 neighbors)
 * - Diagonal proximity: entity near corner receives ghosts in up to 3 directions
 * <p>
 * Thread-safe for concurrent boundary checks.
 *
 * @author hal.hildebrand
 */
public class GridBoundaryDetector {

    /**
     * Default AOI radius: 20% of typical cell width.
     */
    public static final float DEFAULT_AOI_RADIUS = 20.0f;

    private final GridConfiguration gridConfig;
    private final float aoiRadius;

    /**
     * Create a boundary detector with default AOI radius.
     *
     * @param gridConfig Grid configuration
     */
    public GridBoundaryDetector(GridConfiguration gridConfig) {
        this(gridConfig, DEFAULT_AOI_RADIUS);
    }

    /**
     * Create a boundary detector with custom AOI radius.
     *
     * @param gridConfig Grid configuration
     * @param aoiRadius  AOI radius for boundary proximity (units)
     */
    public GridBoundaryDetector(GridConfiguration gridConfig, float aoiRadius) {
        this.gridConfig = gridConfig;
        this.aoiRadius = aoiRadius;
    }

    /**
     * Get AOI radius.
     *
     * @return AOI radius
     */
    public float getAoiRadius() {
        return aoiRadius;
    }

    /**
     * Check if an entity is near a cell boundary.
     *
     * @param position Entity position
     * @param coord    Cell coordinate
     * @return true if entity is within AOI radius of any cell edge
     */
    public boolean isNearBoundary(Point3f position, BubbleCoordinate coord) {
        var cellMin = gridConfig.cellMin(coord);
        var cellMax = gridConfig.cellMax(coord);

        // Check distance to each edge
        float distToLeft = position.x - cellMin.x;
        float distToRight = cellMax.x - position.x;
        float distToBottom = position.y - cellMin.y;
        float distToTop = cellMax.y - position.y;

        // Near boundary if any edge distance < AOI radius
        return distToLeft < aoiRadius || distToRight < aoiRadius
               || distToBottom < aoiRadius || distToTop < aoiRadius;
    }

    /**
     * Get all neighbor cells that should receive ghosts for an entity.
     * <p>
     * Returns neighbors based on which boundaries the entity is near:
     * - Near 1 boundary: 1-3 neighbors (edge)
     * - Near 2 boundaries: 1-3 neighbors (corner)
     * - Not near boundary: empty list
     *
     * @param position Entity position
     * @param coord    Entity's current cell coordinate
     * @return Set of neighbor coordinates that need ghosts
     */
    public Set<BubbleCoordinate> getNeighborsNeedingGhosts(Point3f position, BubbleCoordinate coord) {
        var cellMin = gridConfig.cellMin(coord);
        var cellMax = gridConfig.cellMax(coord);

        var neighbors = new HashSet<BubbleCoordinate>();

        // Calculate distances to edges
        float distToLeft = position.x - cellMin.x;
        float distToRight = cellMax.x - position.x;
        float distToBottom = position.y - cellMin.y;
        float distToTop = cellMax.y - position.y;

        // Determine which edges are within AOI
        boolean nearLeft = distToLeft < aoiRadius;
        boolean nearRight = distToRight < aoiRadius;
        boolean nearBottom = distToBottom < aoiRadius;
        boolean nearTop = distToTop < aoiRadius;

        // Add neighbors based on proximity
        int row = coord.row();
        int col = coord.column();

        // Left neighbor
        if (nearLeft && col > 0) {
            neighbors.add(new BubbleCoordinate(row, col - 1));
        }

        // Right neighbor
        if (nearRight && col < gridConfig.columns() - 1) {
            neighbors.add(new BubbleCoordinate(row, col + 1));
        }

        // Bottom neighbor
        if (nearBottom && row > 0) {
            neighbors.add(new BubbleCoordinate(row - 1, col));
        }

        // Top neighbor
        if (nearTop && row < gridConfig.rows() - 1) {
            neighbors.add(new BubbleCoordinate(row + 1, col));
        }

        // Diagonal neighbors (corners)
        if (nearLeft && nearBottom && col > 0 && row > 0) {
            neighbors.add(new BubbleCoordinate(row - 1, col - 1));
        }
        if (nearLeft && nearTop && col > 0 && row < gridConfig.rows() - 1) {
            neighbors.add(new BubbleCoordinate(row + 1, col - 1));
        }
        if (nearRight && nearBottom && col < gridConfig.columns() - 1 && row > 0) {
            neighbors.add(new BubbleCoordinate(row - 1, col + 1));
        }
        if (nearRight && nearTop && col < gridConfig.columns() - 1 && row < gridConfig.rows() - 1) {
            neighbors.add(new BubbleCoordinate(row + 1, col + 1));
        }

        return neighbors;
    }

    /**
     * Get the distance from an entity to the nearest cell boundary.
     *
     * @param position Entity position
     * @param coord    Cell coordinate
     * @return Distance to nearest boundary edge
     */
    public float distanceToBoundary(Point3f position, BubbleCoordinate coord) {
        var cellMin = gridConfig.cellMin(coord);
        var cellMax = gridConfig.cellMax(coord);

        float distToLeft = position.x - cellMin.x;
        float distToRight = cellMax.x - position.x;
        float distToBottom = position.y - cellMin.y;
        float distToTop = cellMax.y - position.y;

        return Math.min(Math.min(distToLeft, distToRight), Math.min(distToBottom, distToTop));
    }

    @Override
    public String toString() {
        return String.format("GridBoundaryDetector[grid=%s, aoiRadius=%.1f]", gridConfig, aoiRadius);
    }
}
