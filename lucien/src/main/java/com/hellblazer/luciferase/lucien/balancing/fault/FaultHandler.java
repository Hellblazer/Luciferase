package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Core fault detection and recovery coordination interface.
 * <p>
 * FaultHandler provides a unified contract for:
 * <ul>
 *   <li><b>Fault Detection</b>: Monitor partition health via heartbeats, barriers, and ghost sync</li>
 *   <li><b>Health Marking</b>: Track partition status transitions (HEALTHY, SUSPECTED, FAILED)</li>
 *   <li><b>Failure Reporting</b>: Accept barrier timeout, sync failure, and heartbeat failure reports</li>
 *   <li><b>Recovery Coordination</b>: Trigger and monitor partition recovery processes</li>
 *   <li><b>Metrics</b>: Provide detection latency, recovery success rates, and failure counts</li>
 * </ul>
 * <p>
 * <b>Thread Safety</b>: All methods must be thread-safe. Status updates may occur concurrently
 * from barrier threads, ghost sync threads, and heartbeat monitors.
 * <p>
 * <b>State Transitions</b> (PartitionStatus):
 * <pre>
 * HEALTHY → SUSPECTED (barrier timeout, sync failure, heartbeat miss)
 * SUSPECTED → HEALTHY (false alarm, partition recovers)
 * SUSPECTED → FAILED (failure confirmation threshold exceeded)
 * FAILED → HEALTHY (recovery successful - coordinated by PartitionRecovery)
 * </pre>
 * <p>
 * <b>Note</b>: Recovery phases are tracked separately via {@link RecoveryPhase}.
 * PartitionStatus only tracks fault detection state (HEALTHY, SUSPECTED, FAILED).
 * <p>
 * <b>Example Usage</b>:
 * <pre>{@code
 * // Setup
 * var config = FaultConfiguration.defaultConfig();
 * var handler = new SimpleFaultHandler(config);
 * handler.start();
 *
 * // Subscribe to events
 * handler.subscribeToChanges(event -> {
 *     log.info("Partition {} transitioned from {} to {}",
 *         event.partitionId(), event.oldStatus(), event.newStatus());
 * });
 *
 * // Track healthy partition
 * handler.markHealthy(partitionId);
 *
 * // Report failure
 * handler.reportBarrierTimeout(partitionId);
 *
 * // Register recovery
 * handler.registerRecovery(partitionId, recovery);
 *
 * // Cleanup
 * handler.stop();
 * }</pre>
 */
public interface FaultHandler {

    // ===== Fault Detection =====

    /**
     * Check health of a specific partition.
     * <p>
     * Returns current status WITHOUT changing state (pure query operation).
     * Status is determined from recent heartbeats, barrier synchronization,
     * and ghost layer communication.
     *
     * @param partitionId partition to check
     * @return current PartitionStatus, or null if partition unknown
     */
    PartitionStatus checkHealth(UUID partitionId);

    /**
     * Get read-only view of partition state.
     * <p>
     * Provides immutable snapshot including status, last seen timestamp,
     * node counts, and metrics.
     *
     * @param partitionId partition identifier
     * @return PartitionView or null if partition unknown
     */
    PartitionView getPartitionView(UUID partitionId);

    /**
     * Subscribe to partition state changes.
     * <p>
     * Callback is invoked whenever status transitions occur (e.g., HEALTHY → SUSPECTED).
     * Events are delivered on internal handler threads; listeners should avoid blocking.
     * <p>
     * Multiple subscriptions are supported. Events are delivered to all active subscribers.
     *
     * @param consumer receives PartitionChangeEvent for each transition
     * @return subscription handle for unsubscribe
     */
    Subscription subscribeToChanges(Consumer<PartitionChangeEvent> consumer);

    // ===== Health Marking =====

    /**
     * Mark a partition as healthy (restore from SUSPECTED or FAILED).
     * <p>
     * Triggers state transition event if status changes. Used when:
     * <ul>
     *   <li>Barrier synchronization completes successfully</li>
     *   <li>Heartbeat received from partition</li>
     *   <li>Ghost sync succeeds after previous failure</li>
     *   <li>Node recovers after temporary failure</li>
     * </ul>
     *
     * @param partitionId partition to mark healthy
     * @throws IllegalStateException if partition unknown (call from unregistered partition)
     */
    void markHealthy(UUID partitionId);

    // ===== Failure Reporting =====

    /**
     * Report barrier timeout for a partition.
     * <p>
     * Indicates synchronization failure, not necessarily node failure (may be
     * temporary network delay or system load). If repeated timeouts occur,
     * partition transitions HEALTHY → SUSPECTED → FAILED.
     * <p>
     * Called by barrier coordination layer (e.g., FaultAwarePartitionRegistry).
     *
     * @param partitionId affected partition
     */
    void reportBarrierTimeout(UUID partitionId);

    /**
     * Report ghost sync failure for a partition.
     * <p>
     * Indicates ghost element synchronization failed (network error, serialization
     * failure, or partition unreachable). May trigger cascading failure detection
     * if multiple syncs fail across partitions.
     * <p>
     * Called by ghost manager during boundary synchronization.
     *
     * @param partitionId affected partition
     */
    void reportSyncFailure(UUID partitionId);

    /**
     * Report node-level heartbeat failure.
     * <p>
     * Used when heartbeat mechanism detects unresponsive node. If sufficient
     * nodes in a partition fail heartbeat checks, the entire partition is
     * marked SUSPECTED.
     * <p>
     * Called by heartbeat monitoring subsystem.
     *
     * @param partitionId partition containing the node
     * @param nodeId specific node that failed heartbeat
     */
    void reportHeartbeatFailure(UUID partitionId, UUID nodeId);

    // ===== Recovery Coordination =====

    /**
     * Register recovery strategy for a partition.
     * <p>
     * Called by RecoveryCoordinator to bind partition-specific recovery logic.
     * When partition fails, registered recovery is invoked automatically.
     * <p>
     * Overwrites any previously registered recovery for this partition.
     *
     * @param partitionId target partition
     * @param recovery strategy to use for this partition
     */
    void registerRecovery(UUID partitionId, PartitionRecovery recovery);

    /**
     * Initiate recovery for failed partition.
     * <p>
     * Only valid from SUSPECTED or FAILED states. Invokes registered PartitionRecovery
     * implementation. Recovery phases are tracked separately via {@link RecoveryPhase}.
     * <p>
     * Returns immediately with future that completes when recovery finishes.
     * Future completes with {@code true} if recovery succeeded, {@code false}
     * if recovery failed.
     *
     * @param partitionId partition to recover
     * @return CompletableFuture&lt;Boolean&gt; - true if recovery completed successfully
     * @throws IllegalStateException if no recovery registered for partition
     */
    CompletableFuture<Boolean> initiateRecovery(UUID partitionId);

    /**
     * Notify recovery complete (success or failure).
     * <p>
     * Called by recovery implementations to signal recovery outcome. Updates
     * partition status and metrics:
     * <ul>
     *   <li>Success: FAILED → HEALTHY</li>
     *   <li>Failure: remains FAILED</li>
     * </ul>
     *
     * @param partitionId recovered partition
     * @param success true if recovery succeeded, false if failed
     */
    void notifyRecoveryComplete(UUID partitionId, boolean success);

    // ===== Configuration & Metrics =====

    /**
     * Get current configuration.
     *
     * @return FaultConfiguration (immutable)
     */
    FaultConfiguration getConfiguration();

    /**
     * Get metrics for a specific partition.
     * <p>
     * Provides detection latency, recovery attempts, success/failure counts,
     * and derived metrics like success rate.
     *
     * @param partitionId target partition
     * @return FaultMetrics (immutable snapshot), or null if partition unknown
     */
    FaultMetrics getMetrics(UUID partitionId);

    /**
     * Get aggregated metrics across all partitions.
     * <p>
     * Combines metrics from all tracked partitions. Useful for system-wide
     * monitoring and alerting.
     *
     * @return FaultMetrics combining all partitions
     */
    FaultMetrics getAggregateMetrics();

    // ===== Lifecycle =====

    /**
     * Start fault monitoring (after construction).
     * <p>
     * Begins heartbeat verification, barrier timeout detection, and event
     * dispatching. Must be called before using handler.
     * <p>
     * Idempotent - multiple calls have no effect.
     */
    void start();

    /**
     * Stop fault monitoring (on shutdown).
     * <p>
     * Cancels all pending operations, closes subscriptions, and stops
     * background threads.
     * <p>
     * Idempotent - multiple calls have no effect.
     */
    void stop();

    /**
     * Check if fault handler is running.
     *
     * @return true if monitoring is active (after start(), before stop())
     */
    boolean isRunning();
}
