package com.hellblazer.luciferase.simulation.metrics;

/**
 * Immutable snapshot of observability metrics at a point in time.
 * <p>
 * Provides aggregated metrics across all bubbles:
 * - Average animator utilization (CPU %)
 * - Total VON neighbor count
 * - Ghost sync latency (latest sample)
 * - Active bubble count
 * - Snapshot timestamp
 * <p>
 * Thread-safe by immutability.
 *
 * @param avgAnimatorUtilization Average CPU utilization across all bubbles (0.0 to 1.0+, >1.0 = over budget)
 * @param totalVonNeighbors      Sum of all VON neighbors across all bubbles
 * @param ghostSyncLatencyNs     Latest ghost sync latency in nanoseconds (null if no samples)
 * @param activeBubbleCount      Number of active bubbles being monitored
 * @param timestamp              Snapshot timestamp (System.currentTimeMillis())
 * @author hal.hildebrand
 */
public record MetricsSnapshot(
    float avgAnimatorUtilization,
    int totalVonNeighbors,
    Long ghostSyncLatencyNs,
    int activeBubbleCount,
    long timestamp
) {
    /**
     * Create a snapshot with validation.
     *
     * @param avgAnimatorUtilization Average utilization (0.0+)
     * @param totalVonNeighbors      Total neighbors (0+)
     * @param ghostSyncLatencyNs     Latency in nanoseconds (null or 0+)
     * @param activeBubbleCount      Active bubbles (0+)
     * @param timestamp              Snapshot time (0+)
     */
    public MetricsSnapshot {
        if (avgAnimatorUtilization < 0.0f) {
            throw new IllegalArgumentException("avgAnimatorUtilization cannot be negative: " + avgAnimatorUtilization);
        }
        if (totalVonNeighbors < 0) {
            throw new IllegalArgumentException("totalVonNeighbors cannot be negative: " + totalVonNeighbors);
        }
        if (ghostSyncLatencyNs != null && ghostSyncLatencyNs < 0) {
            throw new IllegalArgumentException("ghostSyncLatencyNs cannot be negative: " + ghostSyncLatencyNs);
        }
        if (activeBubbleCount < 0) {
            throw new IllegalArgumentException("activeBubbleCount cannot be negative: " + activeBubbleCount);
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp cannot be negative: " + timestamp);
        }
    }
}
