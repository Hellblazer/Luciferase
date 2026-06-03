/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostChannel;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-cfg4o: {@code triggerGhostUpdateAfterAdaptation} called the distributed ghost update (whose two-phase
 * clear-then-populate rebuild reads the spatial index) with NO lock, so it could interleave with concurrent index
 * mutation and tear the boundary set / throw a CME. It now holds the coordinator write lock across the whole update.
 * This stress test runs concurrent mutate + ghost-rebuild and asserts no exception escapes.
 *
 * @author hal.hildebrand
 */
class GhostUpdateConcurrencyTest {

    private static final class StubChannel implements GhostChannel<MortonKey, LongEntityID, String> {
        @Override public void queueGhost(int t, GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public CompletableFuture<Void> flushToTarget(int t) { return CompletableFuture.completedFuture(null); }
        @Override public int getTotalPendingCount() { return 0; }
        @Override public void clear() { }
        @Override public int getCurrentRank() { return 0; }
        @Override public long getTreeId() { return 0L; }
        @Override public GhostType getGhostType() { return GhostType.FACES; }
    }

    @Test
    void concurrentMutateAndGhostRebuildIsConsistent() throws InterruptedException {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.setGhostType(GhostType.FACES);
        octree.setupDistributedGhosts(new StubChannel(), null, null, 0, 0L);
        for (int i = 0; i < 64; i++) {
            octree.insert(new LongEntityID(i), new Point3f(10 + (i % 8) * 5, 10, 10), (byte) 5, "e" + i);
        }

        var error = new AtomicReference<Throwable>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);

        var mutator = new Thread(() -> {
            try {
                start.await();
                for (int i = 64; i < 1064; i++) {
                    octree.insert(new LongEntityID(i), new Point3f(10 + (i % 40) * 4, 20, 20), (byte) 5, "x" + i);
                    if (i % 4 == 0) {
                        octree.removeEntity(new LongEntityID(i - 3));
                    }
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });
        var rebuilder = new Thread(() -> {
            try {
                start.await();
                for (int r = 0; r < 300; r++) {
                    octree.triggerGhostUpdateAfterAdaptation(); // distributed rebuild, now under the write lock
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        mutator.start();
        rebuilder.start();
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "threads must finish");
        assertNull(error.get(), "concurrent mutate + ghost rebuild must not tear / throw (Luciferase-cfg4o): "
                                + error.get());

        // After the storm, a final quiescent rebuild must yield a coherent (non-torn) ghost layer: a complete,
        // non-null boundary set with no half-cleared state. (No exception above only proves no CME; this proves the
        // layer is consistent.)
        octree.triggerGhostUpdateAfterAdaptation();
        var layer = octree.getGhostLayer();
        assertNotNull(layer, "ghost layer present after rebuild");
        assertNotNull(layer.getAllGhostElements(), "ghost element set must be coherent, not half-populated");
        assertEquals(layer.getNumGhostElements(), layer.getAllGhostElements().size(),
                     "ghost count and element list must agree — no torn clear/populate (Luciferase-cfg4o)");
    }
}
