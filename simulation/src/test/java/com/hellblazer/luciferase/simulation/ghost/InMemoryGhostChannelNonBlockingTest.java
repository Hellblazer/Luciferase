/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostEntityHalo;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.103: {@code InMemoryGhostChannel.sendBatch()} must not block the
 * calling thread with {@code Thread.sleep(simulatedLatencyMs)}. Previously a configured latency
 * serialized {@code flush()} into O(neighbors * latency) wall-clock time on the simulation thread (and
 * would block an underlying PrimeMover entity thread). The fix defers delivery onto a daemon scheduler;
 * {@code flush()} / {@code sendBatch()} return promptly and delivery still happens after the delay.
 *
 * @author hal.hildebrand
 */
class InMemoryGhostChannelNonBlockingTest {

    private static final long LATENCY_MS = 300;

    static final class TestEntityID implements EntityID {
        private final String id;
        TestEntityID(String id) { this.id = id; }
        @Override public String toDebugString() { return id; }
        @Override public int compareTo(EntityID other) { return id.compareTo(other.toDebugString()); }
        @Override public boolean equals(Object o) { return o instanceof TestEntityID t && id.equals(t.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private SimulationGhostEntity<TestEntityID, String> ghost() {
        var p = new Point3f(0, 0, 0);
        var halo = new GhostEntityHalo<>(new TestEntityID("g-" + UUID.randomUUID()), "c", p,
                                         new EntityBounds(p, 0.5f), "tree-1");
        return new SimulationGhostEntity<>(halo, UUID.randomUUID(), 1L, 0L, 0L);
    }

    @Test
    void flushDoesNotBlockCallerButStillDelivers() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var channel = new InMemoryGhostChannel<TestEntityID, String>(LATENCY_MS);
            try {
                int targets = 5;
                var received = new CopyOnWriteArrayList<SimulationGhostEntity<TestEntityID, String>>();
                var delivered = new CountDownLatch(targets); // one batch per target
                channel.onReceive((from, ghosts) -> {
                    received.addAll(ghosts);
                    delivered.countDown();
                });

                // Queue to several targets so the OLD code would have slept LATENCY_MS PER target.
                for (int t = 0; t < targets; t++) {
                    channel.queueGhost(UUID.randomUUID(), ghost());
                }

                long startNs = System.nanoTime();
                channel.flush(0L);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                // flush must return well before even a single latency interval — proving it did not
                // block the caller for O(targets * latency) (old behavior would be >= targets*LATENCY).
                assertTrue(elapsedMs < LATENCY_MS,
                           "flush() blocked the caller for " + elapsedMs + "ms; it must not sleep on the "
                           + "calling thread (Luciferase-0frcy.103)");

                // Delivery still happens after the scheduled delay.
                assertTrue(delivered.await(5, TimeUnit.SECONDS), "ghosts must still be delivered after latency");
                assertEquals(targets, received.size(), "all queued ghosts must eventually be delivered");
            } finally {
                channel.close();
            }
        });
    }

    @Test
    void zeroLatencyDeliversInline() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        try {
            var received = new CopyOnWriteArrayList<SimulationGhostEntity<TestEntityID, String>>();
            channel.onReceive((from, ghosts) -> received.addAll(ghosts));
            channel.queueGhost(UUID.randomUUID(), ghost());
            channel.flush(0L);
            assertEquals(1, received.size(), "zero-latency channel must deliver synchronously");
        } finally {
            channel.close();
        }
    }

    /**
     * Luciferase-7wzml.205: {@code close()} previously called {@code shutdownNow()} on the
     * latency scheduler without reporting the count of dropped in-flight (delayed) batches.
     * <p>
     * This test verifies that the drop count is honest: schedule several batches with a long
     * latency, immediately close before they can be delivered, and then confirm that
     * {@link InMemoryGhostChannel#getDroppedBatchCount()} reports a non-zero count
     * (fail-loud / honest reporting, not silent loss).
     */
    @Test
    void close_reportsDroppedInFlightBatches() throws Exception {
        // Use a very long latency so the batches are definitely in-flight when close() is called.
        var channel = new InMemoryGhostChannel<TestEntityID, String>(60_000L);
        var received = new CopyOnWriteArrayList<SimulationGhostEntity<TestEntityID, String>>();
        channel.onReceive((from, ghosts) -> received.addAll(ghosts));

        int batchCount = 3;
        for (int i = 0; i < batchCount; i++) {
            channel.queueGhost(UUID.randomUUID(), ghost());
        }
        channel.flush(0L); // schedules batchCount delayed deliveries, returns immediately

        // Close before any delivery can happen — cancels scheduled tasks.
        channel.close();

        // No deliveries should have happened yet (60s latency).
        assertEquals(0, received.size(), "no batches should be delivered before the latency expires");

        // Honest reporting: dropped batch count must be > 0 (Luciferase-7wzml.205).
        assertTrue(channel.getDroppedBatchCount() > 0,
                   "close() must report dropped in-flight batch count (fail-loud), got 0");
    }
}
