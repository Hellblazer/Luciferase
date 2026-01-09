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
 * Tests for TestProcessTopology 2D grid layouts.
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 * <p>
 * Verifies:
 * - 4-process 2x2 grid topology
 * - 8-process 4x2 grid topology
 * - Correct neighbor relationships
 * - Cross-process migration paths
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
        // Given: 4-process 2x2 grid
        var topology = new TestProcessTopology(4, 2);

        // Then: Each process should have 2 neighbors (2x2 grid = 4 corners)
        for (int i = 0; i < 4; i++) {
            var processId = topology.getProcessId(i);
            var neighbors = topology.getNeighborProcesses(processId);
            assertEquals(2, neighbors.size(), "Process " + i + " should have 2 neighbors in 2x2 grid");
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
        // Given: 8-process topology with 2 bubbles per process (4x2 grid)
        var topology = new TestProcessTopology(8, 2);

        // Then: Should have 16 bubbles total
        assertEquals(16, topology.getBubbleCount(), "Should have 16 bubbles (8 processes * 2 bubbles)");

        // And: Should have 8 process IDs
        assertEquals(8, topology.getProcessCount(), "Should have 8 processes");
    }

    @Test
    void test8ProcessTopology_NeighborDistribution() {
        // Given: 8-process 4x2 grid
        // P0 -- P1 -- P2 -- P3  (Row 0)
        // |     |     |     |
        // P4 -- P5 -- P6 -- P7  (Row 1)
        var topology = new TestProcessTopology(8, 2);

        // Then: Corner processes (0, 3, 4, 7) should have 2 neighbors
        for (int cornerIdx : new int[]{0, 3, 4, 7}) {
            var processId = topology.getProcessId(cornerIdx);
            var neighbors = topology.getNeighborProcesses(processId);
            assertEquals(2, neighbors.size(),
                "Corner process " + cornerIdx + " should have 2 neighbors, got: " + neighbors.size());
        }

        // And: Edge processes (1, 2, 5, 6) should have 3 neighbors
        for (int edgeIdx : new int[]{1, 2, 5, 6}) {
            var processId = topology.getProcessId(edgeIdx);
            var neighbors = topology.getNeighborProcesses(processId);
            assertEquals(3, neighbors.size(),
                "Edge process " + edgeIdx + " should have 3 neighbors, got: " + neighbors.size());
        }
    }

    @Test
    void test8ProcessTopology_HorizontalNeighbors() {
        // Given: 8-process 4x2 grid
        var topology = new TestProcessTopology(8, 2);

        var p0 = topology.getProcessId(0);
        var p1 = topology.getProcessId(1);
        var p2 = topology.getProcessId(2);
        var p3 = topology.getProcessId(3);

        // Then: Row 0 horizontal neighbors
        var neighbors0 = topology.getNeighborProcesses(p0);
        assertTrue(neighbors0.contains(p1), "P0 should be neighbor with P1 (horizontal)");

        var neighbors1 = topology.getNeighborProcesses(p1);
        assertTrue(neighbors1.contains(p0), "P1 should be neighbor with P0 (horizontal)");
        assertTrue(neighbors1.contains(p2), "P1 should be neighbor with P2 (horizontal)");

        var neighbors2 = topology.getNeighborProcesses(p2);
        assertTrue(neighbors2.contains(p1), "P2 should be neighbor with P1 (horizontal)");
        assertTrue(neighbors2.contains(p3), "P2 should be neighbor with P3 (horizontal)");

        var neighbors3 = topology.getNeighborProcesses(p3);
        assertTrue(neighbors3.contains(p2), "P3 should be neighbor with P2 (horizontal)");
    }

    @Test
    void test8ProcessTopology_VerticalNeighbors() {
        // Given: 8-process 4x2 grid
        var topology = new TestProcessTopology(8, 2);

        // Vertical relationships
        var p0 = topology.getProcessId(0);
        var p4 = topology.getProcessId(4);
        var p1 = topology.getProcessId(1);
        var p5 = topology.getProcessId(5);
        var p2 = topology.getProcessId(2);
        var p6 = topology.getProcessId(6);
        var p3 = topology.getProcessId(3);
        var p7 = topology.getProcessId(7);

        // Then: Vertical neighbors
        assertTrue(topology.getNeighborProcesses(p0).contains(p4), "P0 should be neighbor with P4 (vertical)");
        assertTrue(topology.getNeighborProcesses(p1).contains(p5), "P1 should be neighbor with P5 (vertical)");
        assertTrue(topology.getNeighborProcesses(p2).contains(p6), "P2 should be neighbor with P6 (vertical)");
        assertTrue(topology.getNeighborProcesses(p3).contains(p7), "P3 should be neighbor with P7 (vertical)");

        assertTrue(topology.getNeighborProcesses(p4).contains(p0), "P4 should be neighbor with P0 (vertical)");
        assertTrue(topology.getNeighborProcesses(p5).contains(p1), "P5 should be neighbor with P1 (vertical)");
        assertTrue(topology.getNeighborProcesses(p6).contains(p2), "P6 should be neighbor with P2 (vertical)");
        assertTrue(topology.getNeighborProcesses(p7).contains(p3), "P7 should be neighbor with P3 (vertical)");
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
        // Given: 8-process 4x2 grid
        var topology = new TestProcessTopology(8, 2);

        // Then: Total neighbor pairs should be 10 (each pair counted once)
        var totalNeighbors = 0;
        for (int i = 0; i < 8; i++) {
            var processId = topology.getProcessId(i);
            totalNeighbors += topology.getNeighborProcesses(processId).size();
        }

        // Each edge connects 2 processes, so total count is 2 * edges
        var edgeCount = totalNeighbors / 2;
        assertEquals(10, edgeCount, "Should have 10 edges in 4x2 grid topology");
    }
}
