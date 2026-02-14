/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Clock} and {@link TestClock}.
 *
 * @author hal.hildebrand
 */
class ClockTest {

    // ==================== Clock Interface Tests ====================

    @Test
    void testSystemClockReturnsCurrentTime() {
        var clock = Clock.system();
        var before = System.currentTimeMillis();
        var clockTime = clock.currentTimeMillis();
        var after = System.currentTimeMillis();

        // Clock time should be between before and after
        assertTrue(clockTime >= before, "Clock should return time >= before");
        assertTrue(clockTime <= after, "Clock should return time <= after");
    }

    @Test
    void testFixedClockReturnsFixedTime() {
        var fixedTime = 1000000000L;
        var clock = Clock.fixed(fixedTime);

        assertEquals(fixedTime, clock.currentTimeMillis());
        assertEquals(fixedTime, clock.currentTimeMillis());

        // Even after delay, still returns same time
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(fixedTime, clock.currentTimeMillis());
    }

    @Test
    void testFixedClockNanoTimeThrowsException() {
        var clock = Clock.fixed(1000L);

        var exception = assertThrows(UnsupportedOperationException.class,
            () -> clock.nanoTime(),
            "Fixed clock should throw on nanoTime() call"
        );

        assertTrue(exception.getMessage().contains("TestClock"),
            "Exception should guide to TestClock");
        assertTrue(exception.getMessage().contains("elapsed time"),
            "Exception should explain the limitation");
    }

    // ==================== TestClock Basic Tests ====================

    @Test
    void testTestClockStartsAtSystemTime() {
        var before = System.currentTimeMillis();
        var clock = new TestClock();
        var after = System.currentTimeMillis();

        var clockTime = clock.currentTimeMillis();
        assertTrue(clockTime >= before, "Clock should start >= system time at creation");
        assertTrue(clockTime <= after + 10, "Clock should start near system time");
    }

    @Test
    void testTestClockWithInitialTime() {
        var initialTime = 1234567890L;
        var clock = new TestClock(initialTime);

        assertEquals(initialTime, clock.currentTimeMillis());
    }

    @Test
    void testAdvanceIncreasesTime() {
        var clock = new TestClock(1000L);
        assertEquals(1000L, clock.currentTimeMillis());

        clock.advance(500);
        assertEquals(1500L, clock.currentTimeMillis());
    }

    @Test
    void testMultipleAdvancesAccumulate() {
        var clock = new TestClock(0L);

        clock.advance(100);
        clock.advance(200);
        clock.advance(300);

        assertEquals(600L, clock.currentTimeMillis());
    }

    @Test
    void testNegativeAdvanceThrowsException() {
        var clock = new TestClock();

        var exception = assertThrows(IllegalArgumentException.class, () -> clock.advance(-100));
        assertTrue(exception.getMessage().contains("-100"));
    }

    @Test
    void testZeroAdvanceIsAllowed() {
        var clock = new TestClock(1000L);
        clock.advance(0);
        assertEquals(1000L, clock.currentTimeMillis());
    }

    // ==================== TestClock Time Setting Tests ====================

    @Test
    void testSetTimeOverridesPreviousTime() {
        var clock = new TestClock(1000L);
        clock.advance(500);
        assertEquals(1500L, clock.currentTimeMillis());

        clock.setTime(2000L);
        assertEquals(2000L, clock.currentTimeMillis());
    }

    @Test
    void testSetTimeFollowedByAdvance() {
        var clock = new TestClock();
        clock.setTime(10000L);
        clock.advance(500);

        assertEquals(10500L, clock.currentTimeMillis());
    }

    // ==================== TestClock Concurrency Tests ====================

    @Test
    void testConcurrentAdvances() throws InterruptedException {
        var clock = new TestClock(0L);
        var threads = 10;
        var advancesPerThread = 1000;
        var advanceAmount = 1L;
        var latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                for (int j = 0; j < advancesPerThread; j++) {
                    clock.advance(advanceAmount);
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        var expectedTime = threads * advancesPerThread * advanceAmount;
        assertEquals(expectedTime, clock.currentTimeMillis());
    }

    @Test
    void testConcurrentReads() throws InterruptedException {
        var clock = new TestClock(1000L);
        var threads = 10;
        var readsPerThread = 1000;
        var latch = new CountDownLatch(threads);
        var minTime = new AtomicLong(Long.MAX_VALUE);
        var maxTime = new AtomicLong(Long.MIN_VALUE);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                for (int j = 0; j < readsPerThread; j++) {
                    var time = clock.currentTimeMillis();
                    minTime.updateAndGet(current -> Math.min(current, time));
                    maxTime.updateAndGet(current -> Math.max(current, time));
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        // All reads should return the same time (fixed clock)
        assertEquals(1000L, minTime.get());
        assertEquals(1000L, maxTime.get());
    }
}
