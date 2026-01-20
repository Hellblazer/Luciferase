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
package com.hellblazer.luciferase.esvo.traversal;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;

/**
 * Traversal utility for DAGs supporting both ABSOLUTE and RELATIVE addressing modes.
 *
 * <p>Provides static helper methods for traversing sparse voxel data structures
 * (both SVO and DAG) with support for polymorphic addressing modes.
 *
 * @author hal.hildebrand
 */
public final class DAGTraversalHelper {

    private DAGTraversalHelper() {
        // Utility class
    }

    /**
     * Resolve the absolute child index for a given parent and octant.
     *
     * <p>Automatically handles both RELATIVE (SVO) and ABSOLUTE (DAG) addressing
     * modes via the data structure's {@link SparseVoxelData#getAddressingMode()}
     * and {@link SparseVoxelData#resolveChildIndex(int, Object, int)} methods.
     *
     * @param data the sparse voxel data (SVO or DAG)
     * @param parentIdx parent node index
     * @param node parent node
     * @param octant child octant [0-7]
     * @return absolute child index in the node pool
     * @throws IndexOutOfBoundsException if octant is not in [0, 7]
     */
    public static int resolveChildIndex(SparseVoxelData data, int parentIdx, ESVONodeUnified node, int octant) {
        return data.resolveChildIndex(parentIdx, node, octant);
    }

    /**
     * Traverse to a leaf node following the given path.
     *
     * <p>The path array contains octant indices [0-7] to follow from root to leaf.
     * Handles both SVO (RELATIVE) and DAG (ABSOLUTE) addressing automatically.
     *
     * @param data the sparse voxel data
     * @param path octant path from root to leaf
     * @return leaf node index in the node pool, or -1 if path is invalid
     */
    public static int traverseToLeaf(SparseVoxelData data, int[] path) {
        var nodes = data.nodes();
        if (nodes.length == 0) {
            return -1;
        }

        int currentIdx = 0; // Start at root
        for (int octant : path) {
            if (octant < 0 || octant > 7) {
                return -1;
            }

            var node = nodes[currentIdx];
            if (node == null || node.getChildMask() == 0) {
                return -1; // Leaf node
            }

            // Check if this octant has a child
            if ((node.getChildMask() & (1 << octant)) == 0) {
                return -1; // No child in this octant
            }

            // Resolve child index using polymorphic addressing
            currentIdx = data.resolveChildIndex(currentIdx, node, octant);

            if (currentIdx < 0 || currentIdx >= nodes.length) {
                return -1; // Invalid child index
            }
        }

        return currentIdx;
    }

    /**
     * Simple ray traversal stub for testing.
     *
     * <p>This is a simplified implementation for testing purposes. Real ray tracing
     * would involve intersection tests and stepping through the octree/DAG structure.
     *
     * @param data the sparse voxel data
     * @param rayOriginX ray origin X
     * @param rayOriginY ray origin Y
     * @param rayOriginZ ray origin Z
     * @param rayDirX ray direction X
     * @param rayDirY ray direction Y
     * @param rayDirZ ray direction Z
     * @param maxSteps maximum traversal steps
     * @return hit node index, or -1 if no hit
     */
    public static int rayTrace(SparseVoxelData data, float rayOriginX, float rayOriginY, float rayOriginZ,
                               float rayDirX, float rayDirY, float rayDirZ, int maxSteps) {
        var nodes = data.nodes();
        if (nodes.length == 0) {
            return -1;
        }

        // Simple implementation: just traverse along the ray direction
        // In a real implementation, this would involve proper ray-AABB intersection tests
        int currentIdx = 0;
        for (int step = 0; step < maxSteps; step++) {
            var node = nodes[currentIdx];
            if (node == null) {
                return -1;
            }

            // If leaf node, return it
            if (node.getChildMask() == 0) {
                return currentIdx;
            }

            // Step through octants (simplified - just follow first child for now)
            boolean foundChild = false;
            for (int octant = 0; octant < 8; octant++) {
                if ((node.getChildMask() & (1 << octant)) != 0) {
                    currentIdx = data.resolveChildIndex(currentIdx, node, octant);
                    if (currentIdx >= 0 && currentIdx < nodes.length) {
                        foundChild = true;
                        break;
                    }
                }
            }

            if (!foundChild) {
                return currentIdx;
            }
        }

        return currentIdx;
    }
}
