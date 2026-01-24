package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;

/**
 * Result of a partition recovery attempt.
 * <p>
 * Immutable record capturing recovery outcome details including success status,
 * duration, strategy used, attempts needed, and detailed status messages.
 * <p>
 * This record is returned by {@link PartitionRecovery#recover(UUID, FaultHandler)}
 * to communicate recovery results to callers and monitoring systems.
 *
 * @param partitionId Which partition was recovered
 * @param success true if recovery succeeded, false if failed
 * @param durationMs Time taken for recovery in milliseconds
 * @param strategy Strategy name used (e.g., "barrier", "cascading", "noop")
 * @param attemptsNeeded Number of attempts required to succeed (1+ for success)
 * @param statusMessage Detailed outcome description (success reason or error)
 * @param failureReason Exception if recovery failed (null on success)
 */
public record RecoveryResult(
    UUID partitionId,
    boolean success,
    long durationMs,
    String strategy,
    int attemptsNeeded,
    String statusMessage,
    Throwable failureReason
) {

    /**
     * Compact constructor with validation.
     */
    public RecoveryResult {
        if (partitionId == null) {
            throw new IllegalArgumentException("partitionId cannot be null");
        }
        if (strategy == null || strategy.isBlank()) {
            throw new IllegalArgumentException("strategy cannot be null or blank");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative, got: " + durationMs);
        }
        if (attemptsNeeded < 1) {
            throw new IllegalArgumentException("attemptsNeeded must be >= 1, got: " + attemptsNeeded);
        }
        if (statusMessage == null || statusMessage.isBlank()) {
            throw new IllegalArgumentException("statusMessage cannot be null or blank");
        }
    }

    /**
     * Create successful recovery result.
     *
     * @param partitionId partition that was recovered
     * @param durationMs time taken in milliseconds
     * @param strategy strategy name used
     * @param attempts number of attempts needed (must be >= 1)
     * @return successful RecoveryResult
     */
    public static RecoveryResult success(
        UUID partitionId,
        long durationMs,
        String strategy,
        int attempts
    ) {
        return new RecoveryResult(
            partitionId,
            true,
            durationMs,
            strategy,
            attempts,
            "Recovery completed successfully",
            null
        );
    }

    /**
     * Create successful recovery result with custom message.
     *
     * @param partitionId partition that was recovered
     * @param durationMs time taken in milliseconds
     * @param strategy strategy name used
     * @param attempts number of attempts needed
     * @param message custom success message
     * @return successful RecoveryResult
     */
    public static RecoveryResult success(
        UUID partitionId,
        long durationMs,
        String strategy,
        int attempts,
        String message
    ) {
        return new RecoveryResult(
            partitionId,
            true,
            durationMs,
            strategy,
            attempts,
            message,
            null
        );
    }

    /**
     * Create failed recovery result.
     *
     * @param partitionId partition that failed to recover
     * @param durationMs time spent attempting recovery
     * @param strategy strategy name used
     * @param attempts number of attempts made
     * @param message failure description
     * @param cause exception that caused failure (may be null)
     * @return failed RecoveryResult
     */
    public static RecoveryResult failure(
        UUID partitionId,
        long durationMs,
        String strategy,
        int attempts,
        String message,
        Throwable cause
    ) {
        return new RecoveryResult(
            partitionId,
            false,
            durationMs,
            strategy,
            attempts,
            message,
            cause
        );
    }
}
