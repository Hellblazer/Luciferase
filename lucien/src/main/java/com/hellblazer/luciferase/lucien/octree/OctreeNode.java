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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.AbstractSpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Enhanced octree node that stores multiple entity IDs instead of content. Mimics C++ EntityContainer approach for
 * multiple entities per node. Extends AbstractSpatialNode to share common functionality with TetreeNode.
 *
 * Thread Safety: This class is NOT thread-safe on its own. It relies on external synchronization provided by
 * AbstractSpatialIndex's read-write lock. All access to node instances must be performed within the appropriate lock
 * context.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class OctreeNode<ID extends EntityID> extends AbstractSpatialNode<ID> {
    private final List<ID> entityIds;
    private       byte     childrenMask = 0;

    /**
     * Create a node with default max entities (10)
     */
    public OctreeNode() {
        super();
        this.entityIds = new ArrayList<>();
    }

    /**
     * Create a node with specified max entities before split
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    public OctreeNode(int maxEntitiesBeforeSplit) {
        super(maxEntitiesBeforeSplit);
        this.entityIds = new ArrayList<>();
    }

    /**
     * Clear a bit in the children mask when a child is removed
     *
     * @param octant the octant index (0-7)
     */
    public void clearChildBit(int octant) {
        if (octant < 0 || octant > 7) {
            throw new IllegalArgumentException("Octant must be 0-7");
        }
        childrenMask &= ~(1 << octant);
    }

    /**
     * Get the children mask indicating which octants have child nodes
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
     * Get all entity IDs as a list (for backward compatibility)
     *
     * @return unmodifiable list view of entity IDs
     */
    public List<ID> getEntityIdsAsList() {
        return Collections.unmodifiableList(entityIds);
    }

    /**
     * Check if a specific octant has a child
     *
     * @param octant the octant index (0-7)
     */
    public boolean hasChild(int octant) {
        if (octant < 0 || octant > 7) {
            throw new IllegalArgumentException("Octant must be 0-7");
        }
        return (childrenMask & (1 << octant)) != 0;
    }

    /**
     * Check if this node has any children
     */
    public boolean hasChildren() {
        return childrenMask != 0;
    }
    
    /**
     * Set whether this node has children (used during balancing operations)
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

    /**
     * Set a bit in the children mask to indicate a child exists
     *
     * @param octant the octant index (0-7)
     */
    public void setChildBit(int octant) {
        if (octant < 0 || octant > 7) {
            throw new IllegalArgumentException("Octant must be 0-7");
        }
        childrenMask |= (1 << octant);
    }

    @Override
    protected void doAddEntity(ID entityId) {
        entityIds.add(entityId);
    }

    @Override
    protected void doClearEntities() {
        entityIds.clear();
    }

    @Override
    protected boolean doRemoveEntity(ID entityId) {
        return entityIds.remove(entityId);
    }

    /**
     * Get mutable entity list for redistribution during splits. Package-private for octree internal use only
     */
    List<ID> getMutableEntityIds() {
        return entityIds;
    }
}
