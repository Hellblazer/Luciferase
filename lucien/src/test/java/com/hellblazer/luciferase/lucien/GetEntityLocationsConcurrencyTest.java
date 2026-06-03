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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-1q51y: {@code getEntityLocations} delegated straight to the entity manager with no lock, while
 * {@code updateEntity} clears then re-inserts the location set under the write lock. A reader could observe the
 * transiently EMPTY set mid-move. With the read lock the reader only ever sees a complete location set (before or
 * after the move), so a concurrently-moving entity's location set is never empty.
 *
 * @author hal.hildebrand
 */
class GetEntityLocationsConcurrencyTest {

    @Test
    void locationsNeverTransientlyEmptyDuringConcurrentMove() throws InterruptedException {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var id = new LongEntityID(1);
        octree.insert(id, new Point3f(100, 100, 100), (byte) 10, "e");

        final int moves = 4000;
        var sawEmpty = new AtomicBoolean(false);
        var reads = new AtomicInteger(0);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);

        var mover = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < moves; i++) {
                    // Alternate positions far enough apart to land in different nodes (clear + re-insert).
                    float c = (i % 2 == 0) ? 100 : 5000;
                    octree.updateEntity(id, new Point3f(c, c, c), (byte) 10);
                }
            } catch (InterruptedException ignored) {
            } finally {
                done.countDown();
            }
        });

        var reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < moves; i++) {
                    if (octree.getEntityLocations(id).isEmpty()) {
                        sawEmpty.set(true);
                    }
                    reads.incrementAndGet();
                }
            } catch (InterruptedException ignored) {
            } finally {
                done.countDown();
            }
        });

        mover.start();
        reader.start();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads must finish");

        assertEquals(moves, reads.get(), "reader completed all reads");
        assertFalse(sawEmpty.get(),
                    "a concurrently-moving entity's location set must never be transiently empty (Luciferase-1q51y)");
    }
}
