package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Status of a partition in the distributed forest.
 * <p>
 * State transitions:
 * <ul>
 *   <li>HEALTHY → SUSPECTED (heartbeat/barrier timeout detected)</li>
 *   <li>SUSPECTED → FAILED (failure confirmation threshold exceeded)</li>
 *   <li>SUSPECTED → HEALTHY (false alarm, partition responds)</li>
 *   <li>FAILED → RECOVERING (recovery process initiated)</li>
 *   <li>RECOVERING → HEALTHY (recovery successful)</li>
 *   <li>RECOVERING → DEGRADED (partial recovery, reduced functionality)</li>
 *   <li>DEGRADED → HEALTHY (full functionality restored)</li>
 * </ul>
 */
public enum PartitionStatus {
    /**
     * Partition is responding normally to heartbeats and barriers.
     */
    HEALTHY,

    /**
     * Partition missed heartbeat or barrier timeout detected.
     * Under observation, may be false alarm or network delay.
     */
    SUSPECTED,

    /**
     * Partition confirmed as failed by consensus.
     * Recovery process should be triggered.
     */
    FAILED,

    /**
     * Active recovery in progress for this partition.
     * Data is being redistributed to surviving partitions.
     */
    RECOVERING,

    /**
     * Partition operational but with partial functionality.
     * Used for recovery strategies that restore incrementally.
     */
    DEGRADED
}
