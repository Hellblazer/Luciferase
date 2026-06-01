/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-9m31 S2: {@link DistributedGhostManager#synchronizeWithAllProcesses()} must fan out flushes to
 * all known remote ranks concurrently (via {@code CompletableFuture.allOf}), not sequentially block on each
 * rank's flush in turn.
 *
 * @author hal.hildebrand
 */
class DistributedGhostManagerAsyncSyncTest {

    /**
     * Channel whose {@code flushToTarget} parks until every expected remote flush has been <em>started</em>.
     * If the manager flushes sequentially (joining each before starting the next), the first flush parks
     * forever (the others never start) and the arrival latch never trips — detected by {@code allArrived}.
     */
    private static final class ConcurrentFlushChannel implements GhostChannel<MortonKey, LongEntityID, String> {
        final CountDownLatch arrival;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        volatile boolean allArrived = false;
        private final ExecutorService exec = Executors.newCachedThreadPool();

        ConcurrentFlushChannel(int expectedRemoteFlushes) {
            this.arrival = new CountDownLatch(expectedRemoteFlushes);
        }

        @Override public void queueGhost(int t, GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public int getTotalPendingCount() { return 0; }
        @Override public void clear() { }
        @Override public int getCurrentRank() { return 0; }
        @Override public long getTreeId() { return 0L; }
        @Override public GhostType getGhostType() { return GhostType.FACES; }

        @Override public CompletableFuture<Void> flushToTarget(int targetRank) {
            return CompletableFuture.runAsync(() -> {
                int n = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(n, Math::max);
                arrival.countDown();
                try {
                    // Trips only if all expected flushes ran concurrently; bounded so a sequential manager
                    // does not hang the test forever.
                    allArrived = arrival.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
            }, exec);
        }

        void shutdown() { exec.shutdownNow(); }
    }

    @Test
    void synchronizeWithAllProcessesFansOutConcurrently() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        var channel = new ConcurrentFlushChannel(3); // ranks 1, 2, 3
        var manager = new DistributedGhostManager<>(octree, channel, detector);
        manager.addKnownProcess(1);
        manager.addKnownProcess(2);
        manager.addKnownProcess(3);

        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                                      manager::synchronizeWithAllProcesses,
                                      "parallel fan-out must complete promptly, not serialize");
            assertTrue(channel.allArrived, "all three flushes must run concurrently (arrival latch trips)");
            assertEquals(3, channel.maxInFlight.get(),
                         "all three remote flushes must be in flight at once (concurrent fan-out)");
        } finally {
            channel.shutdown();
        }
    }
}
