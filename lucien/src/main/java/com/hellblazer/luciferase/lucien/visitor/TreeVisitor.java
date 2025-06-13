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

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;

import java.util.Set;

/**
 * Visitor interface for traversing spatial tree structures.
 * Supports both pre-order and post-order traversal patterns.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * 
 * @author hal.hildebrand
 */
public interface TreeVisitor<ID extends EntityID, Content> {
    
    /**
     * Called when entering a node during traversal.
     * 
     * @param node The spatial node being visited
     * @param level The depth level of this node (0 = root)
     * @param parentIndex The parent node's spatial index (-1 for root)
     * @return true to continue traversing children, false to skip children
     */
    boolean visitNode(SpatialNode<ID> node, int level, long parentIndex);
    
    /**
     * Called when leaving a node after all children have been visited.
     * Only called if visitNode returned true.
     * 
     * @param node The spatial node being left
     * @param level The depth level of this node
     * @param childCount Number of child nodes that were visited
     */
    default void leaveNode(SpatialNode<ID> node, int level, int childCount) {
        // Default: do nothing
    }
    
    /**
     * Called for each entity found in a node.
     * 
     * @param entityId The entity ID
     * @param content The entity content
     * @param nodeIndex The spatial index of the containing node
     * @param level The depth level of the containing node
     */
    default void visitEntity(ID entityId, Content content, long nodeIndex, int level) {
        // Default: do nothing
    }
    
    /**
     * Called before traversal begins.
     * 
     * @param totalNodes Total number of nodes to be traversed
     * @param totalEntities Total number of entities in the tree
     */
    default void beginTraversal(int totalNodes, int totalEntities) {
        // Default: do nothing
    }
    
    /**
     * Called after traversal completes.
     * 
     * @param nodesVisited Number of nodes actually visited
     * @param entitiesVisited Number of entities actually visited
     */
    default void endTraversal(int nodesVisited, int entitiesVisited) {
        // Default: do nothing
    }
    
    /**
     * Controls whether to visit entities in addition to nodes.
     * 
     * @return true to visit entities, false to skip entity visits
     */
    default boolean shouldVisitEntities() {
        return true;
    }
    
    /**
     * Controls the maximum depth to traverse.
     * 
     * @return maximum depth to traverse (-1 for unlimited)
     */
    default int getMaxDepth() {
        return -1;
    }
}