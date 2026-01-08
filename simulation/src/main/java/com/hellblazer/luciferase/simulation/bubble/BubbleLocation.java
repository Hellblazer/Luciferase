/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Point3f;
import java.util.Collection;
import java.util.Objects;

/**
 * Encapsulates a bubble's position in the tetrahedral hierarchy.
 * <p>
 * This record combines:
 * <ul>
 *   <li><b>TetreeKey</b> - Navigation key for spatial hierarchy (level + SFC index)</li>
 *   <li><b>BubbleBounds</b> - Adaptive AABB in RDGCS coordinates representing union of entity AOIs</li>
 * </ul>
 * <p>
 * Key Properties:
 * <ul>
 *   <li>Bounds are adaptive - initially minimal bounding tetrahedron, can expand to span multiple tetrahedra</li>
 *   <li>Supports 3D tetrahedral organization (not 2D grid)</li>
 *   <li>RDGCS precision acceptable (~1.5 unit loss per coordinate)</li>
 *   <li>Thread-safe immutable record</li>
 * </ul>
 *
 * @param key    Navigation key for tetrahedral hierarchy
 * @param bounds Adaptive AABB in RDGCS coordinates
 * @author hal.hildebrand
 */
public record BubbleLocation(
    TetreeKey<? extends TetreeKey> key,
    BubbleBounds bounds
) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if key or bounds is null
     */
    public BubbleLocation {
        Objects.requireNonNull(key, "TetreeKey cannot be null");
        Objects.requireNonNull(bounds, "BubbleBounds cannot be null");
    }

    /**
     * Check if this location is at the root tetrahedron (level 0).
     * <p>
     * Root locations have no parent and represent the coarsest spatial partition.
     *
     * @return true if at root level (level 0), false otherwise
     */
    public boolean isAtRoot() {
        return key.getLevel() == 0;
    }

    /**
     * Check if a Cartesian position is contained within this location's bounds.
     * <p>
     * Uses RDGCS bounding box containment test. This is conservative - it tests
     * against the axis-aligned bounding box in RDGCS space, not exact tetrahedral
     * containment.
     *
     * @param position Cartesian position to test
     * @return true if position is within bounds, false otherwise
     * @throws NullPointerException if position is null
     */
    public boolean contains(Point3f position) {
        Objects.requireNonNull(position, "Position cannot be null");
        return bounds.contains(position);
    }

    /**
     * Check if this location's bounds overlap with another location's bounds.
     * <p>
     * Uses RDGCS AABB overlap detection. Two bounds overlap if their axis-aligned
     * bounding boxes intersect in RDGCS coordinate space.
     *
     * @param other Other bubble location to test
     * @return true if bounds overlap, false otherwise
     * @throws NullPointerException if other is null
     */
    public boolean overlaps(BubbleLocation other) {
        Objects.requireNonNull(other, "Other location cannot be null");
        return bounds.overlaps(other.bounds);
    }

    /**
     * Check if this location's bounds overlap with another bounds object.
     * <p>
     * Convenience method for testing against BubbleBounds directly.
     *
     * @param otherBounds Other bounds to test
     * @return true if bounds overlap, false otherwise
     * @throws NullPointerException if otherBounds is null
     */
    public boolean overlaps(BubbleBounds otherBounds) {
        Objects.requireNonNull(otherBounds, "Other bounds cannot be null");
        return bounds.overlaps(otherBounds);
    }

    /**
     * Create a new BubbleLocation with updated bounds based on current entity positions.
     * <p>
     * This method recalculates the adaptive bounds to minimally enclose all provided
     * entity positions. The TetreeKey is recalculated to locate the tetrahedron
     * containing the centroid of the new bounds.
     * <p>
     * Use this when:
     * <ul>
     *   <li>Entities have moved significantly within the bubble</li>
     *   <li>Entities have been added or removed</li>
     *   <li>Bounds need to shrink after entity removal</li>
     * </ul>
     *
     * @param positions Collection of current entity positions
     * @return New BubbleLocation with recalculated bounds and key
     * @throws NullPointerException     if positions is null
     * @throws IllegalArgumentException if positions is empty
     */
    public BubbleLocation updateBounds(Collection<Point3f> positions) {
        Objects.requireNonNull(positions, "Positions collection cannot be null");
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("Cannot update bounds from empty position collection");
        }

        // Calculate new bounds from entity positions
        var newBounds = BubbleBoundCalculator.fromEntityPositions(positions);

        // Get the centroid to locate the new tetrahedron key
        var centroid = newBounds.centroid();
        var newKey = TetreeKey.create(
            key.getLevel(), // Maintain same level
            0L,              // Will be computed by Tet.locatePointBeyRefinementFromRoot
            0L
        );

        // Locate the tetrahedron containing the new centroid
        var tet = com.hellblazer.luciferase.lucien.tetree.Tet.locatePointBeyRefinementFromRoot(
            (float) centroid.getX(),
            (float) centroid.getY(),
            (float) centroid.getZ(),
            key.getLevel()
        );

        if (tet != null) {
            newKey = tet.tmIndex();
        }

        return new BubbleLocation(newKey, newBounds);
    }

    @Override
    public String toString() {
        return String.format("BubbleLocation{key=%s, bounds=%s}", key, bounds);
    }
}
