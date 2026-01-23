package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;

/**
 * Read-only view of partition health and status.
 * <p>
 * This interface provides a snapshot of a partition's current state in the
 * distributed forest fault tolerance system. Implementations should be immutable
 * or thread-safe, as views may be shared across monitoring threads.
 * <p>
 * Example usage:
 * <pre>{@code
 * PartitionView view = faultHandler.getPartitionView(partitionId);
 * if (view.status() == PartitionStatus.SUSPECTED) {
 *     long staleness = System.currentTimeMillis() - view.lastSeenMs();
 *     if (staleness > 5000) {
 *         // Partition unresponsive for 5+ seconds
 *     }
 * }
 * }</pre>
 */
public interface PartitionView {

    /**
     * Returns the unique identifier of this partition.
     *
     * @return Partition UUID
     */
    UUID partitionId();

    /**
     * Returns the current status of this partition.
     *
     * @return Current partition status
     */
    PartitionStatus status();

    /**
     * Returns the timestamp of the last successful communication with this partition.
     * <p>
     * This value is updated when:
     * <ul>
     *   <li>Heartbeat message received</li>
     *   <li>Barrier synchronization completed</li>
     *   <li>Ghost layer sync succeeded</li>
     * </ul>
     *
     * @return Milliseconds since epoch of last contact
     */
    long lastSeenMs();

    /**
     * Returns the total number of nodes in this partition.
     *
     * @return Total node count
     */
    int nodeCount();

    /**
     * Returns the number of healthy (responsive) nodes in this partition.
     * <p>
     * For healthy partitions, this equals {@link #nodeCount()}.
     * For degraded partitions, this is less than {@link #nodeCount()}.
     * For failed partitions, this may be zero.
     *
     * @return Healthy node count
     */
    int healthyNodes();

    /**
     * Returns current fault detection and recovery metrics for this partition.
     *
     * @return Fault metrics snapshot
     */
    FaultMetrics metrics();
}
