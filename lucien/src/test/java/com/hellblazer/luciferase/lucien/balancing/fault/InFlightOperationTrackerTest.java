package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for InFlightOperationTracker TOCTOU race fix.
 *
 * <p>Validates that the increment-first pattern closes the time-of-check-time-of-use
 * race between operation threads and the pause barrier thread.
 */
class InFlightOperationTrackerTest {

    private InFlightOperationTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new InFlightOperationTracker();
    }

    /**
     * Test basic operation tracking without pause.
     */
    @Test
    void testBasicOperationTracking() {
        assertEquals(0, tracker.getActiveCount());

        var token1 = tracker.beginOperation();
        assertEquals(1, tracker.getActiveCount());

        var token2 = tracker.beginOperation();
        assertEquals(2, tracker.getActiveCount());

        token1.close();
        assertEquals(1, tracker.getActiveCount());

        token2.close();
        assertEquals(0, tracker.getActiveCount());
    }

    /**
     * Test try variant when not paused.
     */
    @Test
    void testTryBeginOperationWhenNotPaused() {
        var opt = tracker.tryBeginOperation();
        assertTrue(opt.isPresent());
        assertEquals(1, tracker.getActiveCount());

        var token = opt.get();
        token.close();
        assertEquals(0, tracker.getActiveCount());
    }

    /**
     * Test pause barrier with no operations in flight.
     */
    @Test
    void testPauseBarrierWhenNoOperations() throws InterruptedException {
        assertTrue(tracker.pauseAndWait(1, TimeUnit.SECONDS));
        assertTrue(tracker.isPaused());
        tracker.resume();
        assertFalse(tracker.isPaused());
    }

    /**
     * Test pause barrier blocks until operations complete.
     */
    @Test
    void testPauseBarrierWaitsForOperations() throws InterruptedException {
        var token = tracker.beginOperation();
        assertEquals(1, tracker.getActiveCount());

        var pauseCompleted = new AtomicBoolean(false);

        var pauseThread = new Thread(() -> {
            try {
                pauseCompleted.set(tracker.pauseAndWait(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        pauseThread.start();

        // Give pause thread time to set paused flag
        Thread.sleep(100);
        assertTrue(tracker.isPaused());
        assertEquals(1, tracker.getActiveCount());

        // Close operation - should trigger pause barrier completion
        token.close();

        // Wait for pause thread to complete
        pauseThread.join(1000);
        assertFalse(pauseThread.isAlive());
        assertTrue(pauseCompleted.get());
    }

    /**
     * Test TOCTOU race correctness: Increment-first ensures pause barrier is reliable.
     *
     * <p>Key insight: With increment-first pattern, if pause thread reads activeOperations=0,
     * that count will remain 0 (no new operations can sneak in after pause check).
     */
    @Test
    void testTOCTOURaceBasicCorrectness() throws InterruptedException {
        // Start an operation
        var token = tracker.beginOperation();
        assertEquals(1, tracker.getActiveCount());

        // Pause should wait for this operation
        var pauseCompleted = new AtomicBoolean(false);
        var pauseThread = new Thread(() -> {
            try {
                pauseCompleted.set(tracker.pauseAndWait(2, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        pauseThread.start();
        Thread.sleep(100);

        // At this point, pause thread is waiting (activeOperations=1)
        assertTrue(tracker.isPaused());
        assertEquals(1, tracker.getActiveCount());

        // New operations should fail (this validates the increment-first pattern worked)
        var exception = assertThrows(IllegalStateException.class, tracker::beginOperation);
        assertEquals("Operations are paused for recovery", exception.getMessage());

        // Close the operation - pause should now succeed
        token.close();
        pauseThread.join(1000);

        assertTrue(pauseCompleted.get(), "Pause should have completed");
        assertEquals(0, tracker.getActiveCount());
    }

    /**
     * Test pause barrier correctness: verify operations are rejected while paused.
     */
    @Test
    void testPauseBarrierRejectsNewOperations() throws InterruptedException {
        // Initial pause with no operations
        assertTrue(tracker.pauseAndWait(100, TimeUnit.MILLISECONDS));
        assertTrue(tracker.isPaused());

        // Try to start new operation while paused
        var exception = assertThrows(IllegalStateException.class, tracker::beginOperation);
        assertEquals("Operations are paused for recovery", exception.getMessage());
        assertEquals(0, tracker.getActiveCount());

        // Resume and verify we can start operations again
        tracker.resume();
        assertFalse(tracker.isPaused());
        var token = tracker.beginOperation();
        assertEquals(1, tracker.getActiveCount());
        token.close();
    }

    /**
     * Test tryBeginOperation returns empty when paused.
     */
    @Test
    void testTryBeginOperationWhenPaused() throws InterruptedException {
        assertTrue(tracker.pauseAndWait(100, TimeUnit.MILLISECONDS));
        assertTrue(tracker.isPaused());

        var opt = tracker.tryBeginOperation();
        assertFalse(opt.isPresent());
        assertEquals(0, tracker.getActiveCount());

        tracker.resume();
    }

    /**
     * Test that operations started before pause complete after pause detection.
     */
    @Test
    void testOperationsStartedBeforePauseCanComplete() throws InterruptedException {
        var token = tracker.beginOperation();
        assertEquals(1, tracker.getActiveCount());

        var pauseCompleted = new AtomicBoolean(false);

        // Pause in background
        var pauseThread = new Thread(() -> {
            try {
                pauseCompleted.set(tracker.pauseAndWait(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        pauseThread.start();
        Thread.sleep(100);

        // Pause has started, now close the operation
        token.close();
        assertEquals(0, tracker.getActiveCount());

        pauseThread.join(500);
        assertTrue(pauseCompleted.get(), "Pause should complete after operation closes");
    }

    /**
     * Test resume after pause.
     */
    @Test
    void testResumeAfterPause() throws InterruptedException {
        assertTrue(tracker.pauseAndWait(100, TimeUnit.MILLISECONDS));
        assertTrue(tracker.isPaused());

        tracker.resume();
        assertFalse(tracker.isPaused());

        // Should be able to start operations again
        var token = tracker.beginOperation();
        assertEquals(1, tracker.getActiveCount());
        token.close();
        assertEquals(0, tracker.getActiveCount());
    }

    /**
     * Test pause timeout when operations never complete.
     */
    @Test
    void testPauseTimeout() throws InterruptedException {
        var token = tracker.beginOperation();
        assertEquals(1, tracker.getActiveCount());

        // Try to pause with short timeout - should timeout since we hold the operation
        boolean result = tracker.pauseAndWait(50, TimeUnit.MILLISECONDS);
        assertFalse(result, "Pause should timeout");
        assertEquals(1, tracker.getActiveCount());

        // But we should still be paused
        assertTrue(tracker.isPaused());

        // Close operation and verify state
        token.close();
        assertEquals(0, tracker.getActiveCount());
        assertTrue(tracker.isPaused());
    }

    /**
     * Test token close idempotence: closing same token multiple times is safe.
     */
    @Test
    void testTokenCloseIdempotence() {
        var token = tracker.beginOperation();
        assertEquals(1, tracker.getActiveCount());

        token.close();
        assertEquals(0, tracker.getActiveCount());

        // Closing again should be safe
        token.close();
        assertEquals(0, tracker.getActiveCount());
    }

    /**
     * Test multiple operations with pause: ensure all must complete for pause.
     */
    @Test
    void testMultipleOperationsWithPause() throws InterruptedException {
        var token1 = tracker.beginOperation();
        var token2 = tracker.beginOperation();
        var token3 = tracker.beginOperation();
        assertEquals(3, tracker.getActiveCount());

        var pauseCompleted = new AtomicBoolean(false);

        var pauseThread = new Thread(() -> {
            try {
                pauseCompleted.set(tracker.pauseAndWait(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        pauseThread.start();
        Thread.sleep(100);

        assertTrue(tracker.isPaused());

        // Close operations one by one
        token1.close();
        Thread.sleep(50);
        assertEquals(2, tracker.getActiveCount());
        // Pause should still be waiting

        token2.close();
        Thread.sleep(50);
        assertEquals(1, tracker.getActiveCount());
        // Pause should still be waiting

        token3.close();
        assertEquals(0, tracker.getActiveCount());

        pauseThread.join(500);
        assertTrue(pauseCompleted.get(), "Pause should complete after all operations close");
    }
}
