/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostEntityHalo;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression test for Luciferase-0frcy.25: InMemoryGhostChannel.flush() must not drop ghosts
 * queued concurrently with the flush. The prior snapshot-then-clear pattern lost any ghost queued
 * between the {@code new ArrayList<>(ghosts)} snapshot and {@code ghosts.clear()} —
 * CopyOnWriteArrayList.clear() does NOT install a new list instance, so the concurrent add landed
 * in the same instance that clear() then emptied. The fix uses an atomic swap.
 */
class InMemoryGhostChannelFlushRaceTest {

    static final class TestEntityID implements EntityID {
        private final String id;
        TestEntityID(String id) { this.id = id; }
        @Override public String toDebugString() { return id; }
        @Override public int compareTo(EntityID other) { return id.compareTo(other.toDebugString()); }
        @Override public boolean equals(Object o) {
            return o instanceof TestEntityID t && id.equals(t.id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private SimulationGhostEntity<TestEntityID, String> ghost() {
        var p = new Point3f(0, 0, 0);
        var halo = new GhostEntityHalo<>(new TestEntityID("g-" + UUID.randomUUID()), "c", p,
                                         new EntityBounds(p, 0.5f), "tree-1");
        return new SimulationGhostEntity<>(halo, UUID.randomUUID(), 1L, 0L, 0L);
    }

    @Test
    void noGhostIsLostAcrossConcurrentQueueAndFlush() {
        assertTimeoutPreemptively(Duration.ofSeconds(25), () -> {
            int trials = 50;
            int ghostsPerTrial = 500;

            for (int trial = 0; trial < trials; trial++) {
                var channel = new InMemoryGhostChannel<TestEntityID, String>();
                var target = UUID.randomUUID();
                var received = new CopyOnWriteArrayList<SimulationGhostEntity<TestEntityID, String>>();
                channel.onReceive((from, ghosts) -> received.addAll(ghosts));

                var pool = Executors.newFixedThreadPool(2);
                try {
                    var start = new CountDownLatch(1);

                    var producer = pool.submit(() -> {
                        await(start);
                        for (int i = 0; i < ghostsPerTrial; i++) {
                            channel.queueGhost(target, ghost());
                        }
                    });

                    var flusher = pool.submit(() -> {
                        await(start);
                        for (int i = 0; i < ghostsPerTrial; i++) {
                            channel.flush(i);
                        }
                    });

                    start.countDown();
                    producer.get(20, TimeUnit.SECONDS);
                    flusher.get(20, TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                }

                // Drain anything still pending after the producer finished.
                channel.flush(Long.MAX_VALUE);

                assertEquals(ghostsPerTrial, received.size(),
                             "ghosts queued during the flush window were dropped (trial " + trial + ")");
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
