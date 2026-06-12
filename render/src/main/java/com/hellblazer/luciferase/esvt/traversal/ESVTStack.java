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
package com.hellblazer.luciferase.esvt.traversal;

/**
 * Stack structure for ESVT ray traversal operations.
 *
 * <p>Manages traversal state during tetrahedral tree navigation using a scale-indexed
 * approach (not traditional push/pop). Each stack level stores the node index, tMax value,
 * tet type, and entry face for that depth level.
 *
 * <p>This design follows the ESVO pattern where the stack is indexed by scale level,
 * allowing direct access to parent state when popping back up the tree.
 *
 * <p><b>Stack Size:</b> 22 entries for 21-level Tetree depth (root + 21 refinements)
 *
 * <p><b>Front-to-back sorted order (2s3jc P2):</b> Each level stores a packed visit order
 * word in {@code sortedOrderStack}. Layout:
 * <ul>
 *   <li>Bits 27–24: count of intersecting children (0–8, 4 bits)</li>
 *   <li>Bits 23–21: sorted child index 0 (3 bits)</li>
 *   <li>Bits 20–18: sorted child index 1 (3 bits)</li>
 *   <li>Bits 17–15: sorted child index 2 (3 bits)</li>
 *   <li>Bits 14–12: sorted child index 3 (3 bits)</li>
 *   <li>Bits 11– 9: sorted child index 4 (3 bits)</li>
 *   <li>Bits  8– 6: sorted child index 5 (3 bits)</li>
 *   <li>Bits  5– 3: sorted child index 6 (3 bits)</li>
 *   <li>Bits  2– 0: sorted child index 7 (3 bits)</li>
 * </ul>
 * The resume position ({@code sortedPos}) is stored in {@code siblingPosStack} (repurposed from
 * its old "childIdx+1" semantics to "index into sorted order to resume from").
 *
 * @author hal.hildebrand
 */
public final class ESVTStack {

    /** Maximum Tetree depth (21 levels) plus 1 for root */
    public static final int MAX_DEPTH = 21;

    /** Stack size with margin for safety */
    public static final int STACK_SIZE = 24;

    // Stack storage arrays - indexed by scale level
    private final int[] nodeStack;        // Node indices
    private final float[] tMaxStack;      // tMax values for each level
    private final byte[] typeStack;       // Tet types (0-5) at each level
    private final byte[] entryFaceStack;  // Entry face (0-3) at each level
    /** Sorted position (index into sorted order) for resuming after pop. Repurposed from siblingPos. */
    private final byte[] siblingPosStack;
    /**
     * Packed sorted visit order per level. Bits 27–24: count (4 bits); bits 23–0: 8 × 3-bit childIdx slots
     * (slot 0 in bits 23–21, slot 7 in bits 2–0). See class javadoc for the full layout.
     */
    private final int[] sortedOrderStack;
    // Parent vertices at each level (4 vertices * 3 coords = 12 floats per level)
    private final float[][] vertsStack; // [level][12] for v0.xyz, v1.xyz, v2.xyz, v3.xyz

    /**
     * Create a new traversal stack.
     */
    public ESVTStack() {
        this.nodeStack = new int[STACK_SIZE];
        this.tMaxStack = new float[STACK_SIZE];
        this.typeStack = new byte[STACK_SIZE];
        this.entryFaceStack = new byte[STACK_SIZE];
        this.siblingPosStack = new byte[STACK_SIZE];
        this.sortedOrderStack = new int[STACK_SIZE];
        this.vertsStack = new float[STACK_SIZE][12];
        reset();
    }

    /**
     * Reset stack to empty state.
     */
    public void reset() {
        for (var i = 0; i < STACK_SIZE; i++) {
            nodeStack[i] = -1;
            tMaxStack[i] = 0.0f;
            typeStack[i] = -1;
            entryFaceStack[i] = -1;
            siblingPosStack[i] = 0;
            sortedOrderStack[i] = 0;
            java.util.Arrays.fill(vertsStack[i], 0.0f);
        }
    }

    /**
     * Write entry to stack at given scale level.
     *
     * @param scale Scale level (0 to MAX_DEPTH-1)
     * @param nodeIndex Node index to store
     * @param tMax tMax value to store
     * @param tetType Tetrahedron type (0-5)
     * @param entryFace Entry face index (0-3)
     */
    public void write(int scale, int nodeIndex, float tMax, byte tetType, byte entryFace) {
        if (scale >= 0 && scale < STACK_SIZE) {
            nodeStack[scale] = nodeIndex;
            tMaxStack[scale] = tMax;
            typeStack[scale] = tetType;
            entryFaceStack[scale] = entryFace;
        }
    }

    /**
     * Write entry to stack at given scale level (without entry face).
     *
     * @param scale Scale level (0 to MAX_DEPTH-1)
     * @param nodeIndex Node index to store
     * @param tMax tMax value to store
     * @param tetType Tetrahedron type (0-5)
     */
    public void write(int scale, int nodeIndex, float tMax, byte tetType) {
        write(scale, nodeIndex, tMax, tetType, (byte) -1);
    }

    /**
     * Write parent vertices to stack at given scale level.
     *
     * @param scale Scale level
     * @param v0x, v0y, v0z Vertex 0 coordinates
     * @param v1x, v1y, v1z Vertex 1 coordinates
     * @param v2x, v2y, v2z Vertex 2 coordinates
     * @param v3x, v3y, v3z Vertex 3 coordinates
     */
    public void writeVerts(int scale,
                          float v0x, float v0y, float v0z,
                          float v1x, float v1y, float v1z,
                          float v2x, float v2y, float v2z,
                          float v3x, float v3y, float v3z) {
        if (scale >= 0 && scale < STACK_SIZE) {
            var verts = vertsStack[scale];
            verts[0] = v0x; verts[1] = v0y; verts[2] = v0z;
            verts[3] = v1x; verts[4] = v1y; verts[5] = v1z;
            verts[6] = v2x; verts[7] = v2y; verts[8] = v2z;
            verts[9] = v3x; verts[10] = v3y; verts[11] = v3z;
        }
    }

    /**
     * Read parent vertices from stack at given scale level.
     *
     * @param scale Scale level
     * @return Float array with 12 values (4 vertices * 3 coords), or null if invalid
     */
    public float[] readVerts(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return vertsStack[scale];
        }
        return null;
    }

    /**
     * Write sorted position (resume index into sorted order) to stack at given scale level.
     * Repurposed from the old "siblingPos = childIdx+1" semantics to "index in sorted order".
     *
     * @param scale      Scale level
     * @param sortedPos  Position in sorted order to resume from (0-based)
     */
    public void writeSiblingPos(int scale, byte sortedPos) {
        if (scale >= 0 && scale < STACK_SIZE) {
            siblingPosStack[scale] = sortedPos;
        }
    }

    /**
     * Read sorted position (resume index into sorted order) from stack at given scale level.
     *
     * @param scale Scale level
     * @return Sorted position, or 0 if invalid
     */
    public byte readSiblingPos(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return siblingPosStack[scale];
        }
        return 0;
    }

    /**
     * Write the packed sorted visit order for the given scale level.
     *
     * <p>Packing: bits 27–24 = count (4 bits, value 0–8); each of slots 0–7 occupies
     * 3 bits starting from bit 23 downward (slot 0 at bits 23–21, slot 7 at bits 2–0).
     *
     * @param scale        Scale level
     * @param packedOrder  Packed word encoding count + 8 sorted child indices
     */
    public void writeSortedOrder(int scale, int packedOrder) {
        if (scale >= 0 && scale < STACK_SIZE) {
            sortedOrderStack[scale] = packedOrder;
        }
    }

    /**
     * Read the packed sorted visit order from the given scale level.
     *
     * @param scale Scale level
     * @return Packed sorted-order word, or 0 if invalid
     */
    public int readSortedOrder(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return sortedOrderStack[scale];
        }
        return 0;
    }

    /**
     * Extract the count of intersecting children from a packed sorted-order word.
     * Count is stored in bits 27–24.
     *
     * @param packedOrder Packed sorted-order word
     * @return Number of intersecting children (0–8)
     */
    public static int sortedCount(int packedOrder) {
        return (packedOrder >> 24) & 0xF;
    }

    /**
     * Extract the child index at position {@code pos} in the sorted order.
     * Slot 0 is in bits 23–21, slot 1 in bits 20–18, …, slot 7 in bits 2–0.
     *
     * @param packedOrder Packed sorted-order word
     * @param pos         Slot index (0–7)
     * @return Child index (0–7) at that slot
     */
    public static int sortedChildAt(int packedOrder, int pos) {
        int shift = 21 - pos * 3;
        return (packedOrder >> shift) & 0x7;
    }

    /**
     * Build a packed sorted-order word from a count and an array of sorted child indices.
     *
     * @param count        Number of valid entries in {@code sortedChildren}
     * @param sortedChildren Array of child indices in sorted visit order (only first {@code count} used)
     * @return Packed word
     */
    public static int packSortedOrder(int count, int[] sortedChildren) {
        int packed = (count & 0xF) << 24;
        for (int i = 0; i < count; i++) {
            int shift = 21 - i * 3;
            packed |= (sortedChildren[i] & 0x7) << shift;
        }
        return packed;
    }

    /**
     * Read node index from stack at given scale level.
     *
     * @param scale Scale level
     * @return Node index, or -1 if invalid
     */
    public int readNode(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return nodeStack[scale];
        }
        return -1;
    }

    /**
     * Read tMax value from stack at given scale level.
     *
     * @param scale Scale level
     * @return tMax value, or 0.0 if invalid
     */
    public float readTmax(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return tMaxStack[scale];
        }
        return 0.0f;
    }

    /**
     * Read tet type from stack at given scale level.
     *
     * @param scale Scale level
     * @return Tet type (0-5), or -1 if invalid
     */
    public byte readType(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return typeStack[scale];
        }
        return -1;
    }

    /**
     * Read entry face from stack at given scale level.
     *
     * @param scale Scale level
     * @return Entry face (0-3), or -1 if invalid
     */
    public byte readEntryFace(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return entryFaceStack[scale];
        }
        return -1;
    }

    /**
     * Check if stack has valid entry at given scale.
     */
    public boolean hasEntry(int scale) {
        return scale >= 0 && scale < STACK_SIZE && nodeStack[scale] != -1;
    }

    /**
     * Clear entry at specific scale level.
     */
    public void clear(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            nodeStack[scale] = -1;
            tMaxStack[scale] = 0.0f;
            typeStack[scale] = -1;
            entryFaceStack[scale] = -1;
            siblingPosStack[scale] = 0;
            sortedOrderStack[scale] = 0;
        }
    }

    /**
     * Get current stack depth (highest valid entry + 1).
     */
    public int getDepth() {
        for (var i = STACK_SIZE - 1; i >= 0; i--) {
            if (nodeStack[i] != -1) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Copy stack state from another stack.
     * Deep-copies all arrays including per-level vertex data (vertsStack).
     */
    public void copyFrom(ESVTStack other) {
        System.arraycopy(other.nodeStack, 0, this.nodeStack, 0, STACK_SIZE);
        System.arraycopy(other.tMaxStack, 0, this.tMaxStack, 0, STACK_SIZE);
        System.arraycopy(other.typeStack, 0, this.typeStack, 0, STACK_SIZE);
        System.arraycopy(other.entryFaceStack, 0, this.entryFaceStack, 0, STACK_SIZE);
        System.arraycopy(other.siblingPosStack, 0, this.siblingPosStack, 0, STACK_SIZE);
        System.arraycopy(other.sortedOrderStack, 0, this.sortedOrderStack, 0, STACK_SIZE);
        for (int i = 0; i < STACK_SIZE; i++) {
            System.arraycopy(other.vertsStack[i], 0, this.vertsStack[i], 0, 12);
        }
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("ESVTStack[depth=").append(getDepth()).append(", entries={");
        var first = true;
        for (var i = 0; i < STACK_SIZE; i++) {
            if (nodeStack[i] != -1) {
                if (!first) sb.append(", ");
                sb.append(i).append(":(node=").append(nodeStack[i])
                  .append(",tMax=").append(String.format("%.3f", tMaxStack[i]))
                  .append(",type=").append(typeStack[i])
                  .append(",face=").append(entryFaceStack[i]).append(")");
                first = false;
            }
        }
        sb.append("}]");
        return sb.toString();
    }
}
