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
import java.util.ArrayList;
import java.util.List;

/**
 * Router for multi-directional entity migration.
 * <p>
 * Determines the target bubble coordinate for an entity based on its
 * position relative to cell boundaries. Handles edge cases where
 * entities cross multiple boundaries simultaneously (diagonal migration).
 * <p>
 * Tie-breaking rule for diagonal crossings:
 * - When entity crosses both X and Y boundaries simultaneously
 * - Migrate to the direction with the largest boundary overshoot: max(|Δx|, |Δy|)
 * - If |Δx| = |Δy|: Prefer X-axis (deterministic)
 *
 * @author hal.hildebrand
 */
public class MigrationRouter {

    private final GridConfiguration gridConfig;

    public MigrationRouter(GridConfiguration gridConfig) {
        this.gridConfig = gridConfig;
    }

    /**
     * Detect which direction(s) an entity should migrate based on its position.
     * <p>
     * Returns at most one direction, even if entity crosses multiple boundaries.
     * Diagonal tie-breaking: largest overshoot wins, X-axis breaks ties.
     *
     * @param sourceCoord Source bubble coordinate
     * @param position    Entity position
     * @param hysteresis  Distance past boundary required for migration
     * @return Migration direction, or null if entity should not migrate
     */
    public MigrationDirection detectMigrationDirection(
        BubbleCoordinate sourceCoord,
        Point3f position,
        float hysteresis
    ) {
        var cellMin = gridConfig.cellMin(sourceCoord);
        var cellMax = gridConfig.cellMax(sourceCoord);

        // Calculate overshoot distances (positive if past boundary + hysteresis)
        // Entity must be hysteresis distance PAST boundary to migrate
        float overshootNorth = position.y - (cellMax.y + hysteresis); // How far past north boundary + hysteresis
        float overshootSouth = (cellMin.y - hysteresis) - position.y; // How far past south boundary - hysteresis
        float overshootEast = position.x - (cellMax.x + hysteresis);  // How far past east boundary + hysteresis
        float overshootWest = (cellMin.x - hysteresis) - position.x;  // How far past west boundary - hysteresis

        // Determine which boundaries are crossed
        boolean crossedNorth = overshootNorth > 0;
        boolean crossedSouth = overshootSouth > 0;
        boolean crossedEast = overshootEast > 0;
        boolean crossedWest = overshootWest > 0;

        // No boundary crossed
        if (!crossedNorth && !crossedSouth && !crossedEast && !crossedWest) {
            return null;
        }

        // Single boundary crossed (cardinal direction)
        if (crossedNorth && !crossedSouth && !crossedEast && !crossedWest) {
            return validateTarget(sourceCoord, MigrationDirection.NORTH);
        }
        if (crossedSouth && !crossedNorth && !crossedEast && !crossedWest) {
            return validateTarget(sourceCoord, MigrationDirection.SOUTH);
        }
        if (crossedEast && !crossedNorth && !crossedSouth && !crossedWest) {
            return validateTarget(sourceCoord, MigrationDirection.EAST);
        }
        if (crossedWest && !crossedNorth && !crossedSouth && !crossedEast) {
            return validateTarget(sourceCoord, MigrationDirection.WEST);
        }

        // Multiple boundaries crossed - diagonal migration
        // Apply tie-breaking: largest overshoot wins, X-axis breaks ties
        return resolveDiagonal(sourceCoord, overshootNorth, overshootSouth, overshootEast, overshootWest,
                               crossedNorth, crossedSouth, crossedEast, crossedWest);
    }

    /**
     * Resolve diagonal migration using tie-breaking rules.
     *
     * @param sourceCoord Source coordinate
     * @param overshootNorth Overshoot distance north (positive if crossed)
     * @param overshootSouth Overshoot distance south (positive if crossed)
     * @param overshootEast Overshoot distance east (positive if crossed)
     * @param overshootWest Overshoot distance west (positive if crossed)
     * @param crossedNorth True if north boundary crossed
     * @param crossedSouth True if south boundary crossed
     * @param crossedEast True if east boundary crossed
     * @param crossedWest True if west boundary crossed
     * @return Migration direction based on tie-breaking rules
     */
    private MigrationDirection resolveDiagonal(
        BubbleCoordinate sourceCoord,
        float overshootNorth, float overshootSouth, float overshootEast, float overshootWest,
        boolean crossedNorth, boolean crossedSouth, boolean crossedEast, boolean crossedWest
    ) {
        // Get max overshoot for each axis
        float maxY = Math.max(crossedNorth ? overshootNorth : 0, crossedSouth ? overshootSouth : 0);
        float maxX = Math.max(crossedEast ? overshootEast : 0, crossedWest ? overshootWest : 0);

        // Tie-breaking: largest overshoot wins
        boolean preferY = maxY > maxX;
        boolean preferX = maxX > maxY;
        // If maxX == maxY: preferX (X-axis breaks ties)
        if (!preferY && !preferX) {
            preferX = true;
        }

        // Determine primary axis direction
        MigrationDirection primaryY = crossedNorth ? MigrationDirection.NORTH : MigrationDirection.SOUTH;
        MigrationDirection primaryX = crossedEast ? MigrationDirection.EAST : MigrationDirection.WEST;

        if (preferY) {
            // Y-axis dominates - move in Y direction
            return validateTarget(sourceCoord, primaryY);
        } else {
            // X-axis dominates or ties - move in X direction
            return validateTarget(sourceCoord, primaryX);
        }
    }

    /**
     * Validate that the target coordinate is within grid bounds.
     * Returns null if target is out of bounds.
     *
     * @param source Source coordinate
     * @param direction Migration direction
     * @return Direction if target is valid, null otherwise
     */
    private MigrationDirection validateTarget(BubbleCoordinate source, MigrationDirection direction) {
        var target = direction.apply(source);
        return gridConfig.isValid(target) ? direction : null;
    }

    /**
     * Get all valid migration directions from a source coordinate.
     * Useful for determining which neighbors exist (edge/corner cells have fewer neighbors).
     *
     * @param source Source coordinate
     * @return List of valid migration directions (directions where target is in bounds)
     */
    public List<MigrationDirection> getValidDirections(BubbleCoordinate source) {
        var valid = new ArrayList<MigrationDirection>(8);
        for (var direction : MigrationDirection.values()) {
            if (validateTarget(source, direction) != null) {
                valid.add(direction);
            }
        }
        return valid;
    }
}
