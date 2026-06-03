/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-xiv5u: {@code size()} streamed values() with no lock and {@code processEntitiesInSFCOrder} /
 * {@code processNodesInSFCOrder} iterated keySet() then did a separate get() per key (TOCTOU — a concurrent remove
 * nulled the node and silently skipped work). They now read-lock for a consistent snapshot. This test (same package
 * as {@link AbstractSpatialIndex}, so it can call the protected processors) pins ordering/completeness and a
 * concurrency smoke.
 *
 * @author hal.hildebrand
 */
class SFCOrderProcessingTest {

    private static Octree<LongEntityID, String> populated(int n) {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        for (int i = 0; i < n; i++) {
            octree.insert(new LongEntityID(i), new Point3f(100 + i * 50, 100, 100), (byte) 10, "e" + i);
        }
        return octree;
    }

    @Test
    void processEntitiesVisitsAllInSfcOrder() {
        int n = 50;
        var octree = populated(n);

        var visitedKeys = new ArrayList<MortonKey>();
        var count = new int[1];
        octree.processEntitiesInSFCOrder((key, id) -> {
            visitedKeys.add((MortonKey) key);
            count[0]++;
        });

        assertEquals(n, count[0], "every entity must be visited exactly once");
        for (int i = 1; i < visitedKeys.size(); i++) {
            assertTrue(visitedKeys.get(i - 1).compareTo(visitedKeys.get(i)) <= 0,
                       "entities must be visited in non-decreasing SFC key order (Luciferase-xiv5u)");
        }
    }

    @Test
    void sizeMatchesNonEmptyNodeCount() {
        var octree = populated(30);
        // Each entity is at a distinct position/node here, so size() (non-empty node count) is positive and stable.
        assertTrue(octree.size() > 0, "size reflects non-empty nodes");
        int s1 = octree.size();
        assertEquals(s1, octree.size(), "size() is stable under no mutation (read-locked, Luciferase-xiv5u)");
    }

    @Test
    void sfcProcessingIsSafeUnderConcurrentMutation() throws InterruptedException {
        var octree = populated(40);
        var error = new AtomicBoolean(false);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);

        var mutator = new Thread(() -> {
            try {
                start.await();
                for (int i = 40; i < 400; i++) {
                    octree.insert(new LongEntityID(i), new Point3f(100 + (i % 60) * 40, 200, 200), (byte) 10, "x" + i);
                    if (i % 3 == 0) {
                        octree.removeEntity(new LongEntityID(i - 1));
                    }
                }
            } catch (Exception e) {
                error.set(true);
            } finally {
                done.countDown();
            }
        });
        var processor = new Thread(() -> {
            try {
                start.await();
                for (int r = 0; r < 200; r++) {
                    var c = new int[1];
                    octree.processNodesInSFCOrder((key, node) -> c[0]++); // must never throw / CME on a snapshot
                }
            } catch (Exception e) {
                error.set(true);
            } finally {
                done.countDown();
            }
        });

        mutator.start();
        processor.start();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads must finish");
        assertFalse(error.get(), "SFC-order processing on a read-locked snapshot must not throw under concurrent "
                                 + "mutation (Luciferase-xiv5u)");
    }
}
