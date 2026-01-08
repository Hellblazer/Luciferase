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

import javax.vecmath.Point3f;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for computing adaptive bounding boxes from entity positions.
 * <p>
 * This calculator computes minimal axis-aligned bounding boxes (AABBs) in RDGCS
 * coordinate space to represent the union of entity Areas of Interest (AOIs).
 * <p>
 * Key Features:
 * <ul>
 *   <li><b>Minimal Bounds</b> - Computes smallest AABB containing all positions</li>
 *   <li><b>Incremental Updates</b> - Efficiently updates bounds when entities enter/leave</li>
 *   <li><b>Thread-Safe</b> - All methods are static with no shared state</li>
 *   <li><b>RDGCS Precision</b> - Acceptable ~1.5 unit loss per coordinate</li>
 * </ul>
 * <p>
 * Architecture Notes:
 * <ul>
 *   <li>First approximation: AABB bounds (may span multiple tetrahedra)</li>
 *   <li>Future enhancement: Multi-level irregular volumes via Tetree</li>
 *   <li>Uses {@link BubbleBounds#fromEntityPositions(List)} for conversion</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class BubbleBoundCalculator {

    /**
     * Private constructor - utility class should not be instantiated.
     */
    private BubbleBoundCalculator() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Compute minimal bounding tetrahedron (AABB approximation) from entity positions.
     * <p>
     * This is the first approximation of adaptive bounds. It computes an axis-aligned
     * bounding box in RDGCS space that contains all entity positions, then represents
     * this as a {@link BubbleBounds} at level 10.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Convert all positions to RDGCS coordinates</li>
     *   <li>Find min/max along each axis</li>
     *   <li>Create BubbleBounds encompassing the AABB</li>
     *   <li>Locate tetrahedron containing the centroid</li>
     * </ol>
     *
     * @param positions Collection of entity positions (Cartesian)
     * @return BubbleBounds minimally encompassing all positions
     * @throws NullPointerException     if positions is null
     * @throws IllegalArgumentException if positions is empty
     */
    public static BubbleBounds minimalBoundingTetrahedron(Collection<Point3f> positions) {
        Objects.requireNonNull(positions, "Positions collection cannot be null");
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute bounds from empty position collection");
        }

        // Delegate to BubbleBounds.fromEntityPositions which already implements
        // the AABB algorithm correctly
        return BubbleBounds.fromEntityPositions(List.copyOf(positions));
    }

    /**
     * Incrementally update bounds when entities are added or removed.
     * <p>
     * This method efficiently updates existing bounds by:
     * <ul>
     *   <li>Expanding bounds to include newly added entities</li>
     *   <li>Recalculating bounds from scratch if removals leave bounds empty</li>
     *   <li>Potentially shrinking bounds after entity removal</li>
     * </ul>
     * <p>
     * Performance Characteristics:
     * <ul>
     *   <li><b>Add-only</b>: O(n) where n = added.size() - just expand existing bounds</li>
     *   <li><b>Removal</b>: O(m) where m = remaining entities - full recalculation</li>
     * </ul>
     *
     * @param current  Current bounds (may be null if bubble is empty)
     * @param added    Newly added entity positions (may be empty)
     * @param removed  Removed entity positions (may be empty)
     * @param remaining All remaining entity positions after add/remove operations
     * @return Updated BubbleBounds, or null if no entities remain
     * @throws NullPointerException if any collection parameter is null
     */
    public static BubbleBounds updateBounds(BubbleBounds current,
                                           Collection<Point3f> added,
                                           Collection<Point3f> removed,
                                           Collection<Point3f> remaining) {
        Objects.requireNonNull(added, "Added positions cannot be null");
        Objects.requireNonNull(removed, "Removed positions cannot be null");
        Objects.requireNonNull(remaining, "Remaining positions cannot be null");

        // If no entities remain after removal, return null
        if (remaining.isEmpty()) {
            return null;
        }

        // Case 1: No current bounds (first entities being added)
        if (current == null) {
            return minimalBoundingTetrahedron(remaining);
        }

        // Case 2: Only additions, no removals - can just expand bounds
        if (removed.isEmpty() && !added.isEmpty()) {
            var expandedBounds = current;
            for (var position : added) {
                expandedBounds = expandedBounds.expand(position);
            }
            return expandedBounds;
        }

        // Case 3: Removals present - need to recalculate from scratch
        // This is necessary because we can't efficiently shrink AABB bounds without
        // knowing which positions remain
        if (!removed.isEmpty()) {
            return minimalBoundingTetrahedron(remaining);
        }

        // Case 4: No changes (empty added and removed)
        return current;
    }

    /**
     * Compute bounds from union of entity Areas of Interest (AOIs).
     * <p>
     * Conceptual Method: In the future, this could compute irregular multi-level
     * volumes by analyzing entity AOI radii and using Tetree refinement. For now,
     * it's equivalent to {@link #minimalBoundingTetrahedron(Collection)}.
     * <p>
     * Future Enhancement Strategy:
     * <ul>
     *   <li>Analyze entity AOI radii to determine required refinement levels</li>
     *   <li>Use Tetree to partition space into variable-size tetrahedra</li>
     *   <li>Create irregular bounds that tightly fit entity distribution</li>
     * </ul>
     *
     * @param entityPositions Collection of entity positions
     * @return BubbleBounds representing union of entity AOIs
     * @throws NullPointerException     if entityPositions is null
     * @throws IllegalArgumentException if entityPositions is empty
     */
    public static BubbleBounds fromAOIs(Collection<Point3f> entityPositions) {
        // First approximation: same as minimal bounding tetrahedron
        // Future: incorporate AOI radius analysis for tighter bounds
        return minimalBoundingTetrahedron(entityPositions);
    }

    /**
     * Convenience method: Compute bounds from a single entity position.
     * <p>
     * This creates a minimal bounds containing just one position.
     *
     * @param position Single entity position
     * @return BubbleBounds containing the position
     * @throws NullPointerException if position is null
     */
    public static BubbleBounds fromEntityPositions(Point3f position) {
        Objects.requireNonNull(position, "Position cannot be null");
        return BubbleBounds.fromEntityPositions(List.of(position));
    }

    /**
     * Convenience method: Compute bounds from a collection of entity positions.
     * <p>
     * This delegates to {@link BubbleBounds#fromEntityPositions(List)}.
     *
     * @param positions Collection of entity positions
     * @return BubbleBounds minimally encompassing all positions
     * @throws NullPointerException     if positions is null
     * @throws IllegalArgumentException if positions is empty
     */
    public static BubbleBounds fromEntityPositions(Collection<Point3f> positions) {
        Objects.requireNonNull(positions, "Positions collection cannot be null");
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute bounds from empty position collection");
        }
        return BubbleBounds.fromEntityPositions(List.copyOf(positions));
    }
}
