package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Configuration for fault detection and recovery.
 * <p>
 * This record encapsulates timeout thresholds, retry limits, and cascading
 * failure detection parameters for the distributed forest fault tolerance system.
 *
 * @param heartbeatIntervalMs Frequency of heartbeat messages in milliseconds (default 500ms)
 * @param heartbeatTimeoutMs Threshold for detecting missed heartbeat (default 2000ms)
 * @param barrierTimeoutMs Timeout for barrier synchronization operations (default 5000ms)
 * @param maxRetries Maximum recovery retry attempts before giving up (default 3)
 * @param cascadingThreshold Number of failures that trigger cascading recovery (default 2)
 */
public record FaultConfiguration(
    long heartbeatIntervalMs,
    long heartbeatTimeoutMs,
    long barrierTimeoutMs,
    int maxRetries,
    int cascadingThreshold
) {

    /**
     * Compact constructor with validation.
     */
    public FaultConfiguration {
        if (heartbeatIntervalMs < 0) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be non-negative, got: " + heartbeatIntervalMs);
        }
        if (heartbeatTimeoutMs < 0) {
            throw new IllegalArgumentException("heartbeatTimeoutMs must be non-negative, got: " + heartbeatTimeoutMs);
        }
        if (barrierTimeoutMs < 0) {
            throw new IllegalArgumentException("barrierTimeoutMs must be non-negative, got: " + barrierTimeoutMs);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative, got: " + maxRetries);
        }
        if (cascadingThreshold < 0) {
            throw new IllegalArgumentException("cascadingThreshold must be non-negative, got: " + cascadingThreshold);
        }
    }

    /**
     * Returns a configuration with default values.
     */
    public static FaultConfiguration defaultConfig() {
        return new FaultConfiguration(
            500,   // heartbeatIntervalMs
            2000,  // heartbeatTimeoutMs
            5000,  // barrierTimeoutMs
            3,     // maxRetries
            2      // cascadingThreshold
        );
    }

    /**
     * Returns a new configuration with updated heartbeat interval.
     */
    public FaultConfiguration withHeartbeatInterval(long heartbeatIntervalMs) {
        return new FaultConfiguration(
            heartbeatIntervalMs,
            this.heartbeatTimeoutMs,
            this.barrierTimeoutMs,
            this.maxRetries,
            this.cascadingThreshold
        );
    }

    /**
     * Returns a new configuration with updated heartbeat timeout.
     */
    public FaultConfiguration withHeartbeatTimeout(long heartbeatTimeoutMs) {
        return new FaultConfiguration(
            this.heartbeatIntervalMs,
            heartbeatTimeoutMs,
            this.barrierTimeoutMs,
            this.maxRetries,
            this.cascadingThreshold
        );
    }

    /**
     * Returns a new configuration with updated barrier timeout.
     */
    public FaultConfiguration withBarrierTimeout(long barrierTimeoutMs) {
        return new FaultConfiguration(
            this.heartbeatIntervalMs,
            this.heartbeatTimeoutMs,
            barrierTimeoutMs,
            this.maxRetries,
            this.cascadingThreshold
        );
    }

    /**
     * Returns a new configuration with updated max retries.
     */
    public FaultConfiguration withMaxRetries(int maxRetries) {
        return new FaultConfiguration(
            this.heartbeatIntervalMs,
            this.heartbeatTimeoutMs,
            this.barrierTimeoutMs,
            maxRetries,
            this.cascadingThreshold
        );
    }

    /**
     * Returns a new configuration with updated cascading threshold.
     */
    public FaultConfiguration withCascadingThreshold(int cascadingThreshold) {
        return new FaultConfiguration(
            this.heartbeatIntervalMs,
            this.heartbeatTimeoutMs,
            this.barrierTimeoutMs,
            this.maxRetries,
            cascadingThreshold
        );
    }
}
