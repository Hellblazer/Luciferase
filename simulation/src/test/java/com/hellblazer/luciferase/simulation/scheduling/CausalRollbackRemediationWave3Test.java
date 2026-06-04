/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.scheduling;

import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-3 lifecycle remediation beads:
 * Luciferase-0frcy.106 (EntityUpdateEvent mutable Point3f / false immutability),
 * .107 (CausalRollback unlocked readers TOCTOU NoSuchElementException).
 */
class CausalRollbackRemediationWave3Test {

    // ---- Luciferase-0frcy.106: defensive copies make the event truly immutable ----

    @Test
    void entityUpdateEventDefensivelyCopiesMutablePoints() {
        var pos = new Point3f(1f, 2f, 3f);
        var vel = new Point3f(4f, 5f, 6f);
        var event = new EntityUpdateEvent(new StringEntityID("e1"), pos, vel, 10L, 20L);

        // Mutate the caller's points after construction.
        pos.set(100f, 100f, 100f);
        vel.set(200f, 200f, 200f);

        assertEquals(1f, event.position().x, 1e-6f, "event position must not reflect post-construction mutation");
        assertEquals(2f, event.position().y, 1e-6f);
        assertEquals(4f, event.velocity().x, 1e-6f, "event velocity must not reflect post-construction mutation");

        // And the accessor must hand back a copy, not the internal reference.
        event.position().set(999f, 999f, 999f);
        assertEquals(1f, event.position().x, 1e-6f, "accessor must return a defensive copy");
    }

    // ---- Luciferase-0frcy.107: concurrent readers never throw NoSuchElementException ----

    @Test
    void concurrentReadsAndEvictionDoNotThrow() throws Exception {
        CausalRollback<StringEntityID, String> rollback = new CausalRollback<>();
        var executor = Executors.newFixedThreadPool(4);
        var error = new AtomicReference<Throwable>();
        int rounds = 20_000;
        try {
            var start = new CountDownLatch(1);

            // Writer: continuously checkpoint, which evicts the oldest beyond the window.
            var writer = executor.submit(() -> {
                awaitQuietly(start);
                for (int b = 0; b < rounds && error.get() == null; b++) {
                    rollback.checkpoint(b, java.util.Map.of(), java.util.Set.of());
                }
            });

            // Readers hammering the TOCTOU-prone accessors.
            Runnable reader = () -> {
                awaitQuietly(start);
                for (int i = 0; i < rounds && error.get() == null; i++) {
                    try {
                        rollback.getOldestBucket();
                        rollback.getLatestBucket();
                        rollback.isEmpty();
                        rollback.getCheckpointCount();
                        rollback.toString();
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                        return;
                    }
                }
            };
            var r1 = executor.submit(reader);
            var r2 = executor.submit(reader);
            var r3 = executor.submit(reader);

            start.countDown();
            writer.get(30, TimeUnit.SECONDS);
            r1.get(30, TimeUnit.SECONDS);
            r2.get(30, TimeUnit.SECONDS);
            r3.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertNull(error.get(),
                   "no reader may throw (e.g. NoSuchElementException from isEmpty()+getFirst() TOCTOU): "
                   + error.get());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
