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

/**
 * Abstract base implementation of SpatialNodeStorage providing common functionality for spatial index nodes. This class
 * handles entity storage and threshold checking.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public abstract class AbstractSpatialNode<ID extends EntityID> implements SpatialNodeStorage<ID> {

    protected final int maxEntitiesBeforeSplit;

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
    }

    @Override
    public boolean addEntity(ID entityId) {
        if (entityId == null) {
            throw new IllegalArgumentException("Entity ID cannot be null");
        }
        doAddEntity(entityId);
        return shouldSplit();
    }

    @Override
    public void clearEntities() {
        doClearEntities();
    }

    @Override
    public boolean containsEntity(ID entityId) {
        if (entityId == null) {
            return false;
        }
        return getEntityIds().contains(entityId);
    }

    /**
     * Get the maximum entities allowed before split
     */
    public int getMaxEntitiesBeforeSplit() {
        return maxEntitiesBeforeSplit;
    }

    @Override
    public boolean isEmpty() {
        return getEntityCount() == 0;
    }

    @Override
    public boolean removeEntity(ID entityId) {
        if (entityId == null) {
            return false;
        }
        return doRemoveEntity(entityId);
    }

    @Override
    public boolean shouldSplit() {
        return getEntityCount() > maxEntitiesBeforeSplit;
    }

    // Abstract methods for subclasses to implement storage specifics

    /**
     * Actually add the entity to the storage
     */
    protected abstract void doAddEntity(ID entityId);

    /**
     * Clear all entities from storage
     */
    protected abstract void doClearEntities();

    /**
     * Actually remove the entity from storage
     *
     * @return true if the entity was found and removed
     */
    protected abstract boolean doRemoveEntity(ID entityId);
}
