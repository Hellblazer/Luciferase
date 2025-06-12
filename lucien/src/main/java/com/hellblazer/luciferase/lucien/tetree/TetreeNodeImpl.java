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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tetree node implementation that stores entity IDs using a Set. Extends AbstractSpatialNode to share common
 * functionality with OctreeNode.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class TetreeNodeImpl<ID extends EntityID> extends AbstractSpatialNode<ID> {

    private final Set<ID> entityIds;

    /**
     * Create a node with default max entities (10)
     */
    public TetreeNodeImpl() {
        super();
        this.entityIds = new HashSet<>();
    }

    /**
     * Create a node with specified max entities before split
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    public TetreeNodeImpl(int maxEntitiesBeforeSplit) {
        super(maxEntitiesBeforeSplit);
        this.entityIds = new HashSet<>();
    }

    @Override
    public int getEntityCount() {
        return entityIds.size();
    }

    @Override
    public Collection<ID> getEntityIds() {
        return Collections.unmodifiableSet(new HashSet<>(entityIds));
    }

    /**
     * Get entity IDs as a Set (for backward compatibility)
     *
     * @return unmodifiable set view of entity IDs
     */
    public Set<ID> getEntityIdsAsSet() {
        return Collections.unmodifiableSet(new HashSet<>(entityIds));
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
}
