/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.render.inspector;

/**
 * Marker interface for spatial data structures that can be visualized
 * in the SpatialInspectorApp.
 *
 * Implementations include:
 * - ESVOOctreeData (octree-based sparse voxel structure)
 * - ESVTData (tetrahedral sparse voxel structure)
 *
 * @author hal.hildebrand
 */
public interface SpatialData {

    /**
     * Get the number of nodes in this spatial structure.
     * @return node count
     */
    int nodeCount();

    /**
     * Get the maximum depth of this spatial structure.
     * @return maximum depth level
     */
    int maxDepth();

    /**
     * Get the number of leaf nodes (voxels) in this structure.
     * @return leaf count
     */
    int leafCount();

    /**
     * Get the number of internal (non-leaf) nodes.
     * @return internal node count
     */
    int internalCount();

    /**
     * Get the size in bytes of this data structure.
     * @return size in bytes
     */
    int sizeInBytes();

    /**
     * Check if this data structure is empty.
     * @return true if no nodes exist
     */
    default boolean isEmpty() {
        return nodeCount() == 0;
    }
}
