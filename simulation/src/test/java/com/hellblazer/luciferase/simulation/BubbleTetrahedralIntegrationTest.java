package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 1: Bubble with Tetrahedral Bounds
 * <p>
 * Validates complete bubble lifecycle including:
 * - Entity tracking with tetrahedral bounds
 * - Split/merge operations
 * - Frame budget enforcement
 * - VON neighbor tracking
 * <p>
 * These tests validate the integration of BubbleBounds, EnhancedBubble,
 * AdaptiveSplitPolicy, and BubbleLifecycle components.
 *
 * @author hal.hildebrand
 */
public class BubbleTetrahedralIntegrationTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;
    private static final float EPSILON = 0.001f;

    /**
     * Test 1: Full bubble lifecycle with entities
     * <p>
     * Validates: Create, add entities, move entities, remove entities,
     * bounds expand/contract correctly throughout lifecycle
     */
    @Test
    public void testBubbleLifecycleWithEntities() {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);

        // Add 500 entities
        var content = new Object();
        for (int i = 0; i < 500; i++) {
            float x = 1.0f + (i % 20);
            float y = 1.0f + (i / 20);
            float z = 1.0f + (i % 10);
            var pos = new Point3f(x, y, z);
            bubble.addEntity("entity-" + i, pos, content);
        }

        assertEquals(500, bubble.entityCount(), "Should have 500 entities");
        assertNotNull(bubble.bounds(), "Bounds should exist");

        // Verify all entities are contained
        var entities = bubble.getAllEntityRecords();
        for (var entity : entities) {
            assertTrue(bubble.bounds().contains(entity.position()),
                      "Bounds should contain entity: " + entity.id());
        }

        // Move 100 entities to new positions
        for (int i = 0; i < 100; i++) {
            var newPos = new Point3f(5.0f + i, 5.0f + i, 5.0f);
            bubble.updateEntityPosition("entity-" + i, newPos);
        }

        // Verify bounds still contain all entities
        entities = bubble.getAllEntityRecords();
        for (var entity : entities) {
            assertTrue(bubble.bounds().contains(entity.position()),
                      "Bounds should contain moved entity: " + entity.id());
        }

        // Remove 200 entities
        for (int i = 0; i < 200; i++) {
            bubble.removeEntity("entity-" + i);
        }

        assertEquals(300, bubble.entityCount(), "Should have 300 entities after removal");

        // Verify remaining entities still contained
        entities = bubble.getAllEntityRecords();
        for (var entity : entities) {
            assertTrue(bubble.bounds().contains(entity.position()),
                      "Bounds should contain remaining entity: " + entity.id());
        }
    }

    /**
     * Test 2: Split triggered on frame overload
     * <p>
     * Validates: needsSplit() triggers at 120% frame time (12ms)
     */
    @Test
    public void testSplitTriggeredOnOverload() {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);

        // Add 2000 entities
        var content = new Object();
        for (int i = 0; i < 2000; i++) {
            float x = 1.0f + (i % 40);
            float y = 1.0f + (i / 40);
            float z = 1.0f + (i % 20);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }

        assertEquals(2000, bubble.entityCount(), "Should have 2000 entities");

        // Simulate frame time exceeding 12ms (120% of 10ms budget)
        long frameTimeNs = 13_000_000L; // 13ms in nanoseconds
        bubble.recordFrameTime(frameTimeNs);

        assertTrue(bubble.needsSplit(), "Should need split at 130% utilization");

        // Use AdaptiveSplitPolicy to detect clusters
        var splitPolicy = new AdaptiveSplitPolicy(1.2f, 100); // 120% threshold, 100 min entities
        var clusters = splitPolicy.detectClusters(bubble, 100, 5.0f); // min 100 entities per cluster, max 5.0 distance

        // Should detect at least 2 clusters for split
        assertTrue(clusters.size() >= 2,
                  "Should detect at least 2 clusters in overloaded bubble");

        // Verify all entities accounted for in clusters
        int totalEntitiesInClusters = 0;
        for (var cluster : clusters) {
            totalEntitiesInClusters += cluster.entityIds().size();
        }

        assertEquals(2000, totalEntitiesInClusters,
                    "All entities should be assigned to clusters");
    }

    /**
     * Test 3: Join triggered on high affinity
     * <p>
     * Validates: Two bubbles merge when cross-bubble affinity > 60%
     */
    @Test
    public void testJoinTriggeredOnHighAffinity() {
        var bubble1 = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
        var bubble2 = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);

        // Add 200 entities to each bubble in adjacent regions
        var content = new Object();
        for (int i = 0; i < 200; i++) {
            bubble1.addEntity("b1-entity-" + i, new Point3f(1.0f + i, 1.0f, 1.0f), content);
            bubble2.addEntity("b2-entity-" + i, new Point3f(2.0f + i, 2.0f, 2.0f), content);
        }

        assertEquals(200, bubble1.entityCount(), "Bubble1 should have 200 entities");
        assertEquals(200, bubble2.entityCount(), "Bubble2 should have 200 entities");

        // Simulate high cross-bubble interactions (70% affinity)
        var capturedEvents = new ArrayList<BubbleEvent>();
        var lifecycle = new BubbleLifecycle(capturedEvents::add);

        float affinity = 0.7f; // 70% cross-bubble interactions

        assertTrue(lifecycle.shouldJoin(bubble1, bubble2, affinity),
                  "Should join at 70% affinity (above 60% threshold)");

        // Perform join
        var merged = lifecycle.performJoin(bubble1, bubble2);

        assertNotNull(merged, "Merged bubble should not be null");
        assertEquals(400, merged.entityCount(),
                    "Merged bubble should contain all 400 entities");

        // Verify merge event was emitted
        assertEquals(1, capturedEvents.size(), "Should emit exactly one merge event");
        assertTrue(capturedEvents.get(0) instanceof BubbleEvent.Merge,
                  "Event should be a Merge event");

        var mergeEvent = (BubbleEvent.Merge) capturedEvents.get(0);
        assertEquals(400, mergeEvent.totalSize(), "Merge event should show 400 total entities");
    }

    /**
     * Test 4: 100% tetrahedral containment
     * <p>
     * Validates: ALL entities pass bounds.contains() test with tetrahedral bounds
     */
    @Test
    public void testTetrahedralContainment100Percent() {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);

        // Add entities at random positions within a reasonable range
        var random = new Random(42); // Fixed seed for reproducibility
        var content = new Object();
        int entityCount = 1000;

        for (int i = 0; i < entityCount; i++) {
            float x = 1.0f + random.nextFloat() * 5.0f;
            float y = 1.0f + random.nextFloat() * 5.0f;
            float z = 1.0f + random.nextFloat() * 5.0f;
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }

        assertEquals(entityCount, bubble.entityCount(),
                    "Should have " + entityCount + " entities");

        // Verify 100% containment
        var entities = bubble.getAllEntityRecords();
        int containedCount = 0;

        for (var entity : entities) {
            if (bubble.bounds().contains(entity.position())) {
                containedCount++;
            }
        }

        assertEquals(entityCount, containedCount,
                    "ALL entities must be contained in tetrahedral bounds (100% containment)");

        // Calculate containment percentage
        float containmentPercent = (float) containedCount / entityCount * 100.0f;
        assertEquals(100.0f, containmentPercent, EPSILON,
                    "Containment percentage must be exactly 100%");
    }

    /**
     * Test 5: Frame budget enforcement
     * <p>
     * Validates: Frame time monitoring, 10ms target, split at 12ms
     */
    @Test
    public void testFrameBudgetEnforcement() {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);

        // Add reasonable number of entities (should stay under budget)
        var content = new Object();
        for (int i = 0; i < 500; i++) {
            bubble.addEntity("entity-" + i, new Point3f(1.0f + i, 1.0f, 1.0f), content);
        }

        // Simulate frame time at 80% of budget (8ms)
        long frameTime8ms = 8_000_000L;
        bubble.recordFrameTime(frameTime8ms);

        assertFalse(bubble.needsSplit(), "Should not need split at 80% utilization");
        assertEquals(0.8f, bubble.frameUtilization(), EPSILON,
                    "Frame utilization should be 0.8 (80%)");

        // Simulate frame time at 100% of budget (10ms)
        long frameTime10ms = 10_000_000L;
        bubble.recordFrameTime(frameTime10ms);

        assertFalse(bubble.needsSplit(), "Should not need split at 100% utilization");
        assertEquals(1.0f, bubble.frameUtilization(), EPSILON,
                    "Frame utilization should be 1.0 (100%)");

        // Simulate frame time at 121% of budget (12.1ms) - trigger split
        long frameTime121ms = 12_100_000L;
        bubble.recordFrameTime(frameTime121ms);

        assertTrue(bubble.needsSplit(), "Should need split at 121% utilization");
        assertEquals(1.21f, bubble.frameUtilization(), EPSILON,
                    "Frame utilization should be 1.21 (121%)");

        // Simulate frame time at 150% of budget (15ms) - definitely needs split
        long frameTime15ms = 15_000_000L;
        bubble.recordFrameTime(frameTime15ms);

        assertTrue(bubble.needsSplit(), "Should need split at 150% utilization");
        assertEquals(1.5f, bubble.frameUtilization(), EPSILON,
                    "Frame utilization should be 1.5 (150%)");
    }

    /**
     * Test 6: VON neighbor tracking
     * <p>
     * Validates: Add/remove VON neighbors, verify updates
     */
    @Test
    public void testVonNeighborTracking() {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);

        // Initially no neighbors
        assertEquals(0, bubble.getVonNeighbors().size(), "Should start with no neighbors");

        // Add 5 neighbors
        var neighborIds = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            var neighborId = UUID.randomUUID();
            neighborIds.add(neighborId);
            bubble.addVonNeighbor(neighborId);
        }

        assertEquals(5, bubble.getVonNeighbors().size(), "Should have 5 neighbors");

        // Verify all neighbors are tracked
        for (var neighborId : neighborIds) {
            assertTrue(bubble.getVonNeighbors().contains(neighborId),
                      "Should contain neighbor: " + neighborId);
        }

        // Remove 2 neighbors
        bubble.removeVonNeighbor(neighborIds.get(0));
        bubble.removeVonNeighbor(neighborIds.get(1));

        assertEquals(3, bubble.getVonNeighbors().size(), "Should have 3 neighbors after removal");

        // Verify removed neighbors are gone
        assertFalse(bubble.getVonNeighbors().contains(neighborIds.get(0)),
                   "Should not contain removed neighbor 0");
        assertFalse(bubble.getVonNeighbors().contains(neighborIds.get(1)),
                   "Should not contain removed neighbor 1");

        // Verify remaining neighbors still present
        assertTrue(bubble.getVonNeighbors().contains(neighborIds.get(2)),
                  "Should still contain neighbor 2");
        assertTrue(bubble.getVonNeighbors().contains(neighborIds.get(3)),
                  "Should still contain neighbor 3");
        assertTrue(bubble.getVonNeighbors().contains(neighborIds.get(4)),
                  "Should still contain neighbor 4");

        // Adding duplicate neighbor should be idempotent
        bubble.addVonNeighbor(neighborIds.get(2));
        assertEquals(3, bubble.getVonNeighbors().size(),
                    "Adding duplicate neighbor should not change size");

        // Remove all neighbors
        bubble.removeVonNeighbor(neighborIds.get(2));
        bubble.removeVonNeighbor(neighborIds.get(3));
        bubble.removeVonNeighbor(neighborIds.get(4));

        assertEquals(0, bubble.getVonNeighbors().size(),
                    "Should have no neighbors after removing all");
    }
}
