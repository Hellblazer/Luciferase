package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;

/**
 * Event published when recovery state changes.
 * <p>
 * Used by monitoring and observability systems to track recovery operations
 * in real-time. Events are emitted at key transition points during recovery.
 *
 * @param partitionId Partition undergoing recovery
 * @param eventType Type of recovery event
 * @param details Event-specific details (phase name, error message, etc.)
 * @param timestamp When event occurred (milliseconds since epoch)
 */
public record RecoveryEvent(
    UUID partitionId,
    RecoveryEventType eventType,
    String details,
    long timestamp
) {

    /**
     * Compact constructor with validation.
     */
    public RecoveryEvent {
        if (partitionId == null) {
            throw new IllegalArgumentException("partitionId cannot be null");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType cannot be null");
        }
        if (details == null || details.isBlank()) {
            throw new IllegalArgumentException("details cannot be null or blank");
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must be non-negative, got: " + timestamp);
        }
    }

    /**
     * Create event with current timestamp.
     *
     * @param partitionId partition undergoing recovery
     * @param eventType type of event
     * @param details event details
     * @return RecoveryEvent with current timestamp
     */
    public static RecoveryEvent now(UUID partitionId, RecoveryEventType eventType, String details) {
        return new RecoveryEvent(partitionId, eventType, details, System.currentTimeMillis());
    }
}
