/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.causality;

import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for EventReprocessor gap handling with 30-second timeout.
 *
 * Tests verify the gap detection, timeout, and recovery mechanisms when events
 * are lost or delayed beyond queue capacity. Gap handling triggers view changes
 * after 30 seconds of unresolved gaps.
 *
 * @author hal.hildebrand
 */
class EventReprocessorGapHandlingTest {

    private static final long MIN_LOOKAHEAD_MS = 100L;
    private static final long MAX_LOOKAHEAD_MS = 500L;
    private static final int SMALL_QUEUE_SIZE = 10; // Small queue for easy overflow
    private static final long GAP_TIMEOUT_MS = 30000L; // 30 seconds

    private EventReprocessor reprocessor;
    private long currentTime;

    @BeforeEach
    void setUp() {
        currentTime = System.currentTimeMillis();
        reprocessor = new EventReprocessor(
            new EventReprocessor.Configuration(
                MIN_LOOKAHEAD_MS,
                MAX_LOOKAHEAD_MS,
                SMALL_QUEUE_SIZE,
                GAP_TIMEOUT_MS
            )
        );
    }

    /**
     * Test 1: Gap detection on queue overflow.
     * Verify that GapState.DETECTED is set when queue overflows.
     */
    @Test
    void testGapDetectionOnOverflow() {
        // Fill queue to capacity
        for (int i = 0; i < SMALL_QUEUE_SIZE; i++) {
            var event = createEvent("entity" + i, currentTime, i);
            assertTrue(reprocessor.queueEvent(event), "Should queue event " + i);
        }

        // Verify queue is at capacity
        assertEquals(SMALL_QUEUE_SIZE, reprocessor.getQueueDepth());
        assertEquals(EventReprocessor.GapState.NONE, reprocessor.getGapState());

        // Overflow the queue
        var overflowEvent = createEvent("overflow", currentTime, SMALL_QUEUE_SIZE);
        assertFalse(reprocessor.queueEvent(overflowEvent), "Should drop overflow event");

        // Verify gap detected
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());
        assertTrue(reprocessor.getGapStartTimeMs() > 0L, "Gap start time should be set");
        assertEquals(1L, reprocessor.getTotalGaps(), "Should track one gap");
    }

    /**
     * Test 2: Gap timeout after 30 seconds.
     * Verify that checkGapTimeout() returns to normal after timeout expires.
     */
    @Test
    void testGapTimeoutAfter30Seconds() {
        // Trigger gap detection
        fillQueueAndOverflow();

        // Verify gap detected
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());
        var gapStartTime = reprocessor.getGapStartTimeMs();

        // Check timeout before 30 seconds - should remain in DETECTED state
        var beforeTimeout = gapStartTime + GAP_TIMEOUT_MS - 1000L; // 29 seconds
        reprocessor.checkGapTimeout(beforeTimeout);
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());

        // Check timeout at exactly 30 seconds - should transition to TIMEOUT
        var atTimeout = gapStartTime + GAP_TIMEOUT_MS;
        reprocessor.checkGapTimeout(atTimeout);
        assertEquals(EventReprocessor.GapState.TIMEOUT, reprocessor.getGapState());
    }

    /**
     * Test 3: Gap timeout calls view change callback.
     * Verify that viewChangeCallback is invoked when timeout expires.
     */
    @Test
    void testGapTimeoutCallsViewChange() throws InterruptedException {
        var callbackInvoked = new CountDownLatch(1);
        var callbackCount = new AtomicInteger(0);

        // Set callback
        reprocessor.setViewChangeCallback(() -> {
            callbackCount.incrementAndGet();
            callbackInvoked.countDown();
        });

        // Trigger gap detection
        fillQueueAndOverflow();
        var gapStartTime = reprocessor.getGapStartTimeMs();

        // Trigger timeout
        var atTimeout = gapStartTime + GAP_TIMEOUT_MS;
        reprocessor.checkGapTimeout(atTimeout);

        // Verify callback invoked
        assertTrue(callbackInvoked.await(1, TimeUnit.SECONDS), "Callback should be invoked");
        assertEquals(1, callbackCount.get(), "Callback should be invoked exactly once");
        assertEquals(EventReprocessor.GapState.TIMEOUT, reprocessor.getGapState());
    }

    /**
     * Test 4: No gap timeout if queue drains before 30 seconds.
     * Verify that draining the queue allows normal operation.
     */
    @Test
    void testNoGapTimeoutIfQueueDrains() {
        var callbackInvoked = new AtomicInteger(0);
        reprocessor.setViewChangeCallback(callbackInvoked::incrementAndGet);

        // Trigger gap detection
        fillQueueAndOverflow();
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());
        var gapStartTime = reprocessor.getGapStartTimeMs();

        // Process some events to drain queue (below capacity)
        var processTime = currentTime + MIN_LOOKAHEAD_MS + 10L;
        var processed = reprocessor.processReady(processTime, event -> {
            // Process events
        });
        assertTrue(processed > 0, "Should process some events");
        assertTrue(reprocessor.getQueueDepth() < SMALL_QUEUE_SIZE, "Queue should drain");

        // Reset gap state (simulating successful recovery)
        reprocessor.resetGap();
        assertEquals(EventReprocessor.GapState.NONE, reprocessor.getGapState());

        // Check timeout after reset - should not trigger callback
        var afterReset = gapStartTime + GAP_TIMEOUT_MS + 1000L;
        reprocessor.checkGapTimeout(afterReset);

        // Verify no callback invoked
        assertEquals(0, callbackInvoked.get(), "Callback should not be invoked after reset");
        assertEquals(EventReprocessor.GapState.NONE, reprocessor.getGapState());
    }

    /**
     * Test 5: Gap state reset.
     * Verify that resetGap() restores GapState.NONE and clears gap start time.
     */
    @Test
    void testGapStateReset() {
        // Trigger gap detection
        fillQueueAndOverflow();
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());
        assertTrue(reprocessor.getGapStartTimeMs() > 0L);

        // Reset gap
        reprocessor.resetGap();

        // Verify reset
        assertEquals(EventReprocessor.GapState.NONE, reprocessor.getGapState());
        assertEquals(0L, reprocessor.getGapStartTimeMs());
        assertEquals(0L, reprocessor.getGapDurationMs(currentTime));
    }

    /**
     * Test 6: Gap metrics tracking.
     * Verify that totalGaps is incremented on each overflow.
     */
    @Test
    void testGapMetrics() {
        assertEquals(0L, reprocessor.getTotalGaps(), "Initial gaps should be zero");

        // First overflow
        fillQueueAndOverflow();
        assertEquals(1L, reprocessor.getTotalGaps());

        // Reset and cause second overflow
        reprocessor.clear();
        reprocessor.resetGap();
        fillQueueAndOverflow();
        assertEquals(2L, reprocessor.getTotalGaps());

        // Reset and cause third overflow
        reprocessor.clear();
        reprocessor.resetGap();
        fillQueueAndOverflow();
        assertEquals(3L, reprocessor.getTotalGaps());
    }

    /**
     * Test 7: Configurable gap timeout.
     * Verify that custom gapTimeoutMs in Configuration works correctly.
     */
    @Test
    void testConfigurableGapTimeout() {
        var customTimeout = 15000L; // 15 seconds instead of 30
        var customReprocessor = new EventReprocessor(
            new EventReprocessor.Configuration(
                MIN_LOOKAHEAD_MS,
                MAX_LOOKAHEAD_MS,
                SMALL_QUEUE_SIZE,
                customTimeout
            )
        );

        var callbackInvoked = new CountDownLatch(1);
        customReprocessor.setViewChangeCallback(callbackInvoked::countDown);

        // Fill and overflow
        for (int i = 0; i < SMALL_QUEUE_SIZE; i++) {
            customReprocessor.queueEvent(createEvent("entity" + i, currentTime, i));
        }
        customReprocessor.queueEvent(createEvent("overflow", currentTime, SMALL_QUEUE_SIZE));

        // Verify gap detected
        assertEquals(EventReprocessor.GapState.DETECTED, customReprocessor.getGapState());
        var gapStartTime = customReprocessor.getGapStartTimeMs();

        // Check timeout before custom timeout (14 seconds)
        var beforeTimeout = gapStartTime + customTimeout - 1000L;
        customReprocessor.checkGapTimeout(beforeTimeout);
        assertEquals(EventReprocessor.GapState.DETECTED, customReprocessor.getGapState());

        // Check timeout at custom timeout (15 seconds)
        var atTimeout = gapStartTime + customTimeout;
        customReprocessor.checkGapTimeout(atTimeout);
        assertEquals(EventReprocessor.GapState.TIMEOUT, customReprocessor.getGapState());
        assertEquals(0, callbackInvoked.getCount(), "Callback should be invoked");
    }

    /**
     * Test 8: Multiple gaps tracked correctly.
     * Verify that multiple gap cycles are handled correctly.
     */
    @Test
    void testMultipleGapsTracked() {
        var callbackCount = new AtomicInteger(0);
        reprocessor.setViewChangeCallback(callbackCount::incrementAndGet);

        // First gap cycle
        fillQueueAndOverflow();
        assertEquals(1L, reprocessor.getTotalGaps());
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());

        var firstGapStart = reprocessor.getGapStartTimeMs();
        reprocessor.checkGapTimeout(firstGapStart + GAP_TIMEOUT_MS);
        assertEquals(EventReprocessor.GapState.TIMEOUT, reprocessor.getGapState());
        assertEquals(1, callbackCount.get());

        // Reset for second gap
        reprocessor.clear();
        reprocessor.resetGap();
        assertEquals(EventReprocessor.GapState.NONE, reprocessor.getGapState());

        // Wait a bit to ensure different timestamp
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Second gap cycle
        fillQueueAndOverflow();
        assertEquals(2L, reprocessor.getTotalGaps());
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());

        var secondGapStart = reprocessor.getGapStartTimeMs();
        assertTrue(secondGapStart > firstGapStart, "Second gap should have later start time");

        reprocessor.checkGapTimeout(secondGapStart + GAP_TIMEOUT_MS);
        assertEquals(EventReprocessor.GapState.TIMEOUT, reprocessor.getGapState());
        assertEquals(2, callbackCount.get());
    }

    /**
     * Test 9: Gap duration calculation.
     * Verify getGapDurationMs() returns correct duration.
     */
    @Test
    void testGapDurationCalculation() {
        // No gap initially
        assertEquals(0L, reprocessor.getGapDurationMs(currentTime));

        // Trigger gap
        fillQueueAndOverflow();
        var gapStartTime = reprocessor.getGapStartTimeMs();

        // Check duration at various points
        assertEquals(0L, reprocessor.getGapDurationMs(gapStartTime));
        assertEquals(5000L, reprocessor.getGapDurationMs(gapStartTime + 5000L));
        assertEquals(15000L, reprocessor.getGapDurationMs(gapStartTime + 15000L));
        assertEquals(GAP_TIMEOUT_MS, reprocessor.getGapDurationMs(gapStartTime + GAP_TIMEOUT_MS));

        // After reset
        reprocessor.resetGap();
        assertEquals(0L, reprocessor.getGapDurationMs(currentTime));
    }

    /**
     * Test 10: Callback exception handling.
     * Verify that exceptions in viewChangeCallback don't break gap handling.
     */
    @Test
    void testCallbackExceptionHandling() {
        var callbackInvoked = new AtomicInteger(0);

        // Set callback that throws exception
        reprocessor.setViewChangeCallback(() -> {
            callbackInvoked.incrementAndGet();
            throw new RuntimeException("Test exception in callback");
        });

        // Trigger gap and timeout
        fillQueueAndOverflow();
        var gapStartTime = reprocessor.getGapStartTimeMs();

        // Should not throw exception even though callback throws
        assertDoesNotThrow(() -> reprocessor.checkGapTimeout(gapStartTime + GAP_TIMEOUT_MS));

        // Verify callback was invoked despite exception
        assertEquals(1, callbackInvoked.get());
        assertEquals(EventReprocessor.GapState.TIMEOUT, reprocessor.getGapState());
    }

    /**
     * Test 11: Concurrent gap detection and timeout checking.
     * Verify thread safety of gap handling.
     */
    @Test
    void testConcurrentGapHandling() throws InterruptedException {
        var callbackCount = new AtomicInteger(0);
        reprocessor.setViewChangeCallback(callbackCount::incrementAndGet);

        // Fill queue from multiple threads
        var threads = new Thread[5];
        var startLatch = new CountDownLatch(1);

        for (int t = 0; t < threads.length; t++) {
            var threadNum = t;
            threads[t] = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 5; i++) {
                        var event = createEvent("thread" + threadNum + "-" + i, currentTime, threadNum * 100 + i);
                        reprocessor.queueEvent(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[t].start();
        }

        startLatch.countDown(); // Start all threads

        // Wait for all threads
        for (var thread : threads) {
            thread.join();
        }

        // Should have detected gap (queue overflow)
        assertTrue(reprocessor.getTotalGaps() > 0L);
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());

        // Trigger timeout from another thread
        var timeoutThread = new Thread(() -> {
            var gapStartTime = reprocessor.getGapStartTimeMs();
            reprocessor.checkGapTimeout(gapStartTime + GAP_TIMEOUT_MS);
        });
        timeoutThread.start();
        timeoutThread.join();

        // Verify callback invoked exactly once
        assertEquals(1, callbackCount.get());
        assertEquals(EventReprocessor.GapState.TIMEOUT, reprocessor.getGapState());
    }

    /**
     * Test 12: Gap state transitions.
     * Verify all valid state transitions work correctly.
     */
    @Test
    void testGapStateTransitions() {
        // Initial state: NONE
        assertEquals(EventReprocessor.GapState.NONE, reprocessor.getGapState());

        // NONE -> DETECTED (on overflow)
        fillQueueAndOverflow();
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());

        // DETECTED -> TIMEOUT (on timeout)
        var gapStartTime = reprocessor.getGapStartTimeMs();
        reprocessor.checkGapTimeout(gapStartTime + GAP_TIMEOUT_MS);
        assertEquals(EventReprocessor.GapState.TIMEOUT, reprocessor.getGapState());

        // TIMEOUT -> NONE (on reset)
        reprocessor.resetGap();
        assertEquals(EventReprocessor.GapState.NONE, reprocessor.getGapState());

        // Clear queue to allow testing another overflow
        reprocessor.clear();

        // Test DETECTED -> NONE (reset before timeout)
        fillQueueAndOverflow();
        assertEquals(EventReprocessor.GapState.DETECTED, reprocessor.getGapState());
        reprocessor.resetGap();
        assertEquals(EventReprocessor.GapState.NONE, reprocessor.getGapState());
    }

    // Helper methods

    private void fillQueueAndOverflow() {
        // Fill queue to capacity
        for (int i = 0; i < SMALL_QUEUE_SIZE; i++) {
            var event = createEvent("entity" + i, currentTime, i);
            assertTrue(reprocessor.queueEvent(event));
        }

        // Trigger overflow
        var overflowEvent = createEvent("overflow", currentTime, SMALL_QUEUE_SIZE);
        assertFalse(reprocessor.queueEvent(overflowEvent));
    }

    private EntityUpdateEvent createEvent(String entityId, long timestamp, long lamportClock) {
        return new EntityUpdateEvent(
            new StringEntityID(entityId),
            new Point3f(0, 0, 0),
            new Point3f(0, 0, 0),
            timestamp,
            lamportClock
        );
    }
}
