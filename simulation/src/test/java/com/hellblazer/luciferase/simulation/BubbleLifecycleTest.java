package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for BubbleLifecycle - bubble merge based on interaction affinity.
 * <p>
 * Tests cover:
 * - Join decision based on interaction affinity
 * - Join execution with entity preservation
 * - Bounds merging for tetrahedral volumes
 * - VON neighbor updates
 * - Event emission for lifecycle changes
 * <p>
 * These tests MUST pass before implementing BubbleLifecycle (TDD red phase).
 *
 * @author hal.hildebrand
 */
public class BubbleLifecycleTest {

    private static final float EPSILON = 0.001f;
    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;
    private static final float MERGE_THRESHOLD = 0.6f; // 60% cross-bubble interactions

    private BubbleLifecycle lifecycle;
    private List<BubbleEvent> capturedEvents;
    private Consumer<BubbleEvent> eventCaptor;

    @BeforeEach
    public void setup() {
        capturedEvents = new ArrayList<>();
        eventCaptor = event -> capturedEvents.add(event);
        lifecycle = new BubbleLifecycle(eventCaptor);
    }

    /**
     * Test 1: Join at 70% affinity
     * <p>
     * Validates: shouldJoin returns true when affinity > threshold
     */
    @Test
    public void testShouldJoinHighAffinity() {
        var bubble1 = createBubbleWithEntities(100, 10.0f);
        var bubble2 = createBubbleWithEntities(100, 50.0f);

        // Simulate 70% cross-bubble interactions
        float affinity = 0.7f;

        boolean shouldJoin = lifecycle.shouldJoin(bubble1, bubble2, affinity);

        assertTrue(shouldJoin, "Should join at 70% affinity (above 60% threshold)");
    }

    /**
     * Test 2: No join at 30% affinity
     * <p>
     * Validates: shouldJoin returns false when affinity < threshold
     */
    @Test
    public void testShouldNotJoinLowAffinity() {
        var bubble1 = createBubbleWithEntities(100, 10.0f);
        var bubble2 = createBubbleWithEntities(100, 50.0f);

        // Simulate 30% cross-bubble interactions
        float affinity = 0.3f;

        boolean shouldJoin = lifecycle.shouldJoin(bubble1, bubble2, affinity);

        assertFalse(shouldJoin, "Should not join at 30% affinity (below 60% threshold)");
    }

    /**
     * Test 3: Returns 0.5 for zero interactions (boundary case)
     * <p>
     * Validates: calculateAffinity handles zero interactions gracefully
     */
    @Test
    public void testCalculateAffinityZeroInteractions() {
        var bubble1 = createBubbleWithEntities(100, 10.0f);
        var bubble2 = createBubbleWithEntities(100, 50.0f);

        // No recorded interactions
        float affinity = lifecycle.calculateAffinity(bubble1, bubble2, 0, 0);

        assertEquals(0.5f, affinity, EPSILON,
                    "Zero interactions should return boundary value 0.5");
    }

    /**
     * Test 4: All entities in merged bubble
     * <p>
     * Validates: performJoin preserves all entities from both bubbles
     */
    @Test
    public void testPerformJoinPreservesEntities() {
        var bubble1 = createBubbleWithEntities(100, 10.0f);
        var bubble2 = createBubbleWithEntities(150, 50.0f);

        int originalCount = bubble1.entityCount() + bubble2.entityCount();

        var merged = lifecycle.performJoin(bubble1, bubble2);

        assertNotNull(merged, "Merged bubble should not be null");
        assertEquals(originalCount, merged.entityCount(),
                    "Merged bubble should contain all entities from both sources");
    }

    /**
     * Test 5: Bounds contain all entities
     * <p>
     * Validates: calculateMergedBounds creates bounds encompassing all entities
     */
    @Test
    public void testMergedBoundsEncompassesAll() {
        var bubble1 = createBubbleWithEntities(100, 10.0f);
        var bubble2 = createBubbleWithEntities(100, 50.0f);

        var mergedBounds = lifecycle.calculateMergedBounds(bubble1.bounds(), bubble2.bounds());

        assertNotNull(mergedBounds, "Merged bounds should not be null");

        // Verify merged bounds contain a sample entity from each original bubble
        var entities1 = bubble1.getAllEntityRecords();
        var entities2 = bubble2.getAllEntityRecords();

        if (!entities1.isEmpty()) {
            assertTrue(mergedBounds.contains(entities1.get(0).position()),
                      "Merged bounds should contain entities from bubble1");
        }
        if (!entities2.isEmpty()) {
            assertTrue(mergedBounds.contains(entities2.get(0).position()),
                      "Merged bounds should contain entities from bubble2");
        }
    }

    /**
     * Test 6: Merged bubble has combined neighbors
     * <p>
     * Validates: Merged bubble has union of VON neighbors
     */
    @Test
    public void testVonNeighborsUnion() {
        var bubble1 = createBubbleWithEntities(100, 10.0f);
        var bubble2 = createBubbleWithEntities(100, 50.0f);

        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        var neighbor3 = UUID.randomUUID();

        bubble1.addVonNeighbor(neighbor1);
        bubble1.addVonNeighbor(neighbor2);
        bubble2.addVonNeighbor(neighbor2);
        bubble2.addVonNeighbor(neighbor3);

        var merged = lifecycle.performJoin(bubble1, bubble2);

        var mergedNeighbors = merged.getVonNeighbors();

        assertEquals(3, mergedNeighbors.size(), "Merged bubble should have 3 unique neighbors");
        assertTrue(mergedNeighbors.contains(neighbor1), "Should contain neighbor1 from bubble1");
        assertTrue(mergedNeighbors.contains(neighbor2), "Should contain shared neighbor2");
        assertTrue(mergedNeighbors.contains(neighbor3), "Should contain neighbor3 from bubble2");
    }

    /**
     * Test 7: Repeated transfer is no-op
     * <p>
     * Validates: Entity transfer is idempotent
     */
    @Test
    public void testEntityTransferIdempotent() {
        var source = createBubbleWithEntities(100, 10.0f);
        var target = createBubbleWithEntities(50, 50.0f);

        int sourceCount = source.entityCount();
        int targetCount = target.entityCount();
        int expectedFinalCount = sourceCount + targetCount;

        // First transfer
        lifecycle.transferEntities(source, target);

        assertEquals(expectedFinalCount, target.entityCount(),
                    "Target should have all entities after first transfer");
        assertEquals(0, source.entityCount(),
                    "Source should be empty after transfer");

        // Second transfer (should be no-op)
        lifecycle.transferEntities(source, target);

        assertEquals(expectedFinalCount, target.entityCount(),
                    "Target count should remain unchanged after second transfer");
    }

    /**
     * Test 8: Event emitted with correct data
     * <p>
     * Validates: performJoin emits BubbleEvent.Merge with correct bubbles
     */
    @Test
    public void testJoinEmitsMergeEvent() {
        var bubble1 = createBubbleWithEntities(100, 10.0f);
        var bubble2 = createBubbleWithEntities(100, 50.0f);

        capturedEvents.clear();

        var merged = lifecycle.performJoin(bubble1, bubble2);

        assertEquals(1, capturedEvents.size(), "Should emit exactly one event");

        var event = capturedEvents.get(0);
        assertTrue(event instanceof BubbleEvent.Merge, "Event should be a Merge event");

        var mergeEvent = (BubbleEvent.Merge) event;
        assertNotNull(mergeEvent.result(), "Event should contain merged bubble ID");
        assertTrue(mergeEvent.bubble1() != null && mergeEvent.bubble2() != null,
                  "Event should contain both source bubble IDs");
    }

    /**
     * Test 9: Smaller bubble properly shut down
     * <p>
     * Validates: After join, dissolved bubble is emptied and marked inactive
     */
    @Test
    public void testDissolvedBubbleShutdown() {
        var smaller = createBubbleWithEntities(100, 10.0f);
        var larger = createBubbleWithEntities(200, 50.0f);

        var merged = lifecycle.performJoin(smaller, larger);

        // Smaller bubble should be emptied
        assertEquals(0, smaller.entityCount(),
                    "Dissolved bubble should have no entities");

        // Verify larger bubble absorbed all entities
        assertEquals(300, merged.entityCount(),
                    "Merged bubble should have all 300 entities");
    }

    /**
     * Test 10: Notify affected neighbors
     * <p>
     * Validates: updateVonNeighbors updates neighbor references
     */
    @Test
    public void testJoinUpdatesVonNeighborsOfNeighbors() {
        var bubble1 = createBubbleWithEntities(100, 10.0f);
        var bubble2 = createBubbleWithEntities(100, 50.0f);

        // Create neighbor bubbles that point to bubble1 and bubble2
        var neighbor1 = createBubbleWithEntities(50, 0.0f);
        var neighbor2 = createBubbleWithEntities(50, 100.0f);

        neighbor1.addVonNeighbor(bubble1.id());
        neighbor2.addVonNeighbor(bubble2.id());

        bubble1.addVonNeighbor(neighbor1.id());
        bubble2.addVonNeighbor(neighbor2.id());

        var merged = lifecycle.performJoin(bubble1, bubble2);

        // Update neighbor references
        lifecycle.updateVonNeighbors(merged, bubble1, bubble2, List.of(neighbor1, neighbor2));

        // Verify neighbors now point to merged bubble
        assertTrue(neighbor1.getVonNeighbors().contains(merged.id()),
                  "Neighbor1 should reference merged bubble");
        assertFalse(neighbor1.getVonNeighbors().contains(bubble1.id()),
                   "Neighbor1 should not reference dissolved bubble1");

        assertTrue(neighbor2.getVonNeighbors().contains(merged.id()),
                  "Neighbor2 should reference merged bubble");
        assertFalse(neighbor2.getVonNeighbors().contains(bubble2.id()),
                   "Neighbor2 should not reference dissolved bubble2");
    }

    /**
     * Helper: Create a bubble with specified number of entities at a base position.
     */
    private EnhancedBubble createBubbleWithEntities(int count, float basePos) {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
        var content = new EnhancedBubble.EntityRecord("test", new Point3f(0, 0, 0), new Object(), 0);

        for (int i = 0; i < count; i++) {
            float x = basePos + (i % 10);
            float y = basePos + (i % 10);
            float z = basePos + (i % 10);
            bubble.addEntity("entity-" + basePos + "-" + i, new Point3f(x, y, z), content);
        }

        return bubble;
    }
}
