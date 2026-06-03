/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-h08sd: CascadingRecoveryImpl used Executors.newCachedThreadPool, so a cascading failure (many
 * partitions failing at once, each parking on a Thread.sleep backoff) spawned N unbounded platform threads. It now
 * uses a virtual-thread-per-task executor, so N concurrent parked backoffs are cheap.
 *
 * @author hal.hildebrand
 */
class CascadingRecoveryExecutorTest {

    @Test
    void defaultRecoveryExecutorRunsTasksOnVirtualThreads() throws Exception {
        var recovery = new CascadingRecoveryImpl();
        Field f = CascadingRecoveryImpl.class.getDeclaredField("executor");
        f.setAccessible(true);
        var executor = (ExecutorService) f.get(recovery);

        var onVirtual = new AtomicBoolean(false);
        executor.submit(() -> onVirtual.set(Thread.currentThread().isVirtual())).get(5, TimeUnit.SECONDS);

        assertTrue(onVirtual.get(),
                   "cascading-recovery tasks must run on virtual threads, not an unbounded cached pool (Luciferase-h08sd)");
    }
}
