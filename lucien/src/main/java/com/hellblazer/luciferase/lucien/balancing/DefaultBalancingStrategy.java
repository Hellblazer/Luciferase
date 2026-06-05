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
 * <p><b>Spatial locality warning</b>: {@link #distributeEntities} uses a simple round-robin
 * assignment that ignores entity positions. This means entities may be assigned to child nodes
 * that do not contain their spatial location, degrading range-query and k-NN performance after
 * a split. This is a known limitation of the DEFAULT strategy (see Luciferase-7wzml.108).
 * A spatially-correct implementation would require the entity positions or child-octant
 * information to be threaded through the {@link TreeBalancingStrategy} interface, which is a
 * follow-up scope item. Callers requiring spatial locality should override
 * {@link #distributeEntities} or provide an alternative {@link TreeBalancingStrategy}.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public class DefaultBalancingStrategy<ID extends EntityID> implements TreeBalancingStrategy<ID> {

    private static final int DEFAULT_MAX_ENTITIES_PER_NODE = 100;

    private final double mergeFactor;      // Factor of max entities for merge threshold
    private final double splitFactor;      // Factor of max entities for split threshold
    private final double imbalanceThreshold; // Threshold for tree-wide rebalancing
    private final long   minRebalancingInterval;
    private final int    maxEntitiesPerNode; // Configured capacity; drives getMaxEntitiesForLevel

    /**
     * Create with default settings.
     */
    public DefaultBalancingStrategy() {
        this(0.25, 0.9, 0.3, 60000, DEFAULT_MAX_ENTITIES_PER_NODE);
    }

    /**
     * Create with custom settings (uses {@value #DEFAULT_MAX_ENTITIES_PER_NODE} as default max entities per node).
     *
     * @param mergeFactor            fraction of max entities below which to merge (0.0-1.0)
     * @param splitFactor            fraction of max entities above which to split (0.0-1.0)
     * @param imbalanceThreshold     imbalance factor above which to rebalance (0.0-1.0)
     * @param minRebalancingInterval minimum time between rebalances in milliseconds
     */
    public DefaultBalancingStrategy(double mergeFactor, double splitFactor, double imbalanceThreshold,
                                    long minRebalancingInterval) {
        this(mergeFactor, splitFactor, imbalanceThreshold, minRebalancingInterval, DEFAULT_MAX_ENTITIES_PER_NODE);
    }

    /**
     * Create with custom settings and explicit capacity.
     *
     * @param mergeFactor            fraction of max entities below which to merge (0.0-1.0)
     * @param splitFactor            fraction of max entities above which to split (0.0-1.0)
     * @param imbalanceThreshold     imbalance factor above which to rebalance (0.0-1.0)
     * @param minRebalancingInterval minimum time between rebalances in milliseconds
     * @param maxEntitiesPerNode     actual tree capacity; used by shouldMerge to compute parent capacity
     */
    public DefaultBalancingStrategy(double mergeFactor, double splitFactor, double imbalanceThreshold,
                                    long minRebalancingInterval, int maxEntitiesPerNode) {
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
        this.maxEntitiesPerNode = maxEntitiesPerNode;
    }

    /**
     * Distributes entities across child nodes using a simple round-robin assignment.
     *
     * <p><b>Non-spatial</b>: entities are assigned by index modulo {@code childCount},
     * without regard to their spatial location. This is intentionally simple — the
     * {@link TreeBalancingStrategy} interface provides only the entity ID set and the
     * child count, not entity positions or octant membership, so true spatial assignment
     * cannot be performed at this layer without an API change.
     *
     * <p>After a split, query performance may degrade because entities may reside in
     * child nodes that do not spatially contain them (Luciferase-7wzml.108). Callers
     * requiring locality-preserving distribution should provide a custom
     * {@link TreeBalancingStrategy} implementation that has access to entity positions.
     *
     * @param entities   the set of entity IDs to distribute
     * @param childCount the number of child partitions
     * @return one non-null {@link Set} per child, containing the assigned entity IDs;
     *         all input entities appear in exactly one child set
     */
    @Override
    @SuppressWarnings("unchecked")
    public Set<ID>[] distributeEntities(Set<ID> entities, int childCount) {
        Set<ID>[] distribution = new Set[childCount];
        for (int i = 0; i < childCount; i++) {
            distribution[i] = new HashSet<>();
        }

        // Round-robin: deterministic, but spatially unaware (see class Javadoc).
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
     * Returns the configured max entities per node value used by shouldMerge. Exposed for testing.
     */
    public int getConfiguredMaxEntitiesPerNode() {
        return maxEntitiesPerNode;
    }

    /**
     * Calculate max entities for a given level using the configured capacity.
     * Applies the same level-adjustment factor used by {@link #getSplitThreshold} so that the
     * merge decision is consistent with the split decision at every depth.
     * Higher levels (closer to root) have a slightly larger effective capacity.
     */
    private int getMaxEntitiesForLevel(byte level) {
        double levelFactor = 1.0 - (level * 0.01); // mirrors getSplitThreshold level factor
        levelFactor = Math.max(0.7, levelFactor);
        return (int) (maxEntitiesPerNode * levelFactor);
    }
}
