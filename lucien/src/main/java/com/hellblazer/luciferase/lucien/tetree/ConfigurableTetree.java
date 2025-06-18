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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;

/**
 * Configurable Tetree implementation that supports switching between different node
 * storage strategies based on entity count thresholds. This extends the base Tetree
 * functionality with adaptive node support.
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class ConfigurableTetree<ID extends EntityID, Content> extends Tetree<ID, Content> {

    private final TetreeConfiguration configuration;

    /**
     * Create a ConfigurableTetree with default configuration
     */
    public ConfigurableTetree(EntityIDGenerator<ID> idGenerator) {
        this(idGenerator, new TetreeConfiguration());
    }

    /**
     * Create a ConfigurableTetree with specified configuration
     */
    public ConfigurableTetree(EntityIDGenerator<ID> idGenerator, TetreeConfiguration configuration) {
        this(idGenerator, 10, Constants.getMaxRefinementLevel(), new EntitySpanningPolicy(), configuration);
    }

    /**
     * Create a ConfigurableTetree with custom settings
     */
    public ConfigurableTetree(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                              TetreeConfiguration configuration) {
        this(idGenerator, maxEntitiesPerNode, maxDepth, new EntitySpanningPolicy(), configuration);
    }

    /**
     * Create a ConfigurableTetree with full configuration
     */
    public ConfigurableTetree(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                              EntitySpanningPolicy spanningPolicy, TetreeConfiguration configuration) {
        super(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
        this.configuration = configuration;
    }

    @Override
    protected TetreeNodeImpl<ID> createNode() {
        // Create adaptive nodes that can switch between implementations
        // Note: We return TetreeNodeImpl but internally use TetreeAdaptiveNode
        // which extends AbstractSpatialNode. This works because TetreeAdaptiveNode
        // provides all the same methods as TetreeNodeImpl.
        return new TetreeAdaptiveNodeWrapper<>(maxEntitiesPerNode, configuration);
    }

    /**
     * Get the configuration used by this Tetree
     *
     * @return current configuration
     */
    public TetreeConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Get storage statistics for a specific node
     *
     * @param nodeIndex the spatial index of the node
     * @return storage statistics, or null if node doesn't exist
     */
    public TetreeAdaptiveNode.StorageStats getNodeStorageStats(long nodeIndex) {
        lock.readLock().lock();
        try {
            TetreeNodeImpl<ID> node = spatialIndex.get(nodeIndex);
            if (node instanceof TetreeAdaptiveNodeWrapper) {
                return ((TetreeAdaptiveNodeWrapper<ID>) node).getStorageStats();
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get overall storage statistics for the entire tree
     *
     * @return aggregate storage statistics
     */
    public TreeStorageStats getTreeStorageStats() {
        lock.readLock().lock();
        try {
            int totalNodes = 0;
            int arrayNodes = 0;
            int setNodes = 0;
            long totalEntities = 0;
            long totalCapacity = 0;

            for (TetreeNodeImpl<ID> node : spatialIndex.values()) {
                totalNodes++;
                if (node instanceof TetreeAdaptiveNodeWrapper) {
                    TetreeAdaptiveNodeWrapper<ID> adaptiveNode = (TetreeAdaptiveNodeWrapper<ID>) node;
                    TetreeAdaptiveNode.StorageStats stats = adaptiveNode.getStorageStats();
                    
                    if ("Array".equals(stats.type())) {
                        arrayNodes++;
                    } else {
                        setNodes++;
                    }
                    
                    totalEntities += stats.entityCount();
                    totalCapacity += stats.capacity();
                }
            }

            float averageFillRatio = totalCapacity > 0 ? (float) totalEntities / totalCapacity : 0.0f;
            
            return new TreeStorageStats(totalNodes, arrayNodes, setNodes, 
                                        totalEntities, totalCapacity, averageFillRatio);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Aggregate storage statistics for the entire tree
     */
    public record TreeStorageStats(int totalNodes, int arrayNodes, int setNodes,
                                   long totalEntities, long totalCapacity, float averageFillRatio) {}

    /**
     * Wrapper class that extends TetreeNodeImpl but delegates to TetreeAdaptiveNode.
     * This allows us to use adaptive nodes while maintaining type compatibility.
     */
    private static class TetreeAdaptiveNodeWrapper<ID extends EntityID> extends TetreeNodeImpl<ID> {
        private final TetreeAdaptiveNode<ID> adaptiveNode;

        public TetreeAdaptiveNodeWrapper(int maxEntitiesBeforeSplit, TetreeConfiguration configuration) {
            super(maxEntitiesBeforeSplit);
            this.adaptiveNode = new TetreeAdaptiveNode<>(maxEntitiesBeforeSplit, configuration);
        }

        @Override
        public int getEntityCount() {
            return adaptiveNode.getEntityCount();
        }

        @Override
        public java.util.Collection<ID> getEntityIds() {
            return adaptiveNode.getEntityIds();
        }

        @Override
        public java.util.Set<ID> getEntityIdsAsSet() {
            return adaptiveNode.getEntityIdsAsSet();
        }

        @Override
        public boolean hasChildren() {
            return adaptiveNode.hasChildren();
        }

        @Override
        public void setHasChildren(boolean hasChildren) {
            adaptiveNode.setHasChildren(hasChildren);
        }

        @Override
        protected void doAddEntity(ID entityId) {
            adaptiveNode.addEntity(entityId);
        }

        @Override
        protected void doClearEntities() {
            adaptiveNode.clearEntities();
        }

        @Override
        protected boolean doRemoveEntity(ID entityId) {
            return adaptiveNode.removeEntity(entityId);
        }

        @Override
        public boolean containsEntity(ID entityId) {
            return adaptiveNode.containsEntity(entityId);
        }

        @Override
        public boolean isEmpty() {
            return adaptiveNode.isEmpty();
        }

        @Override
        public boolean shouldSplit() {
            return adaptiveNode.shouldSplit();
        }

        public TetreeAdaptiveNode.StorageStats getStorageStats() {
            return adaptiveNode.getStorageStats();
        }
    }
}