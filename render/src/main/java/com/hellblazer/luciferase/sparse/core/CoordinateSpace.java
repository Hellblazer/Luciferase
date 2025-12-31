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
 * <p>Different sparse voxel implementations use different coordinate conventions:
 * <ul>
 *   <li><b>ESVO (Octree)</b>: Uses [1, 2] normalized space (origin at 1,1,1)</li>
 *   <li><b>ESVT (Tetree)</b>: Uses [0, 1] normalized space (origin at 0,0,0)</li>
 * </ul>
 *
 * <p>Renderers must generate rays in the correct coordinate space for the data
 * structure being traversed. Mismatched coordinate spaces will result in incorrect
 * ray-voxel intersection results.
 *
 * @author hal.hildebrand
 */
public enum CoordinateSpace {

    /**
     * Normalized [0, 1] coordinate space.
     * Used by ESVT (tetrahedral) structures.
     */
    UNIT_CUBE(0.0f, 1.0f),

    /**
     * Normalized [1, 2] coordinate space.
     * Used by ESVO (octree) structures for efficient bit manipulation.
     */
    OCTREE_SPACE(1.0f, 2.0f);

    private final float min;
    private final float max;

    CoordinateSpace(float min, float max) {
        this.min = min;
        this.max = max;
    }

    /**
     * Get the minimum coordinate value.
     *
     * @return minimum value for all axes
     */
    public float getMin() {
        return min;
    }

    /**
     * Get the maximum coordinate value.
     *
     * @return maximum value for all axes
     */
    public float getMax() {
        return max;
    }

    /**
     * Get the size of the coordinate space.
     *
     * @return max - min
     */
    public float getSize() {
        return max - min;
    }

    /**
     * Get the center of the coordinate space.
     *
     * @return (min + max) / 2
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

    /**
     * Transform a point from [0, 1] normalized space to this coordinate space.
     *
     * @param normalizedValue value in [0, 1]
     * @return value in this coordinate space
     */
    public float fromNormalized(float normalizedValue) {
        return min + normalizedValue * (max - min);
    }

    /**
     * Transform a point from this coordinate space to [0, 1] normalized space.
     *
     * @param value value in this coordinate space
     * @return value in [0, 1]
     */
    public float toNormalized(float value) {
        return (value - min) / (max - min);
    }
}
