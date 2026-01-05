/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.simulation.SimulationGhostEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GhostChannel - batched ghost transmission between servers.
 * <p>
 * GhostChannel provides an abstraction for ghost entity transmission:
 * - Batching: Queue ghosts, send in batches at bucket boundaries
 * - Latency simulation: InMemoryGhostChannel supports configurable latency
 * - Multiple handlers: Multiple receivers can subscribe to incoming batches
 * <p>
 * Implementations:
 * - InMemoryGhostChannel: For testing, with optional simulated latency
 * - GrpcGhostChannel: For production (Phase 5)
 *
 * @author hal.hildebrand
 */
class GhostChannelTest {

    // Simple EntityID implementation for testing
    static class TestEntityID implements EntityID {
        private final String id;

        TestEntityID(String id) {
            this.id = id;
        }

        @Override
        public String toDebugString() {
            return id;
        }

        @Override
        public int compareTo(EntityID other) {
            return id.compareTo(other.toDebugString());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof TestEntityID other)) return false;
            return id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private SimulationGhostEntity<TestEntityID, String> createTestGhost() {
        var position = new Point3f(0, 0, 0);
        var ghostEntity = new GhostZoneManager.GhostEntity<>(
            new TestEntityID("test-" + UUID.randomUUID()),
            "content",
            position,
            new EntityBounds(position, 0.5f),
            "tree-1"
        );

        return new SimulationGhostEntity<>(
            ghostEntity,
            UUID.randomUUID(),  // sourceBubbleId
            1L,                 // bucket
            0L,                 // epoch
            0L                  // version
        );
    }

    @Test
    void testQueueGhost() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var targetId = UUID.randomUUID();
        var ghost = createTestGhost();

        channel.queueGhost(targetId, ghost);

        assertEquals(1, channel.getPendingCount(targetId));
    }

    @Test
    void testSendBatch() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var receivedBatches = new CopyOnWriteArrayList<List<SimulationGhostEntity<TestEntityID, String>>>();
        channel.onReceive((from, ghosts) -> receivedBatches.add(ghosts));

        var targetId = UUID.randomUUID();
        var ghosts = List.of(createTestGhost(), createTestGhost());

        channel.sendBatch(targetId, ghosts);

        assertEquals(1, receivedBatches.size());
        assertEquals(2, receivedBatches.get(0).size());
    }

    @Test
    void testFlushSendsPendingGhosts() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var receivedBatches = new ConcurrentHashMap<UUID, List<SimulationGhostEntity<TestEntityID, String>>>();
        channel.onReceive((from, ghosts) -> receivedBatches.put(from, ghosts));

        var target1 = UUID.randomUUID();
        var target2 = UUID.randomUUID();

        channel.queueGhost(target1, createTestGhost());
        channel.queueGhost(target1, createTestGhost());
        channel.queueGhost(target2, createTestGhost());

        channel.flush(1L);

        assertEquals(2, receivedBatches.get(target1).size());
        assertEquals(1, receivedBatches.get(target2).size());
        assertEquals(0, channel.getPendingCount(target1));
    }

    @Test
    void testBatchNotSentBeforeFlush() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var received = new AtomicBoolean(false);
        channel.onReceive((from, ghosts) -> received.set(true));

        var targetId = UUID.randomUUID();
        channel.queueGhost(targetId, createTestGhost());

        assertFalse(received.get());
        assertEquals(1, channel.getPendingCount(targetId));
    }

    @Test
    void testBatchGroupedByTarget() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var batches = new ConcurrentHashMap<UUID, Integer>();
        channel.onReceive((from, ghosts) -> batches.put(from, ghosts.size()));

        var target1 = UUID.randomUUID();
        var target2 = UUID.randomUUID();

        channel.queueGhost(target1, createTestGhost());
        channel.queueGhost(target2, createTestGhost());
        channel.queueGhost(target1, createTestGhost());

        channel.flush(1L);

        assertEquals(2, batches.get(target1));
        assertEquals(1, batches.get(target2));
    }

    @Test
    void testSimulatedLatency() {
        var latencyMs = 50L;
        var channel = new InMemoryGhostChannel<TestEntityID, String>(latencyMs);
        var received = new AtomicBoolean(false);
        channel.onReceive((from, ghosts) -> received.set(true));

        var start = System.currentTimeMillis();
        channel.sendBatch(UUID.randomUUID(), List.of(createTestGhost()));
        var elapsed = System.currentTimeMillis() - start;

        assertTrue(received.get());
        assertTrue(elapsed >= latencyMs, "Expected latency >= " + latencyMs + "ms, got " + elapsed + "ms");
    }

    @Test
    void testMultipleHandlers() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var handler1Called = new AtomicBoolean(false);
        var handler2Called = new AtomicBoolean(false);

        channel.onReceive((from, ghosts) -> handler1Called.set(true));
        channel.onReceive((from, ghosts) -> handler2Called.set(true));

        channel.sendBatch(UUID.randomUUID(), List.of(createTestGhost()));

        assertTrue(handler1Called.get());
        assertTrue(handler2Called.get());
    }

    @Test
    void testClose() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var targetId = UUID.randomUUID();

        channel.queueGhost(targetId, createTestGhost());
        channel.close();

        assertEquals(0, channel.getPendingCount(targetId));
    }

    @Test
    void testConcurrentQueuing() throws Exception {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var targetId = UUID.randomUUID();
        int threadCount = 10;
        int ghostsPerThread = 100;

        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                for (int i = 0; i < ghostsPerThread; i++) {
                    channel.queueGhost(targetId, createTestGhost());
                }
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount * ghostsPerThread, channel.getPendingCount(targetId));
    }

    @Test
    void testBatchLatencyUnder100ms() {
        var channel = new InMemoryGhostChannel<TestEntityID, String>();
        var targetId = UUID.randomUUID();

        // Queue 1000 ghosts (memory limit)
        for (int i = 0; i < 1000; i++) {
            channel.queueGhost(targetId, createTestGhost());
        }

        var receivedCount = new AtomicInteger(0);
        channel.onReceive((from, ghosts) -> receivedCount.addAndGet(ghosts.size()));

        var start = System.nanoTime();
        channel.flush(1L);
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1000, receivedCount.get());
        assertTrue(elapsedMs < 100, "Expected latency < 100ms, got " + elapsedMs + "ms");
    }
}
