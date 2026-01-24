package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Recovery strategy for a failed partition.
 * <p>
 * Implementations define how to restore a partition to healthy state. Recovery
 * may involve barrier synchronization, state restoration, cascading recovery,
 * or other mechanisms.
 * <p>
 * This interface follows the Strategy pattern, allowing different recovery
 * approaches to be plugged into the fault tolerance system. Common strategies
 * include:
 * <ul>
 *   <li><b>Barrier Recovery</b>: Synchronize via barrier and restore state</li>
 *   <li><b>Cascading Recovery</b>: Multi-level recovery with fallback strategies</li>
 *   <li><b>NoOp Recovery</b>: Testing stub that immediately succeeds</li>
 * </ul>
 * <p>
 * Recovery typically proceeds through these phases:
 * <ol>
 *   <li>Prepare recovery (validate state, acquire locks)</li>
 *   <li>Execute recovery action (barrier sync, state transfer, etc.)</li>
 *   <li>Verify restoration (validation checks)</li>
 *   <li>Complete or fail</li>
 * </ol>
 * <p>
 * <b>Example Usage</b>:
 * <pre>{@code
 * // Create recovery strategy
 * var config = FaultConfiguration.defaultConfig();
 * PartitionRecovery recovery = new BarrierRecoveryImpl(config);
 *
 * // Register with fault handler
 * faultHandler.registerRecovery(partitionId, recovery);
 *
 * // Check if recovery is possible
 * if (recovery.canRecover(partitionId, faultHandler)) {
 *     // Initiate recovery
 *     recovery.recover(partitionId, faultHandler)
 *         .thenAccept(result -> {
 *             if (result.success()) {
 *                 log.info("Recovery succeeded in {}ms after {} attempts",
 *                     result.durationMs(), result.attemptsNeeded());
 *             } else {
 *                 log.error("Recovery failed: {}", result.statusMessage());
 *             }
 *         });
 * }
 * }</pre>
 *
 * @see BarrierRecoveryImpl
 * @see CascadingRecoveryImpl
 * @see NoOpRecoveryImpl
 */
public interface PartitionRecovery {

    /**
     * Recover the partition asynchronously.
     * <p>
     * Execution flow:
     * <ol>
     *   <li>Prepare recovery (validate state, acquire locks)</li>
     *   <li>Execute recovery action (barrier sync, state transfer, etc.)</li>
     *   <li>Verify restoration (validation checks)</li>
     *   <li>Complete or fail</li>
     * </ol>
     * <p>
     * The returned CompletableFuture completes with a {@link RecoveryResult}
     * containing detailed outcome information including success status, duration,
     * number of attempts, and status messages.
     * <p>
     * Recovery executes asynchronously. Callers should not block on the future
     * in performance-critical paths.
     *
     * @param partitionId partition to recover
     * @param handler FaultHandler for coordination and state queries
     * @return CompletableFuture&lt;RecoveryResult&gt; - detailed recovery outcome
     * @throws IllegalStateException if partition not in recoverable state
     * @throws IllegalArgumentException if partitionId or handler is null
     */
    CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler);

    /**
     * Validate that partition can be recovered.
     * <p>
     * Used before initiating recovery to check prerequisites. Verifies:
     * <ul>
     *   <li>Partition is in SUSPECTED or FAILED state</li>
     *   <li>Sufficient healthy partitions exist (quorum)</li>
     *   <li>No conflicting recovery in progress</li>
     *   <li>Resources available (network, memory, etc.)</li>
     * </ul>
     * <p>
     * This is a non-blocking check that does not modify state. Call before
     * {@link #recover} to avoid unnecessary recovery attempts.
     *
     * @param partitionId partition to validate
     * @param handler FaultHandler for state queries
     * @return true if partition can be recovered now, false otherwise
     * @throws IllegalArgumentException if partitionId or handler is null
     */
    boolean canRecover(UUID partitionId, FaultHandler handler);

    /**
     * Get human-readable name of recovery strategy.
     * <p>
     * Used for logging, metrics, debugging, and strategy selection. Examples:
     * <ul>
     *   <li>"barrier-recovery" - Barrier synchronization strategy</li>
     *   <li>"cascading-recovery" - Multi-level fallback strategy</li>
     *   <li>"noop-recovery" - Testing stub strategy</li>
     * </ul>
     *
     * @return strategy name (never null or blank)
     */
    String getStrategyName();

    /**
     * Get recovery configuration if available.
     * <p>
     * Returns the {@link FaultConfiguration} used by this recovery strategy,
     * or null if using system defaults. Configuration includes timeout values,
     * retry limits, and cascading thresholds.
     *
     * @return FaultConfiguration or null if using default
     */
    FaultConfiguration getConfiguration();

    /**
     * Initiate recovery for a failed partition (legacy method).
     * <p>
     * This method provides backward compatibility with existing code. New code
     * should use {@link #recover(UUID, FaultHandler)} for detailed results.
     * <p>
     * Default implementation delegates to {@link #recover} and converts result
     * to boolean (true for success, false for failure).
     *
     * @param failedPartitionId UUID of the partition that failed
     * @return CompletableFuture that completes when recovery finishes
     * @deprecated Use {@link #recover(UUID, FaultHandler)} for detailed results
     */
    @Deprecated(since = "1.0", forRemoval = false)
    default CompletableFuture<Boolean> initiateRecovery(UUID failedPartitionId) {
        // Provide basic implementation that requires recovery to be invoked
        // with proper FaultHandler via recover() method
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException(
                "initiateRecovery(UUID) is deprecated. Use recover(UUID, FaultHandler) instead."
            )
        );
    }
}

