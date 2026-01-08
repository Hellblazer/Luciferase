/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TestProcessCluster - the foundation for 4-process distributed simulation.
 * <p>
 * Phase 6B5.2: TestProcessCluster Infrastructure
 * Bead: Luciferase-d0on
 *
 * @author hal.hildebrand
 */
class TestProcessClusterTest {

    private TestProcessCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = new TestProcessCluster(4, 2); // 4 processes, 2 bubbles each
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop();
        }
    }

    @Test
    void testClusterStartup() throws Exception {
        // When: Start the cluster
        cluster.start();

        // Then: All 4 processes should be running
        assertTrue(cluster.isRunning());
        assertEquals(4, cluster.getProcessCount());

        // All processes should have coordinators
        for (var processId : cluster.getProcessIds()) {
            assertNotNull(cluster.getProcessCoordinator(processId));
            assertTrue(cluster.getProcessCoordinator(processId).isRunning());
        }
    }

    @Test
    void testClusterShutdown() throws Exception {
        // Given: A running cluster
        cluster.start();
        assertTrue(cluster.isRunning());

        // When: Shut down the cluster
        cluster.stop();

        // Then: All processes should be stopped
        assertFalse(cluster.isRunning());
        for (var processId : cluster.getProcessIds()) {
            assertFalse(cluster.getProcessCoordinator(processId).isRunning());
        }
    }

    @Test
    void testProcessAccessById() throws Exception {
        // Given: A running cluster
        cluster.start();

        // When: Access each process by ID
        var processIds = cluster.getProcessIds();

        // Then: Each process is accessible and has correct ID
        assertEquals(4, processIds.size());
        for (var processId : processIds) {
            var coordinator = cluster.getProcessCoordinator(processId);
            assertNotNull(coordinator);
        }
    }

    @Test
    void testTopologySetup() throws Exception {
        // Given: A running cluster
        cluster.start();

        // When: Check topology
        var topology = cluster.getTopology();

        // Then: 8 bubbles should be created
        assertEquals(8, topology.getBubbleCount());

        // Each process should have 2 bubbles
        for (var processId : cluster.getProcessIds()) {
            var bubbles = topology.getBubblesForProcess(processId);
            assertEquals(2, bubbles.size());
        }
    }

    @Test
    void testBubbleNeighborRelationships() throws Exception {
        // Given: A running cluster with topology
        cluster.start();
        var topology = cluster.getTopology();

        // Then: Each bubble should have at least one neighbor
        for (var bubbleId : topology.getAllBubbleIds()) {
            var neighbors = topology.getNeighbors(bubbleId);
            assertFalse(neighbors.isEmpty(), "Bubble " + bubbleId + " should have neighbors");
        }
    }

    @Test
    void testMetricsInitialization() throws Exception {
        // Given: A running cluster
        cluster.start();

        // When: Get metrics
        var metrics = cluster.getMetrics();

        // Then: Metrics should be initialized
        assertNotNull(metrics);
        assertEquals(0, metrics.getTotalMigrations());
        assertEquals(0, metrics.getTotalEntities());
        assertEquals(4, metrics.getActiveProcessCount());
    }

    @Test
    void testCommunicationBetweenProcesses() throws Exception {
        // Given: A running cluster
        cluster.start();
        var processIds = cluster.getProcessIds().stream().toList();
        var process1 = processIds.get(0);
        var process2 = processIds.get(1);

        // When: Send a message from process 1 to process 2
        var messageSent = new CountDownLatch(1);
        var transport2 = cluster.getTransport(process2);

        transport2.onMessage(msg -> messageSent.countDown());

        var transport1 = cluster.getTransport(process1);
        // Use Leave message as a simple message type for testing communication
        transport1.sendToNeighbor(process2, new com.hellblazer.luciferase.simulation.von.VonMessage.Leave(process1));

        // Then: Message should be received
        assertTrue(messageSent.await(1, TimeUnit.SECONDS), "Message should be delivered");
    }

    @Test
    void testMultiProcessHeartbeats() throws Exception {
        // Given: A running cluster
        cluster.start();

        // When: Wait for heartbeat interval
        Thread.sleep(200);

        // Then: All processes should still be alive (no timeout)
        for (var processId : cluster.getProcessIds()) {
            var coordinator = cluster.getProcessCoordinator(processId);
            assertTrue(coordinator.isRunning());
        }
    }

    @Test
    void testGetProcessForBubble() throws Exception {
        // Given: A running cluster with topology
        cluster.start();
        var topology = cluster.getTopology();

        // Then: Each bubble should map to a process
        for (var bubbleId : topology.getAllBubbleIds()) {
            var processId = topology.getProcessForBubble(bubbleId);
            assertNotNull(processId, "Bubble should have a process");
            assertTrue(cluster.getProcessIds().contains(processId));
        }
    }

    @Test
    void testBubblePositions() throws Exception {
        // Given: A running cluster with topology
        cluster.start();
        var topology = cluster.getTopology();

        // Then: Each bubble should have a valid position
        for (var bubbleId : topology.getAllBubbleIds()) {
            var position = topology.getPosition(bubbleId);
            assertNotNull(position, "Bubble should have position");
        }
    }

    @Test
    void testProcessNeighborMapping() throws Exception {
        // Given: A running cluster
        cluster.start();
        var topology = cluster.getTopology();

        // When: Get process-level neighbors
        var processIds = cluster.getProcessIds().stream().toList();

        // Then: Each process should have process-level neighbors
        // Based on 2D grid layout:
        // P1 neighbors: P2, P3
        // P2 neighbors: P1, P4
        // P3 neighbors: P1, P4
        // P4 neighbors: P2, P3
        for (var processId : processIds) {
            var processNeighbors = topology.getNeighborProcesses(processId);
            assertFalse(processNeighbors.isEmpty(),
                "Process " + processId + " should have neighbor processes");
            assertEquals(2, processNeighbors.size(),
                "Each process should have exactly 2 neighbor processes");
        }
    }

    @Test
    void testClusterIdempotence() throws Exception {
        // Given: A cluster
        assertFalse(cluster.isRunning());

        // When: Start, stop, start again
        cluster.start();
        assertTrue(cluster.isRunning());

        cluster.stop();
        assertFalse(cluster.isRunning());

        cluster.start();
        assertTrue(cluster.isRunning());

        // Then: Should be running normally
        assertEquals(4, cluster.getProcessCount());
    }

    @Test
    void testCannotStartTwice() throws Exception {
        // Given: A running cluster
        cluster.start();

        // When/Then: Starting again should throw
        assertThrows(IllegalStateException.class, () -> cluster.start());
    }

    @Test
    void testEntityAccountantIntegration() throws Exception {
        // Given: A running cluster
        cluster.start();

        // When: Get entity accountant
        var accountant = cluster.getEntityAccountant();

        // Then: Accountant should be available and empty initially
        assertNotNull(accountant);
        assertEquals(0, accountant.getTotalOperations());
    }

    @Test
    void testClusterWithCustomProcessCount() throws Exception {
        // When: Create a cluster with different process count
        var customCluster = new TestProcessCluster(2, 4); // 2 processes, 4 bubbles each

        try {
            customCluster.start();

            // Then: Should have correct counts
            assertEquals(2, customCluster.getProcessCount());
            assertEquals(8, customCluster.getTopology().getBubbleCount());
        } finally {
            customCluster.stop();
        }
    }
}
