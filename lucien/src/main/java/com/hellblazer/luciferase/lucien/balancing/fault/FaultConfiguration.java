package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Configuration for fault detection and recovery (REVISED Phase 4.1 specification).
 *
 * <p>This record encapsulates timeout thresholds and recovery parameters for the
 * distributed forest fault tolerance system.
 *
 * <p><b>Detection Latency</b>: suspectTimeoutMs + failureConfirmationMs
 * <ul>
 *   <li>Default: 3000ms + 5000ms = 8 seconds total detection time</li>
 *   <li>For faster detection, reduce timeouts (increases false positive risk)</li>
 * </ul>
 *
 * @param suspectTimeoutMs Time before partition transitions from HEALTHY to SUSPECTED (default 3000ms)
 * @param failureConfirmationMs Additional time before SUSPECTED transitions to FAILED (default 5000ms)
 * @param maxRecoveryRetries Maximum recovery retry attempts before giving up (default 3)
 * @param recoveryTimeoutMs Maximum time for a single recovery attempt (default 30000ms)
 * @param autoRecoveryEnabled Whether recovery initiates automatically on failure detection (default true)
 * @param maxConcurrentRecoveries Maximum number of concurrent partition recoveries (default 3)
 */
public record FaultConfiguration(
    long suspectTimeoutMs,
    long failureConfirmationMs,
    int maxRecoveryRetries,
    long recoveryTimeoutMs,
    boolean autoRecoveryEnabled,
    int maxConcurrentRecoveries
) {

    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if any timeout is negative or maxConcurrentRecoveries < 1
     */
    public FaultConfiguration {
        if (suspectTimeoutMs < 0) {
            throw new IllegalArgumentException(
                "suspectTimeoutMs must be non-negative, got: " + suspectTimeoutMs
            );
        }
        if (failureConfirmationMs < 0) {
            throw new IllegalArgumentException(
                "failureConfirmationMs must be non-negative, got: " + failureConfirmationMs
            );
        }
        if (maxRecoveryRetries < 0) {
            throw new IllegalArgumentException(
                "maxRecoveryRetries must be non-negative, got: " + maxRecoveryRetries
            );
        }
        if (recoveryTimeoutMs < 0) {
            throw new IllegalArgumentException(
                "recoveryTimeoutMs must be non-negative, got: " + recoveryTimeoutMs
            );
        }
        if (maxConcurrentRecoveries < 1) {
            throw new IllegalArgumentException(
                "maxConcurrentRecoveries must be at least 1, got: " + maxConcurrentRecoveries
            );
        }
    }

    /**
     * Returns a configuration with default values optimized for typical distributed systems.
     *
     * <ul>
     *   <li>suspectTimeoutMs: 3000 (3 seconds)</li>
     *   <li>failureConfirmationMs: 5000 (5 seconds)</li>
     *   <li>maxRecoveryRetries: 3</li>
     *   <li>recoveryTimeoutMs: 30000 (30 seconds)</li>
     *   <li>autoRecoveryEnabled: true</li>
     *   <li>maxConcurrentRecoveries: 3</li>
     * </ul>
     *
     * @return default configuration
     */
    public static FaultConfiguration defaultConfig() {
        return new FaultConfiguration(
            3000,  // suspectTimeoutMs
            5000,  // failureConfirmationMs
            3,     // maxRecoveryRetries
            30000, // recoveryTimeoutMs
            true,  // autoRecoveryEnabled
            3      // maxConcurrentRecoveries
        );
    }

    /**
     * Returns a new configuration with updated suspect timeout.
     *
     * @param suspectTimeoutMs new suspect timeout in milliseconds
     * @return new configuration instance
     */
    public FaultConfiguration withSuspectTimeout(long suspectTimeoutMs) {
        return new FaultConfiguration(
            suspectTimeoutMs,
            this.failureConfirmationMs,
            this.maxRecoveryRetries,
            this.recoveryTimeoutMs,
            this.autoRecoveryEnabled,
            this.maxConcurrentRecoveries
        );
    }

    /**
     * Returns a new configuration with updated failure confirmation timeout.
     *
     * @param failureConfirmationMs new failure confirmation timeout in milliseconds
     * @return new configuration instance
     */
    public FaultConfiguration withFailureConfirmation(long failureConfirmationMs) {
        return new FaultConfiguration(
            this.suspectTimeoutMs,
            failureConfirmationMs,
            this.maxRecoveryRetries,
            this.recoveryTimeoutMs,
            this.autoRecoveryEnabled,
            this.maxConcurrentRecoveries
        );
    }

    /**
     * Returns a new configuration with updated max recovery retries.
     *
     * @param maxRecoveryRetries new max retries count
     * @return new configuration instance
     */
    public FaultConfiguration withMaxRetries(int maxRecoveryRetries) {
        return new FaultConfiguration(
            this.suspectTimeoutMs,
            this.failureConfirmationMs,
            maxRecoveryRetries,
            this.recoveryTimeoutMs,
            this.autoRecoveryEnabled,
            this.maxConcurrentRecoveries
        );
    }

    /**
     * Returns a new configuration with updated recovery timeout.
     *
     * @param recoveryTimeoutMs new recovery timeout in milliseconds
     * @return new configuration instance
     */
    public FaultConfiguration withRecoveryTimeout(long recoveryTimeoutMs) {
        return new FaultConfiguration(
            this.suspectTimeoutMs,
            this.failureConfirmationMs,
            this.maxRecoveryRetries,
            recoveryTimeoutMs,
            this.autoRecoveryEnabled,
            this.maxConcurrentRecoveries
        );
    }

    /**
     * Returns a new configuration with updated auto-recovery setting.
     *
     * @param autoRecoveryEnabled whether to enable automatic recovery
     * @return new configuration instance
     */
    public FaultConfiguration withAutoRecovery(boolean autoRecoveryEnabled) {
        return new FaultConfiguration(
            this.suspectTimeoutMs,
            this.failureConfirmationMs,
            this.maxRecoveryRetries,
            this.recoveryTimeoutMs,
            autoRecoveryEnabled,
            this.maxConcurrentRecoveries
        );
    }

    /**
     * Returns a new configuration with updated max concurrent recoveries.
     *
     * @param maxConcurrentRecoveries new max concurrent recoveries count
     * @return new configuration instance
     */
    public FaultConfiguration withMaxConcurrentRecoveries(int maxConcurrentRecoveries) {
        return new FaultConfiguration(
            this.suspectTimeoutMs,
            this.failureConfirmationMs,
            this.maxRecoveryRetries,
            this.recoveryTimeoutMs,
            this.autoRecoveryEnabled,
            maxConcurrentRecoveries
        );
    }
}
