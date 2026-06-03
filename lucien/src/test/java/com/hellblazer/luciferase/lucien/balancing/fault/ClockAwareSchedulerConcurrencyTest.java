/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-1dcx7: ClockAwareScheduler.tick() was read-compare-assign-run on a plain volatile, so two concurrent
 * callers could both pass the interval gate and both run the task (double recovery-check). The CAS interval-advance
 * makes concurrent ticks within one interval run the task at most once.
 *
 * @author hal.hildebrand
 */
class ClockAwareSchedulerConcurrencyTest {

    @Test
    void concurrentTicksRunTaskAtMostOncePerInterval() throws InterruptedException {
        var clock = new AtomicLong(1000);
        var runs = new AtomicInteger(0);
        var scheduler = new ClockAwareScheduler(clock::get, 100, runs::incrementAndGet);
        scheduler.start();             // lastExecutionTime = 1000
        clock.set(1500);               // now - last = 500 >= 100: the gate is open for this single interval

        int threads = 32;
        var trueTicks = new AtomicInteger(0);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (scheduler.tick()) {
                        trueTicks.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "ticker threads must finish");

        assertEquals(1, runs.get(), "task must execute at most once per interval under concurrent ticks (Luciferase-1dcx7)");
        assertEquals(1, trueTicks.get(), "exactly one tick() may report execution");
    }
}
