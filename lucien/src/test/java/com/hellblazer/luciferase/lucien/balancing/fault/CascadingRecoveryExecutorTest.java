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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-h08sd: CascadingRecoveryImpl used Executors.newCachedThreadPool, so a cascading failure (many
 * partitions failing at once, each parking on a Thread.sleep backoff) spawned N unbounded platform threads. It now
 * uses a virtual-thread-per-task executor, so N concurrent parked backoffs are cheap.
 *
 * @author hal.hildebrand
 */
class CascadingRecoveryExecutorTest {

    /**
     * Submit far more concurrently-parked tasks than any sane platform-thread pool would tolerate, and require
     * every one of them to (a) actually start and (b) run on a virtual thread. A cached platform-thread pool would
     * either exhaust OS threads or run on non-virtual carriers — either way this assertion fails.
     */
    @Test
    void recoveryTasksRunOnCheapVirtualThreads() throws Exception {
        var recovery = new CascadingRecoveryImpl();
        var executor = recovery.executor();   // package-private accessor (no reflection)

        int tasks = 1_000;
        var allVirtual = new AtomicInteger(0);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(tasks);
        var finished = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            executor.submit(() -> {
                if (Thread.currentThread().isVirtual()) {
                    allVirtual.incrementAndGet();
                }
                started.countDown();
                try {
                    release.await();   // park all 1000 simultaneously — cheap only on virtual threads
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        assertTrue(started.await(10, TimeUnit.SECONDS),
                   "all " + tasks + " recovery tasks must start concurrently — a bounded platform pool would stall "
                   + "(Luciferase-h08sd)");
        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "all tasks must finish");
        assertEquals(tasks, allVirtual.get(),
                     "every recovery task must run on a virtual thread, not an unbounded cached platform pool "
                     + "(Luciferase-h08sd)");
    }
}
