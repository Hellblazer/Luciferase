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
package com.hellblazer.luciferase.sparse.core;

/**
 * Pointer addressing modes for spatial voxel data structures.
 *
 * <p>This enum distinguishes between two fundamental approaches to child pointer
 * resolution in sparse voxel structures:
 *
 * <ul>
 * <li><b>RELATIVE (SVO)</b>: Child indices are computed relative to the parent node.
 *     Formula: {@code childIndex = parentIdx + childPtr + sparseIdx}
 *     <ul>
 *       <li>Requires parent context for address resolution</li>
 *       <li>Single parent per node</li>
 *       <li>Used by traditional Sparse Voxel Octrees</li>
 *     </ul>
 * </li>
 * <li><b>ABSOLUTE (DAG)</b>: Child indices are absolute positions in the node pool.
 *     Formula: {@code childIndex = childPtr + sparseIdx}
 *     <ul>
 *       <li>Standalone addressing (no parent context needed)</li>
 *       <li>Enables node sharing (multiple parents)</li>
 *       <li>Used by Directed Acyclic Graph structures</li>
 *     </ul>
 * </li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see SparseVoxelData
 */
public enum PointerAddressingMode {
    /**
     * Relative addressing: requires parent context for child index resolution.
     * Used by SVO (Sparse Voxel Octree) structures.
     */
    RELATIVE,

    /**
     * Absolute addressing: no parent context needed for child index resolution.
     * Used by DAG (Directed Acyclic Graph) structures.
     */
    ABSOLUTE
}
