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
package com.hellblazer.luciferase.simulation.topology.metrics;

import java.util.UUID;

/**
 * Metrics for a single bubble used in topology adaptation decisions.
 * <p>
 * Captures density (entities/bubble), load (frame utilization), and
 * boundary stress (migration pressure) for detecting when topology
 * changes are needed.
 *
 * @param bubbleId        the bubble identifier
 * @param entityCount     number of entities in this bubble
 * @param density         entities per unit volume (entityCount / bounds volume)
 * @param frameUtilization frame budget utilization (0.0-1.0+, >1.0 = overloaded)
 * @param boundaryStress  migration rate at boundaries (migrations/second)
 * @param timestamp       when these metrics were collected (milliseconds)
 * @author hal.hildebrand
 */
public record BubbleTopologyMetrics(
    UUID bubbleId,
    int entityCount,
    float density,
    float frameUtilization,
    float boundaryStress,
    long timestamp
) {
    /**
     * Creates metrics for a bubble.
     *
     * @throws IllegalArgumentException if entityCount negative, density/frameUtilization/boundaryStress negative
     */
    public BubbleTopologyMetrics {
        if (entityCount < 0) {
            throw new IllegalArgumentException("Entity count cannot be negative: " + entityCount);
        }
        if (density < 0.0f) {
            throw new IllegalArgumentException("Density cannot be negative: " + density);
        }
        if (frameUtilization < 0.0f) {
            throw new IllegalArgumentException("Frame utilization cannot be negative: " + frameUtilization);
        }
        if (boundaryStress < 0.0f) {
            throw new IllegalArgumentException("Boundary stress cannot be negative: " + boundaryStress);
        }
    }

    /**
     * Checks if this bubble is approaching split threshold.
     * Split threshold: >5000 entities (120% frame utilization)
     *
     * @return true if entity count > 4500 (90% of split threshold)
     */
    public boolean isApproachingSplit() {
        return entityCount > 4500; // 90% of 5000 threshold
    }

    /**
     * Checks if this bubble needs splitting.
     * Split threshold: >5000 entities
     *
     * @return true if entity count > 5000
     */
    public boolean needsSplit() {
        return entityCount > 5000;
    }

    /**
     * Checks if this bubble is approaching merge threshold.
     * Merge threshold: <500 entities (60% affinity)
     *
     * @return true if entity count < 550 (110% of merge threshold)
     */
    public boolean isApproachingMerge() {
        return entityCount < 550; // 110% of 500 threshold
    }

    /**
     * Checks if this bubble needs merging.
     * Merge threshold: <500 entities
     *
     * @return true if entity count < 500
     */
    public boolean needsMerge() {
        return entityCount < 500;
    }

    /**
     * Checks if boundary stress is high (might need move).
     *
     * @return true if boundary stress > 10 migrations/second
     */
    public boolean hasHighBoundaryStress() {
        return boundaryStress > 10.0f;
    }
}
