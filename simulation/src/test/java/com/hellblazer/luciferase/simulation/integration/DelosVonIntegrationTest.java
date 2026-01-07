/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.integration;

import com.hellblazer.luciferase.simulation.delos.fireflies.DelosClusterFactory;
import com.hellblazer.luciferase.simulation.delos.fireflies.DelosVonCluster;
import com.hellblazer.luciferase.simulation.von.Event;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Delos-backed VON (Voronoi Overlay Network).
 * <p>
 * Tests the full stack integration:
 * <ul>
 *   <li>Fireflies cluster formation via LocalServer</li>
 *   <li>DelosClusterNode membership and gossip</li>
 *   <li>DelosVonNode spatial coordination</li>
 *   <li>VON protocol messaging (join, leave, move)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class DelosVonIntegrationTest {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private TestClusterBuilder.TestCluster testCluster;
    private DelosClusterFactory.DelosCluster delosCluster;
    private DelosVonCluster vonCluster;

    @AfterEach
    void tearDown() {
        if (testCluster != null) {
            testCluster.close();
            testCluster = null;
        }
    }

    /**
     * Test 1: Basic cluster formation with 6 nodes.
     * <p>
     * Verifies that Fireflies views can bootstrap and stabilize.
     */
    @Test
    void testClusterFormation() throws Exception {
        // Build and start cluster
        testCluster = new TestClusterBuilder()
            .cardinality(6)
            .build();

        assertTrue(testCluster.bootstrapAndStart(Duration.ofMillis(5), DEFAULT_TIMEOUT_SECONDS),
                   "Cluster should stabilize: " + testCluster.getUnstableNodes());

        // Verify all views have full membership
        for (var view : testCluster.getViews()) {
            assertEquals(6, view.getContext().activeCount(),
                         "Each view should see 6 active members");
        }
    }

    /**
     * Test 2: DelosCluster creation from TestCluster.
     * <p>
     * Verifies DelosClusterNode creation and gossip wiring.
     */
    @Test
    void testDelosClusterCreation() throws Exception {
        // Build and start test cluster
        testCluster = new TestClusterBuilder()
            .cardinality(6)
            .build();
        assertTrue(testCluster.bootstrapAndStart(Duration.ofMillis(5), DEFAULT_TIMEOUT_SECONDS));

        // Create DelosCluster
        delosCluster = DelosClusterFactory.create(
            testCluster.getViews(),
            testCluster.getMembers()
        );

        assertEquals(6, delosCluster.size());
        assertTrue(delosCluster.isFullyConnected(),
                   "Delos cluster should be fully connected: " + delosCluster.getIncompleteNodes());

        // Verify each node has unique ID
        var ids = delosCluster.getNodes().stream()
                              .map(n -> n.getNodeId())
                              .distinct()
                              .count();
        assertEquals(6, ids, "Each node should have unique Digest ID");
    }

    /**
     * Test 3: Gossip message delivery across cluster.
     * <p>
     * Verifies that broadcasts reach all nodes.
     */
    @Test
    void testGossipBroadcast() throws Exception {
        // Build cluster
        testCluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        assertTrue(testCluster.bootstrapAndStart(Duration.ofMillis(5), DEFAULT_TIMEOUT_SECONDS));

        delosCluster = DelosClusterFactory.create(
            testCluster.getViews(),
            testCluster.getMembers()
        );

        // Set up message counters
        var receivedCounts = new AtomicInteger[4];
        for (int i = 0; i < 4; i++) {
            receivedCounts[i] = new AtomicInteger(0);
            int idx = i;
            delosCluster.getNode(i).getGossipAdapter().subscribe("test.topic", msg -> {
                receivedCounts[idx].incrementAndGet();
            });
        }

        // Broadcast from node 0
        var sender = delosCluster.getNode(0);
        var message = new com.hellblazer.luciferase.simulation.delos.GossipAdapter.Message(
            sender.getNodeUuid(),
            "test payload".getBytes()
        );
        sender.getGossipAdapter().broadcast("test.topic", message);

        // All nodes should receive (including sender)
        Thread.sleep(100); // Allow async delivery
        for (int i = 0; i < 4; i++) {
            assertEquals(1, receivedCounts[i].get(),
                         "Node " + i + " should receive broadcast");
        }
    }

    /**
     * Test 4: VON cluster creation with spatial positions.
     * <p>
     * Verifies DelosVonNode creation and position assignment.
     */
    @Test
    void testVonClusterCreation() throws Exception {
        // Build Delos cluster
        testCluster = new TestClusterBuilder()
            .cardinality(6)
            .build();
        assertTrue(testCluster.bootstrapAndStart(Duration.ofMillis(5), DEFAULT_TIMEOUT_SECONDS));

        delosCluster = DelosClusterFactory.create(
            testCluster.getViews(),
            testCluster.getMembers()
        );

        // Create VON cluster
        vonCluster = new DelosVonCluster.Builder(delosCluster)
            .positionStrategy(DelosVonCluster.PositionStrategy.GRID)
            .spatialExtent(100.0)
            .build();

        assertEquals(6, vonCluster.size());

        // Verify positions are assigned
        for (var node : vonCluster.getNodes()) {
            assertNotNull(node.position(), "Node should have position");
            assertTrue(node.position().getX() >= 0, "Position X should be positive");
            assertTrue(node.position().getY() >= 0, "Position Y should be positive");
            assertTrue(node.position().getZ() >= 0, "Position Z should be positive");
        }
    }

    /**
     * Test 5: VON join protocol - nodes announce and discover each other.
     * <p>
     * Verifies that join announcements propagate and nodes discover neighbors.
     */
    @Test
    void testVonJoinProtocol() throws Exception {
        // Build cluster
        testCluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        assertTrue(testCluster.bootstrapAndStart(Duration.ofMillis(5), DEFAULT_TIMEOUT_SECONDS));

        delosCluster = DelosClusterFactory.create(
            testCluster.getViews(),
            testCluster.getMembers()
        );

        // Track join events
        var joinEvents = new ArrayList<Event.Join>();
        vonCluster = new DelosVonCluster.Builder(delosCluster)
            .positionStrategy(DelosVonCluster.PositionStrategy.CLUSTERED)
            .spatialExtent(50.0)
            .globalEventHandler(event -> {
                if (event instanceof Event.Join join) {
                    synchronized (joinEvents) {
                        joinEvents.add(join);
                    }
                }
            })
            .build();

        // Announce all nodes
        vonCluster.announceAll();

        // Wait for propagation
        Thread.sleep(200);

        // Each node should receive join events from others (3 per node = 12 total)
        // But we receive our own messages too, so 4*4 = 16 minus 4 self = 12
        synchronized (joinEvents) {
            assertTrue(joinEvents.size() >= 4,
                       "Should receive join events, got: " + joinEvents.size());
        }

        // Verify neighbor discovery
        for (var node : vonCluster.getNodes()) {
            assertTrue(node.neighbors().size() >= 1,
                       "Node " + node.id() + " should have neighbors after join, has: " +
                       node.neighbors().size());
        }
    }

    /**
     * Test 6: VON move protocol - position updates propagate to neighbors.
     * <p>
     * Verifies that move messages are delivered to neighbors.
     */
    @Test
    void testVonMoveProtocol() throws Exception {
        // Build cluster
        testCluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        assertTrue(testCluster.bootstrapAndStart(Duration.ofMillis(5), DEFAULT_TIMEOUT_SECONDS));

        delosCluster = DelosClusterFactory.create(
            testCluster.getViews(),
            testCluster.getMembers()
        );

        // Track move events
        var moveEvents = new ArrayList<Event.Move>();
        vonCluster = new DelosVonCluster.Builder(delosCluster)
            .positionStrategy(DelosVonCluster.PositionStrategy.ORIGIN)
            .globalEventHandler(event -> {
                if (event instanceof Event.Move move) {
                    synchronized (moveEvents) {
                        moveEvents.add(move);
                    }
                }
            })
            .build();

        // Announce all to establish neighbors
        vonCluster.announceAll();
        Thread.sleep(100);

        // Move node 0
        var mover = vonCluster.getNode(0);
        var newPosition = new Point3D(50.0, 50.0, 50.0);
        mover.move(newPosition);

        // Wait for propagation
        Thread.sleep(100);

        // Verify move was received
        synchronized (moveEvents) {
            assertTrue(moveEvents.size() >= 1,
                       "Should receive move events, got: " + moveEvents.size());

            var moveFromNode0 = moveEvents.stream()
                                          .filter(m -> m.nodeId().equals(mover.id()))
                                          .findFirst();
            assertTrue(moveFromNode0.isPresent(), "Should have move from node 0");
            assertEquals(newPosition, moveFromNode0.get().newPosition(),
                         "Move position should match");
        }
    }

    /**
     * Test 7: VON leave protocol - departing nodes notify neighbors.
     * <p>
     * Verifies that leave announcements propagate and neighbors update.
     */
    @Test
    void testVonLeaveProtocol() throws Exception {
        // Build cluster
        testCluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        assertTrue(testCluster.bootstrapAndStart(Duration.ofMillis(5), DEFAULT_TIMEOUT_SECONDS));

        delosCluster = DelosClusterFactory.create(
            testCluster.getViews(),
            testCluster.getMembers()
        );

        // Track leave events
        var leaveEvents = new ArrayList<Event.Leave>();
        var latch = new CountDownLatch(3); // Expect 3 other nodes to receive

        vonCluster = new DelosVonCluster.Builder(delosCluster)
            .positionStrategy(DelosVonCluster.PositionStrategy.ORIGIN)
            .globalEventHandler(event -> {
                if (event instanceof Event.Leave leave) {
                    synchronized (leaveEvents) {
                        leaveEvents.add(leave);
                        latch.countDown();
                    }
                }
            })
            .build();

        // Announce all to establish neighbors
        vonCluster.announceAll();
        Thread.sleep(100);

        // Node 0 announces leave
        var leaver = vonCluster.getNode(0);
        var leaverId = leaver.id();
        leaver.announceLeave();

        // Wait for leave propagation
        assertTrue(latch.await(2, TimeUnit.SECONDS),
                   "Leave should propagate to all nodes");

        synchronized (leaveEvents) {
            assertTrue(leaveEvents.size() >= 1,
                       "Should receive leave events, got: " + leaveEvents.size());

            var leaveFromNode0 = leaveEvents.stream()
                                            .filter(l -> l.nodeId().equals(leaverId))
                                            .findFirst();
            assertTrue(leaveFromNode0.isPresent(), "Should have leave from node 0");
        }
    }

    /**
     * Test 8: Full stack integration - cluster forms, VON coordinates, messages flow.
     * <p>
     * Comprehensive test of all layers working together.
     */
    @Test
    void testFullStackIntegration() throws Exception {
        // Build 12-node cluster (matching E2ETest default)
        testCluster = new TestClusterBuilder()
            .cardinality(12)
            .build();
        assertTrue(testCluster.bootstrapAndStart(Duration.ofMillis(5), DEFAULT_TIMEOUT_SECONDS),
                   "12-node cluster should stabilize");

        // Create Delos cluster
        delosCluster = DelosClusterFactory.create(
            testCluster.getViews(),
            testCluster.getMembers()
        );
        assertTrue(delosCluster.isFullyConnected());

        // Create VON cluster with random positions
        var eventCount = new AtomicInteger(0);
        vonCluster = new DelosVonCluster.Builder(delosCluster)
            .positionStrategy(DelosVonCluster.PositionStrategy.RANDOM)
            .spatialExtent(200.0)
            .globalEventHandler(event -> eventCount.incrementAndGet())
            .build();

        assertEquals(12, vonCluster.size());

        // Phase 1: Join - all nodes announce
        vonCluster.announceAll();
        Thread.sleep(200);

        // Verify neighbor discovery
        int totalNeighbors = vonCluster.getTotalNeighborCount();
        assertTrue(totalNeighbors >= 12,
                   "Should have substantial neighbor connections, got: " + totalNeighbors);

        // Phase 2: Move - all nodes move
        for (var node : vonCluster.getNodes()) {
            var currentPos = node.position();
            var newPos = new Point3D(
                currentPos.getX() + 10.0,
                currentPos.getY() + 10.0,
                currentPos.getZ() + 10.0
            );
            node.move(newPos);
        }
        Thread.sleep(200);

        // Phase 3: Verify event flow
        int events = eventCount.get();
        assertTrue(events > 0, "Should have received events, got: " + events);

        System.out.printf("Full stack test passed: %d nodes, %d total neighbors, %d events%n",
                          vonCluster.size(), totalNeighbors, events);
    }
}
