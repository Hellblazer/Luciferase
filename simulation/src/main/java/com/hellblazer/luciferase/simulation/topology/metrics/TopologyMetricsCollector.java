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

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates topology metrics collection from multiple monitoring components.
 * <p>
 * Aggregates data from:
 * <ul>
 *   <li>{@link DensityMonitor} - Split/merge threshold detection</li>
 *   <li>{@link ClusteringDetector} - Entity clustering analysis</li>
 *   <li>{@link BoundaryStressAnalyzer} - Migration pressure tracking</li>
 * </ul>
 * <p>
 * Creates {@link TopologyMetricsSnapshot} combining all metrics for topology
 * adaptation decisions. Designed for periodic collection (e.g., every 5 seconds)
 * with minimal overhead (<10ms per collection).
 * <p>
 * <b>Collection Flow</b>:
 * <ol>
 *   <li>Get entity distribution from EntityAccountant</li>
 *   <li>Update DensityMonitor with entity counts</li>
 *   <li>Collect per-bubble metrics (density, frame utilization, boundary stress)</li>
 *   <li>Run ClusteringDetector on bubbles needing analysis</li>
 *   <li>Aggregate into TopologyMetricsSnapshot</li>
 * </ol>
 * <p>
 * Thread-safe: All component classes use concurrent data structures.
 *
 * @author hal.hildebrand
 */
public class TopologyMetricsCollector {

    private final DensityMonitor         densityMonitor;
    private final ClusteringDetector     clusteringDetector;
    private final BoundaryStressAnalyzer boundaryStressAnalyzer;
    private volatile Clock               clock;

    /**
     * Creates a topology metrics collector with specified configuration.
     *
     * @param splitThreshold          entity count above which split is recommended
     * @param mergeThreshold          entity count below which merge is recommended
     * @param minClusterSize          minimum entities per cluster for clustering detection
     * @param maxClusterDistance      maximum intra-cluster distance
     * @param boundaryStressWindowMs  sliding window for boundary stress tracking (milliseconds)
     * @throws IllegalArgumentException if any threshold invalid
     */
    public TopologyMetricsCollector(int splitThreshold, int mergeThreshold,
                                    int minClusterSize, float maxClusterDistance,
                                    long boundaryStressWindowMs) {
        this.densityMonitor = new DensityMonitor(splitThreshold, mergeThreshold);
        this.clusteringDetector = new ClusteringDetector(minClusterSize, maxClusterDistance);
        this.boundaryStressAnalyzer = new BoundaryStressAnalyzer(boundaryStressWindowMs);
        this.clock = Clock.system();
    }

    /**
     * Sets the clock for deterministic simulation time.
     * <p>
     * IMPORTANT: Use this for PrimeMover integration. The clock should be
     * injected to ensure simulated time is used instead of wall-clock time.
     * Also propagates clock to BoundaryStressAnalyzer.
     *
     * @param clock the clock implementation
     */
    public void setClock(Clock clock) {
        this.clock = clock;
        this.boundaryStressAnalyzer.setClock(clock);
    }

    /**
     * Collects topology metrics from bubble grid and entity accountant.
     * <p>
     * This method should be called periodically (e.g., every 5 seconds) to
     * update topology metrics for adaptation decisions.
     *
     * @param bubbleGrid      the bubble grid to analyze
     * @param entityAccountant the entity accountant for distribution data
     * @return complete topology metrics snapshot
     * @throws NullPointerException if any parameter is null
     */
    public TopologyMetricsSnapshot collect(TetreeBubbleGrid bubbleGrid, EntityAccountant entityAccountant) {
        if (bubbleGrid == null) {
            throw new NullPointerException("Bubble grid cannot be null");
        }
        if (entityAccountant == null) {
            throw new NullPointerException("Entity accountant cannot be null");
        }

        long timestamp = clock.currentTimeMillis();

        // Step 1: Get entity distribution
        var distribution = entityAccountant.getDistribution();

        // Step 2: Update density monitor
        densityMonitor.update(distribution);

        // Step 3: Collect per-bubble metrics
        var bubbleMetrics = new HashMap<UUID, BubbleTopologyMetrics>();
        int totalEntities = 0;
        float totalDensity = 0.0f;

        for (var bubble : bubbleGrid.getAllBubbles()) {
            var bubbleId = bubble.id();
            int entityCount = distribution.getOrDefault(bubbleId, 0);
            totalEntities += entityCount;

            // Calculate density (entities per unit volume)
            // For simplicity, assume unit volume per bubble (can be refined with actual bounds)
            float density = entityCount; // Simplified: density = entity count

            // Get frame utilization from bubble
            float frameUtilization = bubble.frameUtilization();

            // Get boundary stress
            float boundaryStress = boundaryStressAnalyzer.getMigrationRate(bubbleId);

            // Create bubble metrics
            var metrics = new BubbleTopologyMetrics(
                bubbleId,
                entityCount,
                density,
                frameUtilization,
                boundaryStress,
                timestamp
            );

            bubbleMetrics.put(bubbleId, metrics);
            totalDensity += density;
        }

        // Step 4: Calculate average density
        float averageDensity = bubbleMetrics.isEmpty() ? 0.0f : totalDensity / bubbleMetrics.size();

        // Step 5: Run clustering detection on bubbles that might benefit
        var allClusters = new ArrayList<EntityClusterInfo>();
        for (var bubble : bubbleGrid.getAllBubbles()) {
            var metrics = bubbleMetrics.get(bubble.id());
            if (metrics != null && shouldAnalyzeClustering(metrics)) {
                var clusters = clusteringDetector.detectClusters(bubble);
                allClusters.addAll(clusters);
            }
        }

        // Step 6: Create and return snapshot
        return new TopologyMetricsSnapshot(
            timestamp,
            bubbleMetrics,
            allClusters,
            totalEntities,
            averageDensity
        );
    }

    /**
     * Determines if a bubble should be analyzed for clustering.
     * <p>
     * Clustering analysis is expensive, so we only run it on bubbles that:
     * <ul>
     *   <li>Are approaching or exceeding split threshold</li>
     *   <li>Have high boundary stress</li>
     * </ul>
     *
     * @param metrics bubble metrics
     * @return true if clustering analysis should be performed
     */
    private boolean shouldAnalyzeClustering(BubbleTopologyMetrics metrics) {
        return metrics.isApproachingSplit()
               || metrics.needsSplit()
               || metrics.hasHighBoundaryStress();
    }

    /**
     * Records a migration event for boundary stress tracking.
     * <p>
     * Call this method when an entity crosses a bubble boundary to track
     * migration pressure for topology adaptation decisions.
     *
     * @param bubbleId  the bubble identifier
     * @param timestamp migration timestamp (milliseconds)
     */
    public void recordMigration(UUID bubbleId, long timestamp) {
        boundaryStressAnalyzer.recordMigration(bubbleId, timestamp);
    }

    /**
     * Records a migration event with current timestamp.
     * <p>
     * Convenience method that uses injected Clock for timestamp.
     *
     * @param bubbleId the bubble identifier
     */
    public void recordMigration(UUID bubbleId) {
        recordMigration(bubbleId, clock.currentTimeMillis());
    }

    /**
     * Gets the density monitor for direct access.
     *
     * @return density monitor instance
     */
    public DensityMonitor getDensityMonitor() {
        return densityMonitor;
    }

    /**
     * Gets the clustering detector for direct access.
     *
     * @return clustering detector instance
     */
    public ClusteringDetector getClusteringDetector() {
        return clusteringDetector;
    }

    /**
     * Gets the boundary stress analyzer for direct access.
     *
     * @return boundary stress analyzer instance
     */
    public BoundaryStressAnalyzer getBoundaryStressAnalyzer() {
        return boundaryStressAnalyzer;
    }

    /**
     * Resets all tracking state.
     * <p>
     * Used for testing and cleanup.
     */
    public void reset() {
        densityMonitor.reset();
        boundaryStressAnalyzer.reset();
    }
}
