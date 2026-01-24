package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;

/**
 * Per-partition recovery state tracking (Phase 4.3).
 * <p>
 * Immutable record tracking the current state of a partition recovery operation.
 * Includes phase, timing information, retry count, and error details.
 * <p>
 * Used by {@link DefaultPartitionRecovery} to maintain recovery state machine.
 *
 * @param partitionId UUID of partition being recovered
 * @param currentPhase Current recovery phase
 * @param startTimeMs Timestamp when recovery started (milliseconds)
 * @param lastUpdateMs Timestamp of last state transition (milliseconds)
 * @param retryCount Number of recovery attempts so far
 * @param errorMessage Last error message (empty string if no error)
 */
public record RecoveryState(
    UUID partitionId,
    RecoveryPhase currentPhase,
    long startTimeMs,
    long lastUpdateMs,
    int retryCount,
    String errorMessage
) {

    /**
     * Compact constructor with validation.
     */
    public RecoveryState {
        if (partitionId == null) {
            throw new IllegalArgumentException("partitionId cannot be null");
        }
        if (currentPhase == null) {
            throw new IllegalArgumentException("currentPhase cannot be null");
        }
        if (startTimeMs < 0) {
            throw new IllegalArgumentException("startTimeMs must be non-negative, got: " + startTimeMs);
        }
        if (lastUpdateMs < 0) {
            throw new IllegalArgumentException("lastUpdateMs must be non-negative, got: " + lastUpdateMs);
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be non-negative, got: " + retryCount);
        }
        if (errorMessage == null) {
            throw new IllegalArgumentException("errorMessage cannot be null (use empty string for no error)");
        }
    }

    /**
     * Create initial recovery state in IDLE phase.
     *
     * @param partitionId partition to track
     * @return new RecoveryState in IDLE phase with current timestamp
     */
    public static RecoveryState initial(UUID partitionId) {
        var now = System.currentTimeMillis();
        return new RecoveryState(
            partitionId,
            RecoveryPhase.IDLE,
            now,
            now,
            0,
            ""
        );
    }

    /**
     * Check if recovery is currently active.
     * <p>
     * Returns true if current phase is not a terminal state (COMPLETE or FAILED).
     * Active recovery may be in progress or transitioning between phases.
     *
     * @return true if recovery is active, false if in terminal state
     */
    public boolean isActive() {
        return currentPhase != RecoveryPhase.COMPLETE &&
               currentPhase != RecoveryPhase.FAILED &&
               currentPhase != RecoveryPhase.IDLE;
    }

    /**
     * Create new state with updated phase and timestamp.
     *
     * @param newPhase new recovery phase
     * @return new RecoveryState with updated phase and lastUpdateMs
     */
    public RecoveryState withPhase(RecoveryPhase newPhase) {
        return new RecoveryState(
            partitionId,
            newPhase,
            startTimeMs,
            System.currentTimeMillis(),
            retryCount,
            errorMessage
        );
    }

    /**
     * Create new state with error message.
     *
     * @param error error description
     * @return new RecoveryState with error message
     */
    public RecoveryState withError(String error) {
        return new RecoveryState(
            partitionId,
            currentPhase,
            startTimeMs,
            System.currentTimeMillis(),
            retryCount,
            error != null ? error : ""
        );
    }

    /**
     * Create new state with incremented retry count.
     *
     * @return new RecoveryState with retryCount + 1
     */
    public RecoveryState withRetry() {
        return new RecoveryState(
            partitionId,
            currentPhase,
            startTimeMs,
            System.currentTimeMillis(),
            retryCount + 1,
            errorMessage
        );
    }
}
