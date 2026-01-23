package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.Objects;
import java.util.UUID;

/**
 * Event representing a partition status change.
 * <p>
 * This record captures state transitions in the fault tolerance system,
 * enabling event-driven monitoring and logging of partition health changes.
 * <p>
 * Common reasons:
 * <ul>
 *   <li>{@code "heartbeat_timeout"} - Heartbeat not received within threshold</li>
 *   <li>{@code "barrier_timeout"} - Barrier synchronization timeout</li>
 *   <li>{@code "ghost_sync_failure"} - Ghost layer synchronization failed</li>
 *   <li>{@code "recovery_started"} - Recovery process initiated</li>
 *   <li>{@code "recovery_completed"} - Recovery successfully completed</li>
 *   <li>{@code "recovery_failed"} - Recovery failed after max retries</li>
 *   <li>{@code "false_alarm"} - Suspected partition responded, no failure</li>
 * </ul>
 *
 * @param partitionId Unique identifier of the affected partition
 * @param oldStatus Previous partition status
 * @param newStatus New partition status
 * @param timestamp When the change occurred (milliseconds since epoch)
 * @param reason Human-readable explanation of why the status changed
 */
public record PartitionChangeEvent(
    UUID partitionId,
    PartitionStatus oldStatus,
    PartitionStatus newStatus,
    long timestamp,
    String reason
) {

    /**
     * Compact constructor with null validation.
     */
    public PartitionChangeEvent {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(oldStatus, "oldStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
