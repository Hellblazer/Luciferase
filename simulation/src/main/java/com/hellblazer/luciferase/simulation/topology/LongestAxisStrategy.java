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
 * Split plane strategy that selects the longest axis of bubble bounds.
 * <p>
 * This is the default strategy for bubble splitting. It analyzes the
 * bubble's bounds in Cartesian space, identifies the longest dimension
 * (X, Y, or Z), and creates a plane perpendicular to that axis passing
 * through the centroid.
 * <p>
 * Benefits:
 * - Maximizes split effectiveness (largest dimension divided)
 * - Balanced subdivision (plane through centroid)
 * - Adaptive to bubble shape
 * <p>
 * Example:
 * - Bubble bounds: 100m (X) × 20m (Y) × 20m (Z)
 * - Selected plane: X-axis aligned through centroid
 */
public class LongestAxisStrategy implements SplitPlaneStrategy {

    @Override
    public SplitPlane calculate(com.hellblazer.luciferase.simulation.bubble.BubbleBounds bubbleBounds,
                                List<EnhancedBubble.EntityRecord> entities) {
        // Delegate to SplitPlane factory method
        return SplitPlane.alongLongestAxis(bubbleBounds);
    }
}
