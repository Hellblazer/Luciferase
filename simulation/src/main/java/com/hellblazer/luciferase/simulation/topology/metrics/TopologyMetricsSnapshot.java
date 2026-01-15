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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Complete snapshot of topology metrics at a point in time.
 * <p>
 * Aggregates per-bubble metrics, cluster detection results, and boundary
 * stress analysis for topology adaptation decisions.
 *
 * @param timestamp      when this snapshot was collected (milliseconds)
 * @param bubbleMetrics  per-bubble topology metrics
 * @param clusters       detected entity clusters
 * @param totalEntities  total entity count across all bubbles
 * @param averageDensity average entity density across bubbles
 * @author hal.hildebrand
 */
public record TopologyMetricsSnapshot(
    long timestamp,
    Map<UUID, BubbleTopologyMetrics> bubbleMetrics,
    List<EntityClusterInfo> clusters,
    int totalEntities,
    float averageDensity
) {
    /**
     * Creates a metrics snapshot.
     *
     * @throws IllegalArgumentException if totalEntities negative or averageDensity negative
     * @throws NullPointerException     if bubbleMetrics or clusters is null
     */
    public TopologyMetricsSnapshot {
        if (bubbleMetrics == null) {
            throw new NullPointerException("Bubble metrics cannot be null");
        }
        if (clusters == null) {
            throw new NullPointerException("Clusters cannot be null");
        }
        if (totalEntities < 0) {
            throw new IllegalArgumentException("Total entities cannot be negative: " + totalEntities);
        }
        if (averageDensity < 0.0f) {
            throw new IllegalArgumentException("Average density cannot be negative: " + averageDensity);
        }
    }

    /**
     * Gets the number of bubbles in this snapshot.
     *
     * @return bubble count
     */
    public int bubbleCount() {
        return bubbleMetrics.size();
    }

    /**
     * Gets the number of detected clusters.
     *
     * @return cluster count
     */
    public int clusterCount() {
        return clusters.size();
    }

    /**
     * Finds all bubbles needing splits.
     *
     * @return list of bubble IDs that exceed split threshold
     */
    public List<UUID> bubblesNeedingSplit() {
        return bubbleMetrics.values()
                            .stream()
                            .filter(BubbleTopologyMetrics::needsSplit)
                            .map(BubbleTopologyMetrics::bubbleId)
                            .toList();
    }

    /**
     * Finds all bubbles needing merges.
     *
     * @return list of bubble IDs below merge threshold
     */
    public List<UUID> bubblesNeedingMerge() {
        return bubbleMetrics.values()
                            .stream()
                            .filter(BubbleTopologyMetrics::needsMerge)
                            .map(BubbleTopologyMetrics::bubbleId)
                            .toList();
    }

    /**
     * Finds all bubbles with high boundary stress.
     *
     * @return list of bubble IDs with high migration pressure
     */
    public List<UUID> bubblesWithHighStress() {
        return bubbleMetrics.values()
                            .stream()
                            .filter(BubbleTopologyMetrics::hasHighBoundaryStress)
                            .map(BubbleTopologyMetrics::bubbleId)
                            .toList();
    }

    /**
     * Checks if any topology action is recommended.
     *
     * @return true if any bubble needs split/merge or has high stress
     */
    public boolean needsTopologyAction() {
        return !bubblesNeedingSplit().isEmpty()
               || !bubblesNeedingMerge().isEmpty()
               || !bubblesWithHighStress().isEmpty();
    }
}
