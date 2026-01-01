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

import com.hellblazer.luciferase.render.inspector.SpatialData;

import java.nio.ByteBuffer;

/**
 * Interface for sparse voxel data structures.
 *
 * <p>Extends {@link SpatialData} with operations specific to sparse voxel structures,
 * including node access, far pointer resolution, and contour handling.
 *
 * <p>Both ESVO (octree) and ESVT (tetrahedral) data structures implement this interface.
 *
 * <p><b>Node Types:</b> The interface uses {@link SparseVoxelNode} as the generic
 * node type. Implementations may return more specific node types.
 *
 * <p><b>Coordinate Spaces:</b> Different implementations use different coordinate
 * conventions. Use {@link #getCoordinateSpace()} to determine the correct space
 * for ray generation and intersection testing.
 *
 * <p><b>Thread Safety:</b> Implementations should be safe for concurrent read access.
 * Write operations require external synchronization.
 *
 * @param <N> the concrete node type
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.core.ESVOOctreeData
 * @see com.hellblazer.luciferase.esvt.core.ESVTData
 * @see CoordinateSpace
 */
public interface SparseVoxelData<N extends SparseVoxelNode> extends SpatialData {

    // === Coordinate Space ===

    /**
     * Get the coordinate space used by this data structure.
     *
     * <p>Renderers must generate rays in this coordinate space for correct
     * intersection results.
     *
     * @return the coordinate space (never null)
     */
    CoordinateSpace getCoordinateSpace();

    // === Node Access ===

    /**
     * Get all nodes in the structure.
     *
     * <p>Nodes are stored in depth-first order for efficient traversal.
     *
     * @return array of nodes (may be internal array, do not modify)
     */
    N[] nodes();

    /**
     * Get a node at the specified index.
     *
     * @param index node index
     * @return node at index, or null if index is out of bounds
     */
    default N getNode(int index) {
        var n = nodes();
        return (index >= 0 && index < n.length) ? n[index] : null;
    }

    /**
     * Get the root node.
     *
     * @return root node, or null if empty
     */
    default N root() {
        var n = nodes();
        return n.length > 0 ? n[0] : null;
    }

    // === Far Pointers ===

    /**
     * Get the far pointers array.
     *
     * <p>When a node has {@code isFar()==true}, its childPtr is an index into
     * this array, and {@code farPointers[childPtr]} contains the actual
     * relative offset.
     *
     * @return far pointers array, or empty array if no far pointers
     */
    int[] getFarPointers();

    /**
     * Check if this data has far pointers.
     *
     * @return true if far pointers exist
     */
    default boolean hasFarPointers() {
        var fp = getFarPointers();
        return fp != null && fp.length > 0;
    }

    /**
     * Get the number of far pointers.
     *
     * @return number of far pointers
     */
    default int farPointerCount() {
        var fp = getFarPointers();
        return fp != null ? fp.length : 0;
    }

    /**
     * Resolve a child pointer, handling far pointers if needed.
     *
     * @param node the node to resolve child pointer for
     * @return the actual child index in the nodes array
     */
    default int resolveChildPtr(N node) {
        int ptr = node.getChildPtr();
        if (node.isFar()) {
            var fp = getFarPointers();
            if (fp != null && ptr < fp.length) {
                return fp[ptr];
            }
        }
        return ptr;
    }

    // === Contours ===

    /**
     * Get the contour data array.
     *
     * <p>Contour data provides surface refinement information. The contour
     * pointer in each node indexes into this array.
     *
     * @return contour data array, or empty array if no contours
     */
    default int[] getContours() {
        return new int[0]; // Default: no contours
    }

    /**
     * Check if this data has contour information.
     *
     * @return true if contours exist
     */
    default boolean hasContours() {
        var c = getContours();
        return c != null && c.length > 0;
    }

    /**
     * Get the number of contour values.
     *
     * @return number of contour values
     */
    default int contourCount() {
        var c = getContours();
        return c != null ? c.length : 0;
    }

    // === Size Calculations ===

    /**
     * Get the size in bytes of the node array.
     *
     * @return node data size in bytes
     */
    default int nodeSizeInBytes() {
        return nodeCount() * SparseVoxelNode.SIZE_BYTES;
    }

    /**
     * Get the size in bytes of the contour array.
     *
     * @return contour data size in bytes
     */
    default int contourSizeInBytes() {
        return contourCount() * 4; // 4 bytes per int
    }

    /**
     * Get the size in bytes of the far pointer array.
     *
     * @return far pointer data size in bytes
     */
    default int farPointerSizeInBytes() {
        return farPointerCount() * 4; // 4 bytes per int
    }

    // === Serialization ===

    /**
     * Pack all nodes into a ByteBuffer for GPU transfer.
     *
     * <p>Uses native byte order for performance.
     *
     * @return ByteBuffer containing packed node data
     */
    ByteBuffer nodesToByteBuffer();

    /**
     * Pack contours into a ByteBuffer for GPU transfer.
     *
     * <p>Uses native byte order for performance.
     *
     * @return ByteBuffer containing packed contour data (may be empty)
     */
    default ByteBuffer contoursToByteBuffer() {
        var contours = getContours();
        var buffer = ByteBuffer.allocateDirect(contourSizeInBytes())
                               .order(java.nio.ByteOrder.nativeOrder());
        if (contours != null) {
            for (int contour : contours) {
                buffer.putInt(contour);
            }
        }
        buffer.flip();
        return buffer;
    }

    // === Statistics ===

    /**
     * Get the ratio of leaf nodes to total nodes.
     *
     * @return leaf ratio (0.0 to 1.0)
     */
    default float leafRatio() {
        int count = nodeCount();
        return count > 0 ? (float) leafCount() / count : 0.0f;
    }

    /**
     * Get the average bytes per node including all data.
     *
     * @return average bytes per node
     */
    default float averageBytesPerNode() {
        int count = nodeCount();
        return count > 0 ? (float) sizeInBytes() / count : 0.0f;
    }
}
