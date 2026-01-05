package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.spatial.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BubbleDynamicsManager - core bubble lifecycle orchestration.
 * <p>
 * BubbleDynamicsManager coordinates:
 * - Bubble merge (cross-bubble affinity > 0.6)
 * - Bubble split (disconnected interaction graph)
 * - Entity transfer (entity affinity < 0.5)
 * - Bubble migration (load balancing)
 * - Partition recovery (stock neighbors)
 * <p>
 * Success criteria from Phase 4:
 * - Merge preserves all entities (no duplication/loss)
 * - Split preserves all entities
 * - Entity reassignment < 0.01% loss rate
 * - Idempotency prevents duplicate migrations
 * - Stock neighbors enable partition recovery
 *
 * @author hal.hildebrand
 */
class BubbleDynamicsManagerTest {

    // Simple EntityID for testing
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

    private BubbleDynamicsManager<TestEntityID> manager;
    private ExternalBubbleTracker bubbleTracker;
    private GhostLayerHealth health;
    private MigrationLog migrationLog;
    private StockNeighborList stockNeighbors;
    private List<BubbleEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        bubbleTracker = new ExternalBubbleTracker();
        health = new GhostLayerHealth();
        migrationLog = new MigrationLog();
        stockNeighbors = new StockNeighborList(10);
        capturedEvents = new CopyOnWriteArrayList<>();

        manager = new BubbleDynamicsManager<>(
            bubbleTracker,
            health,
            migrationLog,
            stockNeighbors,
            capturedEvents::add
        );
    }

    @Test
    void testInitialState() {
        assertEquals(0, manager.getBubbleCount(),
                    "Initially no bubbles tracked");
        assertEquals(0, capturedEvents.size(),
                    "No events emitted initially");
    }

    @Test
    void testRegisterBubble() {
        var bubbleId = UUID.randomUUID();
        var entities = Set.of(new TestEntityID("e1"), new TestEntityID("e2"));

        manager.registerBubble(bubbleId, entities);

        assertEquals(1, manager.getBubbleCount());
        assertEquals(2, manager.getEntityCount(bubbleId));
    }

    @Test
    void testMergeCandidateDetection() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        manager.registerBubble(bubble1, Set.of(
            new TestEntityID("e1"),
            new TestEntityID("e2")
        ));
        manager.registerBubble(bubble2, Set.of(
            new TestEntityID("e3"),
            new TestEntityID("e4")
        ));

        // Simulate high cross-bubble affinity (> 0.6 threshold)
        // Record many ghost interactions between bubbles
        for (int i = 0; i < 100; i++) {
            bubbleTracker.recordGhostInteraction(bubble2);
        }

        manager.processBucket(100L);

        // Should detect merge candidate
        var mergeCandidates = manager.getMergeCandidates(0.6f);
        assertFalse(mergeCandidates.isEmpty(),
                   "Should identify high-affinity merge candidate");
    }

    @Test
    void testBubbleMerge() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var entity1 = new TestEntityID("e1");
        var entity2 = new TestEntityID("e2");
        var entity3 = new TestEntityID("e3");

        manager.registerBubble(bubble1, Set.of(entity1, entity2));  // Size 2 (larger)
        manager.registerBubble(bubble2, Set.of(entity3));           // Size 1 (smaller)

        // Merge bubble2 (smaller) into bubble1 (larger)
        manager.mergeBubbles(bubble1, bubble2, 100L);

        // Verify event emitted
        assertEquals(1, capturedEvents.size());
        assertTrue(capturedEvents.get(0) instanceof BubbleEvent.Merge);

        var mergeEvent = (BubbleEvent.Merge) capturedEvents.get(0);
        assertEquals(bubble1, mergeEvent.bubble1());
        assertEquals(bubble2, mergeEvent.bubble2());
        assertEquals(bubble1, mergeEvent.result(),
                    "Larger bubble (bubble1) should be result");
        assertEquals(3, mergeEvent.totalSize(),
                    "All entities preserved");

        // Verify bubble2 removed, bubble1 has all entities
        assertEquals(1, manager.getBubbleCount(),
                    "Bubble2 should be removed");
        assertEquals(3, manager.getEntityCount(bubble1),
                    "Bubble1 should have all 3 entities");

        // Verify no entity loss or duplication
        var entities = manager.getEntities(bubble1);
        assertTrue(entities.contains(entity1));
        assertTrue(entities.contains(entity2));
        assertTrue(entities.contains(entity3));
    }

    @Test
    void testBubbleSplit() {
        var sourceBubble = UUID.randomUUID();
        var entity1 = new TestEntityID("e1");
        var entity2 = new TestEntityID("e2");
        var entity3 = new TestEntityID("e3");
        var entity4 = new TestEntityID("e4");

        manager.registerBubble(sourceBubble, Set.of(
            entity1, entity2, entity3, entity4
        ));

        // Split into 2 components
        var component1Entities = Set.of(entity1, entity2);
        var component2Entities = Set.of(entity3, entity4);

        var components = manager.splitBubble(
            sourceBubble,
            List.of(component1Entities, component2Entities),
            100L
        );

        // Verify event emitted
        assertEquals(1, capturedEvents.size());
        assertTrue(capturedEvents.get(0) instanceof BubbleEvent.Split);

        var splitEvent = (BubbleEvent.Split) capturedEvents.get(0);
        assertEquals(sourceBubble, splitEvent.source());
        assertEquals(2, splitEvent.componentCount());
        assertEquals(4, splitEvent.totalSize(),
                    "All entities preserved");
        assertTrue(splitEvent.components().contains(sourceBubble),
                  "Source bubble becomes first component");

        // Verify source bubble still exists (becomes first component)
        assertTrue(manager.hasBubble(sourceBubble));
        assertEquals(2, manager.getEntityCount(sourceBubble));

        // Verify new bubble created for second component
        assertEquals(2, manager.getBubbleCount(),
                    "Should have 2 bubbles after split");

        // Verify no entity loss
        var allEntities = new HashSet<TestEntityID>();
        for (var bubbleId : components) {
            allEntities.addAll(manager.getEntities(bubbleId));
        }
        assertEquals(4, allEntities.size(), "All entities preserved");
        assertTrue(allEntities.contains(entity1));
        assertTrue(allEntities.contains(entity2));
        assertTrue(allEntities.contains(entity3));
        assertTrue(allEntities.contains(entity4));
    }

    @Test
    void testEntityTransfer() {
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();
        var entity = new TestEntityID("e1");

        manager.registerBubble(sourceBubble, Set.of(entity));
        manager.registerBubble(targetBubble, Set.of());

        // Transfer entity (affinity 0.7 with target)
        manager.transferEntity(entity, sourceBubble, targetBubble, 0.7f, 100L);

        // Verify event emitted
        assertEquals(1, capturedEvents.size());
        assertTrue(capturedEvents.get(0) instanceof BubbleEvent.EntityTransfer);

        var transferEvent = (BubbleEvent.EntityTransfer) capturedEvents.get(0);
        assertEquals(entity, transferEvent.entityId());
        assertEquals(sourceBubble, transferEvent.sourceBubble());
        assertEquals(targetBubble, transferEvent.targetBubble());
        assertEquals(0.7f, transferEvent.affinity(), 0.01f);

        // Verify entity moved
        assertEquals(0, manager.getEntityCount(sourceBubble),
                    "Source should be empty");
        assertEquals(1, manager.getEntityCount(targetBubble),
                    "Target should have entity");
        assertTrue(manager.getEntities(targetBubble).contains(entity));
    }

    @Test
    void testEntityTransferIdempotency() {
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();
        var entity = new TestEntityID("e1");
        var token = UUID.randomUUID();

        manager.registerBubble(sourceBubble, Set.of(entity));
        manager.registerBubble(targetBubble, Set.of());

        // First transfer with token
        manager.transferEntityWithToken(
            entity, sourceBubble, targetBubble, 0.7f, token, 100L
        );

        assertEquals(1, manager.getEntityCount(targetBubble));
        assertEquals(1, capturedEvents.size());

        // Duplicate transfer attempt (same token)
        manager.transferEntityWithToken(
            entity, sourceBubble, targetBubble, 0.7f, token, 100L
        );

        // Should be rejected (idempotency)
        assertEquals(1, manager.getEntityCount(targetBubble),
                    "Entity count should not change");
        assertEquals(1, capturedEvents.size(),
                    "No duplicate event emitted");
    }

    @Test
    void testDriftingEntityDetection() {
        var bubbleId = UUID.randomUUID();
        var entity1 = new TestEntityID("e1");  // High affinity
        var entity2 = new TestEntityID("e2");  // Low affinity (drifting)

        manager.registerBubble(bubbleId, Set.of(entity1, entity2));

        // Set affinity values
        manager.setEntityAffinity(entity1, bubbleId, 0.8f);  // High
        manager.setEntityAffinity(entity2, bubbleId, 0.3f);  // Low (< 0.5)

        var drifting = manager.getDriftingEntities(bubbleId, 0.5f);

        assertEquals(1, drifting.size());
        assertTrue(drifting.contains(entity2),
                  "Low affinity entity should be drifting");
        assertFalse(drifting.contains(entity1),
                   "High affinity entity should not be drifting");
    }

    @Test
    void testPartitionDetection() {
        health.setExpectedNeighbors(10);

        // Simulate partition: only 3 out of 10 neighbors known
        for (int i = 0; i < 3; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        manager.processBucket(100L);

        // NC = 3/10 = 0.3 (< 0.5 = partition threshold)
        var partitionEvents = capturedEvents.stream()
            .filter(e -> e instanceof BubbleEvent.PartitionDetected)
            .toList();

        assertFalse(partitionEvents.isEmpty(),
                   "Should detect partition (NC < 0.5)");

        var partition = (BubbleEvent.PartitionDetected) partitionEvents.get(0);
        assertEquals(3, partition.knownNeighbors());
        assertEquals(10, partition.expectedNeighbors());
        assertTrue(partition.nc() < 0.5f, "NC should indicate partition");
    }

    @Test
    void testPartitionRecovery() {
        health.setExpectedNeighbors(10);

        // Phase 1: Partition detected
        for (int i = 0; i < 3; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }
        manager.processBucket(100L);

        // Verify partition detected
        assertTrue(capturedEvents.stream()
            .anyMatch(e -> e instanceof BubbleEvent.PartitionDetected));

        capturedEvents.clear();

        // Phase 2: Recovery via stock neighbors
        // Discover 8 more neighbors
        for (int i = 0; i < 8; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }
        manager.processBucket(150L);  // 50 buckets later

        // NC = 11/10 > 0.9 (full recovery)
        var recoveryEvents = capturedEvents.stream()
            .filter(e -> e instanceof BubbleEvent.PartitionRecovered)
            .toList();

        assertFalse(recoveryEvents.isEmpty(),
                   "Should detect partition recovery");

        var recovery = (BubbleEvent.PartitionRecovered) recoveryEvents.get(0);
        assertTrue(recovery.nc() >= 0.9f, "NC should indicate recovery");
        assertEquals(50L, recovery.recoveryDuration(),
                    "Recovery took 50 buckets");
    }

    @Test
    void testBubbleMigration() {
        var bubbleId = UUID.randomUUID();
        var sourceNode = UUID.randomUUID();
        var targetNode = UUID.randomUUID();

        manager.registerBubble(bubbleId, Set.of(
            new TestEntityID("e1"),
            new TestEntityID("e2")
        ));

        manager.migrateBubble(bubbleId, sourceNode, targetNode, 100L);

        // Verify event emitted
        assertEquals(1, capturedEvents.size());
        assertTrue(capturedEvents.get(0) instanceof BubbleEvent.BubbleMigration);

        var migration = (BubbleEvent.BubbleMigration) capturedEvents.get(0);
        assertEquals(bubbleId, migration.bubbleId());
        assertEquals(sourceNode, migration.sourceNode());
        assertEquals(targetNode, migration.targetNode());
        assertEquals(2, migration.entityCount());
        assertTrue(migration.isLocalMigration());
    }

    @Test
    void testProcessBucketIntegration() {
        health.setExpectedNeighbors(5);

        // Register bubbles
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        manager.registerBubble(bubble1, Set.of(
            new TestEntityID("e1"),
            new TestEntityID("e2")
        ));
        manager.registerBubble(bubble2, Set.of(
            new TestEntityID("e3")
        ));

        // Simulate ghost interactions (build affinity)
        for (int i = 0; i < 50; i++) {
            bubbleTracker.recordGhostInteraction(bubble2);
        }

        // Simulate neighbor discovery
        for (int i = 0; i < 4; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        // Process bucket - should check merge/split/partition
        manager.processBucket(100L);

        // Verify manager ran checks (no specific assertions, just coverage)
        assertNotNull(manager.getMergeCandidates(0.6f));
    }

    @Test
    void testUnregisterBubble() {
        var bubbleId = UUID.randomUUID();
        manager.registerBubble(bubbleId, Set.of(new TestEntityID("e1")));

        assertEquals(1, manager.getBubbleCount());

        manager.unregisterBubble(bubbleId);

        assertEquals(0, manager.getBubbleCount());
        assertFalse(manager.hasBubble(bubbleId));
    }

    @Test
    void testGetAllBubbles() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        manager.registerBubble(bubble1, Set.of(new TestEntityID("e1")));
        manager.registerBubble(bubble2, Set.of(new TestEntityID("e2")));

        var bubbles = manager.getAllBubbles();

        assertEquals(2, bubbles.size());
        assertTrue(bubbles.contains(bubble1));
        assertTrue(bubbles.contains(bubble2));
    }

    @Test
    void testConcurrentBubbleRegistration() throws InterruptedException {
        int threadCount = 10;
        int bubblesPerThread = 10;

        var threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < bubblesPerThread; i++) {
                    var bubbleId = UUID.randomUUID();
                    var entity = new TestEntityID("t" + threadId + "-e" + i);
                    manager.registerBubble(bubbleId, Set.of(entity));
                }
            });
            threads[t].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertEquals(100, manager.getBubbleCount(),
                    "All 100 bubbles should be registered (thread-safe)");
    }
}
