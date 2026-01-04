package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.Map;

/**
 * Statistics snapshot for SpatialTumbler performance and state metrics.
 * <p>
 * Phase 5: Fully implemented for load balancing integration.
 * Provides metrics for:
 * - Region distribution (count by level, entity distribution)
 * - Load metrics (average/max/min entities per region)
 * - Split/join operation history
 * - Performance metrics (operation times, throughput)
 * <p>
 * Thread-safe immutable snapshot.
 *
 * @author hal.hildebrand
 */
public record TumblerStatistics(
    // Region distribution
    int totalRegions,
    Map<Byte, Integer> regionsByLevel,      // Level -> count
    Map<TetreeKey<?>, Integer> entityCounts, // Region -> entity count

    // Load metrics
    int totalEntities,
    float averageEntitiesPerRegion,
    int maxEntitiesPerRegion,
    int minEntitiesPerRegion,
    TetreeKey<?> mostLoadedRegion,
    TetreeKey<?> leastLoadedRegion,

    // Operation history
    long totalSplits,
    long totalJoins,
    long splitsSinceLastSnapshot,
    long joinsSinceLastSnapshot,

    // Performance metrics
    long averageSplitTimeNanos,
    long averageJoinTimeNanos,
    float splitThroughput,     // Splits per second
    float joinThroughput,      // Joins per second

    // Boundary metrics
    int totalBoundaryZones,
    int totalBoundaryEntities,

    // Timestamp
    long snapshotTimeMillis
) {

    /**
     * Calculate load imbalance ratio.
     * <p>
     * Ratio of max load to average load. Perfect balance = 1.0.
     * Higher values indicate imbalance.
     *
     * @return Load imbalance ratio
     */
    public float loadImbalanceRatio() {
        if (averageEntitiesPerRegion == 0.0f) {
            return 1.0f;
        }
        return maxEntitiesPerRegion / averageEntitiesPerRegion;
    }

    /**
     * Calculate load variance.
     * <p>
     * Normalized variance: (max - min) / average.
     * Lower is better (0.0 = perfect balance).
     *
     * @return Load variance
     */
    public float loadVariance() {
        if (averageEntitiesPerRegion == 0.0f) {
            return 0.0f;
        }
        return (maxEntitiesPerRegion - minEntitiesPerRegion) / averageEntitiesPerRegion;
    }

    /**
     * Check if split/join activity is high.
     *
     * @param threshold Activity threshold (operations per second)
     * @return true if high activity
     */
    public boolean isHighActivity(float threshold) {
        return (splitThroughput + joinThroughput) > threshold;
    }

    /**
     * Get region at specific level.
     *
     * @param level Tetree level
     * @return Number of regions at level
     */
    public int getRegionsAtLevel(byte level) {
        return regionsByLevel.getOrDefault(level, 0);
    }

    /**
     * Get entity count for region.
     *
     * @param regionKey Region key
     * @return Number of entities in region
     */
    public int getEntityCount(TetreeKey<?> regionKey) {
        return entityCounts.getOrDefault(regionKey, 0);
    }

    /**
     * Create empty statistics snapshot.
     *
     * @return Empty snapshot
     */
    public static TumblerStatistics empty() {
        return new TumblerStatistics(
            0, Map.of(), Map.of(),
            0, 0.0f, 0, 0, null, null,
            0L, 0L, 0L, 0L,
            0L, 0L, 0.0f, 0.0f,
            0, 0,
            System.currentTimeMillis()
        );
    }

    @Override
    public String toString() {
        return String.format(
            "TumblerStatistics{regions=%d, entities=%d, avg=%.1f, max=%d, min=%d, " +
            "splits=%d, joins=%d, imbalance=%.2f}",
            totalRegions, totalEntities, averageEntitiesPerRegion,
            maxEntitiesPerRegion, minEntitiesPerRegion,
            totalSplits, totalJoins, loadImbalanceRatio()
        );
    }
}
