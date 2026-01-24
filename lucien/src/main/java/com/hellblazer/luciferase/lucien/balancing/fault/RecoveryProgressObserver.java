package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Observer interface for recovery progress (optional).
 * <p>
 * Used by monitoring, logging, and observability systems to track recovery
 * operations in real-time. Implementations receive callbacks during recovery
 * execution to track progress and events.
 * <p>
 * This is an optional helper interface - recovery strategies may choose to
 * support observers or not. Not all {@link PartitionRecovery} implementations
 * need to support progress observation.
 * <p>
 * <b>Thread Safety</b>: Implementations must be thread-safe, as callbacks may
 * be invoked from recovery executor threads.
 * <p>
 * <b>Example Usage</b>:
 * <pre>{@code
 * var observer = new RecoveryProgressObserver() {
 *     @Override
 *     public void onProgress(RecoveryProgress progress) {
 *         log.info("Recovery {}% complete: {}", progress.percentComplete(), progress.message());
 *     }
 *
 *     @Override
 *     public void onEvent(RecoveryEvent event) {
 *         log.info("Recovery event {}: {}", event.eventType(), event.details());
 *     }
 * };
 *
 * // If recovery supports observers (cast to specific implementation):
 * if (recovery instanceof BarrierRecoveryImpl barrierRecovery) {
 *     barrierRecovery.addObserver(observer);
 * }
 * }</pre>
 */
public interface RecoveryProgressObserver {

    /**
     * Called periodically during recovery to report progress.
     * <p>
     * Implementations should avoid blocking operations, as this may delay
     * recovery execution. Log asynchronously or use non-blocking I/O.
     *
     * @param progress current recovery progress
     */
    void onProgress(RecoveryProgress progress);

    /**
     * Called when recovery state transitions occur.
     * <p>
     * Events are emitted at key points: start, phase changes, completion, failure.
     *
     * @param event recovery event details
     */
    void onEvent(RecoveryEvent event);
}
