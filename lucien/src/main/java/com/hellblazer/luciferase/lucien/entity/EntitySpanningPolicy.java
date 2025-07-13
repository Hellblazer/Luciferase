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
package com.hellblazer.luciferase.lucien.entity;

/**
 * Policy for handling entities that span multiple octree nodes. Equivalent to C++ DO_SPLIT_PARENT_ENTITIES
 * configuration.
 *
 * @author hal.hildebrand
 */
public class EntitySpanningPolicy {

    private final SpanningStrategy     strategy;
    private final boolean              requireBounds;
    private final float                minSpanThreshold;
    private final SpanningOptimization optimization;
    private final int                  maxSpanNodes;
    private final boolean              adaptiveThreshold;
    private final float                memoryEfficiencyRatio;

    /**
     * Create a policy with default settings (no spanning)
     */
    public EntitySpanningPolicy() {
        this(SpanningStrategy.SINGLE_NODE_ONLY, false, 0.0f, SpanningOptimization.BALANCED, 1000, false, 0.8f);
    }

    /**
     * Create a policy with custom settings
     *
     * @param strategy         The spanning strategy to use
     * @param requireBounds    Whether entities must have bounds to be inserted
     * @param minSpanThreshold Minimum size (relative to node size) for spanning
     */
    public EntitySpanningPolicy(SpanningStrategy strategy, boolean requireBounds, float minSpanThreshold) {
        this(strategy, requireBounds, minSpanThreshold, SpanningOptimization.BALANCED, 1000, false, 0.8f);
    }

    /**
     * Create a policy with full configuration
     *
     * @param strategy              The spanning strategy to use
     * @param requireBounds         Whether entities must have bounds to be inserted
     * @param minSpanThreshold      Minimum size (relative to node size) for spanning
     * @param optimization          The optimization strategy to use
     * @param maxSpanNodes          Maximum number of nodes an entity can span
     * @param adaptiveThreshold     Whether to use adaptive threshold adjustment
     * @param memoryEfficiencyRatio Memory efficiency target (0.0-1.0)
     */
    public EntitySpanningPolicy(SpanningStrategy strategy, boolean requireBounds, float minSpanThreshold,
                                SpanningOptimization optimization, int maxSpanNodes, boolean adaptiveThreshold,
                                float memoryEfficiencyRatio) {
        this.strategy = strategy;
        this.requireBounds = requireBounds;
        this.minSpanThreshold = minSpanThreshold;
        this.optimization = optimization;
        this.maxSpanNodes = maxSpanNodes;
        this.adaptiveThreshold = adaptiveThreshold;
        this.memoryEfficiencyRatio = Math.max(0.0f, Math.min(1.0f, memoryEfficiencyRatio));
    }

    /**
     * Create an adaptive spanning policy that adjusts based on usage patterns
     */
    public static EntitySpanningPolicy adaptive() {
        return new EntitySpanningPolicy(SpanningStrategy.SPAN_TO_OVERLAPPING, true, 0.05f,
                                        SpanningOptimization.ADAPTIVE, 1500, true, 0.75f);
    }

    /**
     * Create a memory-optimized spanning policy
     */
    public static EntitySpanningPolicy memoryOptimized() {
        return new EntitySpanningPolicy(SpanningStrategy.SPAN_TO_OVERLAPPING, true, 0.1f,
                                        SpanningOptimization.MEMORY_EFFICIENT, 500, true, 0.9f);
    }

    /**
     * Create a performance-optimized spanning policy
     */
    public static EntitySpanningPolicy performanceOptimized() {
        return new EntitySpanningPolicy(SpanningStrategy.SPAN_TO_OVERLAPPING, true, 0.0f,
                                        SpanningOptimization.PERFORMANCE_FOCUSED, 2000, true, 0.6f);
    }

    /**
     * Create a policy with spanning enabled
     */
    public static EntitySpanningPolicy withSpanning() {
        return new EntitySpanningPolicy(SpanningStrategy.SPAN_TO_OVERLAPPING, true, 0.0f);
    }

    /**
     * Calculate the maximum number of nodes an entity should span
     *
     * @param entitySize   The size of the entity
     * @param nodeSize     The size of spatial nodes
     * @param currentNodes Current number of nodes in the index
     * @return maximum number of nodes to span
     */
    public int calculateMaxSpanNodes(float entitySize, float nodeSize, int currentNodes) {
        // Apply memory efficiency constraints
        float memoryFactor = Math.max(0.1f, memoryEfficiencyRatio);
        int memoryLimitedMax = (int) (maxSpanNodes * memoryFactor);

        // Adjust based on current system load
        if (currentNodes > 10000) {
            memoryLimitedMax = Math.max(10, memoryLimitedMax / 2);
        } else if (currentNodes > 5000) {
            memoryLimitedMax = Math.max(50, memoryLimitedMax * 3 / 4);
        }

        // Calculate theoretical span based on entity/node size ratio
        int theoreticalSpan = (int) Math.ceil(Math.pow(entitySize / nodeSize, 3));

        return Math.min(memoryLimitedMax, Math.max(1, theoreticalSpan));
    }

    /**
     * Get the maximum number of nodes an entity can span
     */
    public int getMaxSpanNodes() {
        return maxSpanNodes;
    }

    /**
     * Get the memory efficiency target ratio
     */
    public float getMemoryEfficiencyRatio() {
        return memoryEfficiencyRatio;
    }

    /**
     * Get the minimum span threshold
     */
    public float getMinSpanThreshold() {
        return minSpanThreshold;
    }

    /**
     * Get the spanning optimization strategy
     */
    public SpanningOptimization getOptimization() {
        return optimization;
    }

    /**
     * Get the spanning strategy
     */
    public SpanningStrategy getStrategy() {
        return strategy;
    }

    /**
     * Check if adaptive threshold adjustment is enabled
     */
    public boolean isAdaptiveThreshold() {
        return adaptiveThreshold;
    }

    /**
     * Check if spanning is enabled
     */
    public boolean isSpanningEnabled() {
        return strategy != SpanningStrategy.SINGLE_NODE_ONLY;
    }

    /**
     * Check if bounds are required for insertion
     */
    public boolean requiresBounds() {
        return requireBounds;
    }

    /**
     * Check if an entity should span based on its size relative to node size
     *
     * @param entitySize The size of the entity's bounding box
     * @param nodeSize   The size of the octree node
     * @return true if the entity should span multiple nodes
     */
    public boolean shouldSpan(float entitySize, float nodeSize) {
        if (!isSpanningEnabled()) {
            return false;
        }

        // Entity should span if it's larger than threshold * node size
        return entitySize > (minSpanThreshold * nodeSize);
    }

    /**
     * Check if an entity should span based on advanced criteria
     *
     * @param entitySize   The size of the entity's bounding box
     * @param nodeSize     The size of the spatial node
     * @param currentNodes Current number of nodes in the index
     * @param entityCount  Current number of entities
     * @param level        The spatial subdivision level
     * @return true if the entity should span multiple nodes
     */
    public boolean shouldSpanAdvanced(float entitySize, float nodeSize, int currentNodes, int entityCount, byte level) {
        if (!isSpanningEnabled()) {
            return false;
        }

        // Apply optimization-specific logic
        switch (optimization) {
            case MEMORY_EFFICIENT -> {
                // More conservative spanning to reduce memory usage
                float adjustedThreshold = adaptiveThreshold ? calculateAdaptiveThreshold(currentNodes, entityCount)
                                                            : minSpanThreshold;
                return entitySize > (adjustedThreshold * nodeSize * 1.5f); // Higher threshold
            }
            case PERFORMANCE_FOCUSED -> {
                // More aggressive spanning for better query performance
                float adjustedThreshold = adaptiveThreshold ? calculateAdaptiveThreshold(currentNodes, entityCount)
                                                            : minSpanThreshold;
                return entitySize > (adjustedThreshold * nodeSize * 0.5f); // Lower threshold
            }
            case ADAPTIVE -> {
                // Adaptive spanning based on current system state
                return shouldSpanAdaptive(entitySize, nodeSize, currentNodes, entityCount, level);
            }
            default -> {
                // Balanced approach
                return entitySize > (minSpanThreshold * nodeSize);
            }
        }
    }

    /**
     * Check if entities should span to internal nodes
     */
    public boolean spanToInternalNodes() {
        return strategy == SpanningStrategy.SPAN_TO_OVERLAPPING;
    }

    /**
     * Calculate adaptive threshold based on system state
     */
    private float calculateAdaptiveThreshold(int currentNodes, int entityCount) {
        if (entityCount == 0) {
            return minSpanThreshold;
        }

        // Calculate node density (nodes per entity)
        float nodeDensity = (float) currentNodes / entityCount;

        // Adjust threshold based on node density
        if (nodeDensity > 100) {
            // High node density - be more conservative
            return minSpanThreshold * 2.0f;
        } else if (nodeDensity < 10) {
            // Low node density - be more aggressive
            return minSpanThreshold * 0.5f;
        } else {
            // Moderate density - linear interpolation
            float factor = 0.5f + (nodeDensity - 10) / 90.0f * 1.5f;
            return minSpanThreshold * factor;
        }
    }

    /**
     * Adaptive spanning logic that considers multiple factors
     */
    private boolean shouldSpanAdaptive(float entitySize, float nodeSize, int currentNodes, int entityCount,
                                       byte level) {
        // Basic size check
        float sizeRatio = entitySize / nodeSize;
        if (sizeRatio <= minSpanThreshold) {
            return false;
        }

        // Level-based adjustments
        float levelFactor = 1.0f;
        if (level < 3) {
            // Coarse levels - be more conservative
            levelFactor = 1.5f;
        } else if (level > 10) {
            // Fine levels - be more aggressive
            levelFactor = 0.7f;
        }

        // Memory pressure considerations
        if (entityCount > 0) {
            float avgNodesPerEntity = (float) currentNodes / entityCount;
            if (avgNodesPerEntity > 200) {
                // High memory usage - be more conservative
                levelFactor *= 1.8f;
            } else if (avgNodesPerEntity < 5) {
                // Low memory usage - be more aggressive
                levelFactor *= 0.6f;
            }
        }

        return sizeRatio > (minSpanThreshold * levelFactor);
    }

    /**
     * Strategy for handling entities that span multiple nodes
     */
    public enum SpanningStrategy {
        /**
         * Entities are only stored in a single node (no spanning). Large entities stay in parent nodes.
         */
        SINGLE_NODE_ONLY,

        /**
         * Entities can span multiple nodes. Entity IDs are copied to all overlapping nodes. This is the C++
         * DO_SPLIT_PARENT_ENTITIES=true behavior.
         */
        SPAN_TO_OVERLAPPING,

        /**
         * Entities span to leaf nodes only. Internal nodes don't store spanning entities.
         */
        SPAN_TO_LEAVES_ONLY
    }

    /**
     * Optimization strategy for spanning operations
     */
    public enum SpanningOptimization {
        /**
         * Balanced approach between memory usage and performance
         */
        BALANCED,

        /**
         * Prioritize memory efficiency, reduce spanning aggressiveness
         */
        MEMORY_EFFICIENT,

        /**
         * Prioritize query performance, more aggressive spanning
         */
        PERFORMANCE_FOCUSED,

        /**
         * Adaptive spanning that adjusts based on usage patterns
         */
        ADAPTIVE
    }
}
