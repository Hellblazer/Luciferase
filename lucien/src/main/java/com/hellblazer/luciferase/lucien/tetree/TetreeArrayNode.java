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

import java.util.*;

/**
 * Array-based Tetree node implementation that stores entity IDs using a pre-allocated array.
 * This implementation provides better cache performance for nodes with many entities compared
 * to the Set-based implementation.
 *
 * Thread Safety: This class is NOT thread-safe on its own. It relies on external synchronization
 * provided by AbstractSpatialIndex's read-write lock. All access to node instances must be
 * performed within the appropriate lock context.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class TetreeArrayNode<ID extends EntityID> extends AbstractSpatialNode<ID> {

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final float GROWTH_FACTOR = 1.5f;

    private ID[] entityIds;
    private int entityCount;
    private boolean hasChildren;

    /**
     * Create a node with default max entities (10) and default initial capacity
     */
    @SuppressWarnings("unchecked")
    public TetreeArrayNode() {
        super();
        this.entityIds = (ID[]) new EntityID[DEFAULT_INITIAL_CAPACITY];
        this.entityCount = 0;
    }

    /**
     * Create a node with specified max entities before split and default initial capacity
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    @SuppressWarnings("unchecked")
    public TetreeArrayNode(int maxEntitiesBeforeSplit) {
        super(maxEntitiesBeforeSplit);
        // Allocate initial capacity based on expected max entities
        int initialCapacity = Math.min(maxEntitiesBeforeSplit * 2, DEFAULT_INITIAL_CAPACITY);
        this.entityIds = (ID[]) new EntityID[initialCapacity];
        this.entityCount = 0;
    }

    /**
     * Create a node with specified max entities and initial array capacity
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     * @param initialCapacity initial array capacity
     */
    @SuppressWarnings("unchecked")
    public TetreeArrayNode(int maxEntitiesBeforeSplit, int initialCapacity) {
        super(maxEntitiesBeforeSplit);
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        this.entityIds = (ID[]) new EntityID[initialCapacity];
        this.entityCount = 0;
    }

    @Override
    public int getEntityCount() {
        return entityCount;
    }

    @Override
    public Collection<ID> getEntityIds() {
        // Return a list view of the array contents
        List<ID> result = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            result.add(entityIds[i]);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get entity IDs as a Set (for compatibility with TetreeNodeImpl)
     *
     * @return unmodifiable set view of entity IDs
     */
    public Set<ID> getEntityIdsAsSet() {
        Set<ID> result = new HashSet<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            result.add(entityIds[i]);
        }
        return Collections.unmodifiableSet(result);
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
        // Check if entity already exists
        for (int i = 0; i < entityCount; i++) {
            if (entityIds[i].equals(entityId)) {
                return; // Already exists, no need to add
            }
        }

        // Ensure capacity
        if (entityCount >= entityIds.length) {
            growArray();
        }

        // Add the entity
        entityIds[entityCount++] = entityId;
    }

    @Override
    protected void doClearEntities() {
        // Clear references to help GC
        Arrays.fill(entityIds, 0, entityCount, null);
        entityCount = 0;
    }

    @Override
    protected boolean doRemoveEntity(ID entityId) {
        // Find the entity
        for (int i = 0; i < entityCount; i++) {
            if (entityIds[i].equals(entityId)) {
                // Found it - shift remaining elements left
                System.arraycopy(entityIds, i + 1, entityIds, i, entityCount - i - 1);
                entityIds[--entityCount] = null; // Clear reference
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsEntity(ID entityId) {
        if (entityId == null) {
            return false;
        }
        for (int i = 0; i < entityCount; i++) {
            if (entityIds[i].equals(entityId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Grow the array when capacity is reached
     */
    @SuppressWarnings("unchecked")
    private void growArray() {
        int newCapacity = (int) (entityIds.length * GROWTH_FACTOR);
        ID[] newArray = (ID[]) new EntityID[newCapacity];
        System.arraycopy(entityIds, 0, newArray, 0, entityCount);
        entityIds = newArray;
    }

    /**
     * Get the current array capacity
     *
     * @return current capacity of the internal array
     */
    public int getCapacity() {
        return entityIds.length;
    }

    /**
     * Compact the array to minimize memory usage.
     * This creates a new array sized exactly to the current entity count.
     */
    @SuppressWarnings("unchecked")
    public void compact() {
        if (entityCount < entityIds.length && entityCount > 0) {
            ID[] compactArray = (ID[]) new EntityID[entityCount];
            System.arraycopy(entityIds, 0, compactArray, 0, entityCount);
            entityIds = compactArray;
        }
    }

    /**
     * Get the fill ratio of the array
     *
     * @return ratio of used slots to total capacity (0.0 to 1.0)
     */
    public float getFillRatio() {
        return entityIds.length == 0 ? 0.0f : (float) entityCount / entityIds.length;
    }
}