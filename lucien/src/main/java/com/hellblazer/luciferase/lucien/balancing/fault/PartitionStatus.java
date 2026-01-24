package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Status of a partition in the distributed forest (REVISED Phase 4.1 specification).
 *
 * <p>Represents the fault detection state for a partition. State transitions follow
 * a fault detection workflow:
 *
 * <ul>
 *   <li>HEALTHY → SUSPECTED (barrier timeout detected)</li>
 *   <li>SUSPECTED → FAILED (failure confirmation threshold exceeded)</li>
 *   <li>SUSPECTED → HEALTHY (false alarm, partition responds)</li>
 * </ul>
 *
 * <p>Recovery state is tracked separately in {@link RecoveryPhase}.
 *
 * @see RecoveryPhase
 */
public enum PartitionStatus {
    /**
     * Partition is responding normally to barriers and health checks.
     */
    HEALTHY,

    /**
     * Partition missed barrier timeout - under observation.
     * <p>
     * May be a false alarm or network delay. Requires confirmation
     * before transitioning to FAILED.
     */
    SUSPECTED,

    /**
     * Partition confirmed as failed (consecutive timeouts exceeded threshold).
     * <p>
     * Recovery process should be triggered via {@link PartitionRecovery}.
     */
    FAILED
}
