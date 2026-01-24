package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Event types for recovery progress tracking.
 * <p>
 * Used by monitoring and observability systems to track recovery state transitions
 * and understand what phase of recovery is currently executing.
 */
public enum RecoveryEventType {
    /**
     * Recovery operation initiated.
     */
    RECOVERY_STARTED,

    /**
     * Validation phase - checking preconditions for recovery.
     */
    RECOVERY_VALIDATION,

    /**
     * Barrier synchronization phase - coordinating across partitions.
     */
    RECOVERY_BARRIER,

    /**
     * State synchronization phase - transferring data between partitions.
     */
    RECOVERY_STATE_SYNC,

    /**
     * Verification phase - checking post-recovery consistency.
     */
    RECOVERY_VERIFICATION,

    /**
     * Recovery completed successfully.
     */
    RECOVERY_COMPLETED,

    /**
     * Recovery failed (after exhausting retries).
     */
    RECOVERY_FAILED
}
