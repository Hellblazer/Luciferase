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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-us4zr: {@code AbstractSpatialIndex.kNearestNeighbors} delegated straight to {@code KnnSearcher},
 * which traverses via the fine-grained per-node locking strategy — independent of the global {@code lock} that
 * every write path ({@code lock.writeLock()}) and the other read queries ({@code entitiesInRegion}, collision)
 * use. kNN was therefore the one query that did NOT take a snapshot-consistent view: it could traverse during a
 * write and observe non-linearizable state. The fix wraps the kNN delegation in {@code lock.readLock()}.
 *
 * <p>The underlying stores (ConcurrentSkipListMap + CopyOnWriteArrayList) are individually thread-safe, so the
 * symptom of the old code was inconsistent RESULTS, not a crash — which makes a "never throws" race test vacuous.
 * Instead this test asserts the actual contract deterministically: while another thread holds the global write
 * lock, a kNN call must BLOCK until that write lock is released (i.e. kNN now participates in the global
 * read/write lock). On the pre-fix code kNN returns while the write is still held.
 *
 * <p>{@code lock} is {@code protected} on {@code AbstractSpatialIndex}; this test lives in the same package.
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

        // Access the global lock (protected, same package).
        ReadWriteLock lock = ((AbstractSpatialIndex<?, ?, ?>) octree).lock;

        var writeHeld = new AtomicBoolean(false);
        var writeAcquired = new CountDownLatch(1);
        long holdMillis = 250;

        var writer = new Thread(() -> {
            lock.writeLock().lock();
            try {
                writeHeld.set(true);
                writeAcquired.countDown();
                Thread.sleep(holdMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                writeHeld.set(false);   // cleared BEFORE releasing the lock
                lock.writeLock().unlock();
            }
        }, "writer");
        writer.start();

        assertTrue(writeAcquired.await(5, TimeUnit.SECONDS), "writer must acquire the write lock");
        // The write lock is now held. A coordinated kNN must not complete until it is released.
        var result = octree.kNearestNeighbors(new Point3f(500, 500, 500), 8, Float.MAX_VALUE);

        assertFalse(writeHeld.get(),
                    "kNearestNeighbors returned while the global write lock was still held — it does not "
                    + "coordinate with writes (Luciferase-us4zr)");
        assertNotNull(result, "kNN should still return a result once the write lock is released");
        writer.join(5_000);
    }
}
