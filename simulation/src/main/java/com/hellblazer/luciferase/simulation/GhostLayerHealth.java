package com.hellblazer.luciferase.simulation;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VON NC (Neighbor Consistency) metric for ghost layer health monitoring.
 * <p>
 * Implements VON's neighbor consistency metric:
 * - NC = known_neighbors / actual_neighbors
 * - Perfect consistency: NC = 1.0 (all actual neighbors discovered)
 * - Degradation: NC < 0.9 (missing > 10% of neighbors)
 * - Partition risk: NC < 0.5 (minority partition indicator)
 * <p>
 * Ghost layer discovery via VON "boundary neighbors" pattern:
 * - When ghost arrives from bubble B, learn about bubble B
 * - No global bubble registry needed (distributed discovery)
 * - NC tracks completeness of lazy discovery
 * <p>
 * Usage:
 * <pre>
 * var health = new GhostLayerHealth();
 * health.setExpectedNeighbors(fireflies.getMembers().size());
 *
 * // On ghost received
 * health.recordGhostSource(ghost.sourceBubbleId());
 *
 * // Monitor health
 * if (health.isDegraded(0.9f)) {
 *     log.warn("Ghost layer degraded: NC={}", health.neighborConsistency());
 * }
 * </pre>
 *
 * @author hal.hildebrand
 */
public class GhostLayerHealth {

    /**
     * Default NC threshold for healthy operation (90% neighbor coverage).
     */
    public static final float DEFAULT_NC_THRESHOLD = 0.9f;

    /**
     * Default partition risk threshold (50% neighbor coverage).
     */
    public static final float DEFAULT_PARTITION_THRESHOLD = 0.5f;

    /**
     * Health snapshot for monitoring.
     *
     * @param knownNeighbors       Number of known ghost sources
     * @param expectedNeighbors    Expected number of neighbors (from membership)
     * @param missingNeighbors     Number of missing neighbors
     * @param neighborConsistency  NC metric value
     * @param isHealthy            Whether NC >= DEFAULT_NC_THRESHOLD
     */
    public record HealthSnapshot(
        int knownNeighbors,
        int expectedNeighbors,
        int missingNeighbors,
        float neighborConsistency,
        boolean isHealthy
    ) {
    }

    private final Set<UUID> knownSources;
    private final AtomicInteger expectedNeighbors;

    /**
     * Create a new ghost layer health monitor.
     */
    public GhostLayerHealth() {
        this.knownSources = ConcurrentHashMap.newKeySet();
        this.expectedNeighbors = new AtomicInteger(0);
    }

    /**
     * Set the expected number of neighbors.
     * <p>
     * Typically set from membership view: `fireflies.getMembers().size() - 1` (exclude self).
     *
     * @param count Expected neighbor count
     */
    public void setExpectedNeighbors(int count) {
        expectedNeighbors.set(count);
    }

    /**
     * Record a ghost source (bubble that sent a ghost entity).
     * <p>
     * Call when ghost arrives: `health.recordGhostSource(ghost.sourceBubbleId())`
     *
     * @param bubbleId Source bubble UUID
     */
    public void recordGhostSource(UUID bubbleId) {
        knownSources.add(bubbleId);
    }

    /**
     * Remove a ghost source (bubble no longer sending ghosts).
     * <p>
     * Call when neighbor leaves or ghost zone no longer overlaps.
     *
     * @param bubbleId Source bubble UUID to remove
     */
    public void removeGhostSource(UUID bubbleId) {
        knownSources.remove(bubbleId);
    }

    /**
     * Get the number of known ghost sources (discovered neighbors).
     *
     * @return Count of unique bubble IDs that have sent ghosts
     */
    public int getKnownNeighbors() {
        return knownSources.size();
    }

    /**
     * Get the expected number of neighbors.
     *
     * @return Expected neighbor count (from membership view)
     */
    public int getExpectedNeighbors() {
        return expectedNeighbors.get();
    }

    /**
     * Get the set of known ghost sources.
     *
     * @return Unmodifiable set of bubble UUIDs
     */
    public Set<UUID> getKnownSources() {
        return Collections.unmodifiableSet(knownSources);
    }

    /**
     * Calculate the NC (Neighbor Consistency) metric.
     * <p>
     * NC = known_neighbors / expected_neighbors
     * <p>
     * Values:
     * - 1.0: Perfect consistency (all neighbors discovered)
     * - 0.9: Healthy threshold (90% coverage)
     * - 0.5: Partition risk threshold
     * - 0.0: No neighbors discovered
     * <p>
     * Edge case: If expected_neighbors = 0, returns 1.0 (no neighbors expected).
     *
     * @return NC value in range [0.0, 1.0]
     */
    public float neighborConsistency() {
        int expected = expectedNeighbors.get();
        if (expected == 0) {
            return 1.0f;  // Edge case: no neighbors expected
        }

        int known = knownSources.size();

        // Clamp to 1.0 if more neighbors discovered than expected
        return Math.min(1.0f, known / (float) expected);
    }

    /**
     * Get the number of missing expected neighbors.
     *
     * @return Count of neighbors not yet discovered (max 0)
     */
    public int missingExpectedGhosts() {
        return Math.max(0, expectedNeighbors.get() - knownSources.size());
    }

    /**
     * Check if ghost layer is degraded (NC below threshold).
     *
     * @param threshold NC threshold (typically 0.9)
     * @return true if NC < threshold
     */
    public boolean isDegraded(float threshold) {
        return neighborConsistency() < threshold;
    }

    /**
     * Check if partition risk detected (NC below threshold).
     * <p>
     * NC < 0.5 indicates minority partition (missing > 50% of neighbors).
     *
     * @param threshold Partition risk threshold (typically 0.5)
     * @return true if NC < threshold
     */
    public boolean isPartitionRisk(float threshold) {
        return neighborConsistency() < threshold;
    }

    /**
     * Get a health snapshot for monitoring/metrics.
     *
     * @return HealthSnapshot with current metrics
     */
    public HealthSnapshot getHealthSnapshot() {
        int known = knownSources.size();
        int expected = expectedNeighbors.get();
        int missing = Math.max(0, expected - known);
        float nc = neighborConsistency();
        boolean healthy = nc >= DEFAULT_NC_THRESHOLD;

        return new HealthSnapshot(known, expected, missing, nc, healthy);
    }

    /**
     * Reset all state (for testing or reinit).
     */
    public void reset() {
        knownSources.clear();
        expectedNeighbors.set(0);
    }

    @Override
    public String toString() {
        return String.format("GhostLayerHealth{NC=%.2f, known=%d, expected=%d}",
                            neighborConsistency(), knownSources.size(), expectedNeighbors.get());
    }
}
