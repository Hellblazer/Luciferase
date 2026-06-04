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
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Luciferase-7wzml.96: DistributedGhostManager.shouldPerformSync() must use the injected Clock, not
 * System.currentTimeMillis(), so sync-interval logic is deterministically testable.
 */
class DistributedGhostManagerClockTest {

    /** Minimal channel that counts successful flushes. */
    private static final class CountingChannel implements GhostChannel<MortonKey, LongEntityID, String> {
        final AtomicInteger flushCount = new AtomicInteger();

        @Override public void queueGhost(int t, GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public CompletableFuture<Void> flushToTarget(int t) {
            flushCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        @Override public int getTotalPendingCount() { return 0; }
        @Override public void clear() { }
        @Override public int getCurrentRank() { return 0; }
        @Override public long getTreeId() { return 0L; }
        @Override public GhostType getGhostType() { return GhostType.FACES; }
    }

    private static DistributedGhostManager<MortonKey, LongEntityID, String> managerWith(
            CountingChannel channel, TestClock clock) {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.insert(new Point3f(0, 0, 0), (byte) 5, "seed");
        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        var mgr = new DistributedGhostManager<>(octree, channel, detector);
        mgr.setClock(clock);
        mgr.addKnownProcess(1);
        return mgr;
    }

    /**
     * shouldPerformSync must return false when clock time is within the interval, and true once the clock
     * advances past it (Luciferase-7wzml.96).
     */
    @Test
    void syncIntervalFlipsDeterministicallyWithTestClock() {
        var clock = new TestClock(1_000L);
        var channel = new CountingChannel();
        var mgr = managerWith(channel, clock);

        // lastSyncTime == 0; syncIntervalMs == 30_000 (default)
        // At t=1_000 ms, elapsed = 1_000 ms < 30_000 ms → no sync
        // We first stamp lastSyncTime by forcing a sync at t=0 by NOT having done any yet.
        // Actually lastSyncTime starts at 0, clock starts at 1_000 ms, elapsed = 1_000 < 30_000 → no sync.
        // Advance clock to exactly the boundary (not yet past): elapsed == syncIntervalMs → still false (strictly >)
        clock.setTime(30_000L);   // elapsed = 30_000 - 0 = 30_000, NOT > 30_000 → no sync
        mgr.synchronizeIfDue();
        assertEquals(0, channel.flushCount.get(), "sync must NOT fire when elapsed == syncIntervalMs (strict >)");

        // Advance 1 ms past boundary → elapsed = 30_001 > 30_000 → sync fires
        clock.setTime(30_001L);
        mgr.synchronizeIfDue();
        assertEquals(1, channel.flushCount.get(), "sync must fire once elapsed exceeds syncIntervalMs");

        // lastSyncTime is now 30_001; clock stays at 30_001 → elapsed = 0 → no second sync
        mgr.synchronizeIfDue();
        assertEquals(1, channel.flushCount.get(), "sync must NOT fire again immediately after lastSyncTime stamped");
    }
}
