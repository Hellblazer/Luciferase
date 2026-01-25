/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

/**
 * Factory for creating SplitPlaneStrategy instances.
 * <p>
 * Provides convenient factory methods for all built-in split plane strategies:
 * - longestAxis(): Default strategy, splits along longest dimension
 * - xAxis(): Always split along X-axis
 * - yAxis(): Always split along Y-axis
 * - zAxis(): Always split along Z-axis
 * - cyclic(): Cycle through X → Y → Z → X
 * - forAxis(): Map SplitAxis enum to corresponding strategy
 * <p>
 * Usage:
 * <pre>
 * var strategy = SplitPlaneStrategies.longestAxis();
 * var plane = strategy.calculate(bubble, entities);
 * </pre>
 */
public class SplitPlaneStrategies {

    /**
     * Create strategy that splits along longest axis of bubble bounds.
     * <p>
     * This is the default strategy - analyzes bubble dimensions and
     * selects the longest axis for splitting.
     *
     * @return LongestAxisStrategy instance
     */
    public static SplitPlaneStrategy longestAxis() {
        return new LongestAxisStrategy();
    }

    /**
     * Create strategy that always splits along X-axis.
     * <p>
     * Plane perpendicular to X-axis through centroid.
     *
     * @return XAxisStrategy instance
     */
    public static SplitPlaneStrategy xAxis() {
        return new XAxisStrategy();
    }

    /**
     * Create strategy that always splits along Y-axis.
     * <p>
     * Plane perpendicular to Y-axis through centroid.
     *
     * @return YAxisStrategy instance
     */
    public static SplitPlaneStrategy yAxis() {
        return new YAxisStrategy();
    }

    /**
     * Create strategy that always splits along Z-axis.
     * <p>
     * Plane perpendicular to Z-axis through centroid.
     *
     * @return ZAxisStrategy instance
     */
    public static SplitPlaneStrategy zAxis() {
        return new ZAxisStrategy();
    }

    /**
     * Create strategy that cycles through axes: X → Y → Z → X.
     * <p>
     * Each call to calculate() returns the next axis in sequence.
     * Thread-safe for concurrent use.
     *
     * @return CyclicAxisStrategy instance
     */
    public static SplitPlaneStrategy cyclic() {
        return new CyclicAxisStrategy();
    }

    /**
     * Create strategy for specific SplitAxis enum value.
     * <p>
     * Maps SplitAxis values to corresponding strategy implementations:
     * - X → XAxisStrategy
     * - Y → YAxisStrategy
     * - Z → ZAxisStrategy
     * - LONGEST → LongestAxisStrategy
     * - DENSITY_WEIGHTED → LongestAxisStrategy (fallback, density not yet implemented)
     *
     * @param axis the split axis to use
     * @return strategy instance for the specified axis
     * @throws IllegalArgumentException if axis is null
     */
    public static SplitPlaneStrategy forAxis(SplitPlane.SplitAxis axis) {
        if (axis == null) {
            throw new IllegalArgumentException("axis must not be null");
        }

        return switch (axis) {
            case X -> xAxis();
            case Y -> yAxis();
            case Z -> zAxis();
            case LONGEST -> longestAxis();
            case DENSITY_WEIGHTED -> longestAxis(); // Fallback until density strategy implemented
        };
    }

    /**
     * Private constructor - this is a utility class.
     */
    private SplitPlaneStrategies() {
        throw new UnsupportedOperationException("Utility class");
    }
}
