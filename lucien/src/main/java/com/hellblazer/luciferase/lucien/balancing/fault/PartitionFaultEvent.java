package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;

/**
 * Sealed interface for partition fault events (Phase 4.1).
 *
 * <p>Represents state transitions in the fault detection and recovery lifecycle.
 * All permitted subtypes are record implementations for immutability and pattern matching.
 *
 * <p><b>Permitted Subtypes</b>:
 * <ul>
 *   <li>{@link PartitionSuspected} - Partition missed barrier, under observation</li>
 *   <li>{@link PartitionFailed} - Partition confirmed as failed</li>
 *   <li>{@link PartitionRecovered} - Partition successfully recovered</li>
 * </ul>
 *
 * <p><b>Pattern Matching Example</b>:
 * <pre>
 * String message = switch (event) {
 *     case PartitionSuspected e -> "Suspected: " + e.reason();
 *     case PartitionFailed e -> "Failed: " + e.reason();
 *     case PartitionRecovered e -> "Recovered";
 * };
 * </pre>
 */
public sealed interface PartitionFaultEvent
    permits PartitionFaultEvent.PartitionSuspected,
            PartitionFaultEvent.PartitionFailed,
            PartitionFaultEvent.PartitionRecovered {

    /**
     * Returns the partition UUID associated with this event.
     *
     * @return partition identifier
     */
    UUID partitionId();

    /**
     * Returns the timestamp when this event occurred.
     *
     * @return event timestamp in milliseconds since epoch
     */
    long timestamp();

    /**
     * Event emitted when a partition transitions to SUSPECTED status.
     *
     * <p>Indicates the partition missed a barrier timeout or health check,
     * but has not yet been confirmed as failed.
     *
     * @param partitionId the suspected partition UUID
     * @param timestamp event timestamp (milliseconds since epoch)
     * @param reason reason for suspicion (e.g., "Barrier timeout", "Heartbeat missed")
     */
    record PartitionSuspected(UUID partitionId, long timestamp, String reason)
        implements PartitionFaultEvent {
    }

    /**
     * Event emitted when a partition transitions to FAILED status.
     *
     * <p>Indicates the partition has been confirmed as failed after exceeding
     * the failure confirmation threshold.
     *
     * @param partitionId the failed partition UUID
     * @param timestamp event timestamp (milliseconds since epoch)
     * @param reason reason for failure (e.g., "Consecutive timeouts", "Network partition")
     */
    record PartitionFailed(UUID partitionId, long timestamp, String reason)
        implements PartitionFaultEvent {
    }

    /**
     * Event emitted when a partition successfully recovers from FAILED status.
     *
     * <p>Indicates recovery has completed and the partition has transitioned
     * back to HEALTHY status.
     *
     * @param partitionId the recovered partition UUID
     * @param timestamp event timestamp (milliseconds since epoch)
     */
    record PartitionRecovered(UUID partitionId, long timestamp)
        implements PartitionFaultEvent {
    }
}
