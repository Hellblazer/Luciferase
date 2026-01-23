package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Metrics for fault detection and recovery operations.
 * <p>
 * This record tracks performance and success rates of the fault tolerance system.
 * All fields are immutable snapshots; use with* methods to create updated instances.
 *
 * @param detectionLatencyMs Time from fault occurrence to detection in milliseconds
 * @param recoveryLatencyMs Time to complete recovery in milliseconds
 * @param failureCount Total number of partition failures detected
 * @param recoveryAttempts Total number of recovery attempts initiated
 * @param successfulRecoveries Number of successful recovery operations
 * @param failedRecoveries Number of failed recovery operations
 */
public record FaultMetrics(
    long detectionLatencyMs,
    long recoveryLatencyMs,
    int failureCount,
    int recoveryAttempts,
    int successfulRecoveries,
    int failedRecoveries
) {

    /**
     * Returns a zero-initialized metrics instance.
     */
    public static FaultMetrics zero() {
        return new FaultMetrics(0, 0, 0, 0, 0, 0);
    }

    /**
     * Returns a new metrics instance with updated detection latency.
     */
    public FaultMetrics withDetectionLatency(long detectionLatencyMs) {
        return new FaultMetrics(
            detectionLatencyMs,
            this.recoveryLatencyMs,
            this.failureCount,
            this.recoveryAttempts,
            this.successfulRecoveries,
            this.failedRecoveries
        );
    }

    /**
     * Returns a new metrics instance with updated recovery latency.
     */
    public FaultMetrics withRecoveryLatency(long recoveryLatencyMs) {
        return new FaultMetrics(
            this.detectionLatencyMs,
            recoveryLatencyMs,
            this.failureCount,
            this.recoveryAttempts,
            this.successfulRecoveries,
            this.failedRecoveries
        );
    }

    /**
     * Returns a new metrics instance with incremented failure count.
     */
    public FaultMetrics withIncrementedFailureCount() {
        return new FaultMetrics(
            this.detectionLatencyMs,
            this.recoveryLatencyMs,
            this.failureCount + 1,
            this.recoveryAttempts,
            this.successfulRecoveries,
            this.failedRecoveries
        );
    }

    /**
     * Returns a new metrics instance with incremented recovery attempts.
     */
    public FaultMetrics withIncrementedRecoveryAttempts() {
        return new FaultMetrics(
            this.detectionLatencyMs,
            this.recoveryLatencyMs,
            this.failureCount,
            this.recoveryAttempts + 1,
            this.successfulRecoveries,
            this.failedRecoveries
        );
    }

    /**
     * Returns a new metrics instance with incremented successful recoveries.
     */
    public FaultMetrics withIncrementedSuccessfulRecoveries() {
        return new FaultMetrics(
            this.detectionLatencyMs,
            this.recoveryLatencyMs,
            this.failureCount,
            this.recoveryAttempts,
            this.successfulRecoveries + 1,
            this.failedRecoveries
        );
    }

    /**
     * Returns a new metrics instance with incremented failed recoveries.
     */
    public FaultMetrics withIncrementedFailedRecoveries() {
        return new FaultMetrics(
            this.detectionLatencyMs,
            this.recoveryLatencyMs,
            this.failureCount,
            this.recoveryAttempts,
            this.successfulRecoveries,
            this.failedRecoveries + 1
        );
    }

    /**
     * Calculates the success rate of recovery operations.
     *
     * @return Success rate from 0.0 to 1.0, or 0.0 if no attempts
     */
    public double successRate() {
        if (recoveryAttempts == 0) {
            return 0.0;
        }
        return (double) successfulRecoveries / recoveryAttempts;
    }
}
