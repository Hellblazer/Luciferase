package com.hellblazer.luciferase.simulation.scheduling;

import com.hellblazer.luciferase.simulation.scheduling.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * GGPO-style bounded rollback for handling late-arriving messages in distributed simulation.
 * <p>
 * Maintains a sliding window of checkpoints (200ms = 2 buckets @ 100ms/bucket) to support
 * rollback without unbounded state retention.
 * <p>
 * Key features:
 * - Bounded checkpoint window (MAX_ROLLBACK_BUCKETS = 2)
 * - Automatic eviction of old checkpoints
 * - Rollback to most recent checkpoint before late message
 * - Graceful rejection of messages beyond window
 * <p>
 * Thread-safe for concurrent checkpoint creation and rollback operations.
 * <p>
 * Usage:
 * <pre>
 * // Create checkpoint at bucket boundary
 * rollback.checkpoint(currentBucket, entityStates, processedMigrations);
 *
 * // Handle incoming message
 * var result = rollback.rollbackIfNeeded(messageBucket);
 * if (result.didRollback()) {
 *     // Restore state from result.restoredStates()
 *     // Reprocess from targetBucket to current
 * } else if (result.isRejected()) {
 *     // Message too old, discard
 * }
 * </pre>
 *
 * @param <ID>      Entity identifier type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class CausalRollback<ID extends EntityID, Content> {

    /**
     * Maximum number of buckets to retain for rollback (200ms window @ 100ms/bucket).
     */
    private static final int MAX_ROLLBACK_BUCKETS = 2;

    /**
     * Checkpoint for a specific bucket.
     *
     * @param bucket               Bucket number this checkpoint represents
     * @param states               Entity states at this bucket
     * @param processedMigrations  Migration UUIDs processed up to this bucket
     */
    public record Checkpoint<ID, Content>(
        long bucket,
        Map<ID, Content> states,
        Set<UUID> processedMigrations
    ) {
    }

    /**
     * Result of rollback operation.
     *
     * @param didRollback    Whether rollback was performed
     * @param isRejected     Whether message was rejected (beyond window)
     * @param targetBucket   Bucket rolled back to (if didRollback)
     * @param restoredStates Entity states restored (if didRollback)
     */
    public record RollbackResult<ID, Content>(
        boolean didRollback,
        boolean isRejected,
        long targetBucket,
        Optional<Map<ID, Content>> restoredStates
    ) {

        /**
         * No rollback needed (message is current or future).
         */
        public static <ID, Content> RollbackResult<ID, Content> noRollback() {
            return new RollbackResult<>(false, false, -1, Optional.empty());
        }

        /**
         * Message rejected (beyond rollback window).
         */
        public static <ID, Content> RollbackResult<ID, Content> rejected() {
            return new RollbackResult<>(false, true, -1, Optional.empty());
        }

        /**
         * Rollback performed to target bucket.
         */
        public static <ID, Content> RollbackResult<ID, Content> rolledBack(
            long targetBucket,
            Map<ID, Content> restoredStates
        ) {
            return new RollbackResult<>(true, false, targetBucket, Optional.of(restoredStates));
        }
    }

    private final Deque<Checkpoint<ID, Content>> checkpoints;

    /**
     * Create a new causal rollback manager.
     */
    public CausalRollback() {
        this.checkpoints = new ConcurrentLinkedDeque<>();
    }

    /**
     * Create a checkpoint at the specified bucket.
     * <p>
     * Automatically evicts checkpoints beyond MAX_ROLLBACK_BUCKETS.
     *
     * @param bucket               Bucket number
     * @param states               Entity states at this bucket
     * @param processedMigrations  Migration UUIDs processed up to this bucket
     */
    public synchronized void checkpoint(long bucket, Map<ID, Content> states, Set<UUID> processedMigrations) {
        // Create immutable copies
        var immutableStates = Map.copyOf(states);
        var immutableMigrations = Set.copyOf(processedMigrations);

        var checkpoint = new Checkpoint<>(bucket, immutableStates, immutableMigrations);

        checkpoints.addLast(checkpoint);

        // Maintain bounded window
        while (checkpoints.size() > MAX_ROLLBACK_BUCKETS) {
            checkpoints.removeFirst();
        }
    }

    /**
     * Check if rollback is needed for a message arriving at the specified bucket.
     * <p>
     * Three possible outcomes:
     * - No rollback: Message is for current or future bucket
     * - Rollback: Message is for past bucket within rollback window
     * - Rejected: Message is for bucket beyond rollback window
     *
     * @param messageBucket Bucket number of the incoming message
     * @return RollbackResult indicating outcome and restored state if rolled back
     */
    public synchronized RollbackResult<ID, Content> rollbackIfNeeded(long messageBucket) {
        if (checkpoints.isEmpty()) {
            return RollbackResult.noRollback();
        }

        long latestBucket = getLatestBucket();

        // No rollback needed for current or future messages
        if (messageBucket >= latestBucket) {
            return RollbackResult.noRollback();
        }

        long oldestBucket = getOldestBucket();

        // Reject messages beyond rollback window
        if (messageBucket < oldestBucket) {
            return RollbackResult.rejected();
        }

        // Find checkpoint to rollback to (most recent checkpoint before messageBucket)
        Checkpoint<ID, Content> targetCheckpoint = null;
        for (var checkpoint : checkpoints) {
            if (checkpoint.bucket() <= messageBucket) {
                targetCheckpoint = checkpoint;
            } else {
                break;  // Checkpoints are ordered, stop when we pass messageBucket
            }
        }

        if (targetCheckpoint == null) {
            // This shouldn't happen given the checks above, but handle defensively
            return RollbackResult.rejected();
        }

        // Discard checkpoints after rollback target
        while (!checkpoints.isEmpty() && checkpoints.getLast().bucket() > targetCheckpoint.bucket()) {
            checkpoints.removeLast();
        }

        return RollbackResult.rolledBack(targetCheckpoint.bucket(), targetCheckpoint.states());
    }

    /**
     * Get checkpoint for a specific bucket.
     *
     * @param bucket Bucket number
     * @return Optional containing checkpoint if it exists
     */
    public Optional<Checkpoint<ID, Content>> getCheckpoint(long bucket) {
        return checkpoints.stream()
                         .filter(cp -> cp.bucket() == bucket)
                         .findFirst();
    }

    /**
     * Check if a checkpoint exists for the specified bucket.
     *
     * @param bucket Bucket number
     * @return true if checkpoint exists
     */
    public boolean hasCheckpointForBucket(long bucket) {
        return checkpoints.stream()
                         .anyMatch(cp -> cp.bucket() == bucket);
    }

    /**
     * Check if there are no checkpoints.
     *
     * @return true if no checkpoints exist
     */
    public boolean isEmpty() {
        return checkpoints.isEmpty();
    }

    /**
     * Get the number of checkpoints currently retained.
     *
     * @return Checkpoint count
     */
    public int getCheckpointCount() {
        return checkpoints.size();
    }

    /**
     * Get the oldest bucket with a checkpoint.
     *
     * @return Oldest bucket number, or -1 if no checkpoints
     */
    public long getOldestBucket() {
        return checkpoints.isEmpty() ? -1 : checkpoints.getFirst().bucket();
    }

    /**
     * Get the most recent bucket with a checkpoint.
     *
     * @return Latest bucket number, or -1 if no checkpoints
     */
    public long getLatestBucket() {
        return checkpoints.isEmpty() ? -1 : checkpoints.getLast().bucket();
    }

    @Override
    public String toString() {
        if (checkpoints.isEmpty()) {
            return "CausalRollback{empty}";
        }
        return String.format("CausalRollback{buckets=%d-%d, count=%d}",
                            getOldestBucket(), getLatestBucket(), checkpoints.size());
    }
}
