/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConsensusBubbleGrid.
 * <p>
 * Tests 4-bubble topology at Tetree Level 1 with:
 * - Grid creation with 4 bubbles
 * - TetreeKey assignments (Tet 0-1, 2-3, 4-5, 6-7)
 * - Neighbor discovery
 * - Committee membership tracking
 * - Topology queries
 * <p>
 * Phase 8B Day 1: Tetree Bubble Topology Setup
 *
 * @author hal.hildebrand
 */
class ConsensusBubbleGridTest {

    private Digest viewId;
    private Context<?> context;
    private List<Digest> nodeIds;

    @BeforeEach
    void setUp() {
        viewId = DigestAlgorithm.DEFAULT.digest("test-view".getBytes());

        // Create 4 node IDs for committee
        nodeIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            nodeIds.add(DigestAlgorithm.DEFAULT.digest(("node-" + i).getBytes()));
        }

        // Create context with 4 members (t=1, q=3)
        context = null; // Context not needed for these tests
    }

    @Test
    void testGridCreationWith4Bubbles() {
        // When: Create grid
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        // Then: 4 bubbles created
        assertNotNull(grid);
        assertEquals(4, grid.getBubbles().size(), "Grid should have exactly 4 bubbles");

        // Verify each bubble is non-null
        for (int i = 0; i < 4; i++) {
            assertNotNull(grid.getBubble(i), "Bubble " + i + " should not be null");
        }
    }

    @Test
    void testTetreeKeyAssignmentsCorrect() {
        // When: Create grid
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        // Then: Each bubble has 2 distinct tetrahedra at level 1
        for (int bubbleIdx = 0; bubbleIdx < 4; bubbleIdx++) {
            var bubble = grid.getBubble(bubbleIdx);
            var tets = bubble.getTetrahedra();

            assertEquals(2, tets.length, "Bubble " + bubbleIdx + " should have 2 tetrahedra");
            assertEquals(1, tets[0].getLevel(), "Tet 0 should be at level 1");
            assertEquals(1, tets[1].getLevel(), "Tet 1 should be at level 1");

            // Tetrahedra should be distinct (different tm-indices)
            assertNotEquals(tets[0], tets[1],
                "Bubble " + bubbleIdx + " should have 2 distinct tetrahedra");
        }
    }

    @Test
    void testNeighborDiscoveryFunctional() {
        // When: Create grid
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        // Then: Each bubble should have neighbors
        // In tetrahedral topology at L1, bubbles share faces
        for (int i = 0; i < 4; i++) {
            var neighbors = grid.getNeighborBubbles(i);
            assertNotNull(neighbors, "Bubble " + i + " should have neighbor list");
            assertTrue(neighbors.size() > 0, "Bubble " + i + " should have at least 1 neighbor");
            assertTrue(neighbors.size() <= 3, "Bubble " + i + " should have at most 3 neighbors");

            // Verify neighbor indices are valid
            for (var neighborIdx : neighbors) {
                assertTrue(neighborIdx >= 0 && neighborIdx < 4,
                    "Neighbor index should be in range [0,3]");
                assertNotEquals(i, neighborIdx, "Bubble should not be its own neighbor");
            }
        }
    }

    @Test
    void testCommitteeMembershipTracking() {
        // When: Create grid
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        // Then: Committee members tracked correctly
        var committeeMembers = grid.getCommitteeMembers();
        assertNotNull(committeeMembers);
        assertEquals(4, committeeMembers.size(), "Committee should have 4 members");

        // Verify each node ID is in committee
        for (var nodeId : nodeIds) {
            assertTrue(committeeMembers.contains(nodeId),
                "Committee should contain node " + nodeId);
        }
    }

    @Test
    void testBubbleCoordinatorIdentification() {
        // When: Create grid
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        // Then: Each bubble has exactly 1 coordinator
        for (int bubbleIdx = 0; bubbleIdx < 4; bubbleIdx++) {
            var foundCoordinator = false;
            for (var nodeId : nodeIds) {
                if (grid.isBubbleCoordinator(bubbleIdx, nodeId)) {
                    assertFalse(foundCoordinator,
                        "Bubble " + bubbleIdx + " should have only 1 coordinator");
                    foundCoordinator = true;
                }
            }
            assertTrue(foundCoordinator,
                "Bubble " + bubbleIdx + " should have a coordinator");
        }
    }

    @Test
    void testTopologyQueryGetBubbleAtTetrahedron() {
        // When: Create grid
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        // Then: Can lookup which bubble owns each tetrahedron
        // Test a few key tetrahedra
        var bubble0Node = grid.getBubble(0);
        var tet0 = bubble0Node.getTetrahedra()[0];
        var owningNode = grid.getBubbleAtTetrahedron(tet0);
        assertNotNull(owningNode, "Should find owning node for tet 0");

        var bubble3Node = grid.getBubble(3);
        var tet7 = bubble3Node.getTetrahedra()[1];
        owningNode = grid.getBubbleAtTetrahedron(tet7);
        assertNotNull(owningNode, "Should find owning node for tet 7");
    }

    @Test
    void testCanMigrateBetweenBubbles() {
        // When: Create grid
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        // Then: Can determine if migration is allowed between bubbles
        // Adjacent bubbles should allow migration
        var neighbors0 = grid.getNeighborBubbles(0);
        if (!neighbors0.isEmpty()) {
            var neighbor = neighbors0.get(0);
            assertTrue(grid.canMigrateBetweenBubbles(0, neighbor),
                "Should allow migration between adjacent bubbles");
        }

        // Same bubble should allow migration (intra-bubble)
        assertTrue(grid.canMigrateBetweenBubbles(0, 0),
            "Should allow intra-bubble migration");
    }

    @Test
    void testInvalidBubbleIndexThrowsException() {
        // When: Create grid
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        // Then: Invalid bubble index throws exception
        assertThrows(IllegalArgumentException.class, () -> grid.getBubble(-1),
            "Negative bubble index should throw exception");
        assertThrows(IllegalArgumentException.class, () -> grid.getBubble(4),
            "Bubble index >= 4 should throw exception");
    }

    @Test
    void testGridCreationRequiresFourNodes() {
        // Given: Wrong number of nodes
        var tooFewNodes = List.of(nodeIds.get(0), nodeIds.get(1));
        var tooManyNodes = new ArrayList<>(nodeIds);
        tooManyNodes.add(DigestAlgorithm.DEFAULT.digest("extra-node".getBytes()));

        // Then: Should throw exception
        assertThrows(IllegalArgumentException.class,
            () -> new ConsensusBubbleGrid(viewId, context, tooFewNodes),
            "Should reject grid with < 4 nodes");
        assertThrows(IllegalArgumentException.class,
            () -> new ConsensusBubbleGrid(viewId, context, tooManyNodes),
            "Should reject grid with > 4 nodes");
    }

}
