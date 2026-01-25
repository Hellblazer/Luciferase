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
        // Compute entity centroid if entities exist
        float cx, cy, cz;
        if (entities.isEmpty()) {
            var centroid = bubbleBounds.centroid();
            cx = (float) centroid.getX();
            cy = (float) centroid.getY();
            cz = (float) centroid.getZ();
        } else {
            float sumX = 0.0f, sumY = 0.0f, sumZ = 0.0f;
            for (var entity : entities) {
                var pos = entity.position();
                sumX += pos.x;
                sumY += pos.y;
                sumZ += pos.z;
            }
            int count = entities.size();
            cx = sumX / count;
            cy = sumY / count;
            cz = sumZ / count;
        }

        // Get next axis in cycle (0 = X, 1 = Y, 2 = Z)
        var axisIndex = counter.getAndIncrement() % 3;

        return switch (axisIndex) {
            case 0 -> SplitPlane.xAxis(cx);
            case 1 -> SplitPlane.yAxis(cy);
            case 2 -> SplitPlane.zAxis(cz);
            default -> throw new IllegalStateException("Unexpected axis index: " + axisIndex);
        };
    }
}
