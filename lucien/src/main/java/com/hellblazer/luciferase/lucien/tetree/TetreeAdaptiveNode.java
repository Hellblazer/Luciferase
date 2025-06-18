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

import com.hellblazer.luciferase.lucien.AbstractSpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Collection;
import java.util.Set;

/**
 * Adaptive Tetree node implementation that can switch between Set-based and Array-based
 * storage based on entity count thresholds. This provides optimal performance for both
 * small and large entity counts.
 *
 * Thread Safety: This class is NOT thread-safe on its own. It relies on external synchronization
 * provided by AbstractSpatialIndex's read-write lock. All access to node instances must be
 * performed within the appropriate lock context.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class TetreeAdaptiveNode<ID extends EntityID> extends AbstractSpatialNode<ID> {

    private final TetreeConfiguration configuration;
    private AbstractSpatialNode<ID> delegate;
    private boolean hasChildren;

    /**
     * Create an adaptive node with default configuration
     */
    public TetreeAdaptiveNode() {
        this(10, new TetreeConfiguration());
    }

    /**
     * Create an adaptive node with specified max entities and default configuration
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    public TetreeAdaptiveNode(int maxEntitiesBeforeSplit) {
        this(maxEntitiesBeforeSplit, new TetreeConfiguration());
    }

    /**
     * Create an adaptive node with specified configuration
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     * @param configuration Tetree configuration settings
     */
    public TetreeAdaptiveNode(int maxEntitiesBeforeSplit, TetreeConfiguration configuration) {
        super(maxEntitiesBeforeSplit);
        this.configuration = configuration;
        
        // Initialize with appropriate delegate based on configuration
        if (configuration.isAlwaysUseArrayNodes()) {
            this.delegate = new TetreeArrayNode<>(maxEntitiesBeforeSplit, configuration.getArrayInitialCapacity());
        } else {
            this.delegate = new TetreeNodeImpl<>(maxEntitiesBeforeSplit);
        }
    }

    @Override
    public int getEntityCount() {
        return delegate.getEntityCount();
    }

    @Override
    public Collection<ID> getEntityIds() {
        return delegate.getEntityIds();
    }

    /**
     * Get entity IDs as a Set (for compatibility)
     *
     * @return unmodifiable set view of entity IDs
     */
    public Set<ID> getEntityIdsAsSet() {
        if (delegate instanceof TetreeNodeImpl) {
            return ((TetreeNodeImpl<ID>) delegate).getEntityIdsAsSet();
        } else if (delegate instanceof TetreeArrayNode) {
            return ((TetreeArrayNode<ID>) delegate).getEntityIdsAsSet();
        } else {
            throw new IllegalStateException("Unknown delegate type: " + delegate.getClass());
        }
    }

    /**
     * Check if this node has children
     *
     * @return true if this node has been subdivided
     */
    public boolean hasChildren() {
        return hasChildren;
    }

    /**
     * Set whether this node has children
     *
     * @param hasChildren true if this node has been subdivided
     */
    public void setHasChildren(boolean hasChildren) {
        this.hasChildren = hasChildren;
    }

    @Override
    protected void doAddEntity(ID entityId) {
        // Check if we should switch to array-based storage
        if (shouldSwitchToArray()) {
            switchToArrayNode();
        }
        
        delegate.addEntity(entityId);
    }

    @Override
    protected void doClearEntities() {
        delegate.clearEntities();
        
        // Consider switching back to set-based storage after clearing
        if (configuration.isUseArrayNodes() && !configuration.isAlwaysUseArrayNodes() 
            && delegate instanceof TetreeArrayNode) {
            switchToSetNode();
        }
    }

    @Override
    protected boolean doRemoveEntity(ID entityId) {
        boolean removed = delegate.removeEntity(entityId);
        
        // Check if we should compact or switch storage type
        if (removed && delegate instanceof TetreeArrayNode) {
            TetreeArrayNode<ID> arrayNode = (TetreeArrayNode<ID>) delegate;
            
            // Compact if fill ratio is low
            if (configuration.isEnableNodeCompaction() 
                && arrayNode.getFillRatio() < configuration.getCompactionThreshold()) {
                arrayNode.compact();
            }
            
            // Switch back to set if entity count dropped below threshold
            if (!configuration.isAlwaysUseArrayNodes() 
                && getEntityCount() < configuration.getArrayThreshold() / 2) {
                switchToSetNode();
            }
        }
        
        return removed;
    }

    @Override
    public boolean containsEntity(ID entityId) {
        return delegate.containsEntity(entityId);
    }

    /**
     * Check if we should switch to array-based storage
     */
    private boolean shouldSwitchToArray() {
        return configuration.isUseArrayNodes() 
               && !(delegate instanceof TetreeArrayNode)
               && (configuration.isAlwaysUseArrayNodes() 
                   || getEntityCount() >= configuration.getArrayThreshold());
    }

    /**
     * Switch the internal storage to array-based
     */
    private void switchToArrayNode() {
        Collection<ID> currentEntities = delegate.getEntityIds();
        TetreeArrayNode<ID> newNode = new TetreeArrayNode<>(
            maxEntitiesBeforeSplit, 
            Math.max(currentEntities.size(), configuration.getArrayInitialCapacity())
        );
        
        // Copy entities to new node
        for (ID entityId : currentEntities) {
            newNode.addEntity(entityId);
        }
        
        delegate = newNode;
    }

    /**
     * Switch the internal storage to set-based
     */
    private void switchToSetNode() {
        Collection<ID> currentEntities = delegate.getEntityIds();
        TetreeNodeImpl<ID> newNode = new TetreeNodeImpl<>(maxEntitiesBeforeSplit);
        
        // Copy entities to new node
        for (ID entityId : currentEntities) {
            newNode.addEntity(entityId);
        }
        
        delegate = newNode;
    }

    /**
     * Get the current storage type
     *
     * @return "Set" or "Array" based on current implementation
     */
    public String getStorageType() {
        if (delegate instanceof TetreeNodeImpl) {
            return "Set";
        } else if (delegate instanceof TetreeArrayNode) {
            return "Array";
        } else {
            return "Unknown";
        }
    }

    /**
     * Get storage statistics
     *
     * @return statistics about the current storage
     */
    public StorageStats getStorageStats() {
        if (delegate instanceof TetreeArrayNode) {
            TetreeArrayNode<ID> arrayNode = (TetreeArrayNode<ID>) delegate;
            return new StorageStats("Array", getEntityCount(), arrayNode.getCapacity(), arrayNode.getFillRatio());
        } else {
            return new StorageStats("Set", getEntityCount(), getEntityCount(), 1.0f);
        }
    }

    /**
     * Storage statistics record
     */
    public record StorageStats(String type, int entityCount, int capacity, float fillRatio) {}
}