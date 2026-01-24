package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks in-flight balance and sync operations to enable synchronous pause.
 *
 * <p>This class solves the problem where pause flags don't prevent in-progress
 * operations from completing. By tracking active operations, we can block
 * until all in-flight work completes before proceeding with recovery.
 *
 * <p><b>Usage Pattern</b>:
 * <pre>
 * // In balance() or sync() methods:
 * try (var token = tracker.beginOperation()) {
 *     // ... perform balance/sync work ...
 * }
 *
 * // In enterRecoveryMode():
 * tracker.pauseAndWait(5, TimeUnit.SECONDS);
 * // All in-flight operations are now complete
 *
 * // In exitRecoveryMode():
 * tracker.resume();
 * </pre>
 *
 * <p><b>Thread Safety</b>: Fully thread-safe using atomic operations and latches.
 */
public class InFlightOperationTracker {

    private static final Logger log = LoggerFactory.getLogger(InFlightOperationTracker.class);

    private final AtomicInteger activeOperations = new AtomicInteger(0);
    private final AtomicReference<CountDownLatch> completionLatch = new AtomicReference<>();
    private volatile boolean paused = false;

    /**
     * Begin tracking an operation. Call this at the START of balance()/sync().
     *
     * @return token that MUST be closed when operation completes
     * @throws IllegalStateException if operations are paused
     */
    public OperationToken beginOperation() {
        if (paused) {
            log.debug("Operation rejected: tracker is paused");
            throw new IllegalStateException("Operations are paused for recovery");
        }

        int count = activeOperations.incrementAndGet();
        log.debug("Operation started, active count: {}", count);
        return new OperationToken(this);
    }

    /**
     * Try to begin an operation, returning empty if paused.
     * Use when caller wants to skip rather than throw.
     */
    public Optional<OperationToken> tryBeginOperation() {
        if (paused) {
            return Optional.empty();
        }
        return Optional.of(beginOperation());
    }

    /**
     * Called when an operation completes (via OperationToken.close()).
     */
    void endOperation() {
        int remaining = activeOperations.decrementAndGet();
        log.debug("Operation completed, active count: {}", remaining);

        // Signal completion latch if this was the last operation
        if (paused && remaining == 0) {
            var latch = completionLatch.get();
            if (latch != null) {
                log.debug("Last operation completed, signaling pause barrier");
                latch.countDown();
            }
        }
    }

    /**
     * Pause and wait for all in-flight operations to complete.
     *
     * <p>This method:
     * <ol>
     * <li>Sets paused flag (rejects new operations)</li>
     * <li>Waits for active operations to complete</li>
     * <li>Returns when barrier is clear OR timeout expires</li>
     * </ol>
     *
     * @param timeout maximum time to wait
     * @param unit    time unit
     * @return true if all operations completed, false if timeout expired
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean pauseAndWait(long timeout, TimeUnit unit) throws InterruptedException {
        log.info("Pausing operations, waiting for {} in-flight to complete", activeOperations.get());

        // Step 1: Set pause flag to reject new operations
        paused = true;

        // Step 2: Check if any operations are active
        int active = activeOperations.get();
        if (active == 0) {
            log.debug("No in-flight operations, pause barrier clear immediately");
            return true;
        }

        // Step 3: Create latch and wait for completion
        var latch = new CountDownLatch(1);
        completionLatch.set(latch);

        // Re-check in case operations completed between check and latch setup
        if (activeOperations.get() == 0) {
            log.debug("Operations completed during latch setup");
            return true;
        }

        log.info("Waiting up to {} {} for {} operations to complete", timeout, unit, activeOperations.get());

        boolean completed = latch.await(timeout, unit);

        if (completed) {
            log.info("Pause barrier clear - all in-flight operations completed");
        } else {
            log.warn("Pause barrier timeout - {} operations still active after {} {}", activeOperations.get(), timeout,
                     unit);
        }

        return completed;
    }

    /**
     * Resume operations after recovery.
     */
    public void resume() {
        paused = false;
        completionLatch.set(null);  // Clear latch for next pause cycle
        log.info("Operations resumed");
    }

    /**
     * Check if operations are currently paused.
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Get count of active operations.
     */
    public int getActiveCount() {
        return activeOperations.get();
    }

    /**
     * Token returned by beginOperation() that must be closed when done.
     * Use with try-with-resources for automatic cleanup.
     */
    public static class OperationToken implements AutoCloseable {

        private final InFlightOperationTracker tracker;
        private volatile boolean closed = false;

        OperationToken(InFlightOperationTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                tracker.endOperation();
            }
        }
    }
}
