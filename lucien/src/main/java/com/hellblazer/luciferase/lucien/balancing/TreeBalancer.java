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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.List;
import java.util.Set;

/**
 * Interface for tree balancing operations. Provides methods to split, merge, and rebalance nodes in the spatial tree.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public interface TreeBalancer<Key extends SpatialKey<Key>, ID extends EntityID> {

    /**
     * Check if a specific node needs balancing.
     *
     * @param nodeIndex the spatial index of the node
     * @return balancing action needed, or NONE
     */
    BalancingAction checkNodeBalance(Key nodeIndex);

    /**
     * Get current tree balancing statistics.
     *
     * @return tree balancing statistics
     */
    TreeBalancingStrategy.TreeBalancingStats getBalancingStats();

    /**
     * Check if automatic balancing is enabled.
     *
     * @return true if automatic balancing is enabled
     */
    boolean isAutoBalancingEnabled();

    /**
     * Merge underpopulated nodes into their parent.
     *
     * @param nodeIndices the spatial indices of nodes to merge
     * @param parentIndex the parent node index
     * @return true if merge was successful
     */
    boolean mergeNodes(Set<Key> nodeIndices, Key parentIndex);

    /**
     * Rebalance a subtree starting from the given node.
     *
     * <p>Implementations must either perform real rebalancing work or signal non-support
     * via an exception. A return value of 0 means zero nodes were modified (either the
     * subtree was already balanced, or the subtree rooted at {@code rootNodeIndex} did not
     * exist). Implementations must NOT return 0 as a silent no-op when the operation is
     * unsupported — throw {@link UnsupportedOperationException} instead.
     *
     * @param rootNodeIndex the root of the subtree to rebalance
     * @return number of nodes modified (0 if the subtree is already balanced or empty;
     *         never 0 as a silent not-supported sentinel)
     */
    int rebalanceSubtree(Key rootNodeIndex);

    /**
     * Perform a full tree rebalancing operation.
     *
     * @return rebalancing result
     */
    RebalancingResult rebalanceTree();

    /**
     * Enable or disable automatic balancing.
     *
     * @param enabled true to enable automatic balancing
     */
    void setAutoBalancingEnabled(boolean enabled);

    /**
     * Set the balancing strategy.
     *
     * @param strategy the balancing strategy to use
     */
    void setBalancingStrategy(TreeBalancingStrategy<ID> strategy);

    /**
     * Split an overpopulated node into child nodes.
     *
     * @param nodeIndex the spatial index of the node to split
     * @param nodeLevel the level of the node
     * @return list of created child node indices
     */
    List<Key> splitNode(Key nodeIndex, byte nodeLevel);

    /**
     * Types of balancing actions
     */
    enum BalancingAction {
        NONE, SPLIT, MERGE, REDISTRIBUTE
    }

    /**
     * Result of a rebalancing operation.
     *
     * <p>Convention: {@code successful=true} with {@link #hasChanges()}{@code ==false} means either the tree was
     * already balanced or balancing is not applicable to the structure (e.g. a flat {@code SFCArrayIndex} backed by
     * {@link NoOpTreeBalancer}). {@code successful=false} is reserved for an attempted-but-failed rebalance.
     */
    record RebalancingResult(int nodesCreated, int nodesRemoved, int nodesMerged, int nodesSplit, int entitiesRelocated,
                             long timeTaken, boolean successful) {
        /**
         * Check if any changes were made
         */
        public boolean hasChanges() {
            return totalModifications() > 0 || entitiesRelocated > 0;
        }

        /**
         * Get total number of nodes modified
         */
        public int totalModifications() {
            return nodesCreated + nodesRemoved + nodesMerged + nodesSplit;
        }
    }
}
