/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency regression for the one coherent mutation-lock protocol (Luciferase-n7io1).
 * <p>
 * Two <b>genuinely different</b> multi-bubble entity-writer paths hammer the SAME bubble pair concurrently:
 * <ul>
 *   <li><b>Path 1 — {@link BubbleLifecycle#transferEntities}</b>: a production writer that acquires the
 *       pair via the {@link MutationLocks} utility.</li>
 *   <li><b>Path 2 — an inline manual locker</b>: acquires {@code getMutationLock()} directly in ascending
 *       UUID order WITHOUT the utility (the documented contract that {@code TetrahedralMigration} /
 *       {@code BubbleMerger} historically used). This proves a caller following the ordering by hand
 *       composes deadlock-free with a {@code MutationLocks} caller — the cross-implementation case.</li>
 * </ul>
 * Assertions are sensitive to the ACTUAL corruption class, not just the id set: {@code entityCount()} reads
 * the Tetree spatial index while {@code getAllEntityRecords()} reads the id mapping, so a raced
 * add/remove that leaves them inconsistent (a spatial-index "zombie" or a lost mapping) is caught — a plain
 * id-set check would miss it because {@code idMapping.put} overwrites on a duplicate key.
 * <p>
 * Before n7io1, {@code transferEntities} skipped the lock, so its snapshot/add/remove interleaved with the
 * other writer and corrupted the pair. With the shared protocol the run (1) cannot deadlock — it completes
 * within the timeout — and (2) conserves entities exactly across both the spatial index and the id mapping.
 *
 * @author hal.hildebrand
 */
class BubbleMutationLockConcurrencyTest {

    @Test
    void concurrentDistinctWriterPaths_conserveEntitiesAndDoNotDeadlock() throws Exception {
        var lifecycle = new BubbleLifecycle(e -> { });
        var a = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10);
        var b = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10);

        int entityCount = 50;
        var originalIds = new HashSet<String>();
        for (int i = 0; i < entityCount; i++) {
            var id = "e" + i;
            originalIds.add(id);
            a.addEntity(id, new Point3f(i, 0f, 0f), "content-" + i);
        }

        int iterations = 400;
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);

        // Path 1: production BubbleLifecycle.transferEntities (acquires via MutationLocks), ping-ponging.
        Callable<Void> path1 = () -> {
            start.await();
            for (int i = 0; i < iterations; i++) {
                lifecycle.transferEntities(a, b);
                lifecycle.transferEntities(b, a);
            }
            return null;
        };

        // Path 2: an INLINE manual locker (UUID order, not via MutationLocks), opposite phase.
        Callable<Void> path2 = () -> {
            start.await();
            for (int i = 0; i < iterations; i++) {
                inlineOrderedMove(b, a);
                inlineOrderedMove(a, b);
            }
            return null;
        };

        var f1 = pool.submit(path1);
        var f2 = pool.submit(path2);
        start.countDown();

        // Completion within the timeout proves no lock-ordering deadlock between the utility path and the
        // inline-manual-lock path (both acquire in ascending UUID order).
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // Spatial-index conservation (catches zombies / lost inserts that an id-set check would hide).
        assertThat(a.entityCount() + b.entityCount())
            .as("total spatial-index entity count must be exactly conserved across the pair")
            .isEqualTo(entityCount);

        // Per-bubble internal consistency: spatial index count == id-mapping record count.
        assertThat(a.entityCount())
            .as("bubble A: spatial-index count must match id-mapping record count (no divergence)")
            .isEqualTo(a.getAllEntityRecords().size());
        assertThat(b.entityCount())
            .as("bubble B: spatial-index count must match id-mapping record count (no divergence)")
            .isEqualTo(b.getAllEntityRecords().size());

        // Id-level conservation + uniqueness.
        var inA = new HashSet<>(a.getEntities());
        var inB = new HashSet<>(b.getEntities());
        var union = new HashSet<String>();
        union.addAll(inA);
        union.addAll(inB);
        assertThat(union)
            .as("entity ids must be exactly conserved (no loss/creation)")
            .isEqualTo(originalIds);
        assertThat(java.util.Collections.disjoint(inA, inB))
            .as("no entity may reside in both bubbles (no duplication)")
            .isTrue();
    }

    /**
     * Move every entity {@code src -> dst} atomically by acquiring both mutation locks BY HAND in
     * ascending UUID order — the documented inline contract, deliberately NOT routed through
     * {@link MutationLocks}, so the test exercises a writer path distinct from {@code transferEntities}.
     */
    private static void inlineOrderedMove(EnhancedBubble src, EnhancedBubble dst) {
        int cmp = src.id().compareTo(dst.id());
        ReentrantLock first = cmp <= 0 ? src.getMutationLock() : dst.getMutationLock();
        ReentrantLock second = cmp <= 0 ? dst.getMutationLock() : src.getMutationLock();
        first.lock();
        try {
            second.lock();
            try {
                var records = src.getAllEntityRecords();
                for (var record : records) {
                    dst.addEntity(record.id(), record.position(), record.content());
                }
                for (var record : records) {
                    src.removeEntity(record.id());
                }
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }
}
