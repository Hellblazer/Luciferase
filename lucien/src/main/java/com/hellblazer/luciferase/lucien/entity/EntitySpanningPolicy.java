/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the Luciferase.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.entity;

/**
 * Policy for handling entities that span multiple octree nodes.
 * Equivalent to C++ DO_SPLIT_PARENT_ENTITIES configuration.
 * 
 * @author hal.hildebrand
 */
public class EntitySpanningPolicy {
    
    /**
     * Strategy for handling entities that span multiple nodes
     */
    public enum SpanningStrategy {
        /**
         * Entities are only stored in a single node (no spanning).
         * Large entities stay in parent nodes.
         */
        SINGLE_NODE_ONLY,
        
        /**
         * Entities can span multiple nodes.
         * Entity IDs are copied to all overlapping nodes.
         * This is the C++ DO_SPLIT_PARENT_ENTITIES=true behavior.
         */
        SPAN_TO_OVERLAPPING,
        
        /**
         * Entities span to leaf nodes only.
         * Internal nodes don't store spanning entities.
         */
        SPAN_TO_LEAVES_ONLY
    }
    
    private final SpanningStrategy strategy;
    private final boolean requireBounds;
    private final float minSpanThreshold;
    
    /**
     * Create a policy with default settings (no spanning)
     */
    public EntitySpanningPolicy() {
        this(SpanningStrategy.SINGLE_NODE_ONLY, false, 0.0f);
    }
    
    /**
     * Create a policy with spanning enabled
     */
    public static EntitySpanningPolicy withSpanning() {
        return new EntitySpanningPolicy(SpanningStrategy.SPAN_TO_OVERLAPPING, true, 0.0f);
    }
    
    /**
     * Create a policy with custom settings
     * 
     * @param strategy The spanning strategy to use
     * @param requireBounds Whether entities must have bounds to be inserted
     * @param minSpanThreshold Minimum size (relative to node size) for spanning
     */
    public EntitySpanningPolicy(SpanningStrategy strategy, 
                                boolean requireBounds,
                                float minSpanThreshold) {
        this.strategy = strategy;
        this.requireBounds = requireBounds;
        this.minSpanThreshold = minSpanThreshold;
    }
    
    /**
     * Check if spanning is enabled
     */
    public boolean isSpanningEnabled() {
        return strategy != SpanningStrategy.SINGLE_NODE_ONLY;
    }
    
    /**
     * Check if entities should span to internal nodes
     */
    public boolean spanToInternalNodes() {
        return strategy == SpanningStrategy.SPAN_TO_OVERLAPPING;
    }
    
    /**
     * Check if bounds are required for insertion
     */
    public boolean requiresBounds() {
        return requireBounds;
    }
    
    /**
     * Get the minimum span threshold
     */
    public float getMinSpanThreshold() {
        return minSpanThreshold;
    }
    
    /**
     * Get the spanning strategy
     */
    public SpanningStrategy getStrategy() {
        return strategy;
    }
    
    /**
     * Check if an entity should span based on its size relative to node size
     * 
     * @param entitySize The size of the entity's bounding box
     * @param nodeSize The size of the octree node
     * @return true if the entity should span multiple nodes
     */
    public boolean shouldSpan(float entitySize, float nodeSize) {
        if (!isSpanningEnabled()) {
            return false;
        }
        
        // Entity should span if it's larger than threshold * node size
        return entitySize > (minSpanThreshold * nodeSize);
    }
}