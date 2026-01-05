package com.hellblazer.luciferase.simulation.metrics;

import com.hellblazer.luciferase.simulation.bubble.*;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central metrics aggregation for observability.
 * <p>
 * Collects metrics from all simulation components:
 * - Animator utilization (CPU % = frameTime / targetTime)
 * - VON neighbor count per bubble
 * - Ghost sync latency
 * <p>
 * Thread-safe for concurrent access from multiple components.
 * Uses ConcurrentHashMap for per-bubble metrics and AtomicReference for ghost latency.
 * <p>
 * Usage:
 * <pre>
 * var metrics = new ObservabilityMetrics();
 *
 * // Record frame completion
 * metrics.recordAnimatorFrame(bubbleId, frameTimeNs, targetTimeNs);
 *
 * // Record neighbor count
 * metrics.recordNeighborCount(bubbleId, vonNode.neighbors().size());
 *
 * // Record ghost latency (integration with Task 2)
 * metrics.recordGhostLatency(latencyNs);
 *
 * // Get aggregated snapshot
 * var snapshot = metrics.getSnapshot();
 * System.out.printf("Avg utilization: %.1f%%, Total neighbors: %d%n",
 *                  snapshot.avgAnimatorUtilization() * 100, snapshot.totalVonNeighbors());
 * </pre>
 *
 * @author hal.hildebrand
 */
public class ObservabilityMetrics {

    /**
     * Per-bubble frame metrics.
     */
    private static class FrameMetrics {
        volatile float utilization;

        FrameMetrics(float utilization) {
            this.utilization = utilization;
        }
    }

    /**
     * Per-bubble neighbor metrics.
     */
    private static class NeighborMetrics {
        volatile int count;

        NeighborMetrics(int count) {
            this.count = count;
        }
    }

    private final ConcurrentHashMap<UUID, FrameMetrics> frameMetrics;
    private final ConcurrentHashMap<UUID, NeighborMetrics> neighborMetrics;
    private final AtomicReference<Long> ghostLatencyNs;

    /**
     * Create a new ObservabilityMetrics instance.
     */
    public ObservabilityMetrics() {
        this.frameMetrics = new ConcurrentHashMap<>();
        this.neighborMetrics = new ConcurrentHashMap<>();
        this.ghostLatencyNs = new AtomicReference<>(null);
    }

    /**
     * Record a completed animator frame for a bubble.
     * <p>
     * Calculates utilization as: frameTimeNs / targetTimeNs
     * - 1.0 = 100% utilization (on target)
     * - 0.8 = 80% utilization (underutilized)
     * - 1.5 = 150% utilization (over budget)
     *
     * @param bubbleId     Bubble identifier
     * @param frameTimeNs  Actual frame time in nanoseconds
     * @param targetTimeNs Target frame time in nanoseconds
     */
    public void recordAnimatorFrame(UUID bubbleId, long frameTimeNs, long targetTimeNs) {
        if (targetTimeNs <= 0) {
            throw new IllegalArgumentException("targetTimeNs must be positive: " + targetTimeNs);
        }

        var utilization = (float) frameTimeNs / targetTimeNs;
        frameMetrics.compute(bubbleId, (k, existing) -> {
            if (existing == null) {
                return new FrameMetrics(utilization);
            }
            existing.utilization = utilization;
            return existing;
        });
    }

    /**
     * Record the current VON neighbor count for a bubble.
     * <p>
     * Replaces any previous count for this bubble.
     *
     * @param bubbleId Bubble identifier
     * @param count    Number of VON neighbors
     */
    public void recordNeighborCount(UUID bubbleId, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative: " + count);
        }

        neighborMetrics.compute(bubbleId, (k, existing) -> {
            if (existing == null) {
                return new NeighborMetrics(count);
            }
            existing.count = count;
            return existing;
        });
    }

    /**
     * Record ghost sync latency.
     * <p>
     * Stores the latest latency sample. Integration with Task 2 (GhostSyncLatency)
     * will add averaging and percentile tracking.
     *
     * @param latencyNs Latency in nanoseconds
     */
    public void recordGhostLatency(long latencyNs) {
        if (latencyNs < 0) {
            throw new IllegalArgumentException("latencyNs cannot be negative: " + latencyNs);
        }

        ghostLatencyNs.set(latencyNs);
    }

    /**
     * Get a snapshot of current metrics.
     * <p>
     * Aggregates:
     * - avgAnimatorUtilization: Average across all bubbles with frame metrics
     * - totalVonNeighbors: Sum of all neighbor counts
     * - ghostSyncLatencyNs: Latest latency sample (null if none)
     * - activeBubbleCount: Number of unique bubbles (union of frame + neighbor metrics)
     * - timestamp: Current time
     * <p>
     * Thread-safe: Creates consistent snapshot without locking.
     *
     * @return MetricsSnapshot with aggregated metrics
     */
    public MetricsSnapshot getSnapshot() {
        // Calculate average animator utilization
        var utilizationSum = 0.0f;
        var frameCount = 0;
        for (var metrics : frameMetrics.values()) {
            utilizationSum += metrics.utilization;
            frameCount++;
        }
        var avgUtilization = frameCount > 0 ? utilizationSum / frameCount : 0.0f;

        // Calculate total VON neighbors
        var totalNeighbors = 0;
        for (var metrics : neighborMetrics.values()) {
            totalNeighbors += metrics.count;
        }

        // Count active bubbles (union of both maps)
        var allBubbles = ConcurrentHashMap.<UUID>newKeySet();
        allBubbles.addAll(frameMetrics.keySet());
        allBubbles.addAll(neighborMetrics.keySet());
        var activeBubbles = allBubbles.size();

        // Get latest ghost latency
        var latency = ghostLatencyNs.get();

        // Create snapshot
        return new MetricsSnapshot(
            avgUtilization,
            totalNeighbors,
            latency,
            activeBubbles,
            System.currentTimeMillis()
        );
    }

    /**
     * Clear all metrics (for testing).
     */
    void reset() {
        frameMetrics.clear();
        neighborMetrics.clear();
        ghostLatencyNs.set(null);
    }
}
