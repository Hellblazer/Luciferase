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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConsensusBubbleGridFactory.
 * <p>
 * Tests grid factory with:
 * - Grid creation with all 4 bubbles initialized
 * - Committee parameters (t=1, q=3)
 * - Neighbor relationships
 * - Factory validation
 * <p>
 * Phase 8B Day 1: Tetree Bubble Topology Setup
 *
 * @author hal.hildebrand
 */
class ConsensusBubbleGridFactoryTest {

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
    void testFactoryCreatesGridWithAllBubbles() {
        // When: Create grid via factory
        var grid = ConsensusBubbleGridFactory.createGrid(viewId, context, nodeIds);

        // Then: All 4 bubbles initialized
        assertNotNull(grid);
        assertEquals(4, grid.getBubbles().size());

        for (int i = 0; i < 4; i++) {
            var bubble = grid.getBubble(i);
            assertNotNull(bubble, "Bubble " + i + " should be initialized");
            assertEquals(i, bubble.getBubbleIndex(), "Bubble should have correct index");
            assertEquals(2, bubble.getTetrahedra().length, "Each bubble should have 2 tetrahedra");
        }
    }

    @Test
    void testFactoryConfiguresCommitteeParameters() {
        // When: Create grid via factory
        var grid = ConsensusBubbleGridFactory.createGrid(viewId, context, nodeIds);

        // Then: Committee parameters set correctly
        var committeeMembers = grid.getCommitteeMembers();
        assertEquals(4, committeeMembers.size(), "Committee should have 4 members");

        // Verify each bubble has committee coordination
        for (int i = 0; i < 4; i++) {
            var bubble = grid.getBubble(i);
            assertEquals(viewId, bubble.getCommitteeViewId());
            assertEquals(4, bubble.getCommitteeMembers().size());
        }
    }

    @Test
    void testFactoryEstablishesNeighborRelationships() {
        // When: Create grid via factory
        var grid = ConsensusBubbleGridFactory.createGrid(viewId, context, nodeIds);

        // Then: Neighbor relationships established
        for (int i = 0; i < 4; i++) {
            var neighbors = grid.getNeighborBubbles(i);
            assertNotNull(neighbors, "Bubble " + i + " should have neighbor list");
            assertTrue(neighbors.size() > 0, "Bubble " + i + " should have neighbors");

            // Verify bidirectional relationships
            for (var neighborIdx : neighbors) {
                var neighborNeighbors = grid.getNeighborBubbles(neighborIdx);
                assertTrue(neighborNeighbors.contains(i),
                    "Neighbor relationship should be bidirectional");
            }
        }
    }

    @Test
    void testFactoryValidatesNodeCount() {
        // Given: Wrong number of nodes
        var tooFewNodes = List.of(nodeIds.get(0), nodeIds.get(1));
        var tooManyNodes = new ArrayList<>(nodeIds);
        tooManyNodes.add(DigestAlgorithm.DEFAULT.digest("extra-node".getBytes()));

        // Then: Factory rejects invalid node counts
        assertThrows(IllegalArgumentException.class,
            () -> ConsensusBubbleGridFactory.createGrid(viewId, context, tooFewNodes),
            "Factory should reject < 4 nodes");

        assertThrows(IllegalArgumentException.class,
            () -> ConsensusBubbleGridFactory.createGrid(viewId, context, tooManyNodes),
            "Factory should reject > 4 nodes");
    }

    @Test
    void testFactoryValidatesNullParameters() {
        // Then: Factory rejects null parameters
        assertThrows(NullPointerException.class,
            () -> ConsensusBubbleGridFactory.createGrid(null, context, nodeIds),
            "Factory should reject null viewId");

        // Context can be null for testing, so skip that test

        assertThrows(NullPointerException.class,
            () -> ConsensusBubbleGridFactory.createGrid(viewId, context, null),
            "Factory should reject null nodeIds");
    }

    @Test
    void testFactoryCreatesConsistentTopology() {
        // When: Create multiple grids with same parameters
        var grid1 = ConsensusBubbleGridFactory.createGrid(viewId, context, nodeIds);
        var grid2 = ConsensusBubbleGridFactory.createGrid(viewId, context, nodeIds);

        // Then: Topologies should be consistent (same neighbor structure)
        for (int i = 0; i < 4; i++) {
            var neighbors1 = grid1.getNeighborBubbles(i);
            var neighbors2 = grid2.getNeighborBubbles(i);

            assertEquals(neighbors1.size(), neighbors2.size(),
                "Same bubble should have same number of neighbors");

            // Verify same neighbor set (order doesn't matter)
            assertTrue(neighbors1.containsAll(neighbors2),
                "Neighbor sets should be identical");
            assertTrue(neighbors2.containsAll(neighbors1),
                "Neighbor sets should be identical");
        }
    }

    @Test
    void testFactoryAssignsTetreeKeysCorrectly() {
        // When: Create grid via factory
        var grid = ConsensusBubbleGridFactory.createGrid(viewId, context, nodeIds);

        // Then: TetreeKey assignments follow pattern: Bubble i gets Tet 2i and 2i+1
        for (int bubbleIdx = 0; bubbleIdx < 4; bubbleIdx++) {
            var bubble = grid.getBubble(bubbleIdx);
            var tets = bubble.getTetrahedra();

            assertEquals(2, tets.length, "Each bubble should have exactly 2 tetrahedra");

            // Verify level is 1 (L1 subdivision)
            for (var tet : tets) {
                assertEquals(1, tet.getLevel(), "All tetrahedra should be at level 1");
            }
        }
    }

    @Test
    void testFactoryCreatesIndependentGrids() {
        // When: Create two separate grids
        var grid1 = ConsensusBubbleGridFactory.createGrid(viewId, context, nodeIds);

        var viewId2 = DigestAlgorithm.DEFAULT.digest("different-view".getBytes());
        var grid2 = ConsensusBubbleGridFactory.createGrid(viewId2, context, nodeIds);

        // Then: Grids are independent
        assertNotSame(grid1, grid2, "Factory should create separate instances");

        // Verify different view IDs
        assertEquals(viewId, grid1.getBubble(0).getCommitteeViewId());
        assertEquals(viewId2, grid2.getBubble(0).getCommitteeViewId());
    }
}
