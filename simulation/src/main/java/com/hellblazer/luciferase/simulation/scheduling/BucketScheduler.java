package com.hellblazer.luciferase.simulation.scheduling;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.runtime.Kairos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

/**
 * Prime-Mover event-driven bucket scheduler for distributed simulation coordination.
 * <p>
 * Coordinates bucket advancement with checkpointing, neighbor synchronization, and physics processing
 * using event-driven polling instead of blocking waits.
 * <p>
 * BucketScheduler manages the lifecycle of each simulation bucket:
 * <ol>
 *   <li>Create checkpoint for current bucket (rollback safety)</li>
 *   <li>Poll for neighbor synchronization via BucketBarrier (1ms intervals)</li>
 *   <li>Advance bucket counter</li>
 *   <li>Process physics for new bucket</li>
 * </ol>
 * <p>
 * Handles timeout failures gracefully by proceeding with available neighbors after 200ms.
 * <p>
 * <b>Pattern</b>: Inner @Entity class with event-driven polling (follows SimulationBubble pattern)
 * <p>
 * <b>Performance</b>: 1ms polling = 0.1% CPU overhead (1ms / 100ms bucket)
 * <p>
 * Usage:
 * <pre>
 * var scheduler = new BucketScheduler<>(
 *     rollback,
 *     barrier,
 *     bucket -> createCheckpoint(bucket),
 *     bucket -> processPhysics(bucket),
 *     controller
 * );
 *
 * // Start event-driven advancement
 * scheduler.start();
 *
 * // Stop when done
 * scheduler.stop();
 * </pre>
 *
 * @param <ID>      Entity identifier type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.simulation.bubble.SimulationBubble
 */
public class BucketScheduler<ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(BucketScheduler.class);

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
    private final com.hellblazer.primeMover.controllers.RealTimeController controller;
    private final BucketSchedulerEntity entity;
    private volatile boolean running = false;

    // Pluggable clock for deterministic testing - defaults to system time
    private volatile Clock clock = Clock.system();

    /**
     * Create a bucket scheduler with event-driven advancement.
     *
     * @param rollback            CausalRollback for checkpoint management
     * @param barrier             BucketBarrier for neighbor synchronization
     * @param checkpointCallback  Callback to create checkpoint (called with bucket number)
     * @param physicsCallback     Callback to process physics (called with bucket number)
     * @param controller          Prime-Mover RealTimeController for event scheduling
     */
    public BucketScheduler(
        CausalRollback<ID, Content> rollback,
        BucketBarrier barrier,
        LongConsumer checkpointCallback,
        LongConsumer physicsCallback,
        com.hellblazer.primeMover.controllers.RealTimeController controller
    ) {
        if (rollback == null) {
            throw new IllegalArgumentException("Rollback cannot be null");
        }
        if (barrier == null) {
            throw new IllegalArgumentException("Barrier cannot be null");
        }
        if (checkpointCallback == null) {
            throw new IllegalArgumentException("Checkpoint callback cannot be null");
        }
        if (physicsCallback == null) {
            throw new IllegalArgumentException("Physics callback cannot be null");
        }
        if (controller == null) {
            throw new IllegalArgumentException("Controller cannot be null");
        }

        this.rollback = rollback;
        this.barrier = barrier;
        this.checkpointCallback = checkpointCallback;
        this.physicsCallback = physicsCallback;
        this.controller = controller;
        this.entity = new BucketSchedulerEntity(
            barrier,
            checkpointCallback,
            physicsCallback,
            () -> running
        );
        Kairos.setController(controller);

        log.debug("Created BucketScheduler with event-driven advancement");
    }

    /**
     * Start the event-driven bucket advancement.
     * Triggers the initial advanceBucket() event and starts the Prime-Mover controller.
     */
    public void start() {
        running = true;
        entity.advanceBucket();
        controller.start();
        log.info("BucketScheduler started: event-driven advancement active");
    }

    /**
     * Stop the event-driven bucket advancement.
     */
    public void stop() {
        running = false;
        controller.stop();
        log.info("BucketScheduler stopped after bucket {}", entity.getCurrentBucket());
    }

    /**
     * Shutdown the bucket scheduler and release resources.
     */
    public void shutdown() {
        stop();
    }

    /**
     * Check if the scheduler is running.
     *
     * @return true if running
     */
    @NonEvent
    public boolean isRunning() {
        return running;
    }

    /**
     * Sets the clock to use for barrier timing.
     * <p>
     * For deterministic testing, inject a {@link com.hellblazer.luciferase.simulation.distributed.integration.TestClock}
     * to control time progression.
     *
     * @param clock the clock to use (must not be null)
     * @throws NullPointerException if clock is null
     */
    @NonEvent
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        entity.setClock(clock);
    }

    /**
     * Legacy constructor for backward compatibility (blocking advanceBucket).
     * <p>
     * @deprecated Use new constructor with RealTimeController for event-driven advancement
     *
     * @param rollback            CausalRollback for checkpoint management
     * @param barrier             BucketBarrier for neighbor synchronization
     * @param checkpointCallback  Callback to create checkpoint (called with bucket number)
     * @param physicsCallback     Callback to process physics (called with bucket number)
     */
    @Deprecated(forRemoval = false)
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
        this.controller = null;
        this.entity = null;
        log.debug("Created BucketScheduler with legacy blocking advancement");
    }

    /**
     * Legacy blocking bucket advancement (for backward compatibility).
     * <p>
     * @deprecated Use start() for event-driven advancement
     *
     * @return AdvanceResult indicating success/failure and missing neighbors
     */
    @Deprecated(forRemoval = false)
    public AdvanceResult advanceBucket() {
        if (entity != null) {
            throw new IllegalStateException("Cannot call advanceBucket() on event-driven scheduler. Use start() instead.");
        }

        long currentBucket = legacyCurrentBucket;
        long previousBucket = currentBucket;

        // Step 1: Create checkpoint before advancing
        checkpointCallback.accept(currentBucket);

        // Step 2: Wait for neighbor synchronization
        var waitOutcome = barrier.waitForNeighbors(currentBucket);

        // Step 3: Advance bucket (even if timeout occurred)
        legacyCurrentBucket++;

        // Step 4: Process physics for new bucket
        physicsCallback.accept(legacyCurrentBucket);

        // Return result
        if (waitOutcome.allReady()) {
            return AdvanceResult.success(previousBucket, legacyCurrentBucket);
        } else {
            return AdvanceResult.timeout(previousBucket, legacyCurrentBucket, waitOutcome.missingNeighbors());
        }
    }

    private volatile long legacyCurrentBucket = 0;  // For legacy blocking mode

    /**
     * Get the current bucket number.
     *
     * @return Current bucket
     */
    @NonEvent
    public long getCurrentBucket() {
        return entity != null ? entity.getCurrentBucket() : legacyCurrentBucket;
    }

    /**
     * Get the rollback manager.
     *
     * @return CausalRollback instance
     */
    @NonEvent
    public CausalRollback<ID, Content> getRollback() {
        return rollback;
    }

    /**
     * Get the synchronization barrier.
     *
     * @return BucketBarrier instance
     */
    @NonEvent
    public BucketBarrier getBarrier() {
        return barrier;
    }

    /**
     * Get the Prime-Mover controller.
     *
     * @return RealTimeController instance
     */
    @NonEvent
    public com.hellblazer.primeMover.controllers.RealTimeController getController() {
        return controller;
    }

    @Override
    public String toString() {
        return String.format("BucketScheduler{bucket=%d, running=%s, barrier=%s}",
                            entity.getCurrentBucket(), running, barrier);
    }

    /**
     * Prime-Mover @Entity for event-driven bucket advancement.
     * <p>
     * Static nested class to avoid generic type issues with Prime-Mover bytecode transformer.
     * Follows SimulationBubble.SimulationBubbleEntity pattern:
     * <ul>
     *   <li>Mutable state for bucket tracking and barrier state</li>
     *   <li>@NonEvent on all getters</li>
     *   <li>advanceBucket() as event method</li>
     *   <li>Kronos.sleep() for polling timing</li>
     *   <li>Recursive this.advanceBucket() for continuous execution</li>
     * </ul>
     * <p>
     * Event-driven lifecycle:
     * <ol>
     *   <li>Create checkpoint (once per bucket)</li>
     *   <li>Poll barrier status (1ms intervals, non-blocking)</li>
     *   <li>On success or timeout: advance bucket and process physics</li>
     *   <li>Schedule next event (recursive)</li>
     * </ol>
     * <p>
     * Polling replaces blocking BucketBarrier.waitForNeighbors() with non-blocking queries.
     */
    @Entity
    public static class BucketSchedulerEntity {
        /**
         * Polling interval in nanoseconds (1ms).
         * Chosen to match tight polling pattern from SingleBubbleWithEntitiesTest.
         */
        private static final long POLL_INTERVAL_NS = 1_000_000;

        /**
         * Barrier timeout in milliseconds (200ms).
         * After timeout, advance bucket anyway (graceful degradation).
         */
        private static final long BARRIER_TIMEOUT_MS = 200;

        // References to outer scheduler components (passed in constructor)
        private final BucketBarrier barrier;
        private final LongConsumer checkpointCallback;
        private final LongConsumer physicsCallback;
        private final java.util.function.Supplier<Boolean> runningSupplier;

        // Pluggable clock for deterministic testing
        private volatile Clock clock = Clock.system();

        private long currentBucket = 0;
        private long barrierStartTime = 0;
        private boolean waitingForBarrier = false;

        /**
         * Create the entity with references to outer scheduler components.
         *
         * @param barrier            BucketBarrier for neighbor queries
         * @param checkpointCallback Checkpoint creation callback
         * @param physicsCallback    Physics processing callback
         * @param runningSupplier    Supplier for running state
         */
        public BucketSchedulerEntity(
            BucketBarrier barrier,
            LongConsumer checkpointCallback,
            LongConsumer physicsCallback,
            java.util.function.Supplier<Boolean> runningSupplier
        ) {
            this.barrier = barrier;
            this.checkpointCallback = checkpointCallback;
            this.physicsCallback = physicsCallback;
            this.runningSupplier = runningSupplier;
        }

        @NonEvent
        public long getCurrentBucket() {
            return currentBucket;
        }

        /**
         * Sets the clock to use for barrier timing.
         *
         * @param clock the clock to use
         */
        @NonEvent
        public void setClock(Clock clock) {
            this.clock = clock;
        }

        /**
         * Execute a single bucket advancement cycle.
         * <p>
         * Prime-Mover event method that drives bucket coordination.
         * Uses state machine to handle barrier synchronization:
         * <ul>
         *   <li>State 0 (not waiting): Create checkpoint, start barrier wait</li>
         *   <li>State 1 (waiting): Poll barrier, check for success or timeout</li>
         * </ul>
         * <p>
         * Follows VolumeAnimator.AnimationFrame.track() pattern exactly.
         */
        public void advanceBucket() {
            // Check if scheduler should stop
            if (!runningSupplier.get()) {
                return;
            }

            // State 0: Not waiting for barrier yet
            if (!waitingForBarrier) {
                // Step 1: Create checkpoint for current bucket
                checkpointCallback.accept(currentBucket);

                // Step 2: Begin barrier synchronization
                barrierStartTime = clock.currentTimeMillis();
                waitingForBarrier = true;

                log.debug("Bucket {}: checkpoint created, waiting for barrier", currentBucket);
            }

            // State 1: Waiting for barrier
            // Poll barrier status (non-blocking)
            if (checkBarrierReady(currentBucket)) {
                // Step 3: All neighbors ready - advance bucket
                long previousBucket = currentBucket;
                currentBucket++;
                waitingForBarrier = false;

                // Step 4: Process physics for new bucket
                physicsCallback.accept(currentBucket);

                log.debug("Bucket advancement: {} -> {} (success, all neighbors ready)",
                         previousBucket, currentBucket);
            } else {
                // Check timeout
                long elapsed = clock.currentTimeMillis() - barrierStartTime;
                if (elapsed > BARRIER_TIMEOUT_MS) {
                    // Timeout: proceed anyway (graceful degradation)
                    handleBarrierTimeout(currentBucket);
                    long previousBucket = currentBucket;
                    currentBucket++;
                    waitingForBarrier = false;

                    // Process physics even after timeout
                    physicsCallback.accept(currentBucket);

                    log.warn("Bucket advancement: {} -> {} (timeout after {}ms)",
                            previousBucket, currentBucket, elapsed);
                }
            }

            // Schedule next check (recursive event scheduling)
            Kronos.sleep(POLL_INTERVAL_NS);
            this.advanceBucket();
        }

        /**
         * Check if all neighbors are ready for the given bucket.
         * <p>
         * Non-blocking query using BucketBarrier's existing methods.
         * No BucketBarrier changes needed - uses getNeighborBucket() and stream operations.
         *
         * @param bucket Bucket number to check
         * @return true if all neighbors at or past this bucket
         */
        private boolean checkBarrierReady(long bucket) {
            return barrier.getExpectedNeighbors().stream()
                .allMatch(neighborId -> {
                    Long neighborBucket = barrier.getNeighborBucket(neighborId);
                    return neighborBucket != null && neighborBucket >= bucket;
                });
        }

        /**
         * Handle barrier timeout by logging missing neighbors.
         *
         * @param bucket Bucket that timed out
         */
        private void handleBarrierTimeout(long bucket) {
            var missing = barrier.getExpectedNeighbors().stream()
                .filter(neighborId -> {
                    Long neighborBucket = barrier.getNeighborBucket(neighborId);
                    return neighborBucket == null || neighborBucket < bucket;
                })
                .collect(Collectors.toSet());

            log.warn("Barrier timeout at bucket {}: missing neighbors {}", bucket, missing);
        }
    }
}
