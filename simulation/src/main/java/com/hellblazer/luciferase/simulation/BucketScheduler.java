package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * Coordinates bucket advancement with checkpointing, neighbor synchronization, and physics processing.
 * <p>
 * BucketScheduler manages the lifecycle of each simulation bucket:
 * <ol>
 *   <li>Create checkpoint for current bucket (rollback safety)</li>
 *   <li>Wait for neighbor synchronization via BucketBarrier</li>
 *   <li>Process physics for new bucket</li>
 *   <li>Advance to next bucket</li>
 * </ol>
 * <p>
 * Handles timeout failures gracefully by proceeding with available neighbors.
 * <p>
 * Usage:
 * <pre>
 * var scheduler = new BucketScheduler<>(
 *     rollback,
 *     barrier,
 *     bucket -> createCheckpoint(bucket),
 *     bucket -> processPhysics(bucket)
 * );
 *
 * // Main simulation loop
 * while (running) {
 *     var result = scheduler.advanceBucket();
 *     if (!result.isSuccess()) {
 *         log.warn("Timeout: missing {}", result.missingNeighbors());
 *     }
 *     Thread.sleep(BUCKET_DURATION_MS);
 * }
 * </pre>
 *
 * @param <ID>      Entity identifier type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class BucketScheduler<ID extends EntityID, Content> {

    /**
     * Bucket duration in milliseconds (100ms per frame).
     */
    public static final long BUCKET_DURATION_MS = 100;

    /**
     * Result of bucket advancement.
     *
     * @param isSuccess         Whether advancement succeeded (all neighbors ready)
     * @param previousBucket    Bucket number before advancement
     * @param newBucket         Bucket number after advancement
     * @param missingNeighbors  Neighbors that didn't respond (empty if isSuccess)
     */
    public record AdvanceResult(
        boolean isSuccess,
        long previousBucket,
        long newBucket,
        Set<UUID> missingNeighbors
    ) {
        /**
         * Successful advancement with all neighbors.
         */
        public static AdvanceResult success(long previousBucket, long newBucket) {
            return new AdvanceResult(true, previousBucket, newBucket, Set.of());
        }

        /**
         * Advancement with timeout (some neighbors missing).
         */
        public static AdvanceResult timeout(long previousBucket, long newBucket, Set<UUID> missing) {
            return new AdvanceResult(false, previousBucket, newBucket, Set.copyOf(missing));
        }
    }

    private final CausalRollback<ID, Content> rollback;
    private final BucketBarrier barrier;
    private final LongConsumer checkpointCallback;
    private final LongConsumer physicsCallback;
    private volatile long currentBucket;

    /**
     * Create a bucket scheduler.
     *
     * @param rollback            CausalRollback for checkpoint management
     * @param barrier             BucketBarrier for neighbor synchronization
     * @param checkpointCallback  Callback to create checkpoint (called with bucket number)
     * @param physicsCallback     Callback to process physics (called with bucket number)
     */
    public BucketScheduler(
        CausalRollback<ID, Content> rollback,
        BucketBarrier barrier,
        LongConsumer checkpointCallback,
        LongConsumer physicsCallback
    ) {
        this.rollback = rollback;
        this.barrier = barrier;
        this.checkpointCallback = checkpointCallback;
        this.physicsCallback = physicsCallback;
        this.currentBucket = 0;
    }

    /**
     * Advance to the next bucket.
     * <p>
     * Lifecycle:
     * <ol>
     *   <li>Create checkpoint for current bucket</li>
     *   <li>Wait for neighbor synchronization</li>
     *   <li>Advance bucket counter</li>
     *   <li>Process physics for new bucket</li>
     * </ol>
     *
     * @return AdvanceResult indicating success/failure and missing neighbors
     */
    public AdvanceResult advanceBucket() {
        long previousBucket = currentBucket;

        // Step 1: Create checkpoint before advancing
        checkpointCallback.accept(currentBucket);

        // Step 2: Wait for neighbor synchronization
        var waitOutcome = barrier.waitForNeighbors(currentBucket);

        // Step 3: Advance bucket (even if timeout occurred)
        currentBucket++;

        // Step 4: Process physics for new bucket
        physicsCallback.accept(currentBucket);

        // Return result
        if (waitOutcome.allReady()) {
            return AdvanceResult.success(previousBucket, currentBucket);
        } else {
            return AdvanceResult.timeout(previousBucket, currentBucket, waitOutcome.missingNeighbors());
        }
    }

    /**
     * Get the current bucket number.
     *
     * @return Current bucket
     */
    public long getCurrentBucket() {
        return currentBucket;
    }

    /**
     * Get the rollback manager.
     *
     * @return CausalRollback instance
     */
    public CausalRollback<ID, Content> getRollback() {
        return rollback;
    }

    /**
     * Get the synchronization barrier.
     *
     * @return BucketBarrier instance
     */
    public BucketBarrier getBarrier() {
        return barrier;
    }

    @Override
    public String toString() {
        return String.format("BucketScheduler{bucket=%d, barrier=%s}",
                            currentBucket, barrier);
    }
}
