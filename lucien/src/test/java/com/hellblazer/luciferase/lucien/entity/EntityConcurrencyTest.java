/*
 * Copyright (c) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
package com.hellblazer.luciferase.lucien.entity;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the PR Tranche C fix replacing {@code Entity.locations}'
 * plain {@link java.util.HashSet} with {@link java.util.concurrent.ConcurrentHashMap#newKeySet}.
 *
 * <p>EntityManager.entities is a ConcurrentHashMap so two threads can call
 * EntityManager.addEntityLocation / clearEntityLocations / getEntityLocations
 * against the same Entity concurrently. The prior HashSet inside Entity was
 * racy under any such concurrent insert/move sequence — symptoms ranged from
 * ConcurrentModificationException to silently lost updates.
 */
class EntityConcurrencyTest {

    @Test
    @DisplayName("addLocation from many threads does not lose updates or throw")
    void testConcurrentAddLocationDoesNotLose() throws Exception {
        var entity = new Entity<MortonKey, String>("content", new Point3f(0, 0, 0));

        int threads = 8;
        int perThread = 500;
        var pool = Executors.newFixedThreadPool(threads);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var errors = new ArrayList<Throwable>();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        entity.addLocation(new MortonKey(base + i));
                    }
                } catch (Throwable ex) {
                    synchronized (errors) {
                        errors.add(ex);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Threads did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        synchronized (errors) {
            assertTrue(errors.isEmpty(),
                "Concurrent addLocation produced exceptions: " + errors);
        }
        // With the old HashSet, races would lose updates and/or throw.
        // ConcurrentHashMap.newKeySet guarantees every distinct key is present.
        assertEquals(threads * perThread, entity.getLocations().size());
    }

    @Test
    @DisplayName("getLocations iteration tolerates concurrent mutation")
    void testGetLocationsIterationDuringMutation() throws Exception {
        var entity = new Entity<MortonKey, String>("content", new Point3f(0, 0, 0));
        for (int i = 0; i < 100; i++) {
            entity.addLocation(new MortonKey(i));
        }

        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        var stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        var errors = new ArrayList<Throwable>();

        // Writer thread continuously mutates the location set.
        pool.submit(() -> {
            try {
                go.await();
                int n = 100;
                while (!stop.get()) {
                    entity.addLocation(new MortonKey(n++));
                    if (n > 200) {
                        entity.removeLocation(new MortonKey(n - 100));
                    }
                }
            } catch (Throwable ex) {
                synchronized (errors) { errors.add(ex); }
            }
        });

        // Reader thread iterates the snapshot returned by getLocations many
        // times — with the old HashSet this could throw CME if iteration
        // overlapped a mutation. ConcurrentHashMap.newKeySet's weakly-
        // consistent iterator does not throw.
        pool.submit(() -> {
            try {
                go.await();
                for (int i = 0; i < 1000; i++) {
                    int count = 0;
                    for (var loc : entity.getLocations()) {
                        if (loc != null) count++;
                    }
                    if (count < 0) throw new IllegalStateException();
                }
            } catch (Throwable ex) {
                synchronized (errors) { errors.add(ex); }
            }
        });

        go.countDown();
        Thread.sleep(200);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        synchronized (errors) {
            assertTrue(errors.isEmpty(),
                "Concurrent read/write on Entity.locations produced exceptions: " + errors);
        }
    }
}
