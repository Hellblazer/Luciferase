package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GhostBoundarySync - batched ghost synchronization with TTL and memory limits.
 * <p>
 * GhostBoundarySync manages ghost entity synchronization across bubble boundaries:
 * - Batch ghosts at bucket boundaries (100ms intervals)
 * - Expire stale ghosts (500ms TTL = 5 buckets)
 * - Enforce memory limits (1000 ghosts per neighbor)
 * - Group entities by neighbor region for efficient batching
 * <p>
 * VON integration:
 * - Boundary neighbors = ghost zone overlaps
 * - Discovery via ghost sourceBubbleId
 * - No global bubble registry needed
 *
 * @author hal.hildebrand
 */
class GhostBoundarySyncTest {

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
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestEntityID that)) return false;
            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    // Simple test content
    static class TestContent {
        private final String data;

        TestContent(String data) {
            this.data = data;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestContent that)) return false;
            return data.equals(that.data);
        }

        @Override
        public int hashCode() {
            return data.hashCode();
        }
    }

    private GhostBoundarySync<TestEntityID, TestContent> sync;
    private ExternalBubbleTracker bubbleTracker;
    private GhostLayerHealth health;
    private AtomicInteger ghostsSent;
    private List<SimulationGhostEntity<TestEntityID, TestContent>> receivedGhosts;

    @BeforeEach
    void setUp() {
        bubbleTracker = new ExternalBubbleTracker();
        health = new GhostLayerHealth();
        ghostsSent = new AtomicInteger(0);
        receivedGhosts = new ArrayList<>();

        sync = new GhostBoundarySync<>(
            bubbleTracker,
            health,
            this::sendGhostBatch
        );
    }

    // Mock ghost sender for testing
    private void sendGhostBatch(UUID neighborId, List<SimulationGhostEntity<TestEntityID, TestContent>> ghosts) {
        ghostsSent.addAndGet(ghosts.size());
        receivedGhosts.addAll(ghosts);
    }

    @Test
    void testInitialState() {
        assertEquals(0, sync.getActiveGhostCount(),
                    "Initially no active ghosts");
        assertEquals(0, sync.getExpiredGhostCount(),
                    "Initially no expired ghosts");
    }

    @Test
    void testAddGhostEntity() {
        var entityId = new TestEntityID("entity-1");
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        var bounds = new EntityBounds(position, 0.1f);
        var content = new TestContent("data-1");

        var ghostEntity = new GhostZoneManager.GhostEntity<>(
            entityId,
            content,
            position,
            bounds,
            "tree-A"
        );

        var sourceBubbleId = UUID.randomUUID();
        var neighborId = UUID.randomUUID();

        sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 100L);

        assertEquals(1, sync.getActiveGhostCount(),
                    "Should have 1 active ghost");
    }

    @Test
    void testBatchGhostsAtBucketBoundary() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add 3 ghosts for same neighbor
        for (int i = 0; i < 3; i++) {
            var entityId = new TestEntityID("entity-" + i);
            var position = new Point3f(i * 0.1f, 0.5f, 0.5f);
            var bounds = new EntityBounds(position, 0.1f);
            var ghostEntity = new GhostZoneManager.GhostEntity<>(
                entityId,
                new TestContent("data-" + i),
                position,
                bounds,
                "tree-A"
            );

            sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 100L);
        }

        // Trigger bucket completion
        sync.onBucketComplete(100L);

        assertEquals(3, ghostsSent.get(),
                    "Should send all 3 ghosts in batch");
        assertEquals(3, receivedGhosts.size());
    }

    @Test
    void testGroupGhostsByNeighbor() {
        var neighborA = UUID.randomUUID();
        var neighborB = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add 2 ghosts for neighbor A
        for (int i = 0; i < 2; i++) {
            var entityId = new TestEntityID("entityA-" + i);
            var ghostEntity = createGhostEntity(entityId, i * 0.1f);
            sync.addGhost(ghostEntity, sourceBubbleId, neighborA, 100L);
        }

        // Add 3 ghosts for neighbor B
        for (int i = 0; i < 3; i++) {
            var entityId = new TestEntityID("entityB-" + i);
            var ghostEntity = createGhostEntity(entityId, i * 0.1f);
            sync.addGhost(ghostEntity, sourceBubbleId, neighborB, 100L);
        }

        sync.onBucketComplete(100L);

        assertEquals(5, ghostsSent.get(),
                    "Should send 5 total ghosts (2+3)");
    }

    @Test
    void testTTLExpiration() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add ghost at bucket 100
        var entityId = new TestEntityID("entity-1");
        var ghostEntity = createGhostEntity(entityId, 0.5f);
        sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 100L);

        assertEquals(1, sync.getActiveGhostCount());

        // Advance to bucket 106 (600ms later, beyond 5-bucket TTL)
        sync.expireStaleGhosts(106L);

        assertEquals(0, sync.getActiveGhostCount(),
                    "Ghost should expire after 500ms (5 buckets)");
        assertEquals(1, sync.getExpiredGhostCount(),
                    "Should count 1 expired ghost");
    }

    @Test
    void testTTLNotExpiredWithinWindow() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add ghost at bucket 100
        var entityId = new TestEntityID("entity-1");
        var ghostEntity = createGhostEntity(entityId, 0.5f);
        sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 100L);

        // Advance to bucket 104 (400ms later, within 5-bucket TTL)
        sync.expireStaleGhosts(104L);

        assertEquals(1, sync.getActiveGhostCount(),
                    "Ghost should not expire within 500ms window");
        assertEquals(0, sync.getExpiredGhostCount());
    }

    @Test
    void testMemoryLimit() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add 1100 ghosts (beyond 1000 limit)
        for (int i = 0; i < 1100; i++) {
            var entityId = new TestEntityID("entity-" + i);
            var ghostEntity = createGhostEntity(entityId, i * 0.001f);
            sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 100L);
        }

        assertTrue(sync.getActiveGhostCount() <= 1000,
                  "Should enforce 1000 ghost limit per neighbor");
    }

    @Test
    void testOldestGhostsEvictedWhenLimitExceeded() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add ghosts with increasing timestamps
        for (int i = 0; i < 1100; i++) {
            var entityId = new TestEntityID("entity-" + i);
            var ghostEntity = createGhostEntity(entityId, i * 0.001f);
            sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 100L + i);
        }

        sync.onBucketComplete(1200L);

        // Verify oldest ghosts were evicted (first 100)
        assertEquals(1000, receivedGhosts.size(),
                    "Should send only 1000 most recent ghosts");

        // Verify we have the most recent ghosts (entity-100 onwards)
        var entityIds = receivedGhosts.stream()
            .map(g -> g.entityId().toDebugString())
            .toList();

        // Verify entity-0 through entity-99 were evicted
        assertFalse(entityIds.contains("entity-0"),
                   "Oldest ghost (entity-0) should be evicted");
        assertFalse(entityIds.contains("entity-99"),
                   "Old ghost (entity-99) should be evicted");

        // Verify entity-100 onwards were retained
        assertTrue(entityIds.contains("entity-100"),
                  "Should retain entity-100 (first of most recent 1000)");
        assertTrue(entityIds.contains("entity-1099"),
                  "Should retain entity-1099 (last ghost)");
    }

    @Test
    void testGhostUpdate() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();
        var entityId = new TestEntityID("entity-1");

        // Add initial ghost
        var ghostEntity1 = createGhostEntity(entityId, 0.5f);
        sync.addGhost(ghostEntity1, sourceBubbleId, neighborId, 100L);

        assertEquals(1, sync.getActiveGhostCount());

        // Update same entity (newer bucket)
        var ghostEntity2 = createGhostEntity(entityId, 0.6f);
        sync.addGhost(ghostEntity2, sourceBubbleId, neighborId, 200L);

        assertEquals(1, sync.getActiveGhostCount(),
                    "Update should replace, not add new ghost");

        sync.onBucketComplete(200L);

        assertEquals(1, receivedGhosts.size());
        assertEquals(0.6f, receivedGhosts.get(0).position().x, 0.01f,
                    "Should send updated position");
    }

    @Test
    void testBucketCompletionNotifiesBubbleTracker() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        var entityId = new TestEntityID("entity-1");
        var ghostEntity = createGhostEntity(entityId, 0.5f);
        sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 100L);

        sync.onBucketComplete(100L);

        assertEquals(1, bubbleTracker.getDiscoveredBubbles().size(),
                    "Should notify bubble tracker of discovered bubble");
        assertTrue(bubbleTracker.getDiscoveredBubbles().contains(sourceBubbleId));
    }

    @Test
    void testHealthIntegration() {
        health.setExpectedNeighbors(3);

        var neighborA = UUID.randomUUID();
        var neighborB = UUID.randomUUID();
        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();

        // Add ghosts from 2 different source bubbles
        sync.addGhost(createGhostEntity(new TestEntityID("e1"), 0.1f), bubbleA, neighborA, 100L);
        sync.addGhost(createGhostEntity(new TestEntityID("e2"), 0.2f), bubbleB, neighborB, 100L);

        sync.onBucketComplete(100L);

        assertEquals(2, health.getKnownNeighbors(),
                    "Health should track 2 known neighbors via ghost sources");
        assertEquals(0.67f, health.neighborConsistency(), 0.01f,
                    "NC should be 2/3");
    }

    @Test
    void testEmptyBatch() {
        // No ghosts added
        sync.onBucketComplete(100L);

        assertEquals(0, ghostsSent.get(),
                    "Should not send empty batch");
    }

    @Test
    void testMultipleBucketCycles() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Bucket 100: Add 2 ghosts
        for (int i = 0; i < 2; i++) {
            sync.addGhost(createGhostEntity(new TestEntityID("e" + i), i * 0.1f),
                         sourceBubbleId, neighborId, 100L);
        }
        sync.onBucketComplete(100L);

        assertEquals(2, ghostsSent.get());
        ghostsSent.set(0);
        receivedGhosts.clear();

        // Bucket 200: Add 3 more ghosts
        for (int i = 2; i < 5; i++) {
            sync.addGhost(createGhostEntity(new TestEntityID("e" + i), i * 0.1f),
                         sourceBubbleId, neighborId, 200L);
        }
        sync.onBucketComplete(200L);

        assertEquals(5, ghostsSent.get(),
                    "Should send all 5 active ghosts (2 old + 3 new)");
    }

    @Test
    void testConcurrentGhostAddition() throws InterruptedException {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        int threadCount = 10;
        int ghostsPerThread = 10;

        var threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ghostsPerThread; j++) {
                    var entityId = new TestEntityID("t" + threadId + "-e" + j);
                    var ghostEntity = createGhostEntity(entityId, threadId * 0.1f + j * 0.01f);
                    sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 100L);
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertEquals(100, sync.getActiveGhostCount(),
                    "All 100 ghosts should be tracked (thread-safe)");
    }

    @Test
    void testGetGhostsByNeighbor() {
        var neighborA = UUID.randomUUID();
        var neighborB = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add 2 ghosts for neighbor A
        for (int i = 0; i < 2; i++) {
            sync.addGhost(createGhostEntity(new TestEntityID("eA" + i), i * 0.1f),
                         sourceBubbleId, neighborA, 100L);
        }

        // Add 3 ghosts for neighbor B
        for (int i = 0; i < 3; i++) {
            sync.addGhost(createGhostEntity(new TestEntityID("eB" + i), i * 0.1f),
                         sourceBubbleId, neighborB, 100L);
        }

        var ghostsA = sync.getGhostsByNeighbor(neighborA);
        var ghostsB = sync.getGhostsByNeighbor(neighborB);

        assertEquals(2, ghostsA.size(), "Neighbor A should have 2 ghosts");
        assertEquals(3, ghostsB.size(), "Neighbor B should have 3 ghosts");
    }

    @Test
    void testClearExpiredGhosts() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add ghost at bucket 100
        sync.addGhost(createGhostEntity(new TestEntityID("e1"), 0.5f),
                     sourceBubbleId, neighborId, 100L);

        // Expire and clear
        sync.expireStaleGhosts(700L);  // Beyond TTL
        sync.clearExpiredGhosts();

        assertEquals(0, sync.getExpiredGhostCount(),
                    "Expired ghosts should be cleared");
    }

    // Helper method to create ghost entity
    private GhostZoneManager.GhostEntity<TestEntityID, TestContent> createGhostEntity(
        TestEntityID entityId,
        float x
    ) {
        var position = new Point3f(x, 0.5f, 0.5f);
        var bounds = new EntityBounds(position, 0.1f);
        return new GhostZoneManager.GhostEntity<>(
            entityId,
            new TestContent("data-" + entityId.toDebugString()),
            position,
            bounds,
            "tree-A"
        );
    }
}
