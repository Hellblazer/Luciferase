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

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Abstract base implementation of SpatialNodeStorage providing common functionality for spatial index nodes. This class
 * handles entity storage, child tracking, and threshold checking. Converges on the Octree implementation approach with
 * fine-grained child tracking using a bitmask.
 *
 * Thread Safety: This class is NOT thread-safe on its own. It relies on external synchronization provided by
 * AbstractSpatialIndex's read-write lock. All access to node instances must be performed within the appropriate lock
 * context.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public abstract class AbstractSpatialNode<ID extends EntityID> implements SpatialNodeStorage<ID> {

    protected final int      maxEntitiesBeforeSplit;
    protected final List<ID> entityIds;
    protected       byte     childrenMask = 0;

    /**
     * Create a node with default max entities (10)
     */
    protected AbstractSpatialNode() {
        this(10);
    }

    /**
     * Create a node with specified max entities before split
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    protected AbstractSpatialNode(int maxEntitiesBeforeSplit) {
        if (maxEntitiesBeforeSplit <= 0) {
            throw new IllegalArgumentException("Max entities before split must be positive");
        }
        this.maxEntitiesBeforeSplit = maxEntitiesBeforeSplit;
        this.entityIds = new ArrayList<>();
    }

    @Override
    public boolean addEntity(ID entityId) {
        if (entityId == null) {
            throw new IllegalArgumentException("Entity ID cannot be null");
        }
        entityIds.add(entityId);
        return shouldSplit();
    }

    /**
     * Clear a bit in the children mask when a child is removed
     *
     * @param childIndex the child index (0-7)
     */
    public void clearChildBit(int childIndex) {
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        childrenMask &= ~(1 << childIndex);
    }

    @Override
    public void clearEntities() {
        entityIds.clear();
    }

    @Override
    public boolean containsEntity(ID entityId) {
        if (entityId == null) {
            return false;
        }
        return entityIds.contains(entityId);
    }

    /**
     * Get the children mask indicating which children have nodes
     *
     * @return byte mask where bit i indicates child i exists
     */
    public byte getChildrenMask() {
        return childrenMask;
    }

    @Override
    public int getEntityCount() {
        return entityIds.size();
    }

    @Override
    public Collection<ID> getEntityIds() {
        return Collections.unmodifiableList(entityIds);
    }
    
    /**
     * Get entity IDs as a Set
     * @return an unmodifiable Set containing all entity IDs
     */
    public java.util.Set<ID> getEntityIdsAsSet() {
        return Collections.unmodifiableSet(new java.util.HashSet<>(entityIds));
    }

    /**
     * Get the maximum entities allowed before split
     */
    public int getMaxEntitiesBeforeSplit() {
        return maxEntitiesBeforeSplit;
    }

    /**
     * Check if a specific child exists
     *
     * @param childIndex the child index (0-7)
     * @return true if the specified child exists
     */
    public boolean hasChild(int childIndex) {
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (childrenMask & (1 << childIndex)) != 0;
    }

    /**
     * Check if this node has any children
     *
     * @return true if any child nodes exist
     */
    public boolean hasChildren() {
        return childrenMask != 0;
    }

    @Override
    public boolean isEmpty() {
        return entityIds.isEmpty();
    }

    @Override
    public boolean removeEntity(ID entityId) {
        if (entityId == null) {
            return false;
        }
        return entityIds.remove(entityId);
    }

    /**
     * Set a bit in the children mask to indicate a child exists
     *
     * @param childIndex the child index (0-7)
     */
    public void setChildBit(int childIndex) {
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        childrenMask |= (1 << childIndex);
    }

    /**
     * Set whether this node has children (used during balancing operations)
     *
     * @param hasChildren true if this node has children
     */
    public void setHasChildren(boolean hasChildren) {
        if (hasChildren) {
            // Set at least one bit to indicate children exist
            if (childrenMask == 0) {
                childrenMask = 1; // Set first bit as a flag
            }
        } else {
            // Clear all bits
            childrenMask = 0;
        }
    }

    @Override
    public boolean shouldSplit() {
        return getEntityCount() > maxEntitiesBeforeSplit;
    }

    /**
     * Get mutable entity list for redistribution during splits. Protected for subclass internal use only
     *
     * @return the mutable list of entity IDs
     */
    protected List<ID> getMutableEntityIds() {
        return entityIds;
    }
}
