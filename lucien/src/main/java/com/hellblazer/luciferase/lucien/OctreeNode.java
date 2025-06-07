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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Enhanced octree node that stores multiple entity IDs instead of content.
 * Mimics C++ EntityContainer approach for multiple entities per node.
 * 
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class OctreeNode<ID extends EntityID> {
    private byte childrenMask = 0;
    private final List<ID> entityIds;
    private final int maxEntitiesBeforeSplit;
    
    /**
     * Create a node with default max entities (10)
     */
    public OctreeNode() {
        this(10);
    }
    
    /**
     * Create a node with specified max entities before split
     * 
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    public OctreeNode(int maxEntitiesBeforeSplit) {
        this.entityIds = new ArrayList<>();
        this.maxEntitiesBeforeSplit = maxEntitiesBeforeSplit;
    }
    
    /**
     * Add an entity ID to this node
     * 
     * @return true if the node should be split (exceeds threshold)
     */
    public boolean addEntity(ID entityId) {
        entityIds.add(entityId);
        return entityIds.size() > maxEntitiesBeforeSplit;
    }
    
    /**
     * Remove an entity ID from this node
     * 
     * @return true if the entity was found and removed
     */
    public boolean removeEntity(ID entityId) {
        return entityIds.remove(entityId);
    }
    
    /**
     * Check if this node contains a specific entity
     */
    public boolean containsEntity(ID entityId) {
        return entityIds.contains(entityId);
    }
    
    /**
     * Get all entity IDs in this node
     * 
     * @return unmodifiable view of entity IDs
     */
    public List<ID> getEntityIds() {
        return Collections.unmodifiableList(entityIds);
    }
    
    /**
     * Get mutable entity list for redistribution during splits
     * Package-private for octree internal use only
     */
    List<ID> getMutableEntityIds() {
        return entityIds;
    }
    
    /**
     * Get the number of entities in this node
     */
    public int getEntityCount() {
        return entityIds.size();
    }
    
    /**
     * Check if this node is empty (no entities)
     */
    public boolean isEmpty() {
        return entityIds.isEmpty();
    }
    
    /**
     * Check if this node should be split based on entity count
     */
    public boolean shouldSplit() {
        return entityIds.size() > maxEntitiesBeforeSplit;
    }
    
    /**
     * Clear all entities from this node
     */
    public void clearEntities() {
        entityIds.clear();
    }
    
    /**
     * Get the children mask indicating which octants have child nodes
     */
    public byte getChildrenMask() {
        return childrenMask;
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
     * Get the maximum entities allowed before split
     */
    public int getMaxEntitiesBeforeSplit() {
        return maxEntitiesBeforeSplit;
    }
}