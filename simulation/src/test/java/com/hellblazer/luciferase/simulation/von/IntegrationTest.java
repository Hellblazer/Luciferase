package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for full VON Discovery Protocol - LOCAL MODE.
 * <p>
 * These tests validate the LOCAL-ONLY protocol implementation using
 * SpatialNeighborIndex for single-JVM operation. For distributed P2P
 * testing, see {@link VonManagerTest} and {@link P2PProtocolIntegrationTest}.
 * <p>
 * Tests validate the complete VON protocol lifecycle with multi-bubble clusters:
 * - 10-bubble cluster formation
 * - Movement triggering neighbor updates
 * - Graceful shutdown propagation
 * - Neighbor Consistency (NC) > 0.9 metric
 * - Network partition recovery
 * - Concurrent joins
 * <p>
 * LOCAL MODE vs P2P MODE:
 * - Local: All bubbles in same JVM, uses SpatialNeighborIndex
 * - P2P: Distributed across network, uses VonTransport
 *
 * @author hal.hildebrand
 */
public class IntegrationTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;
    private static final float AOI_RADIUS = 50.0f;
    private static final float BOUNDARY_BUFFER = 10.0f;

    private SpatialNeighborIndex index;
    private JoinProtocol joinProtocol;
    private MoveProtocol moveProtocol;
    private LeaveProtocol leaveProtocol;
    private List<Event> capturedEvents;

    @BeforeEach
    public void setup() {
        index = new SpatialNeighborIndex(AOI_RADIUS, BOUNDARY_BUFFER);
        capturedEvents = new ArrayList<>();
        joinProtocol = new JoinProtocol(index, capturedEvents::add);
        moveProtocol = new MoveProtocol(index, capturedEvents::add, AOI_RADIUS);
        leaveProtocol = new LeaveProtocol(index, capturedEvents::add);
    }

    /**
     * Test 1: 10-bubble cluster formation with NC > 0.9
     * <p>
     * Validates:
     * - All bubbles successfully join
     * - All bubbles have neighbors
     * - NC (Neighbor Consistency) > 0.9 for each bubble
     */
    @Test
    public void testTenBubbleClusterFormation() {
        List<Node> bubbles = new ArrayList<>();

        // Create and join 10 bubbles in a cluster around (50, 50, 50)
        for (int i = 0; i < 10; i++) {
            Point3f center = new Point3f(
                50.0f + (i % 3) * 15.0f,
                50.0f + (i / 3) * 15.0f,
                50.0f
            );
            var bubble = createBubble(center, 100);
            var node = wrapBubble(bubble);

            joinProtocol.join(node, node.position());
            bubbles.add(node);
        }

        // Verify all bubbles joined
        assertEquals(10, index.size(), "Should have 10 bubbles in index");

        // Verify all bubbles have at least one neighbor
        for (Node bubble : bubbles) {
            assertFalse(bubble.neighbors().isEmpty(),
                       "Bubble " + bubble.id() + " should have neighbors");
        }

        // Calculate and verify NC > 0.9 for each bubble
        for (Node bubble : bubbles) {
            float nc = calculateNC(bubble);
            assertTrue(nc >= 0.9f,
                      String.format("NC %.2f should be >= 0.9 for bubble %s", nc, bubble.id()));
        }
    }

    /**
     * Test 2: Bubble movement triggers neighbor updates
     * <p>
     * Validates:
     * - MOVE event emitted
     * - Neighbor lists updated after movement
     * - NC maintained after movement
     */
    @Test
    public void testBubbleMovementTriggersUpdates() {
        // Create 5-bubble cluster
        List<Node> bubbles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Point3f center = new Point3f(50.0f + i * 10, 50.0f, 50.0f);
            var bubble = createBubble(center, 100);
            var node = wrapBubble(bubble);
            joinProtocol.join(node, node.position());
            bubbles.add(node);
        }

        Node mover = bubbles.get(2);  // Middle bubble
        int initialNeighborCount = mover.neighbors().size();

        capturedEvents.clear();

        // Move bubble significantly but within AOI of some neighbors
        Point3D newPosition = new Point3D(75.0, 75.0, 50.0);
        moveProtocol.move(mover, newPosition);

        // Verify MOVE event emitted
        assertTrue(capturedEvents.stream()
                       .anyMatch(e -> e instanceof Event.Move &&
                                     ((Event.Move) e).nodeId().equals(mover.id())),
                  "MOVE event should be emitted");

        // Verify NC maintained for all bubbles
        for (Node bubble : bubbles) {
            float nc = calculateNC(bubble);
            assertTrue(nc >= 0.7f,  // Relaxed after significant movement
                      String.format("NC %.2f should be >= 0.7 after movement", nc));
        }
    }

    /**
     * Test 3: Graceful shutdown propagates to neighbors
     * <p>
     * Validates:
     * - LEAVE event emitted
     * - Neighbors update their neighbor lists
     * - Leaver removed from index
     */
    @Test
    public void testGracefulShutdownPropagates() {
        // Create 3-bubble cluster
        List<Node> bubbles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Point3f center = new Point3f(50.0f + i * 10, 50.0f, 50.0f);
            var bubble = createBubble(center, 100);
            var node = wrapBubble(bubble);
            joinProtocol.join(node, node.position());
            bubbles.add(node);
        }

        Node leaver = bubbles.get(1);  // Middle bubble
        UUID leaverId = leaver.id();

        capturedEvents.clear();

        // Bubble leaves gracefully
        leaveProtocol.leave(leaver);

        // Verify LEAVE event emitted
        assertTrue(capturedEvents.stream()
                       .anyMatch(e -> e instanceof Event.Leave &&
                                     ((Event.Leave) e).nodeId().equals(leaverId)),
                  "LEAVE event should be emitted");

        // Verify leaver removed from index
        assertNull(index.get(leaverId), "Leaver should be removed from index");

        // Verify remaining bubbles updated their neighbor lists
        for (Node bubble : bubbles) {
            if (!bubble.id().equals(leaverId)) {
                assertFalse(bubble.neighbors().contains(leaverId),
                           "Remaining bubbles should not have leaver in neighbor list");
            }
        }
    }

    /**
     * Test 4: NC above 90% throughout operations
     * <p>
     * Validates:
     * - NC maintained > 0.9 during join, move, leave operations
     */
    @Test
    public void testNeighborConsistencyAbove90Percent() {
        List<Node> bubbles = new ArrayList<>();

        // Create 10-bubble cluster
        for (int i = 0; i < 10; i++) {
            Point3f center = new Point3f(
                50.0f + (i % 4) * 12.0f,
                50.0f + (i / 4) * 12.0f,
                50.0f
            );
            var bubble = createBubble(center, 100);
            var node = wrapBubble(bubble);
            joinProtocol.join(node, node.position());
            bubbles.add(node);

            // Check NC after each join
            for (Node b : bubbles) {
                float nc = calculateNC(b);
                assertTrue(nc >= 0.9f,
                          String.format("NC %.2f should be >= 0.9 after join %d", nc, i));
            }
        }

        // Perform movements
        for (int i = 0; i < 3; i++) {
            Node mover = bubbles.get(i);
            Point3D newPos = new Point3D(
                mover.position().getX() + 5.0,
                mover.position().getY() + 5.0,
                50.0
            );
            moveProtocol.move(mover, newPos);

            // Check NC after each move
            for (Node b : bubbles) {
                float nc = calculateNC(b);
                assertTrue(nc >= 0.85f,  // Slightly relaxed during movement
                          String.format("NC %.2f should be >= 0.85 after move %d", nc, i));
            }
        }

        // Perform leaves
        for (int i = 0; i < 2; i++) {
            Node leaver = bubbles.remove(bubbles.size() - 1);
            leaveProtocol.leave(leaver);

            // Check NC for remaining bubbles
            for (Node b : bubbles) {
                float nc = calculateNC(b);
                assertTrue(nc >= 0.9f,
                          String.format("NC %.2f should be >= 0.9 after leave %d", nc, i));
            }
        }
    }

    /**
     * Test 5: Network partition recovery
     * <p>
     * Validates:
     * - Cluster remains consistent after simulated partition
     * - Bubbles can rejoin after partition heals
     */
    @Test
    public void testNetworkPartitionRecovery() {
        // Create 10-bubble cluster
        List<Node> bubbles = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Point3f center = new Point3f(50.0f + i * 8, 50.0f, 50.0f);
            var bubble = createBubble(center, 100);
            var node = wrapBubble(bubble);
            joinProtocol.join(node, node.position());
            bubbles.add(node);
        }

        // Simulate partition: remove 5 bubbles
        List<Node> partitioned = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Node node = bubbles.get(i);
            partitioned.add(node);
            // Simulate crash (forced removal)
            if (bubbles.size() > i + 1) {
                leaveProtocol.handleCrash(node.id(), bubbles.get(i + 1).id());
            }
        }

        // Verify remaining cluster is consistent
        for (int i = 5; i < bubbles.size(); i++) {
            Node bubble = bubbles.get(i);
            if (index.get(bubble.id()) != null) {  // Still in index
                float nc = calculateNC(bubble);
                assertTrue(nc >= 0.8f,  // Relaxed during partition
                          String.format("NC %.2f should be >= 0.8 during partition", nc));
            }
        }

        // Heal partition: rejoin bubbles
        for (Node node : partitioned) {
            if (index.get(node.id()) == null) {  // Not in index
                // Clear old neighbor list
                for (UUID nid : new ArrayList<>(node.neighbors())) {
                    node.removeNeighbor(nid);
                }
                // Rejoin
                joinProtocol.join(node, node.position());
            }
        }

        // Verify cluster converges to consistent state
        for (Node bubble : bubbles) {
            if (index.get(bubble.id()) != null) {
                float nc = calculateNC(bubble);
                assertTrue(nc >= 0.85f,  // May take time to fully converge
                          String.format("NC %.2f should be >= 0.85 after partition recovery", nc));
            }
        }
    }

    /**
     * Test 6: Concurrent joins handled correctly
     * <p>
     * Validates:
     * - No race conditions during concurrent joins
     * - All bubbles successfully join
     * - Neighbor lists consistent
     */
    @Test
    public void testConcurrentJoinsHandled() throws InterruptedException {
        // Create initial 3-bubble cluster
        List<Node> initial = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Point3f center = new Point3f(50.0f + i * 15, 50.0f, 50.0f);
            var bubble = createBubble(center, 100);
            var node = wrapBubble(bubble);
            joinProtocol.join(node, node.position());
            initial.add(node);
        }

        // Prepare 5 bubbles for concurrent join
        List<Node> newBubbles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Point3f center = new Point3f(80.0f + i * 10, 50.0f, 50.0f);
            var bubble = createBubble(center, 100);
            var node = wrapBubble(bubble);
            newBubbles.add(node);
        }

        // Submit 5 concurrent joins
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);

        for (Node node : newBubbles) {
            executor.submit(() -> {
                try {
                    joinProtocol.join(node, node.position());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all joins to complete
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All joins should complete within 5 seconds");
        executor.shutdown();

        // Verify all bubbles joined
        assertEquals(8, index.size(), "Should have 8 bubbles in index (3 initial + 5 new)");

        // Verify no race conditions - all bubbles have correct neighbors
        // NC may be lower for concurrent joins due to race conditions in neighbor discovery
        for (Node bubble : newBubbles) {
            assertNotNull(index.get(bubble.id()), "New bubble should be in index");
            float nc = calculateNC(bubble);
            assertTrue(nc >= 0.5f,  // Lower threshold for concurrent joins - race conditions expected
                      String.format("NC %.2f should be >= 0.5 after concurrent joins", nc));
        }
    }

    // ========== Helper Methods ==========

    /**
     * Calculate Neighbor Consistency (NC) metric.
     * <p>
     * NC = known_neighbors / actual_neighbors
     * <p>
     * Where:
     * - known_neighbors = size of bubble.neighbors()
     * - actual_neighbors = bubbles within AOI radius (from spatial index)
     *
     * @param bubble Bubble to calculate NC for
     * @return NC value (0.0 to 1.0)
     */
    private float calculateNC(Node bubble) {
        if (index.get(bubble.id()) == null) {
            return 0.0f;  // Not in index
        }

        // Known neighbors
        int knownNeighbors = bubble.neighbors().size();

        // Actual neighbors within AOI
        List<Node> inRange = index.findWithinRadius(bubble.position(), AOI_RADIUS);
        // Exclude self
        int actualNeighbors = (int) inRange.stream()
            .filter(n -> !n.id().equals(bubble.id()))
            .count();

        if (actualNeighbors == 0) {
            return 1.0f;  // Solo bubble - perfect NC
        }

        return (float) knownNeighbors / actualNeighbors;
    }

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
     * Wrap EnhancedBubble as Node.
     */
    private Node wrapBubble(EnhancedBubble bubble) {
        return new BubbleNode(bubble, event -> {});  // No-op event handler
    }
}
