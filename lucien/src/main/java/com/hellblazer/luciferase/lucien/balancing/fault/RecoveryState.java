package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-partition recovery state tracking (Phase 4.3.2).
 * <p>
 * Immutable record tracking the current state of a partition recovery operation.
 * Includes phase, timing information, retry count, and flexible metadata storage.
 * <p>
 * Used by {@link DefaultPartitionRecovery} to maintain recovery state machine.
 *
 * @param partitionId UUID of partition being recovered
 * @param currentPhase Current recovery phase
 * @param attemptCount Number of recovery attempts so far
 * @param lastAttemptTime Timestamp of last recovery attempt (milliseconds)
 * @param metadata Flexible key-value storage for recovery context (error messages, coordinator info, etc.)
 */
public record RecoveryState(
    UUID partitionId,
    RecoveryPhase currentPhase,
    int attemptCount,
    long lastAttemptTime,
    Map<String, Object> metadata
) {

    /**
     * Compact constructor with validation.
     */
    public RecoveryState {
        Objects.requireNonNull(partitionId, "partitionId cannot be null");
        Objects.requireNonNull(currentPhase, "currentPhase cannot be null");
        Objects.requireNonNull(metadata, "metadata cannot be null");

        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must be non-negative, got: " + attemptCount);
        }
    }

    /**
     * Create initial recovery state in IDLE phase.
     *
     * @param partitionId partition to track
     * @param currentTime current timestamp in milliseconds
     * @return new RecoveryState in IDLE phase with current timestamp
     */
    public static RecoveryState initial(UUID partitionId, long currentTime) {
        return new RecoveryState(
            partitionId,
            RecoveryPhase.IDLE,
            0,
            currentTime,
            Map.of()
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
     * @param timestamp new timestamp in milliseconds
     * @return new RecoveryState with updated phase and lastAttemptTime
     */
    public RecoveryState withPhase(RecoveryPhase newPhase, long timestamp) {
        return new RecoveryState(
            partitionId,
            newPhase,
            attemptCount,
            timestamp,
            metadata
        );
    }

    /**
     * Create new state with updated metadata.
     *
     * @param newMetadata new metadata map
     * @return new RecoveryState with updated metadata
     */
    public RecoveryState withMetadata(Map<String, Object> newMetadata) {
        return new RecoveryState(
            partitionId,
            currentPhase,
            attemptCount,
            lastAttemptTime,
            newMetadata
        );
    }

    /**
     * Create new state with incremented attempt count.
     *
     * @param timestamp timestamp of the new attempt
     * @return new RecoveryState with attemptCount + 1 and updated timestamp
     */
    public RecoveryState withIncrementedAttempt(long timestamp) {
        return new RecoveryState(
            partitionId,
            currentPhase,
            attemptCount + 1,
            timestamp,
            metadata
        );
    }

    /**
     * Add or update a single metadata entry.
     *
     * @param key metadata key
     * @param value metadata value
     * @return new RecoveryState with updated metadata
     */
    public RecoveryState withMetadataEntry(String key, Object value) {
        var newMetadata = new java.util.HashMap<>(metadata);
        newMetadata.put(key, value);
        return withMetadata(newMetadata);
    }
}
