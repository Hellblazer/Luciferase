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
package com.hellblazer.luciferase.esvt.core;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import com.hellblazer.luciferase.sparse.core.SparseVoxelNode;

import java.nio.ByteBuffer;

/**
 * ESVT (Efficient Sparse Voxel Tetrahedra) Node Structure - 8 bytes
 *
 * Based on H0 validation (2025-12-27): Child types derived from TetreeConnectivity
 * tables instead of stored, enabling 8-byte nodes matching ESVO.
 *
 * Structure: 8 bytes total (matches ESVO)
 *
 * childDescriptor (32 bits) - matches ESVO layout exactly:
 *   Bits 0-7:   leafmask (8 bits) - which children are leaves
 *   Bits 8-15:  childmask (8 bits) - which children exist (Bey 8-way subdivision)
 *   Bit 16:     far flag (1 bit) - far pointer flag
 *   Bits 17-31: childptr (15 bits) - child pointer offset (max 32767)
 *
 * contourDescriptor (32 bits):
 *   Bits 0:     reserved (1 bit) - padding
 *   Bits 1-3:   tetType (3 bits) - tetrahedron type (0-5 for S0-S5)
 *   Bits 4-7:   contourMask (4 bits) - which faces have contours (4 tet faces)
 *   Bits 8-11:  normalMask (4 bits) - which faces have custom normals
 *   Bits 12-31: contourPtr (20 bits) - contour data pointer
 *
 * Key insight: Child types are derived via TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[type][childIdx]
 * Benchmark: 1.04ns lookup vs 0.84ns extraction = 0.20ns difference (negligible)
 *
 * @author hal.hildebrand
 */
public final class ESVTNodeUnified implements SparseVoxelNode {

    // Node size in bytes
    public static final int SIZE_BYTES = 8;

    // === Node data (8 bytes total) ===
    private int childDescriptor;    // First 32 bits
    private int contourDescriptor;  // Second 32 bits

    // === Bit masks for childDescriptor (matches ESVO exactly) ===
    private static final int LEAF_MASK_BITS = 0xFF;             // Bits 0-7: leafmask
    private static final int CHILD_MASK_BITS = 0xFF00;          // Bits 8-15: childmask
    private static final int CHILD_MASK_SHIFT = 8;
    private static final int FAR_FLAG_BIT = 0x10000;            // Bit 16: far flag
    private static final int CHILD_PTR_MASK = 0xFFFE0000;       // Bits 17-31: childptr (15 bits)
    private static final int CHILD_PTR_SHIFT = 17;
    private static final int MAX_CHILD_PTR = (1 << 15) - 1;     // 32767 - max direct pointer

    // === Bit masks for contourDescriptor (ESVT-specific) ===
    private static final int TET_TYPE_MASK = 0x0E;              // Bits 1-3: tet type (3 bits)
    private static final int TET_TYPE_SHIFT = 1;
    private static final int CONTOUR_MASK_BITS = 0xF0;          // Bits 4-7: contour mask (4 bits)
    private static final int CONTOUR_MASK_SHIFT = 4;
    private static final int NORMAL_MASK_BITS = 0xF00;          // Bits 8-11: normal mask (4 bits)
    private static final int NORMAL_MASK_SHIFT = 8;
    private static final int CONTOUR_PTR_MASK = 0xFFFFF000;     // Bits 12-31: contour ptr (20 bits)
    private static final int CONTOUR_PTR_SHIFT = 12;

    // === Constructors ===

    /**
     * Create empty node
     */
    public ESVTNodeUnified() {
        this.childDescriptor = 0;
        this.contourDescriptor = 0;
    }

    /**
     * Create node from raw descriptors (for loading from buffer)
     */
    public ESVTNodeUnified(int childDescriptor, int contourDescriptor) {
        this.childDescriptor = childDescriptor;
        this.contourDescriptor = contourDescriptor;
    }

    /**
     * Create node with tetrahedron type
     */
    public ESVTNodeUnified(byte tetType) {
        this.childDescriptor = 0; // Validity derived from masks
        this.contourDescriptor = (tetType & 0x7) << TET_TYPE_SHIFT;
    }

    // === Tetrahedron Type Operations (ESVT-specific) ===

    /**
     * Get the tetrahedron type (0-5 for S0-S5)
     */
    public byte getTetType() {
        return (byte) ((contourDescriptor & TET_TYPE_MASK) >> TET_TYPE_SHIFT);
    }

    /**
     * Set the tetrahedron type (0-5 for S0-S5)
     */
    public void setTetType(byte type) {
        if (type < 0 || type > 5) {
            throw new IllegalArgumentException("Tet type must be 0-5, got: " + type);
        }
        contourDescriptor = (contourDescriptor & ~TET_TYPE_MASK) |
                           ((type & 0x7) << TET_TYPE_SHIFT);
    }

    /**
     * Get the type of a child tetrahedron using Morton-indexed lookup table.
     * This is the key H0 optimization - derive child types instead of storing them.
     *
     * <p><b>CRITICAL:</b> The childIdx parameter is a Morton index (0-7), NOT a Bey index.
     * This matches how children are stored in the tree (Morton order) and how
     * ESVTTraversal accesses them via BEY_NUMBER_TO_INDEX conversion.</p>
     *
     * @param childIdx The child index (0-7, Morton order)
     * @return The child's tetrahedron type (0-5)
     */
    public byte getChildType(int childIdx) {
        if (childIdx < 0 || childIdx > 7) {
            throw new IllegalArgumentException("Child index must be 0-7, got: " + childIdx);
        }
        // Use Morton-indexed table (from t8code t8_dtet_type_of_child_morton)
        return Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[getTetType()][childIdx];
    }

    // === Child Mask Operations (bits 8-15) ===

    /**
     * Get the child mask - which children exist (8 possible from Bey subdivision)
     */
    public int getChildMask() {
        return (childDescriptor & CHILD_MASK_BITS) >> CHILD_MASK_SHIFT;
    }

    public void setChildMask(int mask) {
        childDescriptor = (childDescriptor & ~CHILD_MASK_BITS) |
                         ((mask & 0xFF) << CHILD_MASK_SHIFT);
    }

    /**
     * Check if a child exists at given index (0-7)
     */
    public boolean hasChild(int childIdx) {
        if (childIdx < 0 || childIdx > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getChildMask() & (1 << childIdx)) != 0;
    }

    // === Leaf Mask Operations (bits 0-7) ===

    /**
     * Get the leaf mask - which children are leaf nodes
     */
    public int getLeafMask() {
        return childDescriptor & LEAF_MASK_BITS;
    }

    public void setLeafMask(int mask) {
        childDescriptor = (childDescriptor & ~LEAF_MASK_BITS) | (mask & 0xFF);
    }

    /**
     * Check if a child is a leaf node
     */
    public boolean isChildLeaf(int childIdx) {
        if (childIdx < 0 || childIdx > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return (getLeafMask() & (1 << childIdx)) != 0;
    }

    // === Child Pointer Operations (bits 17-30) ===

    /**
     * Get child pointer offset (15 bits, max 32767)
     */
    public int getChildPtr() {
        return (childDescriptor & CHILD_PTR_MASK) >>> CHILD_PTR_SHIFT;
    }

    public void setChildPtr(int ptr) {
        if (ptr < 0 || ptr > MAX_CHILD_PTR) {
            throw new IllegalArgumentException("Child pointer must fit in 15 bits (max " + MAX_CHILD_PTR + "), got: " + ptr);
        }
        childDescriptor = (childDescriptor & ~CHILD_PTR_MASK) |
                         (ptr << CHILD_PTR_SHIFT);
    }

    // === Far Flag Operations (bit 16) ===

    public boolean isFar() {
        return (childDescriptor & FAR_FLAG_BIT) != 0;
    }

    public void setFar(boolean far) {
        if (far) {
            childDescriptor |= FAR_FLAG_BIT;
        } else {
            childDescriptor &= ~FAR_FLAG_BIT;
        }
    }

    // === Valid Flag Operations (derived from childMask, like ESVO) ===

    /**
     * Check if this node is valid. A node is valid if it has children or is a leaf.
     * This matches ESVO's approach where validity is derived from the masks.
     */
    public boolean isValid() {
        return getChildMask() != 0 || getLeafMask() != 0;
    }

    /**
     * Set valid flag. For compatibility - sets childMask to 0xFF if valid with no children.
     * @deprecated Use setChildMask/setLeafMask directly instead
     */
    @Deprecated
    public void setValid(boolean valid) {
        // No-op for backward compatibility - validity is now derived from masks
    }

    // === Contour Operations (4 faces for tetrahedra) ===

    /**
     * Get contour mask - which faces have contour data (4 bits for 4 tet faces)
     */
    public int getContourMask() {
        return (contourDescriptor & CONTOUR_MASK_BITS) >> CONTOUR_MASK_SHIFT;
    }

    public void setContourMask(int mask) {
        contourDescriptor = (contourDescriptor & ~CONTOUR_MASK_BITS) |
                           ((mask & 0xF) << CONTOUR_MASK_SHIFT);
    }

    /**
     * Get normal mask - which faces have custom normals (4 bits)
     */
    public int getNormalMask() {
        return (contourDescriptor & NORMAL_MASK_BITS) >> NORMAL_MASK_SHIFT;
    }

    public void setNormalMask(int mask) {
        contourDescriptor = (contourDescriptor & ~NORMAL_MASK_BITS) |
                           ((mask & 0xF) << NORMAL_MASK_SHIFT);
    }

    /**
     * Get contour pointer (20 bits)
     */
    public int getContourPtr() {
        return (contourDescriptor & CONTOUR_PTR_MASK) >>> CONTOUR_PTR_SHIFT;
    }

    public void setContourPtr(int ptr) {
        if (ptr < 0 || ptr >= (1 << 20)) {
            throw new IllegalArgumentException("Contour pointer must fit in 20 bits, got: " + ptr);
        }
        contourDescriptor = (contourDescriptor & ~CONTOUR_PTR_MASK) |
                           (ptr << CONTOUR_PTR_SHIFT);
    }

    public boolean hasContour(int faceIdx) {
        if (faceIdx < 0 || faceIdx > 3) {
            throw new IllegalArgumentException("Face index must be 0-3 for tetrahedra");
        }
        return (getContourMask() & (1 << faceIdx)) != 0;
    }

    /**
     * Check if this node has any contour data.
     *
     * @return true if any face has contour data
     */
    public boolean hasContour() {
        return getContourMask() != 0;
    }

    // === Sparse Indexing Algorithm (same as ESVO) ===

    /**
     * Calculate the relative offset of a child within this node's children.
     * Uses popcount-based sparse indexing (identical to ESVO).
     *
     * @param childIdx The child index (0-7)
     * @return The relative offset (0-based) within this node's children
     */
    public int getChildOffset(int childIdx) {
        if (!hasChild(childIdx)) {
            throw new IllegalArgumentException("Child " + childIdx + " does not exist");
        }
        int mask = getChildMask();
        int bitsBeforeChild = mask & ((1 << childIdx) - 1);
        return Integer.bitCount(bitsBeforeChild);
    }

    /**
     * Calculate the actual index of a child in the sparse array.
     * Uses relative pointer - adds currentNodeIdx to the stored offset.
     *
     * @param childIdx The child index (0-7)
     * @param currentNodeIdx The index of this node in the array
     * @return The actual index in the node array
     */
    public int getChildIndex(int childIdx, int currentNodeIdx) {
        return currentNodeIdx + getChildPtr() + getChildOffset(childIdx);
    }

    /**
     * Calculate the actual index of a child, resolving far pointers if needed.
     * Uses relative pointer - adds currentNodeIdx to the stored offset.
     *
     * @param childIdx The child index (0-7)
     * @param currentNodeIdx The index of this node in the array
     * @param farPointers Array of far pointers (may be null or empty)
     * @return The actual index in the node array
     */
    public int getChildIndex(int childIdx, int currentNodeIdx, int[] farPointers) {
        int relativeOffset = getChildPtr();
        if (isFar() && farPointers != null && relativeOffset < farPointers.length) {
            relativeOffset = farPointers[relativeOffset];
        }
        return currentNodeIdx + relativeOffset + getChildOffset(childIdx);
    }

    /**
     * Get the total number of children this node has.
     */
    public int getChildCount() {
        return Integer.bitCount(getChildMask());
    }

    // === Raw Access (for GPU transfer) ===

    public int getChildDescriptor() {
        return childDescriptor;
    }

    public int getContourDescriptor() {
        return contourDescriptor;
    }

    /**
     * Write this node to a ByteBuffer (for GPU transfer).
     * Writes exactly 8 bytes.
     */
    @Override
    public void writeTo(ByteBuffer buffer) {
        buffer.putInt(childDescriptor);
        buffer.putInt(contourDescriptor);
    }

    /**
     * Create a node from a ByteBuffer (static factory).
     * Reads exactly 8 bytes.
     */
    public static ESVTNodeUnified fromByteBuffer(ByteBuffer buffer) {
        int child = buffer.getInt();
        int contour = buffer.getInt();
        return new ESVTNodeUnified(child, contour);
    }

    /**
     * Read node data from a ByteBuffer into this instance.
     * Reads exactly 8 bytes. Implements {@link SparseVoxelNode#readFrom}.
     */
    @Override
    public void readFrom(ByteBuffer buffer) {
        this.childDescriptor = buffer.getInt();
        this.contourDescriptor = buffer.getInt();
    }

    @Override
    public String toString() {
        return String.format("ESVTNode[child=0x%08X, contour=0x%08X, " +
                           "type=%d, childMask=%02X, leafMask=%02X, ptr=%d, far=%b]",
            childDescriptor, contourDescriptor,
            getTetType(), getChildMask(), getLeafMask(), getChildPtr(), isFar());
    }
}
