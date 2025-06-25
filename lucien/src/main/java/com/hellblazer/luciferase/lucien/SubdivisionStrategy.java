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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.List;
import java.util.Set;

/**
 * Abstract base class for subdivision strategies that determine how nodes should be subdivided when they reach
 * capacity. Based on sophisticated control flow strategies from high-performance C++ spatial index implementations.
 *
 * Different strategies can be used to optimize for different use cases: - Dense point clouds: aggressive subdivision -
 * Large entities: selective subdivision with spanning - Mixed workloads: adaptive strategies
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public abstract class SubdivisionStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    // Configuration parameters
    protected double  spanningThreshold   = 0.5;  // Entity size relative to node size
    protected double  loadFactor          = 0.75;        // Target load factor for nodes
    protected int     minEntitiesForSplit = 4;     // Minimum entities before considering split
    protected boolean adaptiveMode        = true;     // Enable adaptive decision making

    /**
     * Calculate which child nodes an entity should belong to after subdivision
     */
    public abstract Set<Key> calculateTargetNodes(Key parentIndex, byte parentLevel, EntityBounds entityBounds,
                                                  AbstractSpatialIndex<Key, ID, Content, ?> spatialIndex);

    /**
     * Determine the subdivision strategy for a given context
     */
    public abstract SubdivisionResult determineStrategy(SubdivisionContext<Key, ID> context);

    public double getLoadFactor() {
        return loadFactor;
    }

    public int getMinEntitiesForSplit() {
        return minEntitiesForSplit;
    }

    // Getters
    public double getSpanningThreshold() {
        return spanningThreshold;
    }

    public boolean isAdaptiveMode() {
        return adaptiveMode;
    }

    @SuppressWarnings("unchecked")
    public <T extends SubdivisionStrategy<Key, ID, Content>> T withAdaptiveMode(boolean adaptive) {
        this.adaptiveMode = adaptive;
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends SubdivisionStrategy<Key, ID, Content>> T withLoadFactor(double factor) {
        this.loadFactor = factor;
        return (T) this;
    }

    // Configuration methods

    @SuppressWarnings("unchecked")
    public <T extends SubdivisionStrategy<Key, ID, Content>> T withMinEntitiesForSplit(int minEntities) {
        this.minEntitiesForSplit = minEntities;
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends SubdivisionStrategy<Key, ID, Content>> T withSpanningThreshold(double threshold) {
        this.spanningThreshold = threshold;
        return (T) this;
    }

    /**
     * Estimate how large the entity is relative to the node
     */
    protected abstract double estimateEntitySizeFactor(SubdivisionContext<Key, ID> context);

    /**
     * Estimate the benefit of subdivision for the given context
     *
     * @return score from 0.0 (no benefit) to 1.0 (high benefit)
     */
    protected double estimateSubdivisionBenefit(SubdivisionContext<Key, ID> context) {
        if (context.isAtMaxDepth()) {
            return 0.0;
        }

        // Factor 1: How overloaded is the node?
        double overloadFactor = (double) context.currentNodeSize / context.maxEntitiesPerNode;
        overloadFactor = Math.min(overloadFactor, 2.0) / 2.0;

        // Factor 2: How many levels remain?
        double depthFactor = (double) context.getDepthRemaining() / context.maxDepth;

        // Factor 3: Entity size relative to node size (if bounds provided)
        double sizeFactor = 0.5;
        if (context.newEntityBounds != null) {
            // This would need the actual node size calculation
            sizeFactor = estimateEntitySizeFactor(context);
        }

        // Weighted combination
        return (overloadFactor * 0.5) + (depthFactor * 0.3) + (sizeFactor * 0.2);
    }

    /**
     * Check if an entity should span multiple nodes
     */
    protected boolean shouldSpanEntity(SubdivisionContext<Key, ID> context, double nodeSizeEstimate) {
        if (context.newEntityBounds == null) {
            return false;
        }

        double entitySize = Math.max(Math.max(context.newEntityBounds.getMaxX() - context.newEntityBounds.getMinX(),
                                              context.newEntityBounds.getMaxY() - context.newEntityBounds.getMinY()),
                                     context.newEntityBounds.getMaxZ() - context.newEntityBounds.getMinZ());

        return entitySize > nodeSizeEstimate * spanningThreshold;
    }

    /**
     * Control flow decisions for subdivision operations
     */
    public enum ControlFlow {
        /**
         * Keep entity in parent node without subdivision. Used when subdivision would not improve performance.
         */
        INSERT_IN_PARENT,

        /**
         * Split entity across multiple child nodes. Used for large entities that span multiple spatial regions.
         */
        SPLIT_TO_CHILDREN,

        /**
         * Create only the necessary child nodes. Optimizes memory by creating children on-demand.
         */
        CREATE_SINGLE_CHILD,

        /**
         * Perform complete rebalancing of the subtree. Used when tree becomes unbalanced.
         */
        FULL_REBALANCING,

        /**
         * Defer subdivision until bulk operation completes. Optimizes bulk insertions.
         */
        DEFER_SUBDIVISION,

        /**
         * Force immediate subdivision regardless of other factors. Used when node is critically overloaded.
         */
        FORCE_SUBDIVISION
    }

    /**
     * Context information for subdivision decision
     */
    public static class SubdivisionContext<Key extends SpatialKey<Key>, ID extends EntityID> {
        public final Key          nodeIndex;
        public final byte         nodeLevel;
        public final int          currentNodeSize;
        public final int          maxEntitiesPerNode;
        public final boolean      isBulkOperation;
        public final EntityBounds newEntityBounds;
        public final List<ID>     existingEntities;
        public final byte         maxDepth;

        public SubdivisionContext(Key nodeIndex, byte nodeLevel, int currentNodeSize, int maxEntitiesPerNode,
                                  boolean isBulkOperation, EntityBounds newEntityBounds, List<ID> existingEntities,
                                  byte maxDepth) {
            this.nodeIndex = nodeIndex;
            this.nodeLevel = nodeLevel;
            this.currentNodeSize = currentNodeSize;
            this.maxEntitiesPerNode = maxEntitiesPerNode;
            this.isBulkOperation = isBulkOperation;
            this.newEntityBounds = newEntityBounds;
            this.existingEntities = existingEntities;
            this.maxDepth = maxDepth;
        }

        public int getDepthRemaining() {
            return maxDepth - nodeLevel;
        }

        public boolean isAtMaxDepth() {
            return nodeLevel >= maxDepth;
        }

        public boolean isCriticallyOverloaded() {
            return currentNodeSize > maxEntitiesPerNode * 2;
        }

        public boolean isOverloaded() {
            return currentNodeSize > maxEntitiesPerNode;
        }
    }

    /**
     * Result of subdivision operation
     */
    public static class SubdivisionResult<Key extends SpatialKey<Key>> {
        public final ControlFlow decision;
        public final Set<Key>    targetNodes;
        public final boolean     shouldSpanEntity;
        public final String      reason;

        public SubdivisionResult(ControlFlow decision, Set<Key> targetNodes, boolean shouldSpanEntity, String reason) {
            this.decision = decision;
            this.targetNodes = targetNodes;
            this.shouldSpanEntity = shouldSpanEntity;
            this.reason = reason;
        }

        public static SubdivisionResult createSingleChild(SpatialKey<?> targetNode, String reason) {
            return new SubdivisionResult(ControlFlow.CREATE_SINGLE_CHILD, Set.of(targetNode), false, reason);
        }

        public static SubdivisionResult deferSubdivision(String reason) {
            return new SubdivisionResult(ControlFlow.DEFER_SUBDIVISION, null, false, reason);
        }

        public static SubdivisionResult forceSubdivision(String reason) {
            return new SubdivisionResult(ControlFlow.FORCE_SUBDIVISION, null, false, reason);
        }

        public static SubdivisionResult fullRebalancing(String reason) {
            return new SubdivisionResult(ControlFlow.FULL_REBALANCING, null, false, reason);
        }

        public static SubdivisionResult insertInParent(String reason) {
            return new SubdivisionResult(ControlFlow.INSERT_IN_PARENT, null, false, reason);
        }

        public static SubdivisionResult splitToChildren(Set<? extends SpatialKey<?>> targetNodes, String reason) {
            return new SubdivisionResult(ControlFlow.SPLIT_TO_CHILDREN, targetNodes, true, reason);
        }
    }
}
