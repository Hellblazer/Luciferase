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
 * Luciferase-kvdto: CollisionEngine.findCollisionsFineGrained used the per-node fine-grained locking strategy but
 * did not take the global read lock, so (like the pre-us4zr kNN) it could traverse mid-write and observe torn
 * collision results. The fix wraps it in core.lock().readLock(). This asserts the coordination deterministically:
 * while another thread holds the global write lock, findCollisionsFineGrained must block until it is released.
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
                writeHeld.set(false);
                lock.writeLock().unlock();
            }
        }, "writer");
        writer.start();

        assertTrue(writeAcquired.await(5, TimeUnit.SECONDS), "writer must acquire the write lock");
        var result = octree.findCollisionsFineGrained(queryId);

        assertFalse(writeHeld.get(),
                    "findCollisionsFineGrained returned while the global write lock was still held — it does not "
                    + "coordinate with writes (Luciferase-kvdto)");
        assertNotNull(result, "should still return once the write lock is released");
        writer.join(5_000);
    }
}
