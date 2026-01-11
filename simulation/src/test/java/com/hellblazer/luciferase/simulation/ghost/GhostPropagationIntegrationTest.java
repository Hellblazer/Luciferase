package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for complete ghost propagation flow across Phase 3 components.
 * <p>
 * Tests the end-to-end flow:
 * 1. Entity near boundary creates ghost (GhostZoneManager)
 * 2. Ghost added to GhostBoundarySync for batching
 * 3. Ghost sent at bucket boundary
 * 4. ExternalBubbleTracker records interaction
 * 5. GhostLayerHealth updates NC metric
 * 6. Merge candidates identified based on interaction affinity
 * <p>
 * Validates VON "boundary neighbors" pattern:
 * - Ghost zone overlap = boundary neighbor relationship
 * - High interaction count = merge candidate
 * - NC metric tracks discovery completeness
 *
 * @author hal.hildebrand
 */
class GhostPropagationIntegrationTest {

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

    // Simple test content
    static class TestContent {
        private final String data;

        TestContent(String data) {
            this.data = data;
        }
    }

    private GhostBoundarySync<TestEntityID, TestContent> ghostSync;
    private ExternalBubbleTracker bubbleTracker;
    private GhostLayerHealth health;
    private List<SimulationGhostEntity<TestEntityID, TestContent>> sentGhosts;

    @BeforeEach
    void setUp() {
        bubbleTracker = new ExternalBubbleTracker();
        health = new GhostLayerHealth();
        sentGhosts = new CopyOnWriteArrayList<>();

        ghostSync = new GhostBoundarySync<>(
            bubbleTracker,
            health,
            (neighborId, ghosts) -> sentGhosts.addAll(ghosts)
        );
    }

    @Test
    void testEndToEndGhostPropagation() {
        // Setup: 3 expected neighbors (from membership view)
        health.setExpectedNeighbors(3);

        var localBubbleId = UUID.randomUUID();
        var neighborA = UUID.randomUUID();
        var neighborB = UUID.randomUUID();
        var neighborC = UUID.randomUUID();

        var sourceBubbleA = UUID.randomUUID();
        var sourceBubbleB = UUID.randomUUID();

        // Bucket 100: Entities near boundaries create ghosts
        var entity1 = createGhostEntity(new TestEntityID("e1"), 0.1f);
        var entity2 = createGhostEntity(new TestEntityID("e2"), 0.2f);
        var entity3 = createGhostEntity(new TestEntityID("e3"), 0.3f);

        ghostSync.addGhost(entity1, sourceBubbleA, neighborA, 100L);
        ghostSync.addGhost(entity2, sourceBubbleB, neighborB, 100L);
        ghostSync.addGhost(entity3, sourceBubbleA, neighborC, 100L);

        // Verify ghosts are batched (not sent yet)
        assertEquals(3, ghostSync.getActiveGhostCount());
        assertTrue(sentGhosts.isEmpty(), "Ghosts should be batched, not sent yet");

        // Bucket boundary: Trigger batch send
        ghostSync.onBucketComplete(100L);

        // Verify ghosts were sent
        assertEquals(3, sentGhosts.size(), "All 3 ghosts should be sent");

        // Verify ExternalBubbleTracker recorded interactions
        assertEquals(2, bubbleTracker.getDiscoveredBubbles().size(),
                    "Should discover 2 source bubbles");
        assertTrue(bubbleTracker.getDiscoveredBubbles().contains(sourceBubbleA));
        assertTrue(bubbleTracker.getDiscoveredBubbles().contains(sourceBubbleB));

        assertEquals(2, bubbleTracker.getInteractionCount(sourceBubbleA),
                    "Bubble A sent 2 ghosts");
        assertEquals(1, bubbleTracker.getInteractionCount(sourceBubbleB),
                    "Bubble B sent 1 ghost");

        // Verify GhostLayerHealth updated NC metric
        assertEquals(2, health.getKnownNeighbors(),
                    "Health should track 2 discovered neighbors");
        assertEquals(0.67f, health.neighborConsistency(), 0.01f,
                    "NC should be 2/3 = 0.67");
    }

    @Test
    void testGhostPropagationWithTTLExpiration() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Bucket 100: Add ghost
        var entity = createGhostEntity(new TestEntityID("e1"), 0.5f);
        ghostSync.addGhost(entity, sourceBubbleId, neighborId, 100L);

        // Bucket 100: Send ghost
        ghostSync.onBucketComplete(100L);
        assertEquals(1, sentGhosts.size(), "Ghost should be sent");

        sentGhosts.clear();

        // Bucket 104: Ghost still active (within TTL window)
        ghostSync.onBucketComplete(104L);
        assertEquals(1, sentGhosts.size(), "Ghost should be resent (still active)");

        sentGhosts.clear();

        // Bucket 106: Ghost sent then expired (expiration happens after send)
        ghostSync.onBucketComplete(106L);
        assertEquals(1, sentGhosts.size(), "Ghost sent at bucket 106");

        sentGhosts.clear();

        // Bucket 107: Ghost now expired (not sent)
        ghostSync.onBucketComplete(107L);
        assertEquals(0, sentGhosts.size(), "Ghost should not be sent (expired)");
        assertEquals(0, ghostSync.getActiveGhostCount(), "No active ghosts");
    }

    @Test
    void testVONBoundaryNeighborDiscovery() {
        // Simulate VON boundary neighbors pattern
        health.setExpectedNeighbors(8);  // Fireflies membership view

        var localBubbleId = UUID.randomUUID();
        var neighborNorth = UUID.randomUUID();
        var neighborSouth = UUID.randomUUID();
        var neighborEast = UUID.randomUUID();
        var neighborWest = UUID.randomUUID();

        // Source bubbles sending ghosts
        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();
        var bubbleC = UUID.randomUUID();

        // Bucket 100: Multiple ghosts from multiple sources
        for (int i = 0; i < 5; i++) {
            ghostSync.addGhost(
                createGhostEntity(new TestEntityID("n-" + i), i * 0.1f),
                bubbleA, neighborNorth, 100L
            );
        }

        for (int i = 0; i < 3; i++) {
            ghostSync.addGhost(
                createGhostEntity(new TestEntityID("e-" + i), i * 0.1f),
                bubbleB, neighborEast, 100L
            );
        }

        for (int i = 0; i < 2; i++) {
            ghostSync.addGhost(
                createGhostEntity(new TestEntityID("w-" + i), i * 0.1f),
                bubbleC, neighborWest, 100L
            );
        }

        ghostSync.onBucketComplete(100L);

        // Verify discovery via ghost sources
        assertEquals(3, health.getKnownNeighbors(),
                    "Should discover 3 neighbors via ghosts");
        assertEquals(3, bubbleTracker.getDiscoveredBubbles().size());

        // Verify NC metric reflects partial discovery
        assertEquals(0.375f, health.neighborConsistency(), 0.01f,
                    "NC should be 3/8 = 0.375 (37.5% discovered)");
        assertTrue(health.isDegraded(0.9f),
                  "Should be degraded (NC < 0.9)");
    }

    @Test
    void testMergeCandidateIdentification() {
        health.setExpectedNeighbors(5);

        var neighborA = UUID.randomUUID();
        var neighborB = UUID.randomUUID();
        var neighborC = UUID.randomUUID();

        var bubbleHighAffinity = UUID.randomUUID();
        var bubbleMediumAffinity = UUID.randomUUID();
        var bubbleLowAffinity = UUID.randomUUID();

        // Simulate 10 bucket cycles with varying ghost frequencies
        // Note: Ghosts persist and are resent at each bucket until TTL expires,
        // so interaction counts accumulate across buckets
        for (int bucket = 100; bucket < 110; bucket++) {
            // High affinity: 2 ghosts per bucket
            for (int i = 0; i < 2; i++) {
                ghostSync.addGhost(
                    createGhostEntity(new TestEntityID("high-" + bucket + "-" + i), 0.1f),
                    bubbleHighAffinity, neighborA, bucket
                );
            }

            // Medium affinity: 1 ghost per bucket
            ghostSync.addGhost(
                createGhostEntity(new TestEntityID("med-" + bucket), 0.2f),
                bubbleMediumAffinity, neighborB, bucket
            );

            // Low affinity: 1 ghost every 5 buckets
            if (bucket % 5 == 0) {
                ghostSync.addGhost(
                    createGhostEntity(new TestEntityID("low-" + bucket), 0.3f),
                    bubbleLowAffinity, neighborC, bucket
                );
            }

            ghostSync.onBucketComplete(bucket);
        }

        // Verify relative interaction counts (exact values depend on TTL and persistence)
        int highCount = bubbleTracker.getInteractionCount(bubbleHighAffinity);
        int mediumCount = bubbleTracker.getInteractionCount(bubbleMediumAffinity);
        int lowCount = bubbleTracker.getInteractionCount(bubbleLowAffinity);

        assertTrue(highCount > mediumCount,
                  "High affinity should have more interactions than medium");
        assertTrue(mediumCount > lowCount,
                  "Medium affinity should have more interactions than low");

        // Identify merge candidates (threshold based on medium affinity)
        var mergeCandidates = bubbleTracker.getMergeCandidates(mediumCount - 1);

        assertEquals(2, mergeCandidates.size(),
                    "High and medium affinity bubbles are merge candidates");
        assertEquals(bubbleHighAffinity, mergeCandidates.get(0),
                    "Highest affinity should be first");
        assertEquals(bubbleMediumAffinity, mergeCandidates.get(1),
                    "Medium affinity should be second");

        // Verify health metrics
        assertEquals(3, health.getKnownNeighbors());
        assertEquals(0.6f, health.neighborConsistency(), 0.01f,
                    "NC = 3/5 = 0.6");
    }

    @Test
    void testGhostUpdateAndPositionTracking() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();
        var entityId = new TestEntityID("moving-entity");

        // Bucket 100: Entity at position (0.1, 0.5, 0.5)
        var ghost1 = createGhostEntity(entityId, 0.1f);
        ghostSync.addGhost(ghost1, sourceBubbleId, neighborId, 100L);
        ghostSync.onBucketComplete(100L);

        assertEquals(1, sentGhosts.size());
        assertEquals(0.1f, sentGhosts.get(0).position().x, 0.01f);

        sentGhosts.clear();

        // Bucket 101: Entity moved to position (0.3, 0.5, 0.5)
        var ghost2 = createGhostEntity(entityId, 0.3f);
        ghostSync.addGhost(ghost2, sourceBubbleId, neighborId, 101L);
        ghostSync.onBucketComplete(101L);

        // Should send updated ghost (replaces old one)
        assertEquals(1, sentGhosts.size(), "Should send 1 ghost (updated)");
        assertEquals(0.3f, sentGhosts.get(0).position().x, 0.01f,
                    "Position should be updated");
    }

    @Test
    void testMemoryLimitEnforcementInIntegration() {
        var neighborId = UUID.randomUUID();
        var sourceBubbleId = UUID.randomUUID();

        // Add 1100 ghosts (beyond 1000 limit)
        for (int i = 0; i < 1100; i++) {
            var entity = createGhostEntity(new TestEntityID("e" + i), i * 0.001f);
            ghostSync.addGhost(entity, sourceBubbleId, neighborId, 100L + i);
        }

        // Verify memory limit enforced
        assertTrue(ghostSync.getActiveGhostCount() <= 1000,
                  "Should enforce 1000 ghost limit");

        // Send batch
        ghostSync.onBucketComplete(1200L);

        // Verify only recent ghosts were sent
        assertEquals(1000, sentGhosts.size(),
                    "Should send only 1000 most recent ghosts");

        // Verify bubble tracker still recorded all interactions
        assertTrue(bubbleTracker.getInteractionCount(sourceBubbleId) >= 1000,
                   "All ghost interactions should be tracked despite memory limit");
    }

    @Test
    void testConcurrentGhostPropagation() throws InterruptedException {
        health.setExpectedNeighbors(10);

        var neighborId = UUID.randomUUID();
        var sourceBubbles = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            sourceBubbles.add(UUID.randomUUID());
        }

        int threadCount = 5;
        int ghostsPerThread = 20;

        var threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < ghostsPerThread; i++) {
                    var entity = createGhostEntity(
                        new TestEntityID("t" + threadId + "-e" + i),
                        threadId * 0.1f + i * 0.01f
                    );
                    ghostSync.addGhost(
                        entity,
                        sourceBubbles.get(threadId),
                        neighborId,
                        100L + i
                    );
                }
            });
            threads[t].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // Send all ghosts
        ghostSync.onBucketComplete(120L);

        // Verify all interactions recorded
        assertEquals(5, bubbleTracker.getDiscoveredBubbles().size(),
                    "All 5 source bubbles should be discovered");
        assertEquals(5, health.getKnownNeighbors(),
                    "All 5 neighbors should be tracked");

        assertEquals(100, sentGhosts.size(),
                    "All 100 ghosts should be sent (thread-safe)");
    }

    @Test
    void testHealthDegradationAlert() {
        // Simulate degraded discovery (only 7 out of 10 neighbors discovered)
        health.setExpectedNeighbors(10);

        var neighbors = new ArrayList<UUID>();
        var sourceBubbles = new ArrayList<UUID>();

        for (int i = 0; i < 7; i++) {
            neighbors.add(UUID.randomUUID());
            sourceBubbles.add(UUID.randomUUID());
        }

        // Add ghosts from 7 different source bubbles
        for (int i = 0; i < 7; i++) {
            ghostSync.addGhost(
                createGhostEntity(new TestEntityID("e" + i), i * 0.1f),
                sourceBubbles.get(i),
                neighbors.get(i),
                100L
            );
        }

        ghostSync.onBucketComplete(100L);

        // Verify degradation detected
        assertEquals(7, health.getKnownNeighbors());
        assertEquals(0.7f, health.neighborConsistency(), 0.01f);
        assertTrue(health.isDegraded(0.9f),
                  "Should detect degradation (NC = 0.7 < 0.9)");

        // Verify no partition risk
        assertFalse(health.isPartitionRisk(0.5f),
                   "No partition risk (NC = 0.7 > 0.5)");
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
