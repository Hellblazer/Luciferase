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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TestProcessTopology 3D cube layouts.
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 * Phase 6B7: 3D Topology & Entity Simulation
 * <p>
 * Verifies:
 * - 4-process 2x2x1 grid topology (2D layer in 3D space)
 * - 8-process 2x2x2 cube topology (true 3D)
 * - Correct 3D neighbor relationships (face adjacency)
 * - Cross-process migration paths in 3D
 *
 * @author hal.hildebrand
 */
class TestProcessTopologyTest {

    @Test
    void test4ProcessTopology_GridStructure() {
        // Given: 4-process topology with 2 bubbles per process
        var topology = new TestProcessTopology(4, 2);

        // Then: Should have 8 bubbles total
        assertEquals(8, topology.getBubbleCount(), "Should have 8 bubbles (4 processes * 2 bubbles)");

        // And: Should have 4 process IDs
        assertEquals(4, topology.getProcessCount(), "Should have 4 processes");
    }

    @Test
    void test4ProcessTopology_NeighborCount() {
        // Given: 4-process 2x2x1 grid (2D layer in 3D space)
        var topology = new TestProcessTopology(4, 2);

        // Then: Each process should have 2 neighbors (corners of a 2x2x1 grid)
        for (int i = 0; i < 4; i++) {
            var processId = topology.getProcessId(i);
            var neighbors = topology.getNeighborProcesses(processId);
            assertEquals(2, neighbors.size(), "Process " + i + " should have 2 neighbors in 2x2x1 grid");
        }
    }

    @Test
    void test4ProcessTopology_Neighbors() {
        // Given: 4-process 2x2 grid
        //   P0 -- P1
        //   |     |
        //   P2 -- P3
        var topology = new TestProcessTopology(4, 2);

        var p0 = topology.getProcessId(0);
        var p1 = topology.getProcessId(1);
        var p2 = topology.getProcessId(2);
        var p3 = topology.getProcessId(3);

        // Then: Check expected neighbor relationships
        var neighbors0 = topology.getNeighborProcesses(p0);
        assertTrue(neighbors0.contains(p1), "P0 should be neighbor with P1");
        assertTrue(neighbors0.contains(p2), "P0 should be neighbor with P2");

        var neighbors1 = topology.getNeighborProcesses(p1);
        assertTrue(neighbors1.contains(p0), "P1 should be neighbor with P0");
        assertTrue(neighbors1.contains(p3), "P1 should be neighbor with P3");

        var neighbors2 = topology.getNeighborProcesses(p2);
        assertTrue(neighbors2.contains(p0), "P2 should be neighbor with P0");
        assertTrue(neighbors2.contains(p3), "P2 should be neighbor with P3");

        var neighbors3 = topology.getNeighborProcesses(p3);
        assertTrue(neighbors3.contains(p1), "P3 should be neighbor with P1");
        assertTrue(neighbors3.contains(p2), "P3 should be neighbor with P2");
    }

    @Test
    void test8ProcessTopology_GridStructure() {
        // Given: 8-process topology with 2 bubbles per process (2x2x2 cube)
        var topology = new TestProcessTopology(8, 2);

        // Then: Should have 16 bubbles total
        assertEquals(16, topology.getBubbleCount(), "Should have 16 bubbles (8 processes * 2 bubbles)");

        // And: Should have 8 process IDs
        assertEquals(8, topology.getProcessCount(), "Should have 8 processes");
    }

    @Test
    void test8ProcessTopology_NeighborDistribution() {
        // Given: 8-process 2x2x2 cube
        // Z=1 (Top):     Z=0 (Bottom):
        //   P4 -- P5       P0 -- P1
        //   |     |        |     |
        //   P6 -- P7       P2 -- P3
        var topology = new TestProcessTopology(8, 2);

        // Then: All 8 corner processes in a cube have exactly 3 neighbors (face adjacency)
        for (int i = 0; i < 8; i++) {
            var processId = topology.getProcessId(i);
            var neighbors = topology.getNeighborProcesses(processId);
            assertEquals(3, neighbors.size(),
                "Process " + i + " in 2x2x2 cube should have 3 neighbors, got: " + neighbors.size());
        }
    }

    @Test
    void test8ProcessTopology_XAxisNeighbors() {
        // Given: 8-process 2x2x2 cube
        // Z=0 (Bottom layer):
        //   P0(0,0,0) -- P1(1,0,0)
        //   |             |
        //   P2(0,1,0) -- P3(1,1,0)
        var topology = new TestProcessTopology(8, 2);

        var p0 = topology.getProcessId(0);
        var p1 = topology.getProcessId(1);
        var p2 = topology.getProcessId(2);
        var p3 = topology.getProcessId(3);

        // Then: X-axis neighbors (differ only in X coordinate)
        assertTrue(topology.getNeighborProcesses(p0).contains(p1), "P0(0,0,0) should be neighbor with P1(1,0,0)");
        assertTrue(topology.getNeighborProcesses(p2).contains(p3), "P2(0,1,0) should be neighbor with P3(1,1,0)");
    }

    @Test
    void test8ProcessTopology_YAxisNeighbors() {
        // Given: 8-process 2x2x2 cube
        // Y-axis connections (depth in 3D):
        //   P0(0,0,0) <-> P2(0,1,0)
        //   P1(1,0,0) <-> P3(1,1,0)
        //   P4(0,0,1) <-> P6(0,1,1)
        //   P5(1,0,1) <-> P7(1,1,1)
        var topology = new TestProcessTopology(8, 2);

        var p0 = topology.getProcessId(0);
        var p2 = topology.getProcessId(2);
        var p1 = topology.getProcessId(1);
        var p3 = topology.getProcessId(3);
        var p4 = topology.getProcessId(4);
        var p6 = topology.getProcessId(6);
        var p5 = topology.getProcessId(5);
        var p7 = topology.getProcessId(7);

        // Then: Y-axis neighbors (differ only in Y coordinate)
        assertTrue(topology.getNeighborProcesses(p0).contains(p2), "P0(0,0,0) should be neighbor with P2(0,1,0)");
        assertTrue(topology.getNeighborProcesses(p1).contains(p3), "P1(1,0,0) should be neighbor with P3(1,1,0)");
        assertTrue(topology.getNeighborProcesses(p4).contains(p6), "P4(0,0,1) should be neighbor with P6(0,1,1)");
        assertTrue(topology.getNeighborProcesses(p5).contains(p7), "P5(1,0,1) should be neighbor with P7(1,1,1)");
    }

    @Test
    void test8ProcessTopology_ZAxisNeighbors() {
        // Given: 8-process 2x2x2 cube
        // Z-axis connections (vertical in 3D):
        //   P0(0,0,0) <-> P4(0,0,1)
        //   P1(1,0,0) <-> P5(1,0,1)
        //   P2(0,1,0) <-> P6(0,1,1)
        //   P3(1,1,0) <-> P7(1,1,1)
        var topology = new TestProcessTopology(8, 2);

        var p0 = topology.getProcessId(0);
        var p4 = topology.getProcessId(4);
        var p1 = topology.getProcessId(1);
        var p5 = topology.getProcessId(5);
        var p2 = topology.getProcessId(2);
        var p6 = topology.getProcessId(6);
        var p3 = topology.getProcessId(3);
        var p7 = topology.getProcessId(7);

        // Then: Z-axis neighbors (differ only in Z coordinate)
        assertTrue(topology.getNeighborProcesses(p0).contains(p4), "P0(0,0,0) should be neighbor with P4(0,0,1)");
        assertTrue(topology.getNeighborProcesses(p1).contains(p5), "P1(1,0,0) should be neighbor with P5(1,0,1)");
        assertTrue(topology.getNeighborProcesses(p2).contains(p6), "P2(0,1,0) should be neighbor with P6(0,1,1)");
        assertTrue(topology.getNeighborProcesses(p3).contains(p7), "P3(1,1,0) should be neighbor with P7(1,1,1)");
    }

    @Test
    void test8ProcessTopology_NoBubbleDuplicates() {
        // Given: 8-process topology with 2 bubbles per process
        var topology = new TestProcessTopology(8, 2);

        // Then: All bubbles should be unique (by ID, not position)
        var bubbles = topology.getAllBubbleIds();
        assertEquals(16, bubbles.size(), "Should have 16 unique bubble IDs");

        // And: Each process should have exactly 2 bubbles
        for (int i = 0; i < 8; i++) {
            var processId = topology.getProcessId(i);
            var bubblesForProcess = topology.getBubblesForProcess(processId);
            assertEquals(2, bubblesForProcess.size(),
                "Process " + i + " should have exactly 2 bubbles");
        }
    }

    @Test
    void test8ProcessTopology_BubbleProcessMapping() {
        // Given: 8-process topology with 2 bubbles per process
        var topology = new TestProcessTopology(8, 2);

        // Then: Each bubble should map to exactly one process
        for (var bubbleId : topology.getAllBubbleIds()) {
            var processId = topology.getProcessForBubble(bubbleId);
            assertNotNull(processId, "Bubble should map to a process");

            // And: The bubble should be in that process's bubble set
            var bubblesForProcess = topology.getBubblesForProcess(processId);
            assertTrue(bubblesForProcess.contains(bubbleId),
                "Bubble should be in its process's bubble set");
        }
    }

    @Test
    void test8ProcessTopology_BubbleNeighborsExist() {
        // Given: 8-process topology with 2 bubbles per process
        var topology = new TestProcessTopology(8, 2);

        // Then: Each bubble should have neighbors (adjacent bubbles)
        var bubbleCount = 0;
        var bubblesWithNeighbors = 0;
        for (var bubbleId : topology.getAllBubbleIds()) {
            bubbleCount++;
            var neighbors = topology.getNeighbors(bubbleId);
            if (!neighbors.isEmpty()) {
                bubblesWithNeighbors++;
            }
        }

        // Most bubbles should have neighbors (except maybe edge cases)
        assertTrue(bubblesWithNeighbors > bubbleCount * 0.7,
            "At least 70% of bubbles should have neighbors, got: " + bubblesWithNeighbors + "/" + bubbleCount);
    }

    @Test
    void test8ProcessTopology_CrossProcessMigrationPaths() {
        // Given: 8-process topology with 2 bubbles per process
        var topology = new TestProcessTopology(8, 2);

        // Then: All processes should have neighbors (connectivity)
        for (int i = 0; i < 8; i++) {
            var processId = topology.getProcessId(i);
            var neighbors = topology.getNeighborProcesses(processId);
            assertFalse(neighbors.isEmpty(),
                "Process " + i + " should have at least one neighbor for cross-process migration");
        }

        // And: Can migrate from any process to at least one other process
        var allConnected = true;
        for (int i = 0; i < 8; i++) {
            var processId = topology.getProcessId(i);
            if (topology.getNeighborProcesses(processId).isEmpty()) {
                allConnected = false;
                break;
            }
        }
        assertTrue(allConnected, "All processes should have valid migration paths");
    }

    @Test
    void test8ProcessTopology_SymmetricNeighbors() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);

        // Then: Neighbor relationships should be symmetric (if P0->P1 then P1->P0)
        for (int i = 0; i < 8; i++) {
            var processA = topology.getProcessId(i);
            var neighborsA = topology.getNeighborProcesses(processA);

            for (var processB : neighborsA) {
                var neighborsB = topology.getNeighborProcesses(processB);
                assertTrue(neighborsB.contains(processA),
                    "Neighbor relationship should be symmetric: " + i + " <-> " + processA);
            }
        }
    }

    @Test
    void test8ProcessTopology_TotalEdgeCount() {
        // Given: 8-process 2x2x2 cube (topology)
        // A cube has 12 edges (4 on bottom, 4 on top, 4 vertical)
        var topology = new TestProcessTopology(8, 2);

        // Then: Total neighbor pairs should be 12 (each pair counted once)
        var totalNeighbors = 0;
        for (int i = 0; i < 8; i++) {
            var processId = topology.getProcessId(i);
            totalNeighbors += topology.getNeighborProcesses(processId).size();
        }

        // Each edge connects 2 processes, so total count is 2 * edges
        var edgeCount = totalNeighbors / 2;
        assertEquals(12, edgeCount, "Should have 12 edges in 2x2x2 cube topology");
    }
}
