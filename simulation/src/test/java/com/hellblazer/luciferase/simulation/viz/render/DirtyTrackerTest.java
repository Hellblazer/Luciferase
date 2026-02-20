/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DirtyTrackerTest {

    @Test
    void freshKeyVersionIsZero() {
        var tracker = new DirtyTracker();
        var key = new MortonKey(0L, (byte) 5);
        assertEquals(0L, tracker.version(key));
    }

    @Test
    void bumpIncrementsVersion() {
        var tracker = new DirtyTracker();
        var key = new MortonKey(42L, (byte) 5);
        assertEquals(1L, tracker.bump(key));
        assertEquals(2L, tracker.bump(key));
        assertEquals(2L, tracker.version(key));
    }

    @Test
    void isDirtyWhenKeyVersionExceedsCacheVersion() {
        var tracker = new DirtyTracker();
        var key = new MortonKey(1L, (byte) 4);
        assertFalse(tracker.isDirty(key, 0L), "not dirty at version 0");
        tracker.bump(key);
        assertTrue(tracker.isDirty(key, 0L), "dirty after bump vs cache=0");
        assertFalse(tracker.isDirty(key, 1L), "not dirty once cache catches up");
    }

    @Test
    void bumpAllNotifiesAllAffectedKeys() {
        var tracker = new DirtyTracker();
        var k1 = new MortonKey(1L, (byte) 5);
        var k2 = new MortonKey(2L, (byte) 5);
        tracker.bumpAll(java.util.Set.of(k1, k2));
        assertEquals(1L, tracker.version(k1));
        assertEquals(1L, tracker.version(k2));
    }

    @Test
    void concurrentBumpsProduceMonotonicVersionsWithNoLostUpdates() throws InterruptedException {
        var tracker = new DirtyTracker();
        var key = new MortonKey(99L, (byte) 5);
        int threads = 8;
        int bumpsPerThread = 100;
        var latch = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int j = 0; j < bumpsPerThread; j++) {
                    tracker.bump(key);
                }
            });
        }

        latch.countDown();
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals((long) threads * bumpsPerThread, tracker.version(key),
            "All concurrent bumps must be reflected in the final version (no lost updates)");
    }
}
