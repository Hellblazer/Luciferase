package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;

/**
 * Progress updates during recovery operation.
 * <p>
 * Used by observers to track recovery progress and adapt strategies based on
 * current phase and progress percentage.
 * <p>
 * This record is emitted periodically during {@link PartitionRecovery#recover}
 * execution to provide visibility into long-running recovery operations.
 *
 * @param partitionId Partition being recovered
 * @param phase Phase name (e.g., "validating", "barrier-sync", "verification")
 * @param percentComplete Progress indicator (0-100)
 * @param elapsedMs Time spent so far in milliseconds
 * @param message Current phase description
 */
public record RecoveryProgress(
    UUID partitionId,
    String phase,
    int percentComplete,
    long elapsedMs,
    String message
) {

    /**
     * Compact constructor with validation.
     */
    public RecoveryProgress {
        if (partitionId == null) {
            throw new IllegalArgumentException("partitionId cannot be null");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("phase cannot be null or blank");
        }
        if (percentComplete < 0 || percentComplete > 100) {
            throw new IllegalArgumentException("percentComplete must be 0-100, got: " + percentComplete);
        }
        if (elapsedMs < 0) {
            throw new IllegalArgumentException("elapsedMs must be non-negative, got: " + elapsedMs);
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message cannot be null or blank");
        }
    }
}
