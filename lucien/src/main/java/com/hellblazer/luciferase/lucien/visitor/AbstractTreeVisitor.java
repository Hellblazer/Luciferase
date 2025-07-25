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
package com.hellblazer.luciferase.lucien.visitor;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

/**
 * Abstract base class for tree visitors that provides default implementations. Subclasses can override only the methods
 * they need.
 *
 * @param <Key>     The type of spatial key used in the index
 * @param <ID>      The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public abstract class AbstractTreeVisitor<Key extends SpatialKey<Key>, ID extends EntityID, Content>
implements TreeVisitor<Key, ID, Content> {

    protected boolean visitEntities = true;
    protected int     maxDepth      = -1;

    @Override
    public void beginTraversal(int totalNodes, int totalEntities) {
        // Default: do nothing
    }

    @Override
    public void endTraversal(int nodesVisited, int entitiesVisited) {
        // Default: do nothing
    }

    @Override
    public int getMaxDepth() {
        return maxDepth;
    }

    @Override
    public void leaveNode(SpatialIndex.SpatialNode<Key, ID> node, int level, int childCount) {
        // Default: do nothing
    }

    /**
     * Set the maximum depth to traverse.
     *
     * @param maxDepth maximum depth (-1 for unlimited)
     */
    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /**
     * Set whether to visit entities.
     *
     * @param visitEntities true to visit entities
     */
    public void setVisitEntities(boolean visitEntities) {
        this.visitEntities = visitEntities;
    }

    @Override
    public boolean shouldVisitEntities() {
        return visitEntities;
    }

    @Override
    public void visitEntity(ID entityId, Content content, Key nodeIndex, int level) {
        // Default: do nothing
    }

    @Override
    public boolean visitNode(SpatialIndex.SpatialNode<Key, ID> node, int level, Key parentIndex) {
        // Default: continue traversal
        return true;
    }
}
