/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostBatch;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency regression for {@link GrpcGhostChannel} queue/flush (Luciferase-3ko). The pre-fix code
 * (a) re-{@code get()} the queue after {@code computeIfAbsent().add()} — NPE when a concurrent
 * {@code flushToTarget} {@code remove()}d the entry in between; and (b) stored a plain {@code ArrayList}
 * that a concurrent flush drained while a queuer was still adding (CME / lost elements). The fix uses a
 * per-target {@link java.util.concurrent.ConcurrentLinkedQueue}, captures the queue reference, and drains
 * via {@code poll()} without removing the map entry. This test hammers concurrent queue + flush of the
 * same target and asserts no exception and zero element loss.
 *
 * @author hal.hildebrand
 */
class GrpcGhostChannelConcurrencyTest {

    private static final Point3f POS = new Point3f(1, 2, 3);

    @SuppressWarnings("unchecked")
    private static GhostCommunicationManager<MortonKey, LongEntityID, String> mockManager() {
        var mgr = (GhostCommunicationManager<MortonKey, LongEntityID, String>) mock(GhostCommunicationManager.class);
        when(mgr.requestGhostsAsync(anyInt(), anyLong(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(GhostBatch.getDefaultInstance()));
        return mgr;
    }

    private static GhostElement<MortonKey, LongEntityID, String> ghost(long id) {
        return new GhostElement<>(new MortonKey(id, (byte) 5), new LongEntityID(id), "g" + id, POS, 1, 7L);
    }

    @Test
    void flushToTargetSendsEveryQueuedGhostExactlyOnce() {
        var mgr = mockManager();
        // huge batch size so queueGhost never auto-flushes; we flush explicitly
        var channel = new GrpcGhostChannel<>(mgr, 0, 7L, GhostType.FACES, Integer.MAX_VALUE);
        for (long i = 0; i < 50; i++) {
            channel.queueGhost(1, ghost(i));
        }
        assertEquals(50, channel.getPendingCount(1));

        channel.flushToTarget(1).join();
        assertEquals(0, channel.getTotalPendingCount(), "queue drained after flush");
        assertEquals(50, totalGhostsSent(mgr), "every queued ghost sent exactly once");
    }

    @Test
    void concurrentQueueAndFlushLosesNoGhostsAndNeverThrows() throws Exception {
        var mgr = mockManager();
        var channel = new GrpcGhostChannel<>(mgr, 0, 7L, GhostType.FACES, Integer.MAX_VALUE);

        int queuers = 8;
        int perQueuer = 500;
        int target = 1;
        var pool = Executors.newFixedThreadPool(queuers + 1);
        var start = new CountDownLatch(1);
        var queuersDone = new CountDownLatch(queuers);
        var flusherStop = new AtomicBoolean(false);

        // queuers: each adds `perQueuer` ghosts to the same target concurrently
        for (int q = 0; q < queuers; q++) {
            final long base = (long) q * perQueuer;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perQueuer; i++) {
                        channel.queueGhost(target, ghost(base + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    queuersDone.countDown();
                }
            });
        }
        // flusher: continuously drains the same target while queuers are adding
        var flusher = pool.submit(() -> {
            try {
                start.await();
                while (!flusherStop.get()) {
                    channel.flushToTarget(target).join();
                    Thread.onSpinWait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        assertTrue(queuersDone.await(30, TimeUnit.SECONDS), "queuers finished");
        flusherStop.set(true);
        flusher.get(10, TimeUnit.SECONDS);   // surfaces any exception thrown on the flusher thread
        channel.flush().join();              // final drain of whatever the flusher left
        pool.shutdownNow();

        assertEquals(0, channel.getTotalPendingCount(), "nothing left queued after final flush");
        assertEquals(queuers * perQueuer, totalGhostsSent(mgr),
                     "every concurrently-queued ghost was sent exactly once (no NPE, no CME, no loss)");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int totalGhostsSent(GhostCommunicationManager<MortonKey, LongEntityID, String> mgr) {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(mgr, atLeastOnce()).requestGhostsAsync(eq(1), anyLong(), any(), captor.capture());
        return captor.getAllValues().stream().mapToInt(List::size).sum();
    }
}
