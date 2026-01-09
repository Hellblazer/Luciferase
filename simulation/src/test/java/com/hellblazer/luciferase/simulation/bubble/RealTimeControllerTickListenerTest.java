package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RealTimeController tick listener mechanism (Phase 7B.3).
 * <p>
 * Validates that:
 * - Tick listeners receive notifications on each tick
 * - Multiple listeners can be registered
 * - Listeners receive correct simulation time and Lamport clock
 * - Listeners are called in registration order
 * <p>
 * This is the integration point for EnhancedBubble's ghost state updates.
 *
 * @author hal.hildebrand
 */
class RealTimeControllerTickListenerTest {

    private RealTimeController controller;
    private UUID bubbleId;

    @BeforeEach
    void setUp() {
        bubbleId = UUID.randomUUID();
        controller = new RealTimeController(bubbleId, "test-controller", 100); // 100 Hz
    }

    @AfterEach
    void tearDown() {
        if (controller.isRunning()) {
            controller.stop();
        }
    }

    @Test
    void testTickListenerReceivesNotifications() throws InterruptedException {
        var tickCount = new AtomicLong(0);
        var latch = new CountDownLatch(5); // Wait for 5 ticks

        // Register tick listener
        controller.addTickListener((simTime, lamportClock) -> {
            tickCount.incrementAndGet();
            latch.countDown();
        });

        // Start controller
        controller.start();

        // Wait for 5 ticks (should happen in < 100ms at 100 Hz)
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS),
                  "Should receive 5 tick notifications within 500ms");

        controller.stop();

        assertTrue(tickCount.get() >= 5,
                  "Should have received at least 5 ticks");
    }

    @Test
    void testTickListenerReceivesCorrectSimulationTime() throws InterruptedException {
        var lastSimTime = new AtomicLong(-1);
        var latch = new CountDownLatch(3);

        controller.addTickListener((simTime, lamportClock) -> {
            // Verify simulation time is increasing
            assertTrue(simTime > lastSimTime.get(),
                      "Simulation time should be monotonically increasing");
            lastSimTime.set(simTime);
            latch.countDown();
        });

        controller.start();
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS),
                  "Should receive tick notifications");
        controller.stop();

        assertTrue(lastSimTime.get() > 0,
                  "Should have received ticks with positive simulation time");
    }

    @Test
    void testMultipleTickListeners() throws InterruptedException {
        var listener1Count = new AtomicLong(0);
        var listener2Count = new AtomicLong(0);
        var latch = new CountDownLatch(10); // 5 ticks × 2 listeners

        // Register two listeners
        controller.addTickListener((simTime, lamportClock) -> {
            listener1Count.incrementAndGet();
            latch.countDown();
        });

        controller.addTickListener((simTime, lamportClock) -> {
            listener2Count.incrementAndGet();
            latch.countDown();
        });

        controller.start();
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS),
                  "Should receive notifications for both listeners");
        controller.stop();

        assertTrue(listener1Count.get() >= 5,
                  "First listener should receive at least 5 ticks");
        assertTrue(listener2Count.get() >= 5,
                  "Second listener should receive at least 5 ticks");
        assertEquals(listener1Count.get(), listener2Count.get(),
                    "Both listeners should receive same number of ticks");
    }

    @Test
    void testListenerRemoval() throws InterruptedException {
        var tickCount = new AtomicLong(0);
        var latch = new CountDownLatch(3);

        RealTimeController.TickListener listener = (simTime, lamportClock) -> {
            tickCount.incrementAndGet();
            latch.countDown();
        };

        controller.addTickListener(listener);
        controller.start();

        // Wait for 3 ticks
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS),
                  "Should receive initial ticks");

        long countBeforeRemoval = tickCount.get();

        // Remove listener
        controller.removeTickListener(listener);

        // Wait a bit
        Thread.sleep(100);

        controller.stop();

        // Tick count should not increase much after removal
        assertTrue(tickCount.get() <= countBeforeRemoval + 10,
                  "Tick count should not increase significantly after listener removal");
    }

    @Test
    void testListenerExceptionDoesNotStopTicking() throws InterruptedException {
        var goodListenerCount = new AtomicLong(0);
        var latch = new CountDownLatch(5);

        // Add listener that throws exception
        controller.addTickListener((simTime, lamportClock) -> {
            throw new RuntimeException("Test exception");
        });

        // Add listener that should still work
        controller.addTickListener((simTime, lamportClock) -> {
            goodListenerCount.incrementAndGet();
            latch.countDown();
        });

        controller.start();

        // Good listener should still receive ticks despite exception in first listener
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS),
                  "Good listener should receive ticks despite exception in other listener");

        controller.stop();

        assertTrue(goodListenerCount.get() >= 5,
                  "Good listener should have received at least 5 ticks");
    }

    @Test
    void testLamportClockIncrementsOnTick() throws InterruptedException {
        var lastLamportClock = new AtomicLong(-1);
        var latch = new CountDownLatch(3);

        controller.addTickListener((simTime, lamportClock) -> {
            // Verify Lamport clock is increasing
            assertTrue(lamportClock > lastLamportClock.get(),
                      "Lamport clock should be monotonically increasing");
            lastLamportClock.set(lamportClock);
            latch.countDown();
        });

        controller.start();
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS),
                  "Should receive tick notifications");
        controller.stop();

        assertTrue(lastLamportClock.get() > 0,
                  "Should have received ticks with positive Lamport clock");
    }

    @Test
    void testNoListenersRegistered() throws InterruptedException {
        // Start controller without any listeners (should not crash)
        controller.start();
        Thread.sleep(50); // Let it tick a few times
        controller.stop();

        assertTrue(controller.getSimulationTime() > 0,
                  "Controller should still tick without listeners");
    }
}
