package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Recovery phase state machine for partition failure recovery (Phase 4.1).
 *
 * <p>Defines the discrete phases of partition recovery. Each recovery operation
 * progresses through these phases sequentially, with the ability to abort to
 * FAILED at any point.
 *
 * <p><b>Normal Flow</b>:
 * <pre>
 * IDLE → DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE → IDLE
 * </pre>
 *
 * <p><b>Failure Flow</b>:
 * <pre>
 * (any active phase) → FAILED → IDLE (retry)
 * </pre>
 *
 * <p><b>Phase Descriptions</b>:
 * <ul>
 *   <li>{@link #IDLE} - No active recovery, ready to start</li>
 *   <li>{@link #DETECTING} - Confirming partition failure</li>
 *   <li>{@link #REDISTRIBUTING} - Moving data from failed partition</li>
 *   <li>{@link #REBALANCING} - Triggering load balancer</li>
 *   <li>{@link #VALIDATING} - Checking consistency after recovery</li>
 *   <li>{@link #COMPLETE} - Recovery successful, resuming normal ops</li>
 *   <li>{@link #FAILED} - Recovery aborted, ready for retry</li>
 * </ul>
 *
 * @see PartitionRecovery
 */
public enum RecoveryPhase {
    /**
     * No active recovery in progress.
     * <p>
     * Terminal state. Ready to transition to DETECTING when a failure is detected.
     */
    IDLE,

    /**
     * Confirming partition failure before initiating recovery.
     * <p>
     * Validates that the partition is truly failed (not a transient network issue)
     * before proceeding with data redistribution.
     */
    DETECTING,

    /**
     * Redistributing data from the failed partition to surviving partitions.
     * <p>
     * Entities owned by the failed partition are moved to other partitions
     * according to the spatial distribution strategy.
     */
    REDISTRIBUTING,

    /**
     * Triggering load balancer to rebalance the forest.
     * <p>
     * Invokes {@link com.hellblazer.luciferase.lucien.balancing.ParallelBalancer}
     * to redistribute load across surviving partitions.
     */
    REBALANCING,

    /**
     * Validating consistency after recovery.
     * <p>
     * Checks entity counts, ghost layer consistency, and spatial index integrity
     * to ensure recovery completed successfully.
     */
    VALIDATING,

    /**
     * Recovery completed successfully.
     * <p>
     * Terminal state. System returns to IDLE and resumes normal operation.
     */
    COMPLETE,

    /**
     * Recovery failed or aborted.
     * <p>
     * Terminal state. May retry recovery (transition back to IDLE) or
     * escalate to human intervention depending on retry count.
     */
    FAILED
}
