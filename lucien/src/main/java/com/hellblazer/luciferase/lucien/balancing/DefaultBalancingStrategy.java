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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Default implementation of tree balancing strategy with configurable thresholds.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public class DefaultBalancingStrategy<ID extends EntityID> implements TreeBalancingStrategy<ID> {

    private final double mergeFactor;      // Factor of max entities for merge threshold
    private final double splitFactor;      // Factor of max entities for split threshold
    private final double imbalanceThreshold; // Threshold for tree-wide rebalancing
    private final long   minRebalancingInterval;

    /**
     * Create with default settings.
     */
    public DefaultBalancingStrategy() {
        this(0.25, 0.9, 0.3, 60000); // 25% merge, 90% split, 30% imbalance, 1 minute
    }

    /**
     * Create with custom settings.
     *
     * @param mergeFactor            fraction of max entities below which to merge (0.0-1.0)
     * @param splitFactor            fraction of max entities above which to split (0.0-1.0)
     * @param imbalanceThreshold     imbalance factor above which to rebalance (0.0-1.0)
     * @param minRebalancingInterval minimum time between rebalances in milliseconds
     */
    public DefaultBalancingStrategy(double mergeFactor, double splitFactor, double imbalanceThreshold,
                                    long minRebalancingInterval) {
        if (mergeFactor < 0 || mergeFactor > 1) {
            throw new IllegalArgumentException("Merge factor must be between 0 and 1");
        }
        if (splitFactor < 0 || splitFactor > 1) {
            throw new IllegalArgumentException("Split factor must be between 0 and 1");
        }
        if (mergeFactor >= splitFactor) {
            throw new IllegalArgumentException("Merge factor must be less than split factor");
        }

        this.mergeFactor = mergeFactor;
        this.splitFactor = splitFactor;
        this.imbalanceThreshold = imbalanceThreshold;
        this.minRebalancingInterval = minRebalancingInterval;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<ID>[] distributeEntities(Set<ID> entities, int childCount) {
        Set<ID>[] distribution = new Set[childCount];
        for (int i = 0; i < childCount; i++) {
            distribution[i] = new HashSet<>();
        }

        // Simple round-robin distribution
        // In practice, this should use spatial information
        List<ID> entityList = new ArrayList<>(entities);
        for (int i = 0; i < entityList.size(); i++) {
            distribution[i % childCount].add(entityList.get(i));
        }

        return distribution;
    }

    @Override
    public int getMergeThreshold(byte nodeLevel, int maxEntitiesPerNode) {
        // Deeper nodes have lower merge thresholds
        double levelFactor = 1.0 - (nodeLevel * 0.02); // Decrease by 2% per level
        levelFactor = Math.max(0.5, levelFactor); // Minimum 50% of base threshold

        return (int) (maxEntitiesPerNode * mergeFactor * levelFactor);
    }

    @Override
    public long getMinRebalancingInterval() {
        return minRebalancingInterval;
    }

    @Override
    public int getSplitThreshold(byte nodeLevel, int maxEntitiesPerNode) {
        // Deeper nodes split earlier to maintain balance
        double levelFactor = 1.0 - (nodeLevel * 0.01); // Decrease by 1% per level
        levelFactor = Math.max(0.7, levelFactor); // Minimum 70% of base threshold

        return (int) (maxEntitiesPerNode * splitFactor * levelFactor);
    }

    @Override
    public boolean shouldMerge(int entityCount, byte nodeLevel, int[] siblingEntityCounts) {
        // Don't merge root node
        if (nodeLevel == 0) {
            return false;
        }

        // Calculate total entities if merged
        int totalEntities = entityCount;
        for (int siblingCount : siblingEntityCounts) {
            totalEntities += siblingCount;
        }

        // Get parent's max capacity
        int parentMaxEntities = getMaxEntitiesForLevel((byte) (nodeLevel - 1));

        // Merge if combined entities would fit comfortably in parent
        return totalEntities <= parentMaxEntities * splitFactor;
    }

    @Override
    public boolean shouldRebalanceTree(TreeBalancingStats stats) {
        // Check various conditions for rebalancing

        // High imbalance factor
        if (stats.imbalanceFactor() > imbalanceThreshold) {
            return true;
        }

        // Too many underpopulated nodes
        if (stats.underpopulatedPercentage() > 0.5) { // More than 50% underpopulated
            return true;
        }

        // Too many overpopulated nodes
        if (stats.overpopulatedPercentage() > 0.2) { // More than 20% overpopulated
            return true;
        }

        // Too many empty nodes
        double emptyPercentage = stats.totalNodes() > 0 ? (double) stats.emptyNodes() / stats.totalNodes() : 0;
        // More than 30% empty
        return emptyPercentage > 0.3;
    }

    @Override
    public boolean shouldSplit(int entityCount, byte nodeLevel, int maxEntitiesPerNode) {
        // Don't split if at max depth
        if (nodeLevel >= 20) { // Typical max depth
            return false;
        }

        int threshold = getSplitThreshold(nodeLevel, maxEntitiesPerNode);
        return entityCount > threshold;
    }

    /**
     * Helper to calculate max entities for a given level. Higher levels (closer to root) can hold more entities.
     */
    private int getMaxEntitiesForLevel(byte level) {
        // This is a simplified calculation
        // In practice, this would be based on the specific tree implementation
        return 100; // Use a fixed value for now
    }
}
