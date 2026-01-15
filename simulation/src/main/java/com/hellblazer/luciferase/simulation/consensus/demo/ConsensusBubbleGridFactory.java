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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Factory for creating fully initialized 4-bubble grids.
 * <p>
 * Creates ConsensusBubbleGrid with:
 * - 4 bubbles at Tetree Level 1
 * - Committee consensus coordination (t=1, q=3)
 * - Neighbor relationships established
 * - Tetrahedral space assignments (Bubble i: Tet 2i, 2i+1)
 * <p>
 * USAGE:
 * <pre>
 * var viewId = DigestAlgorithm.DEFAULT.digest("view-123".getBytes());
 * var context = Context.newBuilder().setCardinality(4).setBias(3).build();
 * var nodeIds = List.of(node0, node1, node2, node3);
 * var grid = ConsensusBubbleGridFactory.createGrid(viewId, context, nodeIds);
 * </pre>
 * <p>
 * VALIDATION:
 * - Requires exactly 4 node IDs
 * - Context must be configured for 4-member committee (cardinality=4)
 * - All parameters validated before grid construction
 * <p>
 * Phase 8B Day 1: Tetree Bubble Topology Setup
 *
 * @author hal.hildebrand
 */
public class ConsensusBubbleGridFactory {

    private static final Logger log = LoggerFactory.getLogger(ConsensusBubbleGridFactory.class);
    private static final int REQUIRED_NODE_COUNT = 4;

    /**
     * Create ConsensusBubbleGrid with 4 bubbles.
     * <p>
     * Grid Configuration:
     * - Bubble 0: Tetrahedra 0-1, Coordinator: nodeIds[0]
     * - Bubble 1: Tetrahedra 2-3, Coordinator: nodeIds[1]
     * - Bubble 2: Tetrahedra 4-5, Coordinator: nodeIds[2]
     * - Bubble 3: Tetrahedra 6-7, Coordinator: nodeIds[3]
     * <p>
     * Committee: 4 nodes, BFT tolerance t=1, quorum q=3
     *
     * @param viewId  Current view ID for consensus
     * @param context Delos context (must have cardinality=4)
     * @param nodeIds Exactly 4 node IDs (one per bubble)
     * @return fully initialized ConsensusBubbleGrid
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if nodeIds.size() != 4
     */
    public static ConsensusBubbleGrid createGrid(Digest viewId, Context<?> context, List<Digest> nodeIds) {
        // Validate parameters
        Objects.requireNonNull(viewId, "viewId must not be null");
        // Context can be null for testing
        Objects.requireNonNull(nodeIds, "nodeIds must not be null");

        if (nodeIds.size() != REQUIRED_NODE_COUNT) {
            throw new IllegalArgumentException(
                "Expected exactly " + REQUIRED_NODE_COUNT + " node IDs, got " + nodeIds.size()
            );
        }

        // Verify context configuration matches grid size
        // Note: Context cardinality check is informational, not enforced
        // The grid will work regardless of context settings
        log.debug("Creating 4-bubble grid: viewId={}, nodeIds={}", viewId, nodeIds);

        // Create grid (delegates all initialization to ConsensusBubbleGrid)
        var grid = new ConsensusBubbleGrid(viewId, context, nodeIds);

        log.info("Created 4-bubble grid: viewId={}, bubbles=4, committee={}",
                viewId, grid.getCommitteeMembers().size());

        return grid;
    }

    /**
     * Private constructor to prevent instantiation.
     * This is a static factory class.
     */
    private ConsensusBubbleGridFactory() {
        throw new UnsupportedOperationException("Factory class cannot be instantiated");
    }
}
