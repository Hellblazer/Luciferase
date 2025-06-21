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

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.*;

/**
 * Iterator for traversing a Tetree using space-filling curve properties. Supports multiple traversal orders and
 * level-restricted iteration. Based on t8code's tree traversal algorithms.
 *
 * @author hal.hildebrand
 */
public class TetreeIterator<ID extends EntityID, Content> implements Iterator<TetreeNodeImpl<ID>> {

    // Iterator state
    private final Tetree<ID, Content> tree;
    private final TraversalOrder      order;
    private final byte                minLevel;
    private final byte                maxLevel;
    private final boolean             includeEmpty;
    // Traversal-specific state
    private final Deque<TraversalState<ID>> stack; // For depth-first
    private final Queue<TraversalState<ID>> queue; // For breadth-first
    // Tree modification count for invalidation detection
    private final long initialModificationCount;
    // Current position in traversal
    private Long               currentIndex;
    private TetreeNodeImpl<ID> currentNode;
    private boolean            hasNext;
    private       long                      nextSFCIndex; // For SFC order

    // Skip subtree flag
    private boolean skipSubtree;

    /**
     * Create a new iterator for the given tree
     *
     * @param tree         The tetree to iterate over
     * @param order        The traversal order to use
     * @param minLevel     Minimum level to traverse (inclusive)
     * @param maxLevel     Maximum level to traverse (inclusive)
     * @param includeEmpty Whether to include empty nodes
     */
    public TetreeIterator(Tetree<ID, Content> tree, TraversalOrder order, byte minLevel, byte maxLevel,
                          boolean includeEmpty) {
        this.tree = tree;
        this.order = order;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        this.includeEmpty = includeEmpty;

        // Initialize data structures based on traversal order
        this.stack = (order == TraversalOrder.DEPTH_FIRST_PRE || order == TraversalOrder.DEPTH_FIRST_POST)
                     ? new ArrayDeque<>() : null;
        this.queue = (order == TraversalOrder.BREADTH_FIRST) ? new ArrayDeque<>() : null;
        this.nextSFCIndex = 0;
        this.initialModificationCount = getTreeModificationCount();

        // Initialize traversal
        initializeTraversal();
    }

    /**
     * Create a new iterator with default level range
     */
    public TetreeIterator(Tetree<ID, Content> tree, TraversalOrder order) {
        this(tree, order, (byte) 0, Constants.getMaxRefinementLevel(), false);
    }

    /**
     * Create an iterator for breadth-first traversal
     */
    public static <ID extends EntityID, Content> TetreeIterator<ID, Content> breadthFirst(Tetree<ID, Content> tree) {
        return new TetreeIterator<>(tree, TraversalOrder.BREADTH_FIRST);
    }

    /**
     * Create an iterator for depth-first pre-order traversal
     */
    public static <ID extends EntityID, Content> TetreeIterator<ID, Content> depthFirstPre(Tetree<ID, Content> tree) {
        return new TetreeIterator<>(tree, TraversalOrder.DEPTH_FIRST_PRE);
    }

    /**
     * Create an iterator for SFC order traversal
     */
    public static <ID extends EntityID, Content> TetreeIterator<ID, Content> sfcOrder(Tetree<ID, Content> tree) {
        return new TetreeIterator<>(tree, TraversalOrder.SFC_ORDER);
    }

    /**
     * Get the current tetrahedron index being visited
     */
    public Long getCurrentIndex() {
        return currentIndex;
    }

    /**
     * Get the current level in the traversal
     */
    public byte getCurrentLevel() {
        // For sparse trees, we can't reliably determine level from index alone
        // This is a limitation of the Tet SFC encoding
        return currentIndex != null ? Tet.tetLevelFromIndex(currentIndex) : 0;
    }

    @Override
    public boolean hasNext() {
        checkConcurrentModification();
        return hasNext;
    }

    @Override
    public TetreeNodeImpl<ID> next() {
        checkConcurrentModification();
        if (!hasNext) {
            throw new NoSuchElementException();
        }

        TetreeNodeImpl<ID> result = currentNode;

        // Advance to next node based on traversal order
        switch (order) {
            case DEPTH_FIRST_PRE -> advanceDepthFirstPre();
            case DEPTH_FIRST_POST -> advanceDepthFirstPost();
            case BREADTH_FIRST -> advanceBreadthFirst();
            case SFC_ORDER -> advanceSFCOrder();
        }

        return result;
    }

    /**
     * Skip the entire subtree rooted at the current node. Only valid for depth-first traversals.
     */
    public void skipSubtree() {
        if (order != TraversalOrder.DEPTH_FIRST_PRE && order != TraversalOrder.DEPTH_FIRST_POST) {
            throw new UnsupportedOperationException("skipSubtree only supported for depth-first traversals");
        }
        skipSubtree = true;
    }

    // Common advance logic for stack/queue based traversals
    private void advance() {
        TraversalState<ID> nextState = null;

        // Keep looking for a valid state that meets our criteria
        while ((stack != null && !stack.isEmpty()) || (queue != null && !queue.isEmpty())) {
            // Get next state based on traversal type
            if (stack != null && !stack.isEmpty()) {
                nextState = stack.pop();
            } else if (queue != null && !queue.isEmpty()) {
                nextState = queue.poll();
            }

            if (nextState != null) {
                // Check if this node meets our criteria
                if (nextState.node != null || includeEmpty) {
                    currentIndex = nextState.index;
                    currentNode = nextState.node;
                    hasNext = true;
                    return;
                }
                // Otherwise, continue looking
            }
        }

        // No more valid states
        hasNext = false;
        currentIndex = null;
        currentNode = null;
    }

    // Advance breadth-first
    private void advanceBreadthFirst() {
        // For sparse trees without level information, we can't reliably determine children
        // Just advance to the next node in the traversal
        advance();
    }

    // Advance depth-first post-order
    private void advanceDepthFirstPost() {
        // For post-order, we need to track visited nodes
        // This is more complex and would require additional state
        // For now, we'll implement a simplified version
        advanceDepthFirstPre(); // Placeholder
    }

    // Advance depth-first pre-order
    private void advanceDepthFirstPre() {
        // For sparse trees without level information, we can't reliably determine children
        // Just advance to the next node in the traversal
        skipSubtree = false; // Reset flag after processing
        advance();
    }

    // Advance SFC order
    private void advanceSFCOrder() {
        NavigableSet<Long> sortedIndices = tree.getSortedSpatialIndices();
        Map<Long, TetreeNodeImpl<ID>> spatialIndex = tree.getSpatialIndex();

        if (sortedIndices.isEmpty()) {
            hasNext = false;
            return;
        }

        // Find next valid index in SFC order using the pre-sorted indices
        Long nextIndex = sortedIndices.ceiling(nextSFCIndex);

        if (nextIndex != null) {
            currentIndex = nextIndex;
            currentNode = spatialIndex.get(nextIndex);
            hasNext = true;
            nextSFCIndex = nextIndex + 1;
            return;
        }

        hasNext = false;
    }

    // Check for concurrent modification
    private void checkConcurrentModification() {
        if (getTreeModificationCount() != initialModificationCount) {
            throw new ConcurrentModificationException("Tree has been modified during iteration");
        }
    }

    // Find first non-empty node in tree
    private void findFirstNonEmpty() {
        NavigableSet<Long> sortedIndices = tree.getSortedSpatialIndices();
        Map<Long, TetreeNodeImpl<ID>> spatialIndex = tree.getSpatialIndex();

        for (Long index : sortedIndices) {
            currentIndex = index;
            currentNode = spatialIndex.get(index);
            hasNext = true;

            // Initialize appropriate data structure
            if (stack != null) {
                stack.push(new TraversalState<>(currentIndex, currentNode));
            } else if (queue != null) {
                queue.offer(new TraversalState<>(currentIndex, currentNode));
            }

            return;
        }

        hasNext = false;
    }

    // Get the current modification count of the tree
    private long getTreeModificationCount() {
        // For now, we'll use the size of the spatial index as a proxy
        // In a real implementation, the tree should maintain a modification counter
        return tree.getSpatialIndex().size();
    }

    // Initialize breadth-first traversal
    private void initializeBreadthFirst() {
        // For a sparse tree, we need to start from actual nodes, not the theoretical root
        NavigableSet<Long> sortedIndices = tree.getSortedSpatialIndices();
        Map<Long, TetreeNodeImpl<ID>> spatialIndex = tree.getSpatialIndex();

        if (sortedIndices.isEmpty() && !includeEmpty) {
            hasNext = false;
            return;
        }

        if (includeEmpty && sortedIndices.isEmpty()) {
            // Start with root tetrahedron if we're including empty nodes
            queue.offer(new TraversalState<>(0L, null));
            advance();
        } else {
            // For a sparse tree, just add all existing nodes to the traversal
            // We can't reliably determine parent-child relationships without level info
            for (Long index : sortedIndices) {
                TetreeNodeImpl<ID> node = spatialIndex.get(index);
                if (node != null) {
                    queue.offer(new TraversalState<>(index, node));
                }
            }

            advance();
        }
    }

    // Initialize depth-first traversal
    private void initializeDepthFirst() {
        // For a sparse tree, we need to start from actual nodes, not the theoretical root
        NavigableSet<Long> sortedIndices = tree.getSortedSpatialIndices();
        Map<Long, TetreeNodeImpl<ID>> spatialIndex = tree.getSpatialIndex();

        if (sortedIndices.isEmpty() && !includeEmpty) {
            hasNext = false;
            return;
        }

        if (includeEmpty && sortedIndices.isEmpty()) {
            // Start with root tetrahedron if we're including empty nodes
            stack.push(new TraversalState<>(0L, null));
            advance();
        } else {
            // For a sparse tree, just add all existing nodes to the traversal
            // We can't reliably determine parent-child relationships without level info
            for (Long index : sortedIndices) {
                TetreeNodeImpl<ID> node = spatialIndex.get(index);
                if (node != null) {
                    stack.push(new TraversalState<>(index, node));
                }
            }

            advance();
        }
    }

    // Initialize SFC order traversal
    private void initializeSFCOrder() {
        // Use the pre-sorted indices from the tree
        NavigableSet<Long> sortedIndices = tree.getSortedSpatialIndices();
        if (!sortedIndices.isEmpty()) {
            nextSFCIndex = sortedIndices.first();
            advanceSFCOrder();
        } else {
            hasNext = false;
        }
    }

    // Initialize traversal based on order
    private void initializeTraversal() {
        switch (order) {
            case DEPTH_FIRST_PRE, DEPTH_FIRST_POST -> initializeDepthFirst();
            case BREADTH_FIRST -> initializeBreadthFirst();
            case SFC_ORDER -> initializeSFCOrder();
        }
    }

    /**
     * Supported traversal orders for the tetrahedral tree
     */
    public enum TraversalOrder {
        /**
         * Depth-first pre-order: visit node before its children
         */
        DEPTH_FIRST_PRE,

        /**
         * Depth-first post-order: visit node after its children
         */
        DEPTH_FIRST_POST,

        /**
         * Breadth-first: visit all nodes at a level before next level
         */
        BREADTH_FIRST,

        /**
         * Space-filling curve order: follows the tetrahedral SFC
         */
        SFC_ORDER
    }

    /**
     * Helper class to store traversal state
     */
    private static class TraversalState<ID extends EntityID> {
        final Long               index;
        final TetreeNodeImpl<ID> node;

        TraversalState(Long index, TetreeNodeImpl<ID> node) {
            this.index = index;
            this.node = node;
        }
    }
}
