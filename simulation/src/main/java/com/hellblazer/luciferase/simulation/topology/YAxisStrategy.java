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
 * Split plane strategy that always uses Y-axis.
 * <p>
 * Creates a plane perpendicular to the Y-axis passing through
 * the bubble's centroid, regardless of bubble dimensions.
 * <p>
 * Use cases:
 * - Fixed spatial partitioning schemes
 * - Testing and debugging
 * - Scenarios where Y-axis splits are preferred
 */
public class YAxisStrategy implements SplitPlaneStrategy {

    @Override
    public SplitPlane calculate(com.hellblazer.luciferase.simulation.bubble.BubbleBounds bubbleBounds,
                                List<EnhancedBubble.EntityRecord> entities) {
        var centroid = bubbleBounds.centroid();
        return SplitPlane.yAxis((float) centroid.getY());
    }
}
