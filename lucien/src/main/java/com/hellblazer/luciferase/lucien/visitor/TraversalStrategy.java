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

/**
 * Defines the strategy for traversing the spatial tree.
 * 
 * @author hal.hildebrand
 */
public enum TraversalStrategy {
    /**
     * Depth-first search traversal.
     * Visits children before siblings.
     */
    DEPTH_FIRST,
    
    /**
     * Breadth-first search traversal.
     * Visits all nodes at current level before moving to next level.
     */
    BREADTH_FIRST,
    
    /**
     * Level-order traversal.
     * Similar to breadth-first but processes levels explicitly.
     */
    LEVEL_ORDER,
    
    /**
     * Pre-order traversal.
     * Visit node, then children (default for depth-first).
     */
    PRE_ORDER,
    
    /**
     * Post-order traversal.
     * Visit children, then node.
     */
    POST_ORDER,
    
    /**
     * In-order traversal (for binary trees).
     * Visit left children, node, then right children.
     * For octrees/tetrees, visits half children, node, then remaining children.
     */
    IN_ORDER
}