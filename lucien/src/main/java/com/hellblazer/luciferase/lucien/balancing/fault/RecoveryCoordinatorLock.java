package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Coordinates concurrent partition recoveries with quorum enforcement (Phase 4.1).
 *
 * <p>Provides three critical coordination mechanisms:
 * <ol>
 *   <li><b>Quorum Check</b>: Ensures majority of partitions are active before recovery</li>
 *   <li><b>Semaphore Limit</b>: Caps concurrent recoveries to prevent resource exhaustion</li>
 *   <li><b>Ghost Manager Lock</b>: Serializes ghost layer operations during recovery</li>
 * </ol>
 *
 * <p><b>Quorum Requirement</b>:
 * <pre>
 * activePartitions &gt; totalPartitions / 2  // Strict majority
 * </pre>
 *
 * <p>Based on Byzantine failure literature, majority survival is required for
 * safe recovery. If quorum is lost, recovery is rejected to prevent split-brain.
 *
 * <p><b>Concurrency Limiting</b>:
 * <pre>
 * maxConcurrentRecoveries (default 3)
 * </pre>
 *
 * <p>Limits resource usage. If limit reached, new recovery attempts wait or timeout.
 *
 * <p><b>Ghost Manager Serialization</b>:
 * <pre>
 * withGhostManagerLock(() -&gt; {
 *     ghostManager.removeKnownProcess(failedRank);
 *     return null;
 * });
 * </pre>
 *
 * <p>Prevents concurrent ghost manager calls that could corrupt state.
 *
 * <p><b>Usage Example</b>:
 * <pre>
 * var lock = new RecoveryCoordinatorLock(3); // Max 3 concurrent recoveries
 *
 * // Attempt to acquire recovery lock
 * if (lock.acquireRecoveryLock(partitionId, topology, 5, TimeUnit.SECONDS)) {
 *     try {
 *         // Perform recovery with exclusive access
 *         lock.withGhostManagerLock(() -> {
 *             ghostManager.removeKnownProcess(failedRank);
 *             return null;
 *         });
 *     } finally {
 *         lock.releaseRecoveryLock(partitionId);
 *     }
 * } else {
 *     // Lock acquisition failed (quorum lost or semaphore exhausted)
 * }
 * </pre>
 *
 * @see FaultConfiguration#maxConcurrentRecoveries()
 */
public class RecoveryCoordinatorLock {

    private final int maxConcurrentRecoveries;
    private final Semaphore recoverySemaphore;
    private final Set<UUID> activeRecoveries = ConcurrentHashMap.newKeySet();
    private final Object ghostManagerLock = new Object();

    /**
     * Creates a new coordinator lock with specified concurrency limit.
     *
     * @param maxConcurrentRecoveries maximum concurrent recoveries allowed (must be &gt; 0)
     * @throws IllegalArgumentException if maxConcurrentRecoveries &lt; 1
     */
    public RecoveryCoordinatorLock(int maxConcurrentRecoveries) {
        if (maxConcurrentRecoveries < 1) {
            throw new IllegalArgumentException(
                "maxConcurrentRecoveries must be at least 1, got: " + maxConcurrentRecoveries
            );
        }
        this.maxConcurrentRecoveries = maxConcurrentRecoveries;
        this.recoverySemaphore = new Semaphore(maxConcurrentRecoveries);
    }

    /**
     * Check if quorum is maintained (majority of partitions active).
     *
     * <p><b>Quorum Formula</b>: activePartitions &gt; totalPartitions / 2
     *
     * <p><b>Examples</b>:
     * <ul>
     *   <li>3 active out of 5 total → true (60% &gt; 50%)</li>
     *   <li>2 active out of 4 total → false (50% not &gt; 50%)</li>
     *   <li>2 active out of 3 total → true (66% &gt; 50%)</li>
     * </ul>
     *
     * @param activePartitions currently active partitions
     * @param totalPartitions total partitions in topology
     * @return true if quorum maintained, false if majority failed
     */
    public boolean hasQuorum(int activePartitions, int totalPartitions) {
        return activePartitions > totalPartitions / 2;
    }

    /**
     * Acquire recovery lock for a partition.
     *
     * <p><b>Acquisition Conditions</b>:
     * <ol>
     *   <li>Quorum is maintained (majority of partitions active)</li>
     *   <li>Partition is not already being recovered</li>
     *   <li>Semaphore permit available (concurrent recovery limit not exceeded)</li>
     * </ol>
     *
     * <p><b>Failure Scenarios</b>:
     * <ul>
     *   <li>Quorum lost → returns false immediately</li>
     *   <li>Partition already recovering → returns false immediately</li>
     *   <li>Semaphore timeout → returns false after timeout</li>
     * </ul>
     *
     * <p><b>Thread Safety</b>: This method is synchronized to prevent race conditions
     * where two threads could acquire the lock for the same partition simultaneously.
     * The synchronization ensures atomic check-and-add semantics.
     *
     * @param partitionId partition to recover
     * @param topology current partition topology
     * @param timeout lock acquisition timeout
     * @param unit timeout unit
     * @return true if lock acquired, false if quorum lost or timeout
     * @throws InterruptedException if interrupted while waiting for semaphore
     */
    public synchronized boolean acquireRecoveryLock(UUID partitionId, PartitionTopology topology,
                                                    long timeout, TimeUnit unit)
        throws InterruptedException {

        // Check quorum (safe inside synchronized)
        int active = topology.activeRanks().size();
        int total = topology.totalPartitions();

        if (!hasQuorum(active, total)) {
            return false; // Cannot recover without majority
        }

        // Atomic check-and-reserve
        // Check if already recovering this partition
        if (activeRecoveries.contains(partitionId)) {
            return false;
        }

        // Optimistically add partition to reserve the slot
        // This prevents other threads from proceeding even if we're waiting on semaphore
        activeRecoveries.add(partitionId);

        // Acquire semaphore permit (may wait)
        try {
            if (!recoverySemaphore.tryAcquire(timeout, unit)) {
                // Failed to get permit - rollback the reservation
                activeRecoveries.remove(partitionId);
                return false;
            }

            return true;

        } catch (InterruptedException e) {
            // Rollback on interruption
            activeRecoveries.remove(partitionId);
            throw e;
        }
    }

    /**
     * Release recovery lock for a partition.
     *
     * <p>Idempotent: If partition is not in active recovery set, this is a no-op.
     *
     * @param partitionId partition to release
     */
    public void releaseRecoveryLock(UUID partitionId) {
        if (activeRecoveries.remove(partitionId)) {
            recoverySemaphore.release();
        }
    }

    /**
     * Execute action with exclusive ghost manager access.
     *
     * <p>Serializes all ghost manager operations during recovery to prevent
     * state corruption. All threads must funnel through this method to access
     * the ghost manager.
     *
     * <p><b>Thread Safety</b>: Uses synchronized block to ensure mutual exclusion.
     *
     * <p><b>Example</b>:
     * <pre>
     * lock.withGhostManagerLock(() -> {
     *     ghostManager.removeKnownProcess(failedRank);
     *     ghostManager.updateGhostLayer();
     *     return null;
     * });
     * </pre>
     *
     * @param action action to execute with exclusive access
     * @param <T> return type
     * @return result of action
     */
    public <T> T withGhostManagerLock(Supplier<T> action) {
        synchronized (ghostManagerLock) {
            return action.get();
        }
    }

    /**
     * Get current count of active recoveries.
     *
     * @return number of partitions currently being recovered
     */
    public int activeRecoveryCount() {
        return activeRecoveries.size();
    }

    /**
     * Get IDs of partitions currently being recovered.
     *
     * @return immutable snapshot of active recovery IDs
     */
    public Set<UUID> getActiveRecoveries() {
        return Set.copyOf(activeRecoveries);
    }
}
