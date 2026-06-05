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
     * Create event with an explicit timestamp (clock-injectable, Luciferase-7wzml.103).
     *
     * @param partitionId partition undergoing recovery
     * @param eventType type of event
     * @param details event details
     * @param timestampMs explicit timestamp in ms (e.g. from injected Clock)
     * @return RecoveryEvent with the given timestamp
     */
    public static RecoveryEvent at(UUID partitionId, RecoveryEventType eventType, String details, long timestampMs) {
        return new RecoveryEvent(partitionId, eventType, details, timestampMs);
    }

    /**
     * Create event with current wall-clock timestamp.
     *
     * @deprecated Use {@link #at(UUID, RecoveryEventType, String, long) at(..., clock.currentTimeMillis())}
     *     instead. The {@code now()} factory calls {@code System.currentTimeMillis()} directly, which makes
     *     event timestamps non-deterministic in tests. Inject a {@link com.hellblazer.luciferase.common.time.Clock}
     *     and supply {@code clock.currentTimeMillis()} to {@code at()} so tests can control timestamps.
     *     The last production caller was removed in wave-14 (bead my5mf). This factory is retained only
     *     for any remaining test callers; it will be removed in a future wave.
     *
     * @param partitionId partition undergoing recovery
     * @param eventType type of event
     * @param details event details
     * @return RecoveryEvent with current system timestamp
     */
    @Deprecated(since = "wave14", forRemoval = true)
    public static RecoveryEvent now(UUID partitionId, RecoveryEventType eventType, String details) {
        return new RecoveryEvent(partitionId, eventType, details, System.currentTimeMillis());
    }
}
