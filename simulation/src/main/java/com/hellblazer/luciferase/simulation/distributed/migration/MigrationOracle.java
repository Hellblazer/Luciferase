/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.migration;

import javax.vecmath.Point3f;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MigrationOracle - Boundary crossing detection for entity migration (Phase 7E Day 2)
 *
 * Detects when entities cross bubble spatial boundaries and identifies target bubbles.
 * Used by OptimisticMigrator to initiate migrations in response to entity movement.
 *
 * TOPOLOGY:
 * Manages 3D grid of cubic bubbles, each 1.0×1.0×1.0 in world space.
 * Default topology: 2x2x2 = 8 cubes spanning [0, 2.0]³
 *
 * BOUNDARY DETECTION:
 * Entity migrates when:
 * - Current position is outside home bubble's bounds
 * - Distance beyond boundary > tolerance (typically 0.05)
 * - Target bubble determined by spatial position
 *
 * PERFORMANCE:
 * Target: < 10ms for checking 1000 entities per frame
 * Implementation uses spatial caching to avoid redundant computations.
 *
 * THREAD SAFETY:
 * Implementation must be thread-safe for concurrent entity position updates.
 *
 * @author hal.hildebrand
 */
public interface MigrationOracle {

    /**
     * Check if an entity needs migration based on position.
     * Called once per entity per frame to detect boundary crossings.
     *
     * PERFORMANCE:
     * O(1) coordinate lookup + O(1) containment check ≈ < 1μs per entity
     *
     * @param position Entity's current world position
     * @param currentBubbleId Bubble that currently owns the entity
     * @return Optional containing target bubble ID if migration needed, empty otherwise
     * @throws NullPointerException if position or currentBubbleId is null
     */
    Optional<UUID> checkMigration(Point3f position, UUID currentBubbleId);

    /**
     * Determine target bubble for entity at given world position.
     * Used to identify which bubble should own an entity after migration.
     *
     * SEMANTICS:
     * Returns the cube that spatially contains the position,
     * or the nearest cube if position is outside the domain.
     *
     * @param position World position to query
     * @return Target bubble ID for this position
     * @throws NullPointerException if position is null
     */
    UUID getTargetBubble(Point3f position);

    /**
     * Get all entities that crossed bubble boundaries this frame.
     * Called once per update to collect all migration candidates.
     *
     * PERFORMANCE:
     * O(n) where n = number of entities changed position since last frame
     * Typically much less than total entity count (spatial caching).
     *
     * SEMANTICS:
     * Returns entity IDs (String or UUID representation) that crossed boundaries.
     * Caller is responsible for initiating migrations.
     *
     * @return Set of entity IDs that need migration (may be empty)
     */
    Set<String> getEntitiesCrossingBoundaries();

    /**
     * Clear boundary crossing cache after processing migrations.
     * Called after migrations are initiated to reset detection state.
     */
    void clearCrossingCache();

    /**
     * Get the cube coordinate for a world position.
     * Useful for debugging and topology verification.
     *
     * @param position World position
     * @return Cube coordinates, or null if outside domain
     */
    CubeBubbleCoordinate getCoordinateForPosition(Point3f position);

    /**
     * Get the world position bounds for a cube coordinate.
     *
     * @param coordinate Cube coordinate
     * @return Bounds [x_min, y_min, z_min, x_max, y_max, z_max]
     */
    float[] getBoundsForCoordinate(CubeBubbleCoordinate coordinate);

    /**
     * Configure the boundary tolerance for crossing detection.
     * Default: 0.05 (5% of cube width)
     * Larger tolerance prevents thrashing at boundaries.
     *
     * @param tolerance Tolerance in world units
     */
    void setBoundaryTolerance(float tolerance);

    /**
     * Get the current boundary tolerance.
     *
     * @return Tolerance in world units
     */
    float getBoundaryTolerance();
}
