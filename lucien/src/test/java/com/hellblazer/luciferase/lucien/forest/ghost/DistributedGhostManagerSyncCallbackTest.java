/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.balancing.fault.GhostSyncCallback;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Luciferase-963vw: {@code registerSyncCallback} wired a {@link GhostSyncCallback} that was never invoked — the
 * fault-detection integration was dead. The callback now fires per target rank on sync completion (success) and on
 * flush failure.
 *
 * @author hal.hildebrand
 */
class DistributedGhostManagerSyncCallbackTest {

    /** Channel double whose flush either succeeds or fails, to drive the callback. */
    private static final class FlushChannel implements GhostChannel<MortonKey, LongEntityID, String> {
        final int rank;
        final boolean fail;

        FlushChannel(int rank, boolean fail) {
            this.rank = rank;
            this.fail = fail;
        }

        @Override public void queueGhost(int t, GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public CompletableFuture<Void> flushToTarget(int t) {
            return fail ? CompletableFuture.failedFuture(new RuntimeException("flush boom"))
                        : CompletableFuture.completedFuture(null);
        }
        @Override public int getTotalPendingCount() { return 0; }
        @Override public void clear() { }
        @Override public int getCurrentRank() { return rank; }
        @Override public long getTreeId() { return 0L; }
        @Override public GhostType getGhostType() { return GhostType.FACES; }
    }

    private static DistributedGhostManager<MortonKey, LongEntityID, String> manager(boolean failFlush) {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.insert(new Point3f(0, 0, 0), (byte) 5, "corner");
        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        var mgr = new DistributedGhostManager<>(octree, new FlushChannel(0, failFlush), detector);
        mgr.addKnownProcess(1);
        return mgr;
    }

    @Test
    void callbackFiresOnSyncSuccess() {
        var successRank = new AtomicInteger(-1);
        var failures = new AtomicInteger(0);
        var mgr = manager(false);
        mgr.registerSyncCallback(new GhostSyncCallback() {
            @Override public void onSyncSuccess(int targetRank) { successRank.set(targetRank); }
            @Override public void onSyncFailure(int targetRank, Exception cause) { failures.incrementAndGet(); }
        });

        mgr.synchronizeWithAllProcesses();

        assertEquals(1, successRank.get(), "onSyncSuccess must fire for the synced rank (Luciferase-963vw)");
        assertEquals(0, failures.get(), "no failure on a successful flush");
    }

    @Test
    void callbackFiresOnSyncFailure() {
        var failRank = new AtomicInteger(-1);
        var cause = new java.util.concurrent.atomic.AtomicReference<Exception>();
        var mgr = manager(true);
        mgr.registerSyncCallback(new GhostSyncCallback() {
            @Override public void onSyncSuccess(int targetRank) { }
            @Override public void onSyncFailure(int targetRank, Exception ex) { failRank.set(targetRank); cause.set(ex); }
        });

        try {
            mgr.synchronizeWithAllProcesses(); // join() may rethrow the flush failure; the callback fires regardless
        } catch (RuntimeException ignored) {
        }

        assertEquals(1, failRank.get(), "onSyncFailure must fire for the failed rank (Luciferase-963vw)");
        assertNotNull(cause.get(), "failure cause must be propagated");
    }
}
