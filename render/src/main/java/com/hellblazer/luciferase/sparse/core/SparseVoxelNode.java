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

import java.nio.ByteBuffer;

/**
 * Common interface for sparse voxel nodes (ESVO and ESVT).
 *
 * <p>Both ESVO (octree) and ESVT (tetrahedral) nodes share the same 8-byte structure
 * with child descriptors and contour descriptors. This interface captures the
 * common operations supported by both node types.
 *
 * <p><b>Node Structure:</b> 8 bytes total
 * <ul>
 *   <li>childDescriptor (32 bits): child mask, leaf mask, far flag, child pointer</li>
 *   <li>contourDescriptor (32 bits): contour mask, contour pointer, type-specific data</li>
 * </ul>
 *
 * <p><b>Key Differences:</b>
 * <ul>
 *   <li>ESVO: 8-bit contour mask (6 cube faces + 2 reserved)</li>
 *   <li>ESVT: 4-bit contour mask (4 tetrahedron faces), 3-bit tet type</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Implementations should be mutable but thread-safe for
 * concurrent read access. Write operations require external synchronization.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.core.ESVONodeUnified
 * @see com.hellblazer.luciferase.esvt.core.ESVTNodeUnified
 */
public interface SparseVoxelNode {

    /** Node size in bytes (both ESVO and ESVT use 8-byte nodes) */
    int SIZE_BYTES = 8;

    /** Maximum number of children (8 for both octree octants and Bey subdivision) */
    int MAX_CHILDREN = 8;

    // === Descriptor Access ===

    /**
     * Get the raw child descriptor (first 32 bits of node).
     *
     * @return child descriptor containing child mask, leaf mask, far flag, and child pointer
     */
    int getChildDescriptor();

    /**
     * Get the raw contour descriptor (second 32 bits of node).
     *
     * @return contour descriptor containing contour mask and contour pointer
     */
    int getContourDescriptor();

    // === Child Mask Operations ===

    /**
     * Get the child mask indicating which children exist.
     *
     * <p>Bit i is set if child i exists. For sparse indexing, use
     * {@link #getChildIndex(int, int)} to convert from child index to sparse index.
     *
     * @return 8-bit mask of existing children
     */
    int getChildMask();

    /**
     * Set the child mask.
     *
     * @param mask 8-bit mask indicating which children exist
     */
    void setChildMask(int mask);

    /**
     * Check if a child exists at the given index.
     *
     * @param childIdx child index (0-7)
     * @return true if child exists
     * @throws IllegalArgumentException if childIdx is not in range 0-7
     */
    boolean hasChild(int childIdx);

    // === Leaf Mask Operations ===

    /**
     * Get the leaf mask indicating which children are leaves.
     *
     * <p>Bit i is set if child i is a leaf node (contains voxel data).
     * If bit i is clear, child i is an internal node with further children.
     *
     * @return 8-bit mask of leaf children
     */
    int getLeafMask();

    /**
     * Set the leaf mask.
     *
     * @param mask 8-bit mask indicating which children are leaves
     */
    void setLeafMask(int mask);

    /**
     * Check if a child is a leaf node.
     *
     * @param childIdx child index (0-7)
     * @return true if child is a leaf
     * @throws IllegalArgumentException if childIdx is not in range 0-7
     */
    boolean isChildLeaf(int childIdx);

    // === Child Pointer Operations ===

    /**
     * Get the child pointer offset.
     *
     * <p>This is a relative offset to the first child in the node array.
     * The actual child address depends on the far flag.
     *
     * @return child pointer offset
     */
    int getChildPtr();

    /**
     * Set the child pointer offset.
     *
     * @param ptr child pointer offset
     */
    void setChildPtr(int ptr);

    /**
     * Check if this node uses a far pointer.
     *
     * <p>Far pointers allow referencing children beyond the normal pointer range.
     *
     * @return true if using far pointer
     */
    boolean isFar();

    /**
     * Set the far pointer flag.
     *
     * @param isFar true to use far pointer
     */
    void setFar(boolean isFar);

    // === Contour Operations ===

    /**
     * Get the contour mask indicating which faces have contour data.
     *
     * <p><b>Note:</b> The number of bits used differs by implementation:
     * <ul>
     *   <li>ESVO: 8 bits (6 cube faces + 2 reserved)</li>
     *   <li>ESVT: 4 bits (4 tetrahedron faces)</li>
     * </ul>
     *
     * @return contour mask
     */
    int getContourMask();

    /**
     * Set the contour mask.
     *
     * @param mask contour mask
     */
    void setContourMask(int mask);

    /**
     * Get the contour pointer offset.
     *
     * @return contour pointer offset into contour data array
     */
    int getContourPtr();

    /**
     * Set the contour pointer offset.
     *
     * @param ptr contour pointer offset
     */
    void setContourPtr(int ptr);

    // === Sparse Indexing ===

    /**
     * Calculate the sparse index for a child.
     *
     * <p>Given a child index (0-7), returns the position in the sparse child array.
     * This uses popcount of the child mask bits below the target bit.
     *
     * @param childIdx   child index (0-7)
     * @param childMask  child mask to use (typically from {@link #getChildMask()})
     * @return sparse index, or -1 if child does not exist
     */
    default int getChildIndex(int childIdx, int childMask) {
        if ((childMask & (1 << childIdx)) == 0) {
            return -1; // Child doesn't exist
        }
        // Count set bits below childIdx
        int mask = (1 << childIdx) - 1;
        return Integer.bitCount(childMask & mask);
    }

    // === Serialization ===

    /**
     * Write this node to a ByteBuffer at the current position.
     *
     * <p>Writes 8 bytes: childDescriptor followed by contourDescriptor.
     * The buffer position is advanced by 8.
     *
     * @param buffer target buffer with at least 8 bytes remaining
     */
    void writeTo(ByteBuffer buffer);

    /**
     * Read node data from a ByteBuffer at the current position.
     *
     * <p>Reads 8 bytes: childDescriptor followed by contourDescriptor.
     * The buffer position is advanced by 8.
     *
     * @param buffer source buffer with at least 8 bytes remaining
     */
    void readFrom(ByteBuffer buffer);

    // === Utility ===

    /**
     * Count the number of existing children.
     *
     * @return number of children (0-8)
     */
    default int childCount() {
        return Integer.bitCount(getChildMask());
    }

    /**
     * Count the number of leaf children.
     *
     * @return number of leaves (0-8)
     */
    default int leafCount() {
        return Integer.bitCount(getLeafMask() & getChildMask());
    }

    /**
     * Check if this node is empty (no children).
     *
     * @return true if no children exist
     */
    default boolean isEmpty() {
        return getChildMask() == 0;
    }

    /**
     * Check if all children are leaves.
     *
     * @return true if all existing children are leaves
     */
    default boolean isFullyLeaf() {
        int cm = getChildMask();
        int lm = getLeafMask();
        return cm != 0 && (cm & lm) == cm;
    }
}
