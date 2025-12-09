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

import javax.vecmath.Vector3f;

/**
 * Utility class for converting ESVO octree node indices to 3D spatial bounds.
 * 
 * <p>This class provides geometric calculations for octree nodes in ESVO's breadth-first layout.
 * The root node (index 0) spans the full [0,0,0] to [1,1,1] coordinate space, and each level
 * subdivides space by 2 in each dimension.</p>
 * 
 * <p>Node Layout:</p>
 * <ul>
 *   <li>Root: index 0 (level 0)</li>
 *   <li>Children: indices 1-8 (level 1)</li>
 *   <li>Grandchildren: indices 9-72 (level 2)</li>
 *   <li>...</li>
 * </ul>
 * 
 * @author Hal Hildebrand
 */
public class ESVONodeGeometry {
    
    /**
     * Axis-aligned bounding box representing spatial bounds of an octree node.
     */
    public static class Bounds {
        public final Vector3f min;
        public final Vector3f max;
        
        public Bounds(Vector3f min, Vector3f max) {
            this.min = min;
            this.max = max;
        }
        
        public Vector3f getCenter() {
            return new Vector3f(
                (min.x + max.x) / 2.0f,
                (min.y + max.y) / 2.0f,
                (min.z + max.z) / 2.0f
            );
        }
        
        public Vector3f getSize() {
            return new Vector3f(
                max.x - min.x,
                max.y - min.y,
                max.z - min.z
            );
        }
        
        @Override
        public String toString() {
            return String.format("Bounds[min=%s, max=%s]", min, max);
        }
    }
    
    /**
     * Returns the depth level of a node in the octree.
     * 
     * @param nodeIndex the node index (0-based)
     * @return the level (0 = root, 1 = first subdivision, etc.)
     */
    public static int getNodeLevel(int nodeIndex) {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("Node index must be non-negative");
        }
        if (nodeIndex == 0) {
            return 0;
        }
        
        // Calculate level using logarithm base 8
        // Level L contains nodes from indices: sum(8^i, i=0..L-1) + 1 to sum(8^i, i=0..L)
        // Equivalent to: (8^L - 1) / 7 + 1 to (8^(L+1) - 1) / 7
        int level = 0;
        int levelStart = 1; // First node of current level
        int levelSize = 8;  // Number of nodes in current level
        
        while (nodeIndex >= levelStart + levelSize) {
            levelStart += levelSize;
            levelSize *= 8;
            level++;
        }
        
        return level + 1;
    }
    
    /**
     * Returns the size (edge length) of a node at the given level.
     * 
     * @param level the depth level (0 = root)
     * @return the edge length of nodes at this level (root = 1.0, level 1 = 0.5, etc.)
     */
    public static float getNodeSize(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Level must be non-negative");
        }
        return 1.0f / (1 << level); // Equivalent to: 1.0 / (2^level)
    }
    
    /**
     * Returns the center point of a node.
     * 
     * @param nodeIndex the node index
     * @param maxDepth the maximum depth of the octree (for validation)
     * @return the center point of the node
     */
    public static Vector3f getNodeCenter(int nodeIndex, int maxDepth) {
        var bounds = getNodeBounds(nodeIndex, maxDepth);
        return bounds.getCenter();
    }
    
    /**
     * Returns the axis-aligned bounding box of a node.
     * 
     * <p>This method calculates the spatial bounds by walking from the root down to
     * the target node, subdividing the space at each level based on the octant index.</p>
     * 
     * @param nodeIndex the node index
     * @param maxDepth the maximum depth of the octree (for validation)
     * @return the bounding box of the node
     */
    public static Bounds getNodeBounds(int nodeIndex, int maxDepth) {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("Node index must be non-negative");
        }
        
        int level = getNodeLevel(nodeIndex);
        if (level > maxDepth) {
            throw new IllegalArgumentException(
                String.format("Node level %d exceeds maxDepth %d", level, maxDepth)
            );
        }
        
        // Start with root bounds
        var min = new Vector3f(0.0f, 0.0f, 0.0f);
        var max = new Vector3f(1.0f, 1.0f, 1.0f);
        
        if (nodeIndex == 0) {
            return new Bounds(min, max);
        }
        
        // Walk down the tree to find the node's position
        int currentIndex = nodeIndex;
        float[] octantPath = new float[level];
        
        // Build path from node back to root
        for (int i = level - 1; i >= 0; i--) {
            int parentIndex = (currentIndex - 1) / 8;
            int octant = (currentIndex - 1) % 8;
            octantPath[i] = octant;
            currentIndex = parentIndex;
        }
        
        // Apply octant subdivisions from root to node
        for (int i = 0; i < level; i++) {
            int octant = (int) octantPath[i];
            var mid = new Vector3f(
                (min.x + max.x) / 2.0f,
                (min.y + max.y) / 2.0f,
                (min.z + max.z) / 2.0f
            );
            
            // Octant encoding: bit 0 = x, bit 1 = y, bit 2 = z
            // 0: (0,0,0) min-min-min
            // 1: (1,0,0) max-min-min
            // 2: (0,1,0) min-max-min
            // 3: (1,1,0) max-max-min
            // 4: (0,0,1) min-min-max
            // 5: (1,0,1) max-min-max
            // 6: (0,1,1) min-max-max
            // 7: (1,1,1) max-max-max
            
            if ((octant & 1) == 0) {
                max.x = mid.x;
            } else {
                min.x = mid.x;
            }
            
            if ((octant & 2) == 0) {
                max.y = mid.y;
            } else {
                min.y = mid.y;
            }
            
            if ((octant & 4) == 0) {
                max.z = mid.z;
            } else {
                min.z = mid.z;
            }
        }
        
        return new Bounds(min, max);
    }
}
