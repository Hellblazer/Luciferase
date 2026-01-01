/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sparse.core;

/**
 * Defines the normalized coordinate space used by sparse voxel data structures.
 *
 * <p>Both ESVO (Octree) and ESVT (Tetree) implementations use the unified
 * [0, 1] normalized coordinate space ({@link #UNIT_CUBE}).
 *
 * <p><b>Architecture Note:</b> This enum serves as the return type for
 * {@link SparseVoxelData#getCoordinateSpace()}, providing type-safe documentation
 * of the coordinate convention. For transformation operations, use the utility classes:
 * <ul>
 *   <li>{@link SparseCoordinateSpace} - Generic voxel operations (shared code)</li>
 *   <li>{@link com.hellblazer.luciferase.esvo.core.CoordinateSpace} - Octree-specific operations</li>
 * </ul>
 *
 * <p><b>Historical Note:</b> The original ESVO paper used [1,2] coordinate space
 * for IEEE 754 bit manipulation optimizations. This implementation uses [0,1]
 * for consistency and standard graphics pipeline compatibility.
 *
 * @author hal.hildebrand
 * @see SparseVoxelData#getCoordinateSpace()
 * @see SparseCoordinateSpace
 */
public enum CoordinateSpace {

    /**
     * Normalized [0, 1] coordinate space.
     *
     * <p>Used by all sparse voxel structures (ESVO octree, ESVT tetrahedral).
     * This is the only coordinate space in use; the enum exists for type safety
     * and documentation in the {@link SparseVoxelData} interface.
     */
    UNIT_CUBE(0.0f, 1.0f);

    /** Minimum coordinate value (0.0) */
    private final float min;

    /** Maximum coordinate value (1.0) */
    private final float max;

    CoordinateSpace(float min, float max) {
        this.min = min;
        this.max = max;
    }

    /**
     * Get the minimum coordinate value.
     *
     * @return minimum value for all axes (0.0 for UNIT_CUBE)
     */
    public float getMin() {
        return min;
    }

    /**
     * Get the maximum coordinate value.
     *
     * @return maximum value for all axes (1.0 for UNIT_CUBE)
     */
    public float getMax() {
        return max;
    }

    /**
     * Get the size of the coordinate space.
     *
     * @return max - min (1.0 for UNIT_CUBE)
     */
    public float getSize() {
        return max - min;
    }

    /**
     * Get the center of the coordinate space.
     *
     * @return (min + max) / 2 (0.5 for UNIT_CUBE)
     */
    public float getCenter() {
        return (min + max) / 2.0f;
    }

    /**
     * Check if a point is within this coordinate space.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return true if point is within bounds (inclusive)
     */
    public boolean contains(float x, float y, float z) {
        return x >= min && x <= max &&
               y >= min && y <= max &&
               z >= min && z <= max;
    }
}
