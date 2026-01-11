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
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages 4-bubble grid topology at Tetree Level 1.
 * <p>
 * Grid Structure:
 * - 4 bubbles total (one per committee member)
 * - Each bubble owns 2 tetrahedra at L1 (8 total tetrahedra)
 * - Mapping: Bubble 0: Tet 0-1, Bubble 1: Tet 2-3, Bubble 2: Tet 4-5, Bubble 3: Tet 6-7
 * <p>
 * Committee Configuration:
 * - 4 nodes (one coordinator per bubble)
 * - BFT tolerance t=1
 * - Quorum q=3
 * <p>
 * Neighbor Topology:
 * Tetrahedral adjacency at L1 determines which bubbles can directly migrate.
 * Bubbles sharing a tetrahedral face are neighbors.
 * <p>
 * THREAD SAFETY:
 * Immutable topology after construction. Bubbles themselves are thread-safe.
 * <p>
 * Phase 8B Day 1: Tetree Bubble Topology Setup
 *
 * @author hal.hildebrand
 */
public class ConsensusBubbleGrid {

    private static final Logger log = LoggerFactory.getLogger(ConsensusBubbleGrid.class);

    private final Digest viewId;
    private final Context<?> context;
    private final List<ConsensusBubbleNode> bubbles;
    private final Map<TetreeKey<?>, Digest> tetToNode;
    private final Map<TetreeKey<?>, Integer> tetToChildIndex;  // Track child index for testing
    private final List<Set<Integer>> neighborTopology;

    /**
     * Create ConsensusBubbleGrid.
     *
     * @param viewId  Current view ID
     * @param context Delos context for committee coordination
     * @param nodeIds List of exactly 4 node IDs (one per bubble)
     * @throws IllegalArgumentException if nodeIds.size() != 4
     */
    public ConsensusBubbleGrid(Digest viewId, Context<?> context, List<Digest> nodeIds) {
        this.viewId = Objects.requireNonNull(viewId, "viewId must not be null");
        this.context = context; // Context can be null for testing
        Objects.requireNonNull(nodeIds, "nodeIds must not be null");

        if (nodeIds.size() != 4) {
            throw new IllegalArgumentException("Expected exactly 4 node IDs, got " + nodeIds.size());
        }

        // Initialize bubbles and topology
        this.bubbles = new ArrayList<>(4);
        this.tetToNode = new HashMap<>();
        this.tetToChildIndex = new HashMap<>();
        this.neighborTopology = initializeNeighborTopology();

        // Create 4 bubbles with L1 tetrahedra assignments
        var root = Tet.ROOT_TET.toTet();
        for (int i = 0; i < 4; i++) {
            // Each bubble gets 2 consecutive tetrahedra: Bubble i -> Tet 2i, 2i+1
            // These are child indices 0-7 at level 1
            var childIndex0 = i * 2;
            var childIndex1 = i * 2 + 1;
            var tet0 = root.child(childIndex0).tmIndex();
            var tet1 = root.child(childIndex1).tmIndex();
            var tetrahedra = new TetreeKey<?>[] { tet0, tet1 };

            // Create bubble node
            var bubble = new ConsensusBubbleNode(i, tetrahedra, context, viewId, nodeIds);
            bubbles.add(bubble);

            // Map tetrahedra to owning node and child index
            tetToNode.put(tet0, nodeIds.get(i));
            tetToNode.put(tet1, nodeIds.get(i));
            tetToChildIndex.put(tet0, childIndex0);
            tetToChildIndex.put(tet1, childIndex1);

            log.debug("Created bubble {}: node={}, tets=[child {}, child {}]", i, nodeIds.get(i),
                     childIndex0, childIndex1);
        }

        log.info("Initialized 4-bubble grid at Tetree L1: viewId={}, committee={}",
                viewId, nodeIds.size());
    }

    /**
     * Get bubble by index.
     *
     * @param index Bubble index (0-3)
     * @return ConsensusBubbleNode
     * @throws IllegalArgumentException if index out of range
     */
    public ConsensusBubbleNode getBubble(int index) {
        if (index < 0 || index >= 4) {
            throw new IllegalArgumentException("Bubble index must be 0-3, got " + index);
        }
        return bubbles.get(index);
    }

    /**
     * Get all bubbles.
     *
     * @return unmodifiable list of bubbles
     */
    public List<ConsensusBubbleNode> getBubbles() {
        return Collections.unmodifiableList(bubbles);
    }

    /**
     * Get neighbor bubbles for given bubble.
     * <p>
     * Neighbors are determined by tetrahedral face adjacency at L1.
     *
     * @param bubbleIndex Bubble index (0-3)
     * @return list of neighbor bubble indices
     * @throws IllegalArgumentException if index out of range
     */
    public List<Integer> getNeighborBubbles(int bubbleIndex) {
        if (bubbleIndex < 0 || bubbleIndex >= 4) {
            throw new IllegalArgumentException("Bubble index must be 0-3, got " + bubbleIndex);
        }
        return new ArrayList<>(neighborTopology.get(bubbleIndex));
    }

    /**
     * Check if given node is coordinator for given bubble.
     * <p>
     * Current implementation: bubble i is coordinated by nodeIds[i].
     *
     * @param bubbleIndex Bubble index (0-3)
     * @param nodeId      Node ID to check
     * @return true if nodeId is coordinator for bubble
     */
    public boolean isBubbleCoordinator(int bubbleIndex, Digest nodeId) {
        if (bubbleIndex < 0 || bubbleIndex >= 4) {
            throw new IllegalArgumentException("Bubble index must be 0-3, got " + bubbleIndex);
        }
        var bubble = bubbles.get(bubbleIndex);
        // First committee member for this bubble is the coordinator
        // In our mapping, the committee member at position bubbleIndex owns that bubble
        return bubble.getCommitteeMembers().stream()
                .skip(bubbleIndex)
                .findFirst()
                .map(id -> id.equals(nodeId))
                .orElse(false);
    }

    /**
     * Get all committee members.
     *
     * @return set of committee member node IDs
     */
    public Set<Digest> getCommitteeMembers() {
        // All bubbles have same committee members, return from first bubble
        return bubbles.get(0).getCommitteeMembers();
    }

    /**
     * Get node ID owning tetrahedron.
     *
     * @param key TetreeKey to lookup
     * @return node ID owning this tetrahedron, or null if not found
     */
    public Digest getBubbleAtTetrahedron(TetreeKey<?> key) {
        return tetToNode.get(key);
    }

    /**
     * Check if migration between bubbles is allowed.
     * <p>
     * Migration allowed if:
     * - Same bubble (intra-bubble, always allowed)
     * - Adjacent bubbles (neighbors in tetrahedral topology)
     *
     * @param fromBubble Source bubble index
     * @param toBubble   Target bubble index
     * @return true if migration allowed
     */
    public boolean canMigrateBetweenBubbles(int fromBubble, int toBubble) {
        if (fromBubble < 0 || fromBubble >= 4 || toBubble < 0 || toBubble >= 4) {
            return false;
        }

        // Same bubble always allowed
        if (fromBubble == toBubble) {
            return true;
        }

        // Check if bubbles are neighbors
        return neighborTopology.get(fromBubble).contains(toBubble);
    }

    /**
     * Initialize neighbor topology for L1 tetrahedral grid.
     * <p>
     * At L1, we have 8 tetrahedra (types 0-7) from subdividing the root tetrahedron.
     * Bubbles are assigned pairs: 0:(0,1), 1:(2,3), 2:(4,5), 3:(6,7)
     * <p>
     * Tetrahedral face adjacency determines neighbors. For L1 subdivision:
     * - All bubbles share the central vertex (root)
     * - Adjacent types share faces based on Bey subdivision pattern
     * <p>
     * Simplified neighbor graph for 4-bubble L1 grid:
     * - Bubble 0 neighbors: 1, 2, 3 (central connectivity)
     * - Bubble 1 neighbors: 0, 2, 3
     * - Bubble 2 neighbors: 0, 1, 3
     * - Bubble 3 neighbors: 0, 1, 2
     * <p>
     * All bubbles are neighbors at L1 due to shared central vertex.
     *
     * @return list of neighbor sets (one per bubble)
     */
    private List<Set<Integer>> initializeNeighborTopology() {
        var topology = new ArrayList<Set<Integer>>(4);

        // At L1, all 8 tetrahedra share the central vertex
        // This means all 4 bubbles are neighbors
        for (int i = 0; i < 4; i++) {
            var neighbors = new HashSet<Integer>();
            for (int j = 0; j < 4; j++) {
                if (i != j) {
                    neighbors.add(j);
                }
            }
            topology.add(neighbors);
        }

        return topology;
    }

    /**
     * Helper to extract tetrahedral child index from TetreeKey at L1.
     * Uses the tracked mapping from tetrahedra to child indices.
     *
     * @param key TetreeKey at level 1
     * @return child index (0-7)
     */
    private int getTetIndex(TetreeKey<?> key) {
        if (key.getLevel() != 1) {
            throw new IllegalArgumentException("Expected level 1 key, got level " + key.getLevel());
        }
        // Look up the child index from our mapping
        var childIndex = tetToChildIndex.get(key);
        if (childIndex == null) {
            throw new IllegalArgumentException("No child index mapping for key: " + key);
        }
        return childIndex;
    }
}
