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

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;

import java.util.List;

/**
 * Strategy for calculating split plane for bubble subdivision.
 * <p>
 * Enables pluggable split plane calculation algorithms for bubble splitting.
 * Implementations can use different heuristics:
 * - Longest axis through centroid (default)
 * - Fixed axis (X, Y, or Z)
 * - Cyclic rotation through axes
 * - Density-weighted optimization
 * <p>
 * This is a functional interface - can be used with lambdas.
 *
 * @see LongestAxisStrategy
 * @see XAxisStrategy
 * @see YAxisStrategy
 * @see ZAxisStrategy
 * @see CyclicAxisStrategy
 */
@FunctionalInterface
public interface SplitPlaneStrategy {

    /**
     * Calculate the optimal split plane for dividing a bubble.
     * <p>
     * Implementations should:
     * - Analyze bubble bounds to determine split plane
     * - Place plane through centroid for balanced split
     * - Consider entity distribution if provided (for density optimization)
     * - Return plane with correct axis annotation
     *
     * @param bubbleBounds bounds of the bubble to split
     * @param entities     entities within the bubble (for density optimization, may be empty)
     * @return the calculated split plane
     */
    SplitPlane calculate(com.hellblazer.luciferase.simulation.bubble.BubbleBounds bubbleBounds,
                         List<EnhancedBubble.EntityRecord> entities);
}
