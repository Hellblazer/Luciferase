package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p><b>Thread Safety</b>: Fully thread-safe. A single {@link ReentrantLock} guards the active-operation count,
 * the pause flag, and the completion {@link Condition}. Begin/end/pause/resume all mutate that state under the
 * lock, so there is no publish-then-signal window (Luciferase-lere9): an {@code endOperation} that drops the
 * count to zero always signals the same condition the paused waiter is parked on, eliminating the spurious
 * timeout that the prior latch-publication design could stall on.
 */
public class InFlightOperationTracker {

    private static final Logger log = LoggerFactory.getLogger(InFlightOperationTracker.class);

    private final ReentrantLock lock = new ReentrantLock();
    // Signalled when activeOperations reaches 0 while paused; awaited by pauseAndWait.
    private final Condition allOperationsDone = lock.newCondition();
    private int activeOperations = 0;   // guarded by lock
    private boolean paused = false;     // guarded by lock

    /**
     * Begin tracking an operation. Call this at the START of balance()/sync().
     *
     * @return token that MUST be closed when operation completes
     * @throws IllegalStateException if operations are paused
     */
    public OperationToken beginOperation() {
        lock.lock();
        try {
            if (paused) {
                log.debug("Operation rejected: tracker is paused");
                throw new IllegalStateException("Operations are paused for recovery");
            }
            activeOperations++;
            log.debug("Operation started, active count: {}", activeOperations);
            return new OperationToken(this);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Try to begin an operation, returning empty if paused.
     * Use when caller wants to skip rather than throw.
     */
    public Optional<OperationToken> tryBeginOperation() {
        lock.lock();
        try {
            if (paused) {
                return Optional.empty();
            }
            activeOperations++;
            log.debug("Operation started (try), active count: {}", activeOperations);
            return Optional.of(new OperationToken(this));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called when an operation completes (via OperationToken.close()).
     */
    void endOperation() {
        lock.lock();
        try {
            if (activeOperations > 0) {
                activeOperations--;
            }
            log.debug("Operation completed, active count: {}", activeOperations);
            // Signal the pause barrier under the lock — no waiter can miss this, even if pauseAndWait has not yet
            // begun awaiting (it holds the lock across the flag-set and the await loop).
            if (paused && activeOperations == 0) {
                log.debug("Last operation completed, signaling pause barrier");
                allOperationsDone.signalAll();
            }
        } finally {
            lock.unlock();
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
        lock.lock();
        try {
            log.info("Pausing operations, waiting for {} in-flight to complete", activeOperations);
            paused = true;
            long nanos = unit.toNanos(timeout);
            while (activeOperations > 0) {
                if (nanos <= 0L) {
                    log.warn("Pause barrier timeout - {} operations still active after {} {}", activeOperations,
                             timeout, unit);
                    return false;
                }
                // awaitNanos atomically releases the lock and re-acquires it on signal/timeout, so an endOperation
                // signal cannot be lost between the activeOperations check and the wait.
                nanos = allOperationsDone.awaitNanos(nanos);
            }
            log.info("Pause barrier clear - all in-flight operations completed");
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resume operations after recovery.
     */
    public void resume() {
        lock.lock();
        try {
            paused = false;
            log.info("Operations resumed");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if operations are currently paused.
     */
    public boolean isPaused() {
        lock.lock();
        try {
            return paused;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get count of active operations.
     */
    public int getActiveCount() {
        lock.lock();
        try {
            return activeOperations;
        } finally {
            lock.unlock();
        }
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
