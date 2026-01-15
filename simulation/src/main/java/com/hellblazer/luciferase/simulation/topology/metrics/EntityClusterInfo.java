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

import javax.vecmath.Point3f;
import java.util.Set;
import java.util.UUID;

/**
 * Information about an entity cluster detected via K-means clustering.
 * <p>
 * Used to determine if a bubble should move to follow entity migration
 * or if entities should be redistributed during splits.
 *
 * @param clusterId      unique identifier for this cluster
 * @param centroid       spatial center of the cluster
 * @param entityIds      entities in this cluster
 * @param coherence      cluster tightness (0.0-1.0, higher = tighter)
 * @param sourceResidence primary bubble currently containing these entities
 * @author hal.hildebrand
 */
public record EntityClusterInfo(
    UUID clusterId,
    Point3f centroid,
    Set<UUID> entityIds,
    float coherence,
    UUID sourceBubble
) {
    /**
     * Creates cluster information.
     *
     * @throws IllegalArgumentException if coherence not in [0.0, 1.0] or entityIds empty
     * @throws NullPointerException     if any parameter is null
     */
    public EntityClusterInfo {
        if (centroid == null) {
            throw new NullPointerException("Centroid cannot be null");
        }
        if (entityIds == null) {
            throw new NullPointerException("Entity IDs cannot be null");
        }
        if (entityIds.isEmpty()) {
            throw new IllegalArgumentException("Entity IDs cannot be empty");
        }
        if (coherence < 0.0f || coherence > 1.0f) {
            throw new IllegalArgumentException("Coherence must be in [0.0, 1.0]: " + coherence);
        }
    }

    /**
     * Gets the number of entities in this cluster.
     *
     * @return entity count
     */
    public int size() {
        return entityIds.size();
    }

    /**
     * Checks if this cluster is highly coherent (tight grouping).
     *
     * @return true if coherence > 0.7
     */
    public boolean isHighlyCoherent() {
        return coherence > 0.7f;
    }

    /**
     * Checks if this cluster suggests bubble should move.
     * Criteria: High coherence AND centroid far from bubble center
     *
     * @param bubbleCenter the current bubble center
     * @param threshold    distance threshold for "far" (bubble radius)
     * @return true if cluster suggests move
     */
    public boolean suggestsMove(Point3f bubbleCenter, float threshold) {
        if (!isHighlyCoherent()) {
            return false;
        }

        var dx = centroid.x - bubbleCenter.x;
        var dy = centroid.y - bubbleCenter.y;
        var dz = centroid.z - bubbleCenter.z;
        var distanceSquared = dx * dx + dy * dy + dz * dz;
        var thresholdSquared = threshold * threshold;

        return distanceSquared > thresholdSquared;
    }
}
