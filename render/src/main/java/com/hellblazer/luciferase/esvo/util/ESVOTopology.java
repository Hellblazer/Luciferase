/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.util;

/**
 * Utility class for parent/child relationships in ESVO octree topology.
 * 
 * <p>This class provides O(1) calculations for navigating the octree structure using
 * ESVO's breadth-first layout where the root is at index 0, its 8 children at indices 1-8,
 * their 64 children at indices 9-72, and so on.</p>
 * 
 * <p>Octree Layout:</p>
 * <ul>
 *   <li>Root: index 0 (level 0)</li>
 *   <li>Level 1: indices 1-8 (8 nodes = 8^1)</li>
 *   <li>Level 2: indices 9-72 (64 nodes = 8^2)</li>
 *   <li>Level 3: indices 73-584 (512 nodes = 8^3)</li>
 *   <li>...</li>
 * </ul>
 * 
 * @author Hal Hildebrand
 */
public class ESVOTopology {
    
    /** Root node index (always 0) */
    public static final int ROOT_INDEX = 0;
    
    /** Number of children per octree node */
    public static final int OCTREE_BRANCHING_FACTOR = 8;
    
    /**
     * Returns the root node index.
     * 
     * @return the root index (always 0)
     */
    public static int getRootIndex() {
        return ROOT_INDEX;
    }
    
    /**
     * Returns the parent index of a given node.
     * 
     * <p>Formula: parent(i) = (i - 1) / 8 for i > 0</p>
     * 
     * @param nodeIndex the node index
     * @return the parent node index
     * @throws IllegalArgumentException if nodeIndex is 0 (root has no parent) or negative
     */
    public static int getParentIndex(int nodeIndex) {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("Node index must be non-negative");
        }
        if (nodeIndex == 0) {
            throw new IllegalArgumentException("Root node has no parent");
        }
        return (nodeIndex - 1) / OCTREE_BRANCHING_FACTOR;
    }
    
    /**
     * Returns the indices of all 8 potential children of a parent node.
     * 
     * <p>Formula: children(i) = [8*i + 1, 8*i + 2, ..., 8*i + 8]</p>
     * 
     * <p>Note: These are the <i>potential</i> child indices. The actual octree may not
     * have all children allocated (sparse octree). Callers should verify child existence
     * using octree data structures.</p>
     * 
     * @param parentIndex the parent node index
     * @return array of 8 child indices
     * @throws IllegalArgumentException if parentIndex is negative
     */
    public static int[] getChildIndices(int parentIndex) {
        if (parentIndex < 0) {
            throw new IllegalArgumentException("Node index must be non-negative");
        }
        
        int firstChild = parentIndex * OCTREE_BRANCHING_FACTOR + 1;
        int[] children = new int[OCTREE_BRANCHING_FACTOR];
        
        for (int i = 0; i < OCTREE_BRANCHING_FACTOR; i++) {
            children[i] = firstChild + i;
        }
        
        return children;
    }
    
    /**
     * Returns the octant index (0-7) of a child relative to its parent.
     * 
     * <p>Octant encoding:</p>
     * <ul>
     *   <li>0: (-x, -y, -z)</li>
     *   <li>1: (+x, -y, -z)</li>
     *   <li>2: (-x, +y, -z)</li>
     *   <li>3: (+x, +y, -z)</li>
     *   <li>4: (-x, -y, +z)</li>
     *   <li>5: (+x, -y, +z)</li>
     *   <li>6: (-x, +y, +z)</li>
     *   <li>7: (+x, +y, +z)</li>
     * </ul>
     * 
     * @param nodeIndex the node index
     * @return the octant index (0-7) relative to parent
     * @throws IllegalArgumentException if nodeIndex is 0 (root has no octant) or negative
     */
    public static int getOctantIndex(int nodeIndex) {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("Node index must be non-negative");
        }
        if (nodeIndex == 0) {
            throw new IllegalArgumentException("Root node has no octant");
        }
        return (nodeIndex - 1) % OCTREE_BRANCHING_FACTOR;
    }
    
    /**
     * Returns the depth level of a node in the octree.
     * 
     * <p>Uses iterative calculation for O(log n) complexity.</p>
     * 
     * @param nodeIndex the node index
     * @return the level (0 = root, 1 = first subdivision, etc.)
     * @throws IllegalArgumentException if nodeIndex is negative
     */
    public static int getNodeLevel(int nodeIndex) {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("Node index must be non-negative");
        }
        if (nodeIndex == 0) {
            return 0;
        }
        
        int level = 0;
        int levelStart = 1; // First node of current level
        int levelSize = OCTREE_BRANCHING_FACTOR;  // Number of nodes in current level
        
        while (nodeIndex >= levelStart + levelSize) {
            levelStart += levelSize;
            levelSize *= OCTREE_BRANCHING_FACTOR;
            level++;
        }
        
        return level + 1;
    }
    
    /**
     * Returns the indices of all 7 sibling nodes (nodes with the same parent).
     * 
     * <p>The returned array excludes the node itself and includes all other children
     * of the parent.</p>
     * 
     * @param nodeIndex the node index
     * @return array of 7 sibling indices
     * @throws IllegalArgumentException if nodeIndex is 0 (root has no siblings) or negative
     */
    public static int[] getSiblingIndices(int nodeIndex) {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("Node index must be non-negative");
        }
        if (nodeIndex == 0) {
            throw new IllegalArgumentException("Root node has no siblings");
        }
        
        int parentIndex = getParentIndex(nodeIndex);
        int[] allChildren = getChildIndices(parentIndex);
        int[] siblings = new int[OCTREE_BRANCHING_FACTOR - 1];
        
        int siblingIndex = 0;
        for (int child : allChildren) {
            if (child != nodeIndex) {
                siblings[siblingIndex++] = child;
            }
        }
        
        return siblings;
    }
    
    /**
     * Returns the first node index at a given level.
     * 
     * <p>Formula: firstNode(level) = (8^level - 1) / 7</p>
     * 
     * @param level the depth level (0 = root)
     * @return the first node index at this level
     * @throws IllegalArgumentException if level is negative
     */
    public static int getFirstNodeAtLevel(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Level must be non-negative");
        }
        if (level == 0) {
            return 0;
        }
        
        // Sum of geometric series: 1 + 8 + 8^2 + ... + 8^(level-1)
        // = (8^level - 1) / (8 - 1) = (8^level - 1) / 7
        int nodesBeforeLevel = 0;
        int levelSize = 1;
        for (int i = 0; i < level; i++) {
            nodesBeforeLevel += levelSize;
            levelSize *= OCTREE_BRANCHING_FACTOR;
        }
        return nodesBeforeLevel;
    }
    
    /**
     * Returns the number of nodes at a given level.
     * 
     * <p>Formula: nodeCount(level) = 8^level</p>
     * 
     * @param level the depth level (0 = root)
     * @return the number of nodes at this level
     * @throws IllegalArgumentException if level is negative
     */
    public static int getNodeCountAtLevel(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Level must be non-negative");
        }
        return (int) Math.pow(OCTREE_BRANCHING_FACTOR, level);
    }
    
    /**
     * Checks if a node index is valid for a given maximum depth.
     * 
     * @param nodeIndex the node index to check
     * @param maxDepth the maximum depth of the octree
     * @return true if the node index is valid
     */
    public static boolean isValidNodeIndex(int nodeIndex, int maxDepth) {
        if (nodeIndex < 0) {
            return false;
        }
        int level = getNodeLevel(nodeIndex);
        return level <= maxDepth;
    }
}
