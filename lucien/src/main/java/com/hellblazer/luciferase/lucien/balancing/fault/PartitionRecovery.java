package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for partition recovery strategies.
 * <p>
 * Implementations coordinate data redistribution, ghost layer synchronization,
 * and rebalancing when a partition fails. This interface is called by
 * FaultHandler after failure detection and confirmation.
 * <p>
 * Recovery typically proceeds through these phases:
 * <ol>
 *   <li>DETECTING - Confirm partition failure</li>
 *   <li>REDISTRIBUTING - Move data from failed partition to survivors</li>
 *   <li>REBALANCING - Trigger global rebalancing</li>
 *   <li>VALIDATING - Verify data consistency</li>
 *   <li>COMPLETE - Recovery finished successfully</li>
 * </ol>
 * <p>
 * Example usage:
 * <pre>{@code
 * PartitionRecovery recovery = new DefaultPartitionRecovery(
 *     ghostManager,
 *     balancer,
 *     config
 * );
 *
 * faultHandler.registerRecovery(partitionId, recovery);
 *
 * // On failure detection:
 * recovery.initiateRecovery(failedPartitionId)
 *     .thenAccept(success -> {
 *         if (success) {
 *             log.info("Recovery succeeded");
 *         } else {
 *             log.error("Recovery failed");
 *         }
 *     });
 * }</pre>
 */
public interface PartitionRecovery {

    /**
     * Initiate recovery for a failed partition.
     * <p>
     * This method starts an asynchronous recovery process that:
     * <ul>
     *   <li>Removes the failed partition from ghost layer</li>
     *   <li>Redistributes boundary entities to surviving partitions</li>
     *   <li>Triggers global rebalancing</li>
     *   <li>Validates data consistency</li>
     * </ul>
     * <p>
     * The returned CompletableFuture completes with {@code true} if recovery
     * succeeded, {@code false} if recovery failed. The future may complete
     * exceptionally if recovery cannot be initiated (e.g., majority failure).
     *
     * @param failedPartitionId UUID of the partition that failed
     * @return CompletableFuture that completes when recovery finishes
     */
    CompletableFuture<Boolean> initiateRecovery(UUID failedPartitionId);
}
