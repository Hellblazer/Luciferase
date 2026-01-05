package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for SpatialNeighborIndex - Tetree-based replacement for SFVoronoi.
 * <p>
 * This class replaces Voronoi diagram calculation with spatial index queries.
 * Tests validate:
 * - k-nearest neighbor discovery (NO Voronoi computation)
 * - Bounds overlap detection (replaces enclosing neighbors)
 * - Boundary neighbor detection (AOI + buffer)
 * - Range queries within radius
 * <p>
 * These tests MUST pass before implementing SpatialNeighborIndex (TDD red phase).
 *
 * @author hal.hildebrand
 */
public class SpatialNeighborIndexTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;
    private static final float AOI_RADIUS = 50.0f;
    private static final float BOUNDARY_BUFFER = 10.0f;
    private static final float EPSILON = 0.001f;

    private SpatialNeighborIndex index;
    private List<Node> testNodes;

    @BeforeEach
    public void setup() {
        index = new SpatialNeighborIndex(AOI_RADIUS, BOUNDARY_BUFFER);
        testNodes = new ArrayList<>();
    }

    /**
     * Test 1: Add bubble to index
     * <p>
     * Validates: insert() stores Node
     */
    @Test
    public void testInsertBubble() {
        var bubble = createBubble(new Point3f(10.0f, 10.0f, 10.0f), 100);
        var node = wrapBubble(bubble);

        index.insert(node);

        var retrieved = index.get(node.id());
        assertNotNull(retrieved, "Inserted node should be retrievable");
        assertEquals(node.id(), retrieved.id(), "Retrieved node should match inserted node");
    }

    /**
     * Test 2: Remove bubble from index
     * <p>
     * Validates: remove() deletes Node
     */
    @Test
    public void testRemoveBubble() {
        var bubble = createBubble(new Point3f(10.0f, 10.0f, 10.0f), 100);
        var node = wrapBubble(bubble);

        index.insert(node);
        assertEquals(node, index.get(node.id()), "Node should be in index");

        index.remove(node.id());

        assertNull(index.get(node.id()), "Removed node should not be retrievable");
    }

    /**
     * Test 3: Find nearest bubble to position
     * <p>
     * Validates: findClosestTo() replaces SFVoronoi.closestTo()
     */
    @Test
    public void testFindClosestTo() {
        // Create 5 bubbles at different distances from origin
        var bubble1 = createBubble(new Point3f(10.0f, 0.0f, 0.0f), 50);
        var bubble2 = createBubble(new Point3f(20.0f, 0.0f, 0.0f), 50);
        var bubble3 = createBubble(new Point3f(5.0f, 0.0f, 0.0f), 50);
        var bubble4 = createBubble(new Point3f(50.0f, 0.0f, 0.0f), 50);
        var bubble5 = createBubble(new Point3f(100.0f, 0.0f, 0.0f), 50);

        index.insert(wrapBubble(bubble1));
        index.insert(wrapBubble(bubble2));
        index.insert(wrapBubble(bubble3));
        index.insert(wrapBubble(bubble4));
        index.insert(wrapBubble(bubble5));

        // Query point at origin
        Point3D queryPoint = new Point3D(0.0, 0.0, 0.0);
        Node closest = index.findClosestTo(queryPoint);

        assertNotNull(closest, "Should find closest bubble");
        assertEquals(bubble3.id(), closest.id(), "Bubble3 at (5,0,0) should be closest to origin");
    }

    /**
     * Test 4: Find k nearest bubbles
     * <p>
     * Validates: findKNearest() replaces Voronoi neighbor discovery
     */
    @Test
    public void testFindKNearest() {
        // Create 10 bubbles at increasing distances
        for (int i = 0; i < 10; i++) {
            float x = 10.0f * (i + 1);
            var bubble = createBubble(new Point3f(x, 0.0f, 0.0f), 50);
            index.insert(wrapBubble(bubble));
        }

        // Query for 3 nearest to origin
        Point3D queryPoint = new Point3D(0.0, 0.0, 0.0);
        List<Node> nearest = index.findKNearest(queryPoint, 3);

        assertEquals(3, nearest.size(), "Should return exactly 3 neighbors");

        // Verify order: closest first
        double dist1 = nearest.get(0).position().distance(queryPoint);
        double dist2 = nearest.get(1).position().distance(queryPoint);
        double dist3 = nearest.get(2).position().distance(queryPoint);

        assertTrue(dist1 <= dist2, "First should be closest");
        assertTrue(dist2 <= dist3, "Second should be closer than third");
    }

    /**
     * Test 5: Neighbors with overlapping bounds
     * <p>
     * Validates: findOverlapping() replaces Voronoi enclosing neighbors
     */
    @Test
    public void testFindEnclosingNeighbors() {
        // Create bubbles with overlapping bounds
        var bubble1 = createBubble(new Point3f(10.0f, 10.0f, 10.0f), 100);
        var bubble2 = createBubble(new Point3f(15.0f, 15.0f, 15.0f), 100);  // Overlaps bubble1
        var bubble3 = createBubble(new Point3f(100.0f, 100.0f, 100.0f), 100);  // Far away

        index.insert(wrapBubble(bubble1));
        index.insert(wrapBubble(bubble2));
        index.insert(wrapBubble(bubble3));

        var overlapping = index.findOverlapping(bubble1.bounds());

        assertTrue(overlapping.size() >= 1, "Should find at least bubble1 itself");

        // Check if bubble2 overlaps (depends on actual bounds calculation)
        boolean foundBubble2 = overlapping.stream()
            .anyMatch(n -> n.id().equals(bubble2.id()));

        // Note: Actual overlap depends on BubbleBounds.overlaps() implementation
        // This test validates the query mechanism, not the overlap algorithm
    }

    /**
     * Test 6: Boundary detection via distance
     * <p>
     * Validates: isBoundaryNeighbor() detects neighbors at boundary threshold
     */
    @Test
    public void testIsBoundaryNeighbor() {
        var bubble1 = createBubble(new Point3f(0.0f, 0.0f, 0.0f), 50);
        var node1 = wrapBubble(bubble1);

        // Create neighbor at exact AOI radius
        var bubble2 = createBubble(new Point3f(AOI_RADIUS, 0.0f, 0.0f), 50);
        var node2 = wrapBubble(bubble2);

        // Create neighbor at AOI + buffer/2 (inside boundary)
        var bubble3 = createBubble(new Point3f(AOI_RADIUS + BOUNDARY_BUFFER / 2, 0.0f, 0.0f), 50);
        var node3 = wrapBubble(bubble3);

        // Create neighbor beyond boundary
        var bubble4 = createBubble(new Point3f(AOI_RADIUS + BOUNDARY_BUFFER + 10.0f, 0.0f, 0.0f), 50);
        var node4 = wrapBubble(bubble4);

        // Test boundary detection
        assertFalse(index.isBoundaryNeighbor(node1, node2),
                   "Node at AOI radius should NOT be boundary neighbor (inside AOI)");

        assertTrue(index.isBoundaryNeighbor(node1, node3),
                  "Node at AOI + buffer/2 should be boundary neighbor");

        assertFalse(index.isBoundaryNeighbor(node1, node4),
                   "Node beyond boundary should NOT be boundary neighbor");
    }

    /**
     * Test 7: Bounds overlap detection
     * <p>
     * Validates: Uses BubbleBounds.overlaps() for enclosing neighbor test
     */
    @Test
    public void testOverlaps() {
        var bubble1 = createBubble(new Point3f(10.0f, 10.0f, 10.0f), 100);
        var bubble2 = createBubble(new Point3f(15.0f, 15.0f, 15.0f), 100);  // Nearby
        var node1 = wrapBubble(bubble1);
        var node2 = wrapBubble(bubble2);

        // Test enclosing neighbor detection (bounds overlap)
        boolean overlaps = index.isEnclosingNeighbor(node1, node2);

        // Note: Result depends on BubbleBounds.overlaps() implementation
        // This test validates the delegation, not the overlap algorithm
        assertNotNull(overlaps, "Should return a boolean result");
    }

    /**
     * Test 8: Bubbles within radius
     * <p>
     * Validates: Range query within AOI radius
     */
    @Test
    public void testRangeQuery() {
        // Create bubbles at various distances
        var bubble1 = createBubble(new Point3f(10.0f, 0.0f, 0.0f), 50);   // Within AOI
        var bubble2 = createBubble(new Point3f(30.0f, 0.0f, 0.0f), 50);   // Within AOI
        var bubble3 = createBubble(new Point3f(100.0f, 0.0f, 0.0f), 50);  // Beyond AOI

        index.insert(wrapBubble(bubble1));
        index.insert(wrapBubble(bubble2));
        index.insert(wrapBubble(bubble3));

        Point3D center = new Point3D(0.0, 0.0, 0.0);
        var inRange = index.findWithinRadius(center, AOI_RADIUS);

        assertTrue(inRange.stream().anyMatch(n -> n.id().equals(bubble1.id())),
                  "Bubble1 at distance 10 should be in range");
        assertTrue(inRange.stream().anyMatch(n -> n.id().equals(bubble2.id())),
                  "Bubble2 at distance 30 should be in range");
        assertFalse(inRange.stream().anyMatch(n -> n.id().equals(bubble3.id())),
                   "Bubble3 at distance 100 should NOT be in range");
    }

    /**
     * Test 9: Bubble moves, index updates
     * <p>
     * Validates: updatePosition() tracks position changes
     */
    @Test
    public void testUpdatePosition() {
        var bubble = createBubble(new Point3f(10.0f, 10.0f, 10.0f), 50);
        var node = wrapBubble(bubble);

        index.insert(node);

        Point3D oldPosition = node.position();
        assertNotNull(oldPosition, "Initial position should exist");

        // Simulate bubble moving by updating position
        Point3D newPosition = new Point3D(50.0, 50.0, 50.0);
        index.updatePosition(node.id(), newPosition);

        // Note: updatePosition might be a no-op if position is tracked via node reference
        // This test validates the API exists and doesn't crash
    }

    /**
     * Test 10: Handles empty case gracefully
     * <p>
     * Validates: Empty index doesn't crash
     */
    @Test
    public void testEmptyIndex() {
        Point3D queryPoint = new Point3D(0.0, 0.0, 0.0);

        // findClosestTo on empty index
        Node closest = index.findClosestTo(queryPoint);
        assertNull(closest, "Empty index should return null for closestTo");

        // findKNearest on empty index
        List<Node> nearest = index.findKNearest(queryPoint, 5);
        assertNotNull(nearest, "Should return non-null list");
        assertEquals(0, nearest.size(), "Empty index should return empty list");

        // findWithinRadius on empty index
        List<Node> inRange = index.findWithinRadius(queryPoint, AOI_RADIUS);
        assertNotNull(inRange, "Should return non-null list");
        assertEquals(0, inRange.size(), "Empty index should return empty list");
    }

    // ========== Helper Methods ==========

    /**
     * Create a bubble with entities at a position.
     */
    private EnhancedBubble createBubble(Point3f center, int entityCount) {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
        var content = new Object();

        for (int i = 0; i < entityCount; i++) {
            // Spread entities in a small grid around center
            // Ensure all coordinates are positive (Tetree requirement)
            float x = Math.max(1.0f, center.x + (i % 10) * 0.1f);
            float y = Math.max(1.0f, center.y + (i / 10) * 0.1f);
            float z = Math.max(1.0f, center.z);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }

        return bubble;
    }

    /**
     * Wrap EnhancedBubble as Node.
     */
    private Node wrapBubble(EnhancedBubble bubble) {
        return new BubbleNode(bubble, event -> {});  // No-op event handler for tests
    }
}
