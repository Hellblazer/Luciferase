package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for Enhanced Bubble with tetrahedral bounds, spatial index, and VON integration.
 * <p>
 * Tests the enhanced Bubble class that adds:
 * - Internal Tetree spatial index for entity storage
 * - BubbleBounds for tetrahedral bounding volumes
 * - VON neighbor tracking
 * - Frame time monitoring and split detection
 * <p>
 * These tests MUST pass before implementing the enhanced Bubble class (TDD red phase).
 *
 * @author hal.hildebrand
 */
public class EnhancedBubbleTest {

    private static final float EPSILON = 0.001f;
    private static final long TARGET_FRAME_MS = 10;
    private static final byte SPATIAL_LEVEL = 10;

    private UUID bubbleId;
    private EnhancedBubble bubble;

    @BeforeEach
    public void setup() {
        bubbleId = UUID.randomUUID();
        bubble = new EnhancedBubble(bubbleId, SPATIAL_LEVEL, TARGET_FRAME_MS);
    }

    /**
     * Test 1: Basic construction
     * <p>
     * Validates: Bubble creates successfully with spatial index and bounds
     */
    @Test
    public void testCreateBubble() {
        assertNotNull(bubble, "Bubble should not be null");
        assertEquals(bubbleId, bubble.id(), "Bubble ID should match");
        assertEquals(0, bubble.entityCount(), "New bubble should have no entities");
        assertNotNull(bubble.bounds(), "Bubble should have bounds");
        assertNotNull(bubble.getVonNeighbors(), "VON neighbors set should exist");
        assertTrue(bubble.getVonNeighbors().isEmpty(), "New bubble should have no VON neighbors");
    }

    /**
     * Test 2: Entity inserted into spatial index
     * <p>
     * Validates: addEntity inserts into Tetree and updates bounds
     */
    @Test
    public void testAddEntity() {
        var entityId = "entity-1";
        var position = new Point3f(10.0f, 20.0f, 30.0f);
        var content = new EntityContent();

        bubble.addEntity(entityId, position, content);

        assertEquals(1, bubble.entityCount(), "Entity count should be 1");
        assertTrue(bubble.getEntities().contains(entityId), "Entity should be in set");
        assertTrue(bubble.bounds().contains(position), "Bounds should contain entity position");
    }

    /**
     * Test 3: Entity removed, bounds recalculated
     * <p>
     * Validates: removeEntity removes from spatial index and updates bounds
     */
    @Test
    public void testRemoveEntity() {
        var entityId = "entity-1";
        var position = new Point3f(10.0f, 20.0f, 30.0f);
        var content = new EntityContent();

        bubble.addEntity(entityId, position, content);
        assertEquals(1, bubble.entityCount(), "Entity count should be 1 after add");

        bubble.removeEntity(entityId);

        assertEquals(0, bubble.entityCount(), "Entity count should be 0 after remove");
        assertFalse(bubble.getEntities().contains(entityId), "Entity should not be in set");
    }

    /**
     * Test 4: Position update, bounds check
     * <p>
     * Validates: updateEntityPosition updates spatial index correctly
     */
    @Test
    public void testUpdateEntityPosition() {
        var entityId = "entity-1";
        var oldPosition = new Point3f(10.0f, 20.0f, 30.0f);
        var newPosition = new Point3f(50.0f, 60.0f, 70.0f);
        var content = new EntityContent();

        bubble.addEntity(entityId, oldPosition, content);

        bubble.updateEntityPosition(entityId, newPosition);

        assertEquals(1, bubble.entityCount(), "Entity count should remain 1");
        assertTrue(bubble.bounds().contains(newPosition), "Bounds should contain new position");
    }

    /**
     * Test 5: Spatial range query correctness
     * <p>
     * Validates: queryRange returns entities within radius
     */
    @Test
    public void testQueryRange() {
        var content = new EntityContent();

        // Add entities at known positions
        bubble.addEntity("near-1", new Point3f(10.0f, 10.0f, 10.0f), content);
        bubble.addEntity("near-2", new Point3f(12.0f, 12.0f, 12.0f), content);
        bubble.addEntity("far-1", new Point3f(100.0f, 100.0f, 100.0f), content);

        var center = new Point3f(11.0f, 11.0f, 11.0f);
        var radius = 5.0f;

        var results = bubble.queryRange(center, radius);

        assertNotNull(results, "Query results should not be null");
        assertEquals(2, results.size(), "Should find 2 nearby entities");

        var ids = results.stream().map(EntityRecord::id).toList();
        assertTrue(ids.contains("near-1"), "Should find near-1");
        assertTrue(ids.contains("near-2"), "Should find near-2");
        assertFalse(ids.contains("far-1"), "Should not find far-1");
    }

    /**
     * Test 6: kNN query correctness
     * <p>
     * Validates: kNearestNeighbors returns k closest entities
     */
    @Test
    public void testKNearestNeighbors() {
        var content = new EntityContent();

        // Add entities at varying distances
        bubble.addEntity("nearest", new Point3f(10.0f, 10.0f, 10.0f), content);
        bubble.addEntity("middle", new Point3f(15.0f, 15.0f, 15.0f), content);
        bubble.addEntity("farthest", new Point3f(100.0f, 100.0f, 100.0f), content);

        var query = new Point3f(11.0f, 11.0f, 11.0f);
        int k = 2;

        var results = bubble.kNearestNeighbors(query, k);

        assertNotNull(results, "kNN results should not be null");
        assertEquals(2, results.size(), "Should find exactly k=2 neighbors");

        var ids = results.stream().map(EntityRecord::id).toList();
        assertTrue(ids.contains("nearest"), "Should find nearest");
        assertTrue(ids.contains("middle"), "Should find middle");
        assertFalse(ids.contains("farthest"), "Should not find farthest");
    }

    /**
     * Test 7: Bounds adjust as entities move
     * <p>
     * Validates: Bounds recalculate correctly after entity operations
     */
    @Test
    public void testBoundsRecalculation() {
        var content = new EntityContent();

        // Add entity at one position
        bubble.addEntity("entity-1", new Point3f(10.0f, 10.0f, 10.0f), content);
        var initialBounds = bubble.bounds();
        assertTrue(initialBounds.contains(new Point3f(10.0f, 10.0f, 10.0f)),
                  "Initial bounds should contain entity");

        // Add entity far away
        bubble.addEntity("entity-2", new Point3f(100.0f, 100.0f, 100.0f), content);

        bubble.recalculateBounds();
        var expandedBounds = bubble.bounds();

        assertTrue(expandedBounds.contains(new Point3f(10.0f, 10.0f, 10.0f)),
                  "Expanded bounds should contain first entity");
        assertTrue(expandedBounds.contains(new Point3f(100.0f, 100.0f, 100.0f)),
                  "Expanded bounds should contain second entity");
    }

    /**
     * Test 8: Centroid based on entity distribution
     * <p>
     * Validates: centroid() reflects entity positions, not just tetrahedron
     */
    @Test
    public void testCentroidWithEntities() {
        var content = new EntityContent();

        // Add entities symmetrically around a point
        bubble.addEntity("e1", new Point3f(0.0f, 0.0f, 0.0f), content);
        bubble.addEntity("e2", new Point3f(10.0f, 0.0f, 0.0f), content);
        bubble.addEntity("e3", new Point3f(0.0f, 10.0f, 0.0f), content);
        bubble.addEntity("e4", new Point3f(0.0f, 0.0f, 10.0f), content);

        var centroid = bubble.centroid();

        assertNotNull(centroid, "Centroid should not be null");

        // Centroid should be close to (2.5, 2.5, 2.5) for symmetrical distribution
        // (allowing tolerance for tetrahedral vs entity distribution)
        assertTrue(centroid.getX() >= 0 && centroid.getX() <= 10,
                  "Centroid X should be within entity bounds");
        assertTrue(centroid.getY() >= 0 && centroid.getY() <= 10,
                  "Centroid Y should be within entity bounds");
        assertTrue(centroid.getZ() >= 0 && centroid.getZ() <= 10,
                  "Centroid Z should be within entity bounds");
    }

    /**
     * Test 9: VON neighbor tracking
     * <p>
     * Validates: addVonNeighbor correctly tracks neighbor bubbles
     */
    @Test
    public void testAddVonNeighbor() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();

        bubble.addVonNeighbor(neighbor1);
        bubble.addVonNeighbor(neighbor2);

        var neighbors = bubble.getVonNeighbors();
        assertEquals(2, neighbors.size(), "Should have 2 VON neighbors");
        assertTrue(neighbors.contains(neighbor1), "Should contain neighbor1");
        assertTrue(neighbors.contains(neighbor2), "Should contain neighbor2");

        // Adding duplicate should be idempotent
        bubble.addVonNeighbor(neighbor1);
        assertEquals(2, neighbors.size(), "Duplicate add should not increase count");
    }

    /**
     * Test 10: Neighbor removal
     * <p>
     * Validates: removeVonNeighbor correctly removes neighbors
     */
    @Test
    public void testRemoveVonNeighbor() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();

        bubble.addVonNeighbor(neighbor1);
        bubble.addVonNeighbor(neighbor2);

        bubble.removeVonNeighbor(neighbor1);

        var neighbors = bubble.getVonNeighbors();
        assertEquals(1, neighbors.size(), "Should have 1 VON neighbor after removal");
        assertFalse(neighbors.contains(neighbor1), "Should not contain neighbor1");
        assertTrue(neighbors.contains(neighbor2), "Should still contain neighbor2");
    }

    /**
     * Test 11: Frame monitoring
     * <p>
     * Validates: recordFrameTime correctly tracks frame time
     */
    @Test
    public void testRecordFrameTime() {
        // Record a frame time in nanoseconds (5ms = 5,000,000 ns)
        long frameTimeNs = 5_000_000L;

        bubble.recordFrameTime(frameTimeNs);

        // Frame should be recorded
        var utilization = bubble.frameUtilization();
        assertTrue(utilization > 0.0f && utilization <= 1.0f,
                  "Utilization should be positive and <= 1.0 for under-budget frame");
    }

    /**
     * Test 12: No split when under budget
     * <p>
     * Validates: needsSplit returns false when frame time < 120% of budget
     */
    @Test
    public void testNeedsSplitUnderBudget() {
        // Record frame time at 80% of budget (8ms = 8,000,000 ns)
        long frameTimeNs = 8_000_000L;

        bubble.recordFrameTime(frameTimeNs);

        assertFalse(bubble.needsSplit(), "Should not need split at 80% utilization");
    }

    /**
     * Test 13: Split triggered at 120% budget
     * <p>
     * Validates: needsSplit returns true when frame time > 120% of budget (12ms)
     */
    @Test
    public void testNeedsSplitOverBudget() {
        // Record frame time at 125% of budget (12.5ms = 12,500,000 ns)
        long frameTimeNs = 12_500_000L;

        bubble.recordFrameTime(frameTimeNs);

        assertTrue(bubble.needsSplit(), "Should need split at 125% utilization");
    }

    /**
     * Test 14: Correct utilization calculation
     * <p>
     * Validates: frameUtilization returns accurate percentage
     */
    @Test
    public void testFrameUtilization() {
        // Record frame time at exactly budget (10ms = 10,000,000 ns)
        long frameTimeNs = 10_000_000L;

        bubble.recordFrameTime(frameTimeNs);

        var utilization = bubble.frameUtilization();
        assertEquals(1.0f, utilization, EPSILON, "Utilization should be 1.0 at exact budget");
    }

    /**
     * Test 15: Single frame processing
     * <p>
     * Validates: tick() processes a simulation bucket
     */
    @Test
    public void testTickProcessing() {
        var content = new EntityContent();
        bubble.addEntity("entity-1", new Point3f(10.0f, 10.0f, 10.0f), content);

        long bucket = 100L;

        // tick() should execute without error
        assertDoesNotThrow(() -> bubble.tick(bucket), "tick() should not throw exception");
    }

    /**
     * Test 16: Count matches index size
     * <p>
     * Validates: entityCount() accurately reflects spatial index size
     */
    @Test
    public void testEntityCountAccuracy() {
        var content = new EntityContent();

        assertEquals(0, bubble.entityCount(), "Initial count should be 0");

        bubble.addEntity("e1", new Point3f(10.0f, 10.0f, 10.0f), content);
        assertEquals(1, bubble.entityCount(), "Count should be 1 after adding 1 entity");

        bubble.addEntity("e2", new Point3f(20.0f, 20.0f, 20.0f), content);
        bubble.addEntity("e3", new Point3f(30.0f, 30.0f, 30.0f), content);
        assertEquals(3, bubble.entityCount(), "Count should be 3 after adding 3 entities");

        bubble.removeEntity("e2");
        assertEquals(2, bubble.entityCount(), "Count should be 2 after removing 1 entity");

        bubble.removeEntity("e1");
        bubble.removeEntity("e3");
        assertEquals(0, bubble.entityCount(), "Count should be 0 after removing all entities");
    }

    /**
     * Simple EntityContent record for testing.
     */
    record EntityContent() {
    }

    /**
     * EntityRecord for query results.
     */
    record EntityRecord(String id, Point3f position, EntityContent content, long addedBucket) {
    }
}
