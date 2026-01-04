package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for AdaptiveSplitPolicy - cluster-based tetrahedral subdivision.
 * <p>
 * Tests cover:
 * - Split decision based on frame utilization
 * - Cluster detection in RDGCS space (k-means)
 * - Split execution with child bubble creation
 * - Entity redistribution and preservation
 * - Minimum cluster size enforcement
 * <p>
 * These tests MUST pass before implementing AdaptiveSplitPolicy (TDD red phase).
 *
 * @author hal.hildebrand
 */
public class AdaptiveSplitPolicyTest {

    private static final float EPSILON = 0.001f;
    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;

    private AdaptiveSplitPolicy policy;
    private EnhancedBubble bubble;

    @BeforeEach
    public void setup() {
        policy = new AdaptiveSplitPolicy(1.2f, 100);
        bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
    }

    /**
     * Test 1: No split when under budget (80% utilization)
     * <p>
     * Validates: shouldSplit returns false at 80% utilization
     */
    @Test
    public void testShouldSplitUnderBudget() {
        // Record frame time at 80% of budget (8ms = 8,000,000 ns)
        bubble.recordFrameTime(8_000_000L);

        assertFalse(policy.shouldSplit(bubble), "Should not split at 80% utilization");
    }

    /**
     * Test 2: Split triggered at 125% utilization
     * <p>
     * Validates: shouldSplit returns true when over budget
     */
    @Test
    public void testShouldSplitOverBudget() {
        // Record frame time at 125% of budget (12.5ms = 12,500,000 ns)
        bubble.recordFrameTime(12_500_000L);

        assertTrue(policy.shouldSplit(bubble), "Should split at 125% utilization");
    }

    /**
     * Test 3: Uniform distribution uses geometric split
     * <p>
     * Validates: Uniform entity distribution results in geometric (octant) subdivision
     */
    @Test
    public void testDetectClustersUniform() {
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        // Add uniformly distributed entities
        for (int i = 0; i < 200; i++) {
            float x = (float) (Math.random() * 100);
            float y = (float) (Math.random() * 100);
            float z = (float) (Math.random() * 100);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }

        var clusters = policy.detectClusters(bubble, 100, 50.0f);

        assertNotNull(clusters, "Clusters should not be null");
        // Uniform distribution should fall back to geometric split (8 octants)
        // or return small cluster count indicating uniform distribution
        assertTrue(clusters.size() <= 2,
                  "Uniform distribution should result in few or no clear clusters");
    }

    /**
     * Test 4: Two clear clusters detected
     * <p>
     * Validates: Distinct spatial clusters are detected correctly
     */
    @Test
    public void testDetectClustersTwoClusters() {
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        // Create two distinct clusters
        // Cluster 1: around (10, 10, 10)
        for (int i = 0; i < 100; i++) {
            float x = 10 + (float) (Math.random() * 5);
            float y = 10 + (float) (Math.random() * 5);
            float z = 10 + (float) (Math.random() * 5);
            bubble.addEntity("cluster1-" + i, new Point3f(x, y, z), content);
        }

        // Cluster 2: around (50, 50, 50)
        for (int i = 0; i < 100; i++) {
            float x = 50 + (float) (Math.random() * 5);
            float y = 50 + (float) (Math.random() * 5);
            float z = 50 + (float) (Math.random() * 5);
            bubble.addEntity("cluster2-" + i, new Point3f(x, y, z), content);
        }

        var clusters = policy.detectClusters(bubble, 100, 50.0f);

        assertNotNull(clusters, "Clusters should not be null");
        assertEquals(2, clusters.size(), "Should detect exactly 2 clusters");

        // Verify each cluster has minimum size
        for (var cluster : clusters) {
            assertTrue(cluster.size() >= 100,
                      "Each cluster should meet minimum size threshold");
        }
    }

    /**
     * Test 5: Scattered entities use fallback strategy
     * <p>
     * Validates: Scattered distribution falls back to geometric split
     */
    @Test
    public void testDetectClustersScattered() {
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        // Add scattered entities (no clear clusters)
        for (int i = 0; i < 150; i++) {
            float x = (float) (Math.random() * 200);
            float y = (float) (Math.random() * 200);
            float z = (float) (Math.random() * 200);
            bubble.addEntity("scattered-" + i, new Point3f(x, y, z), content);
        }

        var clusters = policy.detectClusters(bubble, 100, 50.0f);

        assertNotNull(clusters, "Clusters should not be null");
        // Scattered distribution should either return 1 cluster (all entities)
        // or indicate geometric split fallback
        assertTrue(clusters.size() <= 2,
                  "Scattered distribution should not produce many clusters");
    }

    /**
     * Test 6: Split creates 2 child bubbles from 2 clusters
     * <p>
     * Validates: performSplit creates correct number of child bubbles
     */
    @Test
    public void testPerformSplitTwoClusters() {
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        // Create two distinct clusters
        for (int i = 0; i < 100; i++) {
            bubble.addEntity("cluster1-" + i, new Point3f(10 + i % 5, 10 + i % 5, 10 + i % 5), content);
        }
        for (int i = 0; i < 100; i++) {
            bubble.addEntity("cluster2-" + i, new Point3f(50 + i % 5, 50 + i % 5, 50 + i % 5), content);
        }

        // Trigger split by exceeding frame budget
        bubble.recordFrameTime(12_500_000L); // 12.5ms = 125% of budget

        var splitResult = policy.analyzeSplit(bubble);

        assertTrue(splitResult.feasible(), "Split should be feasible");
        assertEquals(2, splitResult.clusters().size(), "Should have 2 clusters");

        var childBubbles = policy.performSplit(bubble, splitResult);

        assertNotNull(childBubbles, "Child bubbles should not be null");
        assertEquals(2, childBubbles.size(), "Should create 2 child bubbles");
    }

    /**
     * Test 7: Geometric split creates 8 children (octant subdivision)
     * <p>
     * Validates: Fallback geometric split creates octant bubbles
     */
    @Test
    public void testPerformSplitGeometric() {
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        // Add uniformly distributed entities (triggers geometric split)
        for (int i = 0; i < 800; i++) {
            float x = (float) (Math.random() * 100);
            float y = (float) (Math.random() * 100);
            float z = (float) (Math.random() * 100);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }

        // Trigger split by exceeding frame budget
        bubble.recordFrameTime(12_500_000L); // 12.5ms = 125% of budget

        var splitResult = policy.analyzeSplit(bubble);

        assertTrue(splitResult.feasible(), "Geometric split should be feasible");

        var childBubbles = policy.performSplit(bubble, splitResult);

        assertNotNull(childBubbles, "Child bubbles should not be null");
        // Geometric split may create up to 8 children, but only those with entities
        assertTrue(childBubbles.size() >= 2 && childBubbles.size() <= 8,
                  "Geometric split should create 2-8 child bubbles");
    }

    /**
     * Test 8: All entities accounted for after redistribution
     * <p>
     * Validates: redistributeEntities preserves all entities
     */
    @Test
    public void testRedistributeEntities() {
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        // Add entities
        for (int i = 0; i < 200; i++) {
            bubble.addEntity("entity-" + i, new Point3f(i % 100, i % 100, i % 100), content);
        }

        int originalCount = bubble.entityCount();

        // Trigger split by exceeding frame budget
        bubble.recordFrameTime(12_500_000L); // 12.5ms = 125% of budget

        var splitResult = policy.analyzeSplit(bubble);
        var childBubbles = policy.performSplit(bubble, splitResult);

        // Redistribute entities from source to children
        policy.redistributeEntities(bubble, childBubbles);

        // Count total entities in children
        int totalChildEntities = childBubbles.stream()
                                            .mapToInt(EnhancedBubble::entityCount)
                                            .sum();

        assertEquals(originalCount, totalChildEntities,
                    "All entities should be redistributed to children");
    }

    /**
     * Test 9: Clusters below minimum threshold are merged
     * <p>
     * Validates: Small clusters are combined to meet minimum size
     */
    @Test
    public void testMinimumClusterSize() {
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        // Create small scattered groups (each < 100 entities)
        for (int i = 0; i < 50; i++) {
            bubble.addEntity("group1-" + i, new Point3f(10 + i % 5, 10 + i % 5, 10 + i % 5), content);
        }
        for (int i = 0; i < 50; i++) {
            bubble.addEntity("group2-" + i, new Point3f(30 + i % 5, 30 + i % 5, 30 + i % 5), content);
        }
        for (int i = 0; i < 50; i++) {
            bubble.addEntity("group3-" + i, new Point3f(50 + i % 5, 50 + i % 5, 50 + i % 5), content);
        }

        var clusters = policy.detectClusters(bubble, 100, 50.0f);

        assertNotNull(clusters, "Clusters should not be null");

        // All clusters should meet minimum size requirement (100)
        for (var cluster : clusters) {
            assertTrue(cluster.size() >= 100,
                      "All clusters should meet minimum size after merging small clusters");
        }
    }

    /**
     * Test 10: Split preserves all entities (no loss)
     * <p>
     * Validates: No entities are lost during split operation
     */
    @Test
    public void testSplitPreservesEntities() {
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        // Add entities
        var entityIds = new java.util.HashSet<String>();
        for (int i = 0; i < 300; i++) {
            String id = "entity-" + i;
            entityIds.add(id);
            bubble.addEntity(id, new Point3f(i % 100, i % 100, i % 100), content);
        }

        int originalCount = bubble.entityCount();

        // Trigger split by exceeding frame budget
        bubble.recordFrameTime(12_500_000L); // 12.5ms = 125% of budget

        var splitResult = policy.analyzeSplit(bubble);
        var childBubbles = policy.performSplit(bubble, splitResult);
        policy.redistributeEntities(bubble, childBubbles);

        // Collect all entity IDs from children
        var allChildEntityIds = new java.util.HashSet<String>();
        for (var child : childBubbles) {
            allChildEntityIds.addAll(child.getEntities());
        }

        assertEquals(originalCount, allChildEntityIds.size(),
                    "All entities should be preserved");
        assertTrue(allChildEntityIds.containsAll(entityIds),
                  "All original entity IDs should be present in children");
    }
}
