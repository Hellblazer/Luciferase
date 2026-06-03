/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.SpatialIndex.CollisionPair;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-kvdto: findCollisionsFineGrained used the per-node fine-grained strategy but did not take the global
 * read lock (like pre-us4zr kNN), so it could observe torn collision results during a write. The fix wraps it in
 * core.lock().readLock(). Asserted deterministically via a thread-state handshake: while a writer holds the write
 * lock, the query thread must park on the read lock and must NOT return until the writer releases.
 *
 * @author hal.hildebrand
 */
class FineGrainedCollisionWriteLockTest {

    @Test
    void fineGrainedCollisionBlocksWhileGlobalWriteLockIsHeld() throws InterruptedException {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator(), 4, (byte) 12);
        var rng = new Random(7);
        LongEntityID probe = null;
        for (int i = 0; i < 300; i++) {
            var id = octree.insert(new Point3f(rng.nextFloat() * 500, rng.nextFloat() * 500, rng.nextFloat() * 500),
                                   (byte) 10, "e" + i);
            if (i == 0) {
                probe = id;
            }
        }
        final LongEntityID queryId = probe;

        ReadWriteLock lock = ((AbstractSpatialIndex<?, ?, ?>) octree).lock;
        var writeHeld = new AtomicBoolean(false);
        var writeAcquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queryReturned = new AtomicBoolean(false);
        var resultRef = new AtomicReference<List<CollisionPair<LongEntityID, String>>>();

        var writer = new Thread(() -> {
            lock.writeLock().lock();
            try {
                writeHeld.set(true);
                writeAcquired.countDown();
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                writeHeld.set(false);
                lock.writeLock().unlock();
            }
        }, "writer");
        var query = new Thread(() -> {
            resultRef.set(octree.findCollisionsFineGrained(queryId));
            queryReturned.set(true);
        }, "fg-collision-query");

        writer.start();
        assertTrue(writeAcquired.await(5, TimeUnit.SECONDS), "writer must acquire the write lock");
        query.start();

        boolean observedBlocked = false;
        for (int i = 0; i < 5000 && !observedBlocked; i++) {
            assertFalse(queryReturned.get() && writeHeld.get(),
                        "findCollisionsFineGrained returned while the global write lock was held — it does not "
                        + "coordinate with writes (Luciferase-kvdto)");
            var st = query.getState();
            if (st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING) {
                observedBlocked = true;
            } else {
                Thread.sleep(1);
            }
        }
        assertTrue(observedBlocked, "the query should park on the global read lock while a write is held");
        assertFalse(queryReturned.get(), "the query must not complete while the write lock is held");

        release.countDown();
        query.join(5_000);
        writer.join(5_000);
        assertTrue(queryReturned.get(), "the query must complete once the write lock is released");
        assertNotNull(resultRef.get());
    }
}
