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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Split plane strategy that cycles through axes in order: X → Y → Z → X.
 * <p>
 * Maintains internal state to track which axis to use next. Each call
 * to calculate() returns the next axis in the cycle. Thread-safe using
 * AtomicInteger for the counter.
 * <p>
 * Use cases:
 * - Balanced spatial subdivision across all dimensions
 * - Preventing directional bias in recursive splits
 * - Testing different split patterns
 * <p>
 * Example sequence:
 * 1. First split: X-axis
 * 2. Second split: Y-axis
 * 3. Third split: Z-axis
 * 4. Fourth split: X-axis (cycle repeats)
 */
public class CyclicAxisStrategy implements SplitPlaneStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public SplitPlane calculate(com.hellblazer.luciferase.simulation.bubble.BubbleBounds bubbleBounds,
                                List<EnhancedBubble.EntityRecord> entities) {
        var centroid = bubbleBounds.centroid();

        // Get next axis in cycle (0 = X, 1 = Y, 2 = Z)
        var axisIndex = counter.getAndIncrement() % 3;

        return switch (axisIndex) {
            case 0 -> SplitPlane.xAxis((float) centroid.getX());
            case 1 -> SplitPlane.yAxis((float) centroid.getY());
            case 2 -> SplitPlane.zAxis((float) centroid.getZ());
            default -> throw new IllegalStateException("Unexpected axis index: " + axisIndex);
        };
    }
}
