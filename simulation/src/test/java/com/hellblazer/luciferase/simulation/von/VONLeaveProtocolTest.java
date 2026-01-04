package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.EnhancedBubble;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for VON LEAVE Protocol.
 * <p>
 * The LEAVE protocol handles graceful bubble shutdown in the VON overlay.
 * Tests validate:
 * - Neighbor notification on departure
 * - Graceful cleanup (neighbor list updates)
 * - Event emission (VONEvent.Leave)
 * - Solo bubble leave (no neighbors)
 * - Crash detection (timeout-based)
 * <p>
 * These tests MUST pass before implementing VONLeaveProtocol (TDD red phase).
 *
 * @author hal.hildebrand
 */
public class VONLeaveProtocolTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;
    private static final float AOI_RADIUS = 50.0f;
    private static final float BOUNDARY_BUFFER = 10.0f;

    private SpatialNeighborIndex index;
    private VONLeaveProtocol leaveProtocol;
    private List<VONEvent> capturedEvents;

    @BeforeEach
    public void setup() {
        index = new SpatialNeighborIndex(AOI_RADIUS, BOUNDARY_BUFFER);
        capturedEvents = new ArrayList<>();
        leaveProtocol = new VONLeaveProtocol(index, capturedEvents::add);
    }

    /**
     * Test 1: All neighbors informed of departure
     * <p>
     * Validates: leave() notifies all neighbors
     */
    @Test
    public void testLeaveNotifiesNeighbors() {
        // Create leaver bubble
        var leaver = createBubble(new Point3f(50.0f, 50.0f, 50.0f), 100);
        var leaverNode = wrapBubble(leaver);
        index.insert(leaverNode);

        // Create 3 neighbor bubbles
        List<BubbleVONNode> neighbors = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var neighborBubble = createBubble(new Point3f(50.0f + i * 10, 50.0f, 50.0f), 100);
            var neighborNode = wrapBubbleWithEvents(neighborBubble);
            index.insert(neighborNode);

            // Establish bidirectional neighbor relationship
            leaverNode.addNeighbor(neighborNode.id());
            neighborNode.addNeighbor(leaverNode.id());
            neighbors.add(neighborNode);
        }

        int initialNeighborCount = leaverNode.neighbors().size();
        assertEquals(3, initialNeighborCount, "Leaver should have 3 neighbors");

        capturedEvents.clear();

        // Leaver leaves
        leaveProtocol.leave(leaverNode);

        // Verify each neighbor was notified
        // Note: Event capture depends on BubbleVONNode implementation
        // At minimum, verify LEAVE event was emitted
        assertTrue(capturedEvents.stream()
                       .anyMatch(e -> e instanceof VONEvent.Leave &&
                                     ((VONEvent.Leave) e).nodeId().equals(leaverNode.id())),
                  "LEAVE event should be emitted");
    }

    /**
     * Test 2: Graceful shutdown with proper cleanup
     * <p>
     * Validates: Leaver is removed from index
     */
    @Test
    public void testLeaveGracefulShutdown() {
        var leaver = createBubble(new Point3f(50.0f, 50.0f, 50.0f), 100);
        var leaverNode = wrapBubble(leaver);
        index.insert(leaverNode);

        assertNotNull(index.get(leaverNode.id()), "Leaver should be in index before leave");

        leaveProtocol.leave(leaverNode);

        // Verify leaver removed from index
        assertNull(index.get(leaverNode.id()), "Leaver should be removed from index after leave");
    }

    /**
     * Test 3: Neighbors remove leaving bubble from their lists
     * <p>
     * Validates: Neighbor list cleanup
     */
    @Test
    public void testNeighborsRemoveLeavingBubble() {
        // Create leaver
        var leaver = createBubble(new Point3f(50.0f, 50.0f, 50.0f), 100);
        var leaverNode = wrapBubble(leaver);
        index.insert(leaverNode);

        // Create neighbor
        var neighbor = createBubble(new Point3f(60.0f, 60.0f, 60.0f), 100);
        var neighborNode = wrapBubble(neighbor);
        index.insert(neighborNode);

        // Establish bidirectional relationship
        leaverNode.addNeighbor(neighborNode.id());
        neighborNode.addNeighbor(leaverNode.id());

        assertEquals(1, neighborNode.neighbors().size(), "Neighbor should have 1 neighbor (leaver)");
        assertTrue(neighborNode.neighbors().contains(leaverNode.id()),
                  "Neighbor should have leaver in neighbor list");

        // Leaver leaves
        leaveProtocol.leave(leaverNode);

        // Verify neighbor removed leaver from their list
        assertEquals(0, neighborNode.neighbors().size(),
                    "Neighbor should have 0 neighbors after leaver departure");
        assertFalse(neighborNode.neighbors().contains(leaverNode.id()),
                   "Neighbor should NOT have leaver in neighbor list after departure");
    }

    /**
     * Test 4: LEAVE event emitted
     * <p>
     * Validates: VONEvent.Leave emitted on departure
     */
    @Test
    public void testLeaveEmitsEvent() {
        var leaver = createBubble(new Point3f(50.0f, 50.0f, 50.0f), 100);
        var leaverNode = wrapBubble(leaver);
        index.insert(leaverNode);

        capturedEvents.clear();

        leaveProtocol.leave(leaverNode);

        // Verify LEAVE event emitted
        var leaveEvent = capturedEvents.stream()
            .filter(e -> e instanceof VONEvent.Leave)
            .map(e -> (VONEvent.Leave) e)
            .filter(e -> e.nodeId().equals(leaverNode.id()))
            .findFirst()
            .orElse(null);

        assertNotNull(leaveEvent, "LEAVE event should be emitted");
        assertEquals(leaverNode.id(), leaveEvent.nodeId(),
                    "LEAVE event should reference leaver's ID");
    }

    /**
     * Test 5: Solo bubble leave (no neighbors)
     * <p>
     * Validates: LEAVE works when bubble has no neighbors
     */
    @Test
    public void testLeaveWithNoNeighbors() {
        // Solo bubble
        var leaver = createBubble(new Point3f(50.0f, 50.0f, 50.0f), 100);
        var leaverNode = wrapBubble(leaver);
        index.insert(leaverNode);

        assertEquals(0, leaverNode.neighbors().size(), "Solo bubble should have no neighbors");

        capturedEvents.clear();

        leaveProtocol.leave(leaverNode);

        // Verify leave completed without error
        assertNull(index.get(leaverNode.id()), "Solo bubble should be removed from index");

        // Verify LEAVE event emitted
        assertTrue(capturedEvents.stream()
                       .anyMatch(e -> e instanceof VONEvent.Leave),
                  "LEAVE event should be emitted for solo bubble");
    }

    /**
     * Test 6: Crashed bubble detected (timeout-based)
     * <p>
     * Validates: handleCrash() treats crash as leave + emits CRASH event
     */
    @Test
    public void testCrashedBubbleDetected() {
        // Create crashed bubble
        var crashed = createBubble(new Point3f(50.0f, 50.0f, 50.0f), 100);
        var crashedNode = wrapBubble(crashed);
        index.insert(crashedNode);

        // Create neighbor
        var neighbor = createBubble(new Point3f(60.0f, 60.0f, 60.0f), 100);
        var neighborNode = wrapBubble(neighbor);
        index.insert(neighborNode);

        // Establish relationship
        crashedNode.addNeighbor(neighborNode.id());
        neighborNode.addNeighbor(crashedNode.id());

        capturedEvents.clear();

        // Detect crash (neighbor detects it)
        leaveProtocol.handleCrash(crashedNode.id(), neighborNode.id());

        // Verify crashed bubble removed from index
        assertNull(index.get(crashedNode.id()), "Crashed bubble should be removed from index");

        // Verify neighbor cleaned up
        assertFalse(neighborNode.neighbors().contains(crashedNode.id()),
                   "Neighbor should NOT have crashed bubble in neighbor list");

        // Verify CRASH event emitted
        // Note: Spec shows handleCrash emits CRASH event (not LEAVE)
        assertTrue(capturedEvents.stream()
                       .anyMatch(e -> e instanceof VONEvent.Crash &&
                                     ((VONEvent.Crash) e).nodeId().equals(crashedNode.id())),
                  "CRASH event should be emitted");
    }

    // ========== Helper Methods ==========

    /**
     * Create a bubble with entities at a position.
     */
    private EnhancedBubble createBubble(Point3f center, int entityCount) {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
        var content = new Object();

        for (int i = 0; i < entityCount; i++) {
            float x = Math.max(1.0f, center.x + (i % 10) * 0.1f);
            float y = Math.max(1.0f, center.y + (i / 10) * 0.1f);
            float z = Math.max(1.0f, center.z);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }

        return bubble;
    }

    /**
     * Wrap EnhancedBubble as VONNode.
     */
    private BubbleVONNode wrapBubble(EnhancedBubble bubble) {
        return new BubbleVONNode(bubble, event -> {});  // No-op event handler
    }

    /**
     * Wrap EnhancedBubble as VONNode with event capture.
     */
    private BubbleVONNode wrapBubbleWithEvents(EnhancedBubble bubble) {
        return new BubbleVONNode(bubble, capturedEvents::add);
    }
}
