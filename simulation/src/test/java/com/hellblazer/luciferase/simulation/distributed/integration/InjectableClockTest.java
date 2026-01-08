/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for InjectableClock and TestClock implementations.
 *
 * @author hal.hildebrand
 */
class InjectableClockTest {

    @Test
    void testSystemClock() throws InterruptedException {
        var clock = InjectableClock.system();

        var time1 = clock.currentTimeMillis();
        Thread.sleep(10);
        var time2 = clock.currentTimeMillis();

        assertTrue(time2 > time1, "System clock should advance");
        assertTrue(time2 - time1 >= 10, "System clock should advance by at least 10ms");
    }

    @Test
    void testTestClockInitial() {
        var testClock = new TestClock();
        var systemTime = System.currentTimeMillis();
        var clockTime = testClock.currentTimeMillis();

        assertTrue(Math.abs(clockTime - systemTime) < 100,
                   "Test clock should start near system time (within 100ms)");
    }

    @Test
    void testTestClockAdvance() {
        var testClock = new TestClock();
        var initialTime = testClock.currentTimeMillis();

        testClock.advance(1000);
        var afterAdvance = testClock.currentTimeMillis();

        assertEquals(initialTime + 1000, afterAdvance, "Clock should advance by exact amount");
    }

    @Test
    void testTestClockSetSkew() {
        var testClock = new TestClock();
        var initialTime = testClock.currentTimeMillis();

        testClock.setSkew(5000);
        assertEquals(5000, testClock.getSkew(), "Skew should be set correctly");

        var afterSkew = testClock.currentTimeMillis();
        assertTrue(afterSkew >= initialTime + 5000, "Clock time should reflect skew");
    }

    @Test
    void testMultipleClocks() {
        var clock1 = new TestClock();
        var clock2 = new TestClock();

        clock1.setSkew(1000);
        clock2.setSkew(2000);

        assertEquals(1000, clock1.getSkew());
        assertEquals(2000, clock2.getSkew());

        var time1 = clock1.currentTimeMillis();
        var time2 = clock2.currentTimeMillis();

        assertTrue(Math.abs((time2 - time1) - 1000) < 10,
                   "Clock difference should match skew difference");
    }

    @Test
    void testClockUnderConcurrentReads() throws InterruptedException {
        var testClock = new TestClock();
        testClock.setSkew(10000);

        var threadCount = 10;
        var readsPerThread = 1000;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < readsPerThread; j++) {
                        var time = testClock.currentTimeMillis();
                        assertTrue(time > 0, "Clock should return valid time");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent reads should complete");
        executor.shutdown();
    }

    @Test
    void testClockContinuityAfterAdvance() {
        var testClock = new TestClock();

        var time1 = testClock.currentTimeMillis();
        testClock.advance(100);
        var time2 = testClock.currentTimeMillis();
        testClock.advance(200);
        var time3 = testClock.currentTimeMillis();

        assertTrue(time2 > time1, "Clock should advance monotonically");
        assertTrue(time3 > time2, "Clock should continue advancing");
        assertEquals(time1 + 300, time3, "Total advance should be cumulative");
    }

    @Test
    void testSystemClockMatchesActual() {
        var clock = InjectableClock.system();
        var clockTime = clock.currentTimeMillis();
        var systemTime = System.currentTimeMillis();

        assertTrue(Math.abs(clockTime - systemTime) < 10,
                   "System clock should match actual time within 10ms");
    }
}
