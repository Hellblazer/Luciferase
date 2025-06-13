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

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Set;

/**
 * Strategy interface for tree balancing operations. Defines when and how
 * nodes should be split, merged, or rebalanced.
 *
 * @param <ID> The type of EntityID used for entity identification
 * 
 * @author hal.hildebrand
 */
public interface TreeBalancingStrategy<ID extends EntityID> {
    
    /**
     * Check if a node should be split based on its entity count.
     * 
     * @param entityCount current number of entities in the node
     * @param nodeLevel the level of the node in the tree
     * @param maxEntitiesPerNode maximum allowed entities per node
     * @return true if the node should be split
     */
    boolean shouldSplit(int entityCount, byte nodeLevel, int maxEntitiesPerNode);
    
    /**
     * Check if a node should be merged with siblings or parent.
     * 
     * @param entityCount current number of entities in the node
     * @param nodeLevel the level of the node in the tree
     * @param siblingEntityCounts entity counts of sibling nodes
     * @return true if the node should be merged
     */
    boolean shouldMerge(int entityCount, byte nodeLevel, int[] siblingEntityCounts);
    
    /**
     * Calculate the merge threshold for a given level.
     * Nodes with fewer entities than this threshold are candidates for merging.
     * 
     * @param nodeLevel the level of the node
     * @param maxEntitiesPerNode maximum allowed entities per node
     * @return merge threshold
     */
    int getMergeThreshold(byte nodeLevel, int maxEntitiesPerNode);
    
    /**
     * Calculate the split threshold for a given level.
     * Nodes with more entities than this threshold should be split.
     * 
     * @param nodeLevel the level of the node
     * @param maxEntitiesPerNode maximum allowed entities per node
     * @return split threshold
     */
    int getSplitThreshold(byte nodeLevel, int maxEntitiesPerNode);
    
    /**
     * Determine how to distribute entities among child nodes during a split.
     * 
     * @param entities the entities to distribute
     * @param childCount number of child nodes (8 for octree, variable for tetree)
     * @return array of entity sets, one for each child
     */
    Set<ID>[] distributeEntities(Set<ID> entities, int childCount);
    
    /**
     * Check if rebalancing should be triggered for the entire tree.
     * 
     * @param stats current tree statistics
     * @return true if tree-wide rebalancing should occur
     */
    boolean shouldRebalanceTree(TreeBalancingStats stats);
    
    /**
     * Get the minimum time between rebalancing operations (in milliseconds).
     * 
     * @return minimum rebalancing interval
     */
    long getMinRebalancingInterval();
    
    /**
     * Statistics for tree balancing decisions
     */
    record TreeBalancingStats(
        int totalNodes,
        int underpopulatedNodes,
        int overpopulatedNodes,
        int emptyNodes,
        int maxDepth,
        double averageEntityLoad,
        double loadVariance
    ) {
        /**
         * Calculate the load imbalance factor (0.0 = perfectly balanced, 1.0 = highly imbalanced)
         */
        public double imbalanceFactor() {
            if (averageEntityLoad == 0) return 0;
            return Math.sqrt(loadVariance) / averageEntityLoad;
        }
        
        /**
         * Get the percentage of underpopulated nodes
         */
        public double underpopulatedPercentage() {
            return totalNodes > 0 ? (double) underpopulatedNodes / totalNodes : 0;
        }
        
        /**
         * Get the percentage of overpopulated nodes
         */
        public double overpopulatedPercentage() {
            return totalNodes > 0 ? (double) overpopulatedNodes / totalNodes : 0;
        }
    }
}