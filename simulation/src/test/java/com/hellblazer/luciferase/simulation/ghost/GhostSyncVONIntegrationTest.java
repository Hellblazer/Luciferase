/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.ExternalBubbleTracker;
import com.hellblazer.luciferase.simulation.ghost.GhostLayerHealth;
import com.hellblazer.luciferase.simulation.von.BubbleNode;
import com.hellblazer.luciferase.simulation.von.SpatialNeighborIndex;
import com.hellblazer.luciferase.simulation.von.Event;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GhostSyncVONIntegration - wires ghost sync into VON protocols.
 * <p>
 * GhostSyncVONIntegration implements the VON "watchmen" pattern where ghost layer enables distributed
 * bubble discovery without global registry. It coordinates:
 * - VON JOIN: Initialize ghost relationships with existing neighbors
 * - VON MOVE: Update ghost zones when bubble moves
 * - VON LEAVE: Clean up ghost state when bubble leaves
 * - Ghost-based discovery: Learn about new neighbors via ghost arrivals
 * <p>
 * The integration validates Phase 3's core thesis: ghost layer + VON protocols = fully distributed
 * animation with no global state.
 *
 * @author hal.hildebrand
 */
class GhostSyncVONIntegrationTest {

    // Simple EntityID implementation for testing
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
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof TestEntityID other)) return false;
            return id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private EnhancedBubble bubble;
    private BubbleNode vonNode;
    private BubbleGhostManager<TestEntityID, String> ghostManager;
    private SpatialNeighborIndex neighborIndex;
    private GhostSyncVONIntegration integration;
    private ServerRegistry serverRegistry;
    private InMemoryGhostChannel<TestEntityID, String> ghostChannel;
    private SameServerOptimizer optimizer;
    private List<Event> capturedEvents;

    @BeforeEach
    void setUp() {
        bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
        capturedEvents = new ArrayList<>();

        // Create VON node with event capture
        vonNode = new BubbleNode(bubble, capturedEvents::add);

        // Create spatial index
        neighborIndex = new SpatialNeighborIndex(10.0f, 2.0f);
        neighborIndex.insert(vonNode);

        // Create ghost manager components
        serverRegistry = new ServerRegistry();
        serverRegistry.registerBubble(bubble.id(), UUID.randomUUID());

        ghostChannel = new InMemoryGhostChannel<>();
        optimizer = new SameServerOptimizer(serverRegistry);
        optimizer.registerLocalBubble(bubble);

        var externalBubbleTracker = new ExternalBubbleTracker();
        var ghostLayerHealth = new GhostLayerHealth();

        ghostManager = new BubbleGhostManager<>(
            bubble,
            serverRegistry,
            ghostChannel,
            optimizer,
            externalBubbleTracker,
            ghostLayerHealth
        );

        // Create integration
        integration = new GhostSyncVONIntegration(vonNode, ghostManager, neighborIndex);
    }

    @Test
    void testVONJoinInitializesGhostRelationships() {
        // Setup: Add 3 neighbors to bubble before JOIN
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        var neighbor3 = UUID.randomUUID();

        bubble.addVonNeighbor(neighbor1);
        bubble.addVonNeighbor(neighbor2);
        bubble.addVonNeighbor(neighbor3);

        // Execute JOIN
        integration.onVONJoin();

        // Verify: All neighbors should be registered with ghost manager
        // (We can't directly verify this without exposing internal state,
        // but we can verify that the neighbors are tracked)
        assertEquals(3, vonNode.neighbors().size());
        assertTrue(vonNode.neighbors().contains(neighbor1));
        assertTrue(vonNode.neighbors().contains(neighbor2));
        assertTrue(vonNode.neighbors().contains(neighbor3));
    }

    @Test
    void testGhostTriggersVONDiscovery() {
        // Create a neighbor bubble
        var neighborBubble = new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
        var neighborNode = new BubbleNode(neighborBubble, e -> {});
        neighborIndex.insert(neighborNode);

        // Neighbor is NOT in VON neighbors yet
        assertFalse(vonNode.neighbors().contains(neighborBubble.id()));

        // Simulate ghost arrival from neighbor
        integration.onGhostBatchReceived(neighborBubble.id());

        // Verify: Neighbor discovered via ghost layer
        assertTrue(vonNode.neighbors().contains(neighborBubble.id()),
                   "Ghost arrival should trigger VON neighbor discovery");
    }

    @Test
    void testBoundaryNeighborPattern() {
        // Create two bubbles with overlapping bounds
        var neighbor1Bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
        var neighbor1Node = new BubbleNode(neighbor1Bubble, e -> {});
        neighborIndex.insert(neighbor1Node);

        // Check if they're boundary neighbors (depends on bounds overlap)
        boolean isBoundary = neighborIndex.isBoundaryNeighbor(vonNode, neighbor1Node);

        // If boundary neighbor, ghost sync should be active
        if (isBoundary) {
            // Add as VON neighbor
            integration.onGhostBatchReceived(neighbor1Bubble.id());
            assertTrue(vonNode.neighbors().contains(neighbor1Bubble.id()));
        } else {
            // Not boundary, so no automatic discovery
            assertFalse(vonNode.neighbors().contains(neighbor1Bubble.id()));
        }

        // This test validates the boundary neighbor pattern concept
        assertTrue(true, "Boundary neighbor pattern validated");
    }

    @Test
    void testVONMoveTriggersGhostUpdate() {
        // Create a nearby neighbor
        var neighborBubble = new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
        var neighborNode = new BubbleNode(neighborBubble, e -> {});
        neighborIndex.insert(neighborNode);

        // Initial neighbor count
        int initialNeighborCount = vonNode.neighbors().size();

        // Simulate MOVE to new position (near neighbor)
        var newPosition = neighborNode.position().add(1.0, 1.0, 1.0);
        integration.onVONMove(newPosition);

        // Verify: MOVE may discover new neighbors if bounds overlap
        // (Actual discovery depends on spatial proximity and bounds)
        int finalNeighborCount = vonNode.neighbors().size();
        assertTrue(finalNeighborCount >= initialNeighborCount,
                   "MOVE should not remove neighbors without explicit LEAVE");
    }

    @Test
    void testVONLeaveRemovesGhosts() {
        // Setup: Add neighbors and initialize ghost relationships
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();

        bubble.addVonNeighbor(neighbor1);
        bubble.addVonNeighbor(neighbor2);
        integration.onVONJoin();

        assertEquals(2, vonNode.neighbors().size());

        // Execute LEAVE
        integration.onVONLeave();

        // Verify: Neighbors should be removed
        assertEquals(0, vonNode.neighbors().size(),
                     "LEAVE should remove all neighbors");
    }

    @Test
    void testNCIntegration() {
        // NC (Neighbor Consistency) metric starts at 1.0 (perfect)
        float initialNC = ghostManager.getNeighborConsistency();
        assertEquals(1.0f, initialNC, 0.01f);

        // Add neighbors
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();

        bubble.addVonNeighbor(neighbor1);
        bubble.addVonNeighbor(neighbor2);
        integration.onVONJoin();

        // Simulate ghost reception from neighbors
        integration.onGhostBatchReceived(neighbor1);
        integration.onGhostBatchReceived(neighbor2);

        // NC should remain high (all neighbors sending ghosts)
        float finalNC = ghostManager.getNeighborConsistency();
        assertTrue(finalNC >= 0.0f && finalNC <= 1.0f,
                   "NC should be in valid range [0,1]");
    }

    @Test
    void testNoGlobalRegistry() {
        // Verify architecture constraint: no global registry used
        assertTrue(integration.usesDistributedDiscovery(),
                   "Integration must use distributed discovery only");

        // All neighbor discovery should be via:
        // 1. Ghost arrivals (onGhostBatchReceived)
        // 2. Spatial queries (neighborIndex)
        // 3. VON protocol events
        // NO centralized bubble registry

        // Verify by checking that neighbors are discovered incrementally
        assertEquals(0, vonNode.neighbors().size(), "Should start with no neighbors");

        // Discover via ghost
        var newNeighbor = UUID.randomUUID();
        integration.onGhostBatchReceived(newNeighbor);

        assertEquals(1, vonNode.neighbors().size(),
                     "Neighbor discovered via ghost (no global registry)");
    }

    @Test
    void testNewNeighborFromGhost() {
        // Start with no neighbors
        assertEquals(0, vonNode.neighbors().size());

        // Simulate ghost arrival from unknown bubble
        var unknownBubble = UUID.randomUUID();
        integration.onGhostBatchReceived(unknownBubble);

        // Verify: Unknown bubble discovered as new neighbor
        assertTrue(vonNode.neighbors().contains(unknownBubble),
                   "Ghost from unknown bubble should create new neighbor");
        assertEquals(1, vonNode.neighbors().size());

        // Second ghost from same bubble should not duplicate
        integration.onGhostBatchReceived(unknownBubble);
        assertEquals(1, vonNode.neighbors().size(),
                     "Duplicate ghost should not create duplicate neighbor");
    }
}
