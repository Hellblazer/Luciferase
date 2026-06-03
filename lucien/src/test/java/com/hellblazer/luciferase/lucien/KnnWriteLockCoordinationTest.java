/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

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
 * Luciferase-us4zr: kNearestNeighbors delegated straight to KnnSearcher (per-node fine-grained locks),
 * independent of the global lock every write path takes — so it could traverse mid-write and observe
 * non-linearizable state. The fix wraps the delegation in lock.readLock(). The underlying concurrent stores don't
 * throw on torn reads, so a "never throws" race test would be vacuous; this asserts the lock-coordination contract
 * deterministically via a thread-state handshake (no sleep-timing): while a writer holds the write lock, the kNN
 * query thread must park on the read lock and must NOT return until the writer releases.
 *
 * @author hal.hildebrand
 */
class KnnWriteLockCoordinationTest {

    @Test
    void knnBlocksWhileGlobalWriteLockIsHeld() throws InterruptedException {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator(), 4, (byte) 12);
        var rng = new Random(42);
        for (int i = 0; i < 500; i++) {
            octree.insert(new Point3f(rng.nextFloat() * 1000, rng.nextFloat() * 1000, rng.nextFloat() * 1000),
                          (byte) 10, "e" + i);
        }

        ReadWriteLock lock = ((AbstractSpatialIndex<?, ?, ?>) octree).lock;
        var writeHeld = new AtomicBoolean(false);
        var writeAcquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queryReturned = new AtomicBoolean(false);
        var resultRef = new AtomicReference<List<LongEntityID>>();

        var writer = new Thread(() -> {
            lock.writeLock().lock();
            try {
                writeHeld.set(true);
                writeAcquired.countDown();
                release.await();               // hold until the test confirms the query is blocked
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                writeHeld.set(false);
                lock.writeLock().unlock();
            }
        }, "writer");
        var query = new Thread(() -> {
            resultRef.set(octree.kNearestNeighbors(new Point3f(500, 500, 500), 8, Float.MAX_VALUE));
            queryReturned.set(true);
        }, "knn-query");

        writer.start();
        assertTrue(writeAcquired.await(5, TimeUnit.SECONDS), "writer must acquire the write lock");
        query.start();

        // Deterministic handshake: wait until the query thread parks on the read lock. If it instead RETURNS while
        // the write lock is held, that is the bug (pre-fix kNN took no global lock).
        boolean observedBlocked = false;
        for (int i = 0; i < 5000 && !observedBlocked; i++) {
            assertFalse(queryReturned.get() && writeHeld.get(),
                        "kNN returned while the global write lock was held — it does not coordinate with writes "
                        + "(Luciferase-us4zr)");
            var st = query.getState();
            if (st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING) {
                observedBlocked = true;
            } else {
                Thread.sleep(1);
            }
        }
        assertTrue(observedBlocked, "kNN query should park on the global read lock while a write is held");
        assertFalse(queryReturned.get(), "kNN must not have completed while the write lock is held");

        release.countDown();
        query.join(5_000);
        writer.join(5_000);
        assertTrue(queryReturned.get(), "kNN must complete once the write lock is released");
        assertNotNull(resultRef.get());
    }
}
