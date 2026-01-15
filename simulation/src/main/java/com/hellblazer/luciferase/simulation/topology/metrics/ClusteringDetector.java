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

import com.hellblazer.luciferase.simulation.bubble.AdaptiveSplitPolicy;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Detects entity clusters within bubbles using K-means clustering.
 * <p>
 * Wraps {@link AdaptiveSplitPolicy} K-means clustering and converts
 * results to {@link EntityClusterInfo} for topology adaptation decisions.
 * <p>
 * <b>Algorithm</b>:
 * <ul>
 *   <li>Use AdaptiveSplitPolicy.detectClusters() for K-means (k=2)</li>
 *   <li>Convert javafx.geometry.Point3D to javax.vecmath.Point3f</li>
 *   <li>Calculate coherence based on intra-cluster distance</li>
 *   <li>Filter clusters below minimum size threshold</li>
 * </ul>
 * <p>
 * <b>Coherence Calculation</b>:
 * <ul>
 *   <li>Measures cluster tightness (0.0-1.0)</li>
 *   <li>High coherence (>0.7) indicates entities move together</li>
 *   <li>Low coherence (<0.3) indicates dispersed entities</li>
 *   <li>Formula: 1.0 - (avgDistanceFromCentroid / maxDistance)</li>
 * </ul>
 * <p>
 * Thread-safe: Delegates to thread-safe AdaptiveSplitPolicy.
 *
 * @author hal.hildebrand
 */
public class ClusteringDetector {

    private final AdaptiveSplitPolicy splitPolicy;
    private final int                 minClusterSize;
    private final float               maxDistance;

    /**
     * Creates a clustering detector with specified parameters.
     *
     * @param minClusterSize minimum entities per cluster
     * @param maxDistance    maximum intra-cluster distance
     * @throws IllegalArgumentException if parameters invalid
     */
    public ClusteringDetector(int minClusterSize, float maxDistance) {
        if (minClusterSize <= 0) {
            throw new IllegalArgumentException("Min cluster size must be positive: " + minClusterSize);
        }
        if (maxDistance <= 0.0f) {
            throw new IllegalArgumentException("Max distance must be positive: " + maxDistance);
        }

        this.minClusterSize = minClusterSize;
        this.maxDistance = maxDistance;
        // Create split policy with threshold=1.2 (120% frame utilization)
        this.splitPolicy = new AdaptiveSplitPolicy(1.2f, minClusterSize);
    }

    /**
     * Detects entity clusters within a bubble.
     * <p>
     * Returns empty list if:
     * <ul>
     *   <li>Bubble has too few entities (< minClusterSize * 2)</li>
     *   <li>Entities are uniformly distributed (no clear clusters)</li>
     *   <li>No clusters meet minimum size requirement</li>
     * </ul>
     *
     * @param bubble the bubble to analyze
     * @return list of detected clusters (empty if none found)
     */
    public List<EntityClusterInfo> detectClusters(EnhancedBubble bubble) {
        // Delegate to AdaptiveSplitPolicy for K-means clustering
        var rawClusters = splitPolicy.detectClusters(bubble, minClusterSize, maxDistance);

        if (rawClusters.isEmpty()) {
            return List.of();
        }

        // Convert AdaptiveSplitPolicy.EntityCluster to EntityClusterInfo
        var clusters = new ArrayList<EntityClusterInfo>();
        for (var raw : rawClusters) {
            // Convert Point3D to Point3f
            var centroid = new Point3f(
                (float) raw.centroid().getX(),
                (float) raw.centroid().getY(),
                (float) raw.centroid().getZ()
            );

            // Convert String entity IDs to UUIDs
            var entityIds = new HashSet<UUID>();
            for (var idStr : raw.entityIds()) {
                try {
                    // Extract UUID from entity ID (might be "entity-123" or just UUID)
                    entityIds.add(parseEntityId(idStr));
                } catch (IllegalArgumentException e) {
                    // Skip malformed IDs
                }
            }

            if (entityIds.isEmpty()) {
                continue; // Skip cluster with no valid entity IDs
            }

            // Calculate coherence from cluster tightness
            float coherence = calculateCoherence(bubble, centroid, entityIds, maxDistance);

            // Create EntityClusterInfo
            var clusterInfo = new EntityClusterInfo(
                UUID.randomUUID(), // Generate cluster ID
                centroid,
                entityIds,
                coherence,
                bubble.id() // Source bubble
            );

            clusters.add(clusterInfo);
        }

        return clusters;
    }

    /**
     * Parses entity ID from string.
     * Handles both UUID strings and "entity-{uuid}" format.
     *
     * @param idStr the entity ID string
     * @return parsed UUID
     * @throws IllegalArgumentException if ID cannot be parsed
     */
    private UUID parseEntityId(String idStr) {
        // Try direct UUID parse
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            // Try extracting UUID from "entity-{uuid}" format
            if (idStr.contains("-")) {
                var parts = idStr.split("-", 2);
                if (parts.length == 2) {
                    try {
                        return UUID.fromString(parts[1]);
                    } catch (IllegalArgumentException e2) {
                        // Fall through to exception
                    }
                }
            }
            // If all parsing fails, generate UUID from hash
            return UUID.nameUUIDFromBytes(idStr.getBytes());
        }
    }

    /**
     * Calculates cluster coherence (tightness) from entity positions.
     * <p>
     * Formula: 1.0 - (avgDistanceFromCentroid / maxDistance)
     * <p>
     * Returns:
     * <ul>
     *   <li>1.0: Perfect coherence (all entities at centroid)</li>
     *   <li>0.7+: High coherence (tight cluster)</li>
     *   <li>0.3-0.7: Medium coherence</li>
     *   <li>0.0-0.3: Low coherence (dispersed)</li>
     * </ul>
     *
     * @param bubble      the bubble containing entities
     * @param centroid    cluster centroid
     * @param entityIds   entity IDs in cluster
     * @param maxDistance maximum distance threshold
     * @return coherence value (0.0-1.0)
     */
    private float calculateCoherence(EnhancedBubble bubble, Point3f centroid,
                                     HashSet<UUID> entityIds, float maxDistance) {
        if (entityIds.isEmpty()) {
            return 0.0f;
        }

        // Get all entity records from bubble
        var allRecords = bubble.getAllEntityRecords();
        var clusterPositions = new ArrayList<Point3f>();

        // Find positions of entities in this cluster
        for (var record : allRecords) {
            try {
                var entityUUID = parseEntityId(record.id());
                if (entityIds.contains(entityUUID)) {
                    clusterPositions.add(record.position());
                }
            } catch (IllegalArgumentException e) {
                // Skip entities with invalid IDs
            }
        }

        if (clusterPositions.isEmpty()) {
            return 0.0f;
        }

        // Calculate average distance from centroid
        float totalDistance = 0.0f;
        for (var pos : clusterPositions) {
            var dx = pos.x - centroid.x;
            var dy = pos.y - centroid.y;
            var dz = pos.z - centroid.z;
            var distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            totalDistance += distance;
        }

        float avgDistance = totalDistance / clusterPositions.size();

        // Coherence: 1.0 - (avgDistance / maxDistance)
        // Clamp to [0.0, 1.0]
        float coherence = 1.0f - (avgDistance / maxDistance);
        return Math.max(0.0f, Math.min(1.0f, coherence));
    }

    /**
     * Gets the minimum cluster size threshold.
     *
     * @return minimum entities per cluster
     */
    public int getMinClusterSize() {
        return minClusterSize;
    }

    /**
     * Gets the maximum intra-cluster distance threshold.
     *
     * @return maximum distance
     */
    public float getMaxDistance() {
        return maxDistance;
    }
}
