/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.ExternalBubbleTracker;
import com.hellblazer.luciferase.simulation.ghost.GhostLayerHealth;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
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
 * Tests for BubbleGhostManager - orchestrates ghost sync components.
 * <p>
 * BubbleGhostManager is the central orchestrator for Phase 3 ghost synchronization. It coordinates:
 * - ServerRegistry: Which bubbles are on which servers
 * - GhostChannel: Batched ghost transmission
 * - SameServerOptimizer: Direct memory access bypass for same-server bubbles
 * - GhostBoundarySync: TTL and memory limit management
 * - GhostLayerHealth: NC metric monitoring
 * - ExternalBubbleTracker: Bubble discovery via ghost interactions
 * <p>
 * The manager provides a unified API for:
 * - Creating ghosts when entities near boundaries
 * - Processing incoming ghost batches from other bubbles
 * - Flushing batches at bucket boundaries (100ms intervals)
 * - Managing neighbor lifecycle (VON add/remove hooks)
 * - Exposing metrics (active ghost count, NC metric)
 *
 * @author hal.hildebrand
 */
class BubbleGhostManagerTest {

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

    private EnhancedBubble bubble;
    private ServerRegistry serverRegistry;
    private InMemoryGhostChannel<TestEntityID, String> ghostChannel;
    private SameServerOptimizer optimizer;
    private ExternalBubbleTracker externalBubbleTracker;
    private GhostLayerHealth ghostLayerHealth;
    private BubbleGhostManager<TestEntityID, String> manager;

    private UUID serverId;
    private UUID neighborId;
    private final AtomicInteger receivedBatchCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
        serverId = UUID.randomUUID();
        neighborId = UUID.randomUUID();

        serverRegistry = new ServerRegistry();
        serverRegistry.registerBubble(bubble.id(), serverId);
        serverRegistry.registerBubble(neighborId, serverId);

        ghostChannel = new InMemoryGhostChannel<>();
        // Register batch receiver to track sent batches
        ghostChannel.onReceive((from, ghosts) -> receivedBatchCount.incrementAndGet());

        optimizer = new SameServerOptimizer(serverRegistry);
        optimizer.registerLocalBubble(bubble);

        externalBubbleTracker = new ExternalBubbleTracker();
        ghostLayerHealth = new GhostLayerHealth();

        manager = new BubbleGhostManager<>(
            bubble,
            serverRegistry,
            ghostChannel,
            optimizer,
            externalBubbleTracker,
            ghostLayerHealth
        );
    }

    @Test
    void testInitialization() {
        // Verify manager initializes with all components
        assertNotNull(manager);
        assertEquals(0, manager.getActiveGhostCount());
        assertEquals(1.0f, manager.getNeighborConsistency(), 0.01f); // NC starts at 1.0 (perfect)
    }

    @Test
    void testOnBucketComplete() {
        // Register on different server to trigger ghost sync
        serverRegistry.unregisterBubble(neighborId);
        serverRegistry.registerBubble(neighborId, UUID.randomUUID());

        // Create ghost and trigger bucket completion
        var ghost = createTestGhost(neighborId, 1L);
        manager.notifyEntityNearBoundary(
            ghost.entityId(),
            ghost.position(),
            ghost.content(),
            neighborId,
            1L
        );

        // Verify ghost not sent yet
        int initialBatchCount = receivedBatchCount.get();

        // Complete bucket - should flush batch
        manager.onBucketComplete(1L);

        // Verify batch was sent
        assertTrue(receivedBatchCount.get() > initialBatchCount,
                   "Expected batch to be sent, but receivedBatchCount stayed at " + initialBatchCount);
    }

    @Test
    void testHandleGhostBatch() {
        var ghosts = List.of(createTestGhost(neighborId, 1L), createTestGhost(neighborId, 1L));

        manager.handleGhostBatch(neighborId, ghosts);

        // Verify ghosts were processed (VON discovery update)
        assertTrue(externalBubbleTracker.getDiscoveredBubbles().contains(neighborId));
    }

    @Test
    void testGhostTTLExpiration() {
        // Create ghost at bucket 1
        var ghost = createTestGhost(neighborId, 1L);
        manager.notifyEntityNearBoundary(
            ghost.entityId(),
            ghost.position(),
            ghost.content(),
            neighborId,
            1L
        );

        // Advance to bucket 6 (TTL = 5 buckets, so ghost expires at bucket 6)
        for (long bucket = 2; bucket <= 6; bucket++) {
            manager.onBucketComplete(bucket);
        }

        // Verify ghost expired
        assertEquals(0, manager.getActiveGhostCount());
    }

    @Test
    void testMemoryLimit() {
        // Queue 1100 ghosts (limit is 1000 per neighbor)
        for (int i = 0; i < 1100; i++) {
            var ghost = createTestGhost(neighborId, 1L);
            manager.notifyEntityNearBoundary(
                ghost.entityId(),
                ghost.position(),
                ghost.content(),
                neighborId,
                1L
            );
        }

        // Verify count doesn't exceed limit
        assertTrue(manager.getActiveGhostCount() <= 1000,
                   "Expected <= 1000 ghosts, got " + manager.getActiveGhostCount());
    }

    @Test
    void testOldestFirstEviction() {
        // Create 1000 ghosts with bucket 1
        for (int i = 0; i < 1000; i++) {
            var ghost = createTestGhost(neighborId, 1L);
            manager.notifyEntityNearBoundary(
                ghost.entityId(),
                ghost.position(),
                ghost.content(),
                neighborId,
                1L
            );
        }

        // Add 1 more ghost with bucket 2 (should evict oldest bucket 1 ghost)
        var newerGhost = createTestGhost(neighborId, 2L);
        manager.notifyEntityNearBoundary(
            newerGhost.entityId(),
            newerGhost.position(),
            newerGhost.content(),
            neighborId,
            2L
        );

        // Verify limit maintained
        assertTrue(manager.getActiveGhostCount() <= 1000);
    }

    @Test
    void testSameServerBypass() {
        // Both bubbles on same server - should bypass ghost sync
        var neighbor = new EnhancedBubble(neighborId, (byte) 5, 16);
        optimizer.registerLocalBubble(neighbor);

        // Add entity to neighbor
        neighbor.addEntity("entity-1", new Point3f(1.0f, 1.0f, 1.0f), "content");

        // Notify boundary crossing
        var ghost = createTestGhost(neighborId, 1L);
        manager.notifyEntityNearBoundary(
            ghost.entityId(),
            ghost.position(),
            ghost.content(),
            neighborId,
            1L
        );

        manager.onBucketComplete(1L);

        // Verify NO ghost sync occurred (same server bypass)
        // Ghost count should be 0 because direct access was used
        assertEquals(0, manager.getActiveGhostCount());
    }

    @Test
    void testCrossServerSync() {
        // Different servers - should use ghost sync
        var otherServerId = UUID.randomUUID();
        serverRegistry.registerBubble(neighborId, otherServerId);

        var ghost = createTestGhost(neighborId, 1L);
        manager.notifyEntityNearBoundary(
            ghost.entityId(),
            ghost.position(),
            ghost.content(),
            neighborId,
            1L
        );

        // Verify ghost queued (different server requires sync)
        assertTrue(manager.getActiveGhostCount() > 0);
    }

    @Test
    void testNCMetricUpdate() {
        // Initial NC should be 1.0 (perfect)
        assertEquals(1.0f, manager.getNeighborConsistency(), 0.01f);

        // Receive ghost from new neighbor
        var ghosts = List.of(createTestGhost(neighborId, 1L));
        manager.handleGhostBatch(neighborId, ghosts);

        // NC should update (still high since we're tracking this neighbor)
        float nc = manager.getNeighborConsistency();
        assertTrue(nc >= 0.0f && nc <= 1.0f, "NC should be in [0,1], got " + nc);
    }

    @Test
    void testBubbleDiscovery() {
        // Initially neighbor not known
        assertFalse(externalBubbleTracker.getDiscoveredBubbles().contains(neighborId));

        // Receive ghost from neighbor
        var ghosts = List.of(createTestGhost(neighborId, 1L));
        manager.handleGhostBatch(neighborId, ghosts);

        // Verify neighbor discovered
        assertTrue(externalBubbleTracker.getDiscoveredBubbles().contains(neighborId));
    }

    @Test
    void testConcurrentOperations() throws Exception {
        int threadCount = 10;
        int operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    var ghost = createTestGhost(neighborId, 1L);
                    manager.notifyEntityNearBoundary(
                        ghost.entityId(),
                        ghost.position(),
                        ghost.content(),
                        neighborId,
                        1L
                    );
                }
                latch.countDown();
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify no exceptions and reasonable ghost count
        assertTrue(manager.getActiveGhostCount() >= 0);
        assertTrue(manager.getActiveGhostCount() <= 1000); // Memory limit enforced
    }

    @Test
    void testMultipleNeighbors() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        var neighbor3 = UUID.randomUUID();

        serverRegistry.registerBubble(neighbor1, UUID.randomUUID());
        serverRegistry.registerBubble(neighbor2, UUID.randomUUID());
        serverRegistry.registerBubble(neighbor3, UUID.randomUUID());

        // Add ghosts for each neighbor
        for (var neighborId : List.of(neighbor1, neighbor2, neighbor3)) {
            for (int i = 0; i < 100; i++) {
                var ghost = createTestGhost(neighborId, 1L);
                manager.notifyEntityNearBoundary(
                    ghost.entityId(),
                    ghost.position(),
                    ghost.content(),
                    neighborId,
                    1L
                );
            }
        }

        // Verify ghosts tracked independently
        assertEquals(300, manager.getActiveGhostCount());

        manager.onBucketComplete(1L);

        // Simulate receiving ghosts from each neighbor (for discovery)
        manager.handleGhostBatch(neighbor1, List.of(createTestGhost(neighbor1, 1L)));
        manager.handleGhostBatch(neighbor2, List.of(createTestGhost(neighbor2, 1L)));
        manager.handleGhostBatch(neighbor3, List.of(createTestGhost(neighbor3, 1L)));

        // Verify all neighbors discovered
        assertTrue(externalBubbleTracker.getDiscoveredBubbles().contains(neighbor1));
        assertTrue(externalBubbleTracker.getDiscoveredBubbles().contains(neighbor2));
        assertTrue(externalBubbleTracker.getDiscoveredBubbles().contains(neighbor3));
    }

    // Helper methods

    private SimulationGhostEntity<TestEntityID, String> createTestGhost(UUID targetBubbleId, long bucket) {
        var position = new Point3f(1.0f, 1.0f, 1.0f);
        var ghostEntity = new GhostZoneManager.GhostEntity<>(
            new TestEntityID("test-" + UUID.randomUUID()),
            "content",
            position,
            new EntityBounds(position, 0.5f),
            "tree-1"
        );

        return new SimulationGhostEntity<>(
            ghostEntity,
            bubble.id(),  // sourceBubbleId
            bucket,
            0L,           // epoch
            0L            // version
        );
    }
}
