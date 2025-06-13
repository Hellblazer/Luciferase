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

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.*;

/**
 * Context for tree traversal operations. Maintains state and provides
 * utilities for traversal algorithms.
 *
 * @param <ID> The type of EntityID used for entity identification
 * 
 * @author hal.hildebrand
 */
public class TraversalContext<ID extends EntityID> {
    
    private final Set<Long> visitedNodes;
    private final Stack<Long> nodeStack;
    private final Map<Long, Integer> nodeLevels;
    private int nodesVisited;
    private int entitiesVisited;
    private boolean cancelled;
    
    public TraversalContext() {
        this.visitedNodes = new HashSet<>();
        this.nodeStack = new Stack<>();
        this.nodeLevels = new HashMap<>();
        this.nodesVisited = 0;
        this.entitiesVisited = 0;
        this.cancelled = false;
    }
    
    /**
     * Mark a node as visited.
     * 
     * @param nodeIndex The spatial index of the node
     * @return true if this is the first visit, false if already visited
     */
    public boolean markVisited(long nodeIndex) {
        if (visitedNodes.add(nodeIndex)) {
            nodesVisited++;
            return true;
        }
        return false;
    }
    
    /**
     * Check if a node has been visited.
     * 
     * @param nodeIndex The spatial index of the node
     * @return true if visited, false otherwise
     */
    public boolean isVisited(long nodeIndex) {
        return visitedNodes.contains(nodeIndex);
    }
    
    /**
     * Push a node onto the traversal stack.
     * 
     * @param nodeIndex The spatial index of the node
     * @param level The depth level of the node
     */
    public void pushNode(long nodeIndex, int level) {
        nodeStack.push(nodeIndex);
        nodeLevels.put(nodeIndex, level);
    }
    
    /**
     * Pop a node from the traversal stack.
     * 
     * @return The spatial index of the popped node, or null if stack is empty
     */
    public Long popNode() {
        return nodeStack.isEmpty() ? null : nodeStack.pop();
    }
    
    /**
     * Peek at the top node on the stack without removing it.
     * 
     * @return The spatial index of the top node, or null if stack is empty
     */
    public Long peekNode() {
        return nodeStack.isEmpty() ? null : nodeStack.peek();
    }
    
    /**
     * Get the level of a node.
     * 
     * @param nodeIndex The spatial index of the node
     * @return The depth level, or -1 if not found
     */
    public int getNodeLevel(long nodeIndex) {
        return nodeLevels.getOrDefault(nodeIndex, -1);
    }
    
    /**
     * Increment the entity visit counter.
     */
    public void incrementEntitiesVisited() {
        entitiesVisited++;
    }
    
    /**
     * Cancel the traversal.
     */
    public void cancel() {
        cancelled = true;
    }
    
    /**
     * Check if traversal has been cancelled.
     * 
     * @return true if cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }
    
    /**
     * Get the number of nodes visited.
     * 
     * @return node visit count
     */
    public int getNodesVisited() {
        return nodesVisited;
    }
    
    /**
     * Get the number of entities visited.
     * 
     * @return entity visit count
     */
    public int getEntitiesVisited() {
        return entitiesVisited;
    }
    
    /**
     * Clear all traversal state.
     */
    public void reset() {
        visitedNodes.clear();
        nodeStack.clear();
        nodeLevels.clear();
        nodesVisited = 0;
        entitiesVisited = 0;
        cancelled = false;
    }
    
    /**
     * Get an unmodifiable view of visited nodes.
     * 
     * @return set of visited node indices
     */
    public Set<Long> getVisitedNodes() {
        return Collections.unmodifiableSet(visitedNodes);
    }
    
    /**
     * Create a path from root to a specific node.
     * 
     * @param nodeIndex The target node
     * @return list of node indices from root to target
     */
    public List<Long> getPathToNode(long nodeIndex) {
        // This would require parent tracking - simplified for now
        List<Long> path = new ArrayList<>();
        path.add(nodeIndex);
        return path;
    }
}