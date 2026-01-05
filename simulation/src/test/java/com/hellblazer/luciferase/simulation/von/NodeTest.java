package com.hellblazer.luciferase.simulation.von;

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
 * TDD tests for Node interface and BubbleNode adapter.
 * <p>
 * This adapter wraps EnhancedBubble to implement the VON (Voronoi Overlay Network)
 * Node interface. Tests validate:
 * - Adapter pattern wrapping of EnhancedBubble
 * - Position and neighbor tracking
 * - Notification protocol for MOVE, JOIN, LEAVE
 * - TetreeKeyRouter integration
 * <p>
 * These tests MUST pass before implementing Node and BubbleNode (TDD red phase).
 *
 * @author hal.hildebrand
 */
public class NodeTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;

    private EnhancedBubble bubble;
    private BubbleNode vonNode;
    private List<Event> capturedEvents;

    @BeforeEach
    public void setup() {
        bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
        capturedEvents = new ArrayList<>();
        vonNode = new BubbleNode(bubble, capturedEvents::add);
    }

    /**
     * Test 1: Creates Node from EnhancedBubble
     * <p>
     * Validates: BubbleNode wraps EnhancedBubble correctly
     */
    @Test
    public void testWrapEnhancedBubble() {
        assertNotNull(vonNode, "BubbleNode should not be null");
        assertNotNull(vonNode.id(), "VON node ID should not be null");
        assertEquals(bubble.id(), vonNode.id(), "VON node ID should match bubble ID");
    }

    /**
     * Test 2: Returns bubble UUID
     * <p>
     * Validates: id() delegates to EnhancedBubble.id()
     */
    @Test
    public void testVonNodeId() {
        UUID expectedId = bubble.id();
        UUID actualId = vonNode.id();

        assertEquals(expectedId, actualId, "VON node ID should match bubble ID");
    }

    /**
     * Test 3: Returns bubble centroid
     * <p>
     * Validates: position() returns tetrahedral centroid of bubble bounds
     */
    @Test
    public void testVonNodePosition() {
        // Add entities to bubble
        var content = new Object();
        for (int i = 0; i < 100; i++) {
            float x = 10.0f + i;
            float y = 10.0f + i;
            float z = 10.0f;
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }

        Point3D position = vonNode.position();

        assertNotNull(position, "VON node position should not be null");
        assertEquals(bubble.centroid(), position, "Position should match bubble centroid");
    }

    /**
     * Test 4: Returns vonNeighbors set
     * <p>
     * Validates: neighbors() returns bubble's VON neighbor set
     */
    @Test
    public void testVonNodeNeighbors() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();

        bubble.addVonNeighbor(neighbor1);
        bubble.addVonNeighbor(neighbor2);

        var neighbors = vonNode.neighbors();

        assertEquals(2, neighbors.size(), "Should have 2 neighbors");
        assertTrue(neighbors.contains(neighbor1), "Should contain neighbor1");
        assertTrue(neighbors.contains(neighbor2), "Should contain neighbor2");
    }

    /**
     * Test 5: Triggers notification on neighbors on MOVE
     * <p>
     * Validates: notifyMove() emits Event.Move
     */
    @Test
    public void testNotifyNeighborsMove() {
        var neighborBubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
        var neighbor = new BubbleNode(neighborBubble, capturedEvents::add);

        // Add some entities to establish position
        bubble.addEntity("entity-1", new Point3f(10.0f, 10.0f, 10.0f), new Object());
        neighborBubble.addEntity("neighbor-entity-1", new Point3f(20.0f, 20.0f, 20.0f), new Object());

        capturedEvents.clear();

        // Notify vonNode that neighbor moved
        vonNode.notifyMove(neighbor);

        assertEquals(1, capturedEvents.size(), "Should emit exactly one event");
        assertTrue(capturedEvents.get(0) instanceof Event.Move,
                  "Event should be a Event.Move");

        var moveEvent = (Event.Move) capturedEvents.get(0);
        assertEquals(neighbor.id(), moveEvent.nodeId(),
                    "Move event should contain neighbor ID");
    }

    /**
     * Test 6: Triggers fade notification (LEAVE)
     * <p>
     * Validates: notifyLeave() emits Event.Leave
     */
    @Test
    public void testNotifyNeighborsFade() {
        var neighborBubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
        var neighbor = new BubbleNode(neighborBubble, capturedEvents::add);

        // Add neighbor to vonNode
        vonNode.addNeighbor(neighbor.id());

        capturedEvents.clear();

        // Notify vonNode that neighbor is leaving
        vonNode.notifyLeave(neighbor);

        assertEquals(1, capturedEvents.size(), "Should emit exactly one event");
        assertTrue(capturedEvents.get(0) instanceof Event.Leave,
                  "Event should be a Event.Leave");

        var leaveEvent = (Event.Leave) capturedEvents.get(0);
        assertEquals(neighbor.id(), leaveEvent.nodeId(),
                    "Leave event should contain neighbor ID");
    }

    /**
     * Test 7: TetreeKeyRouter lookup works
     * <p>
     * Validates: Node integrates with TetreeKeyRouter for spatial routing
     * NOTE: Simplified test - full routing tested in integration tests
     */
    @Test
    public void testGetRouterForKey() {
        // Add entities to establish bounds
        var content = new Object();
        for (int i = 0; i < 100; i++) {
            float x = 10.0f + i;
            float y = 10.0f + i;
            float z = 10.0f;
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }

        var bounds = vonNode.bounds();
        assertNotNull(bounds, "VON node bounds should not be null");
        assertNotNull(bounds.rootKey(), "Bounds rootKey should not be null");

        // Verify TetreeKey can be obtained (routing uses this)
        var tetreeKey = bounds.rootKey();
        assertNotNull(tetreeKey, "TetreeKey should be available for routing");
    }

    /**
     * Test 8: Two wrappers for same bubble are equal
     * <p>
     * Validates: Equality based on bubble ID, not wrapper instance
     */
    @Test
    public void testVonNodeEquality() {
        var wrapper1 = new BubbleNode(bubble, capturedEvents::add);
        var wrapper2 = new BubbleNode(bubble, capturedEvents::add);

        assertEquals(wrapper1.id(), wrapper2.id(),
                    "Two wrappers for same bubble should have equal IDs");

        // Test reflexive
        assertEquals(wrapper1, wrapper1, "Wrapper should equal itself");

        // Test symmetric
        assertEquals(wrapper1, wrapper2, "Wrapper1 should equal wrapper2");
        assertEquals(wrapper2, wrapper1, "Wrapper2 should equal wrapper1");

        // Test consistent hashCode
        assertEquals(wrapper1.hashCode(), wrapper2.hashCode(),
                    "Equal objects should have equal hashCodes");
    }
}
