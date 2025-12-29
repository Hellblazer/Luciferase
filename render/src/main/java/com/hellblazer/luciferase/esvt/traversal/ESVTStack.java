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
 * @author hal.hildebrand
 */
public final class ESVTStack {

    /** Maximum Tetree depth (21 levels) plus 1 for root */
    public static final int MAX_DEPTH = 21;

    /** Stack size with margin for safety */
    public static final int STACK_SIZE = 24;

    // Stack storage arrays - indexed by scale level
    private final int[] nodeStack;      // Node indices
    private final float[] tMaxStack;    // tMax values for each level
    private final byte[] typeStack;     // Tet types (0-5) at each level
    private final byte[] entryFaceStack; // Entry face (0-3) at each level
    private final byte[] siblingPosStack; // Sibling position (0-3) for resuming after pop
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
     * Write sibling position to stack at given scale level.
     *
     * @param scale Scale level
     * @param siblingPos Sibling position (0-3) to resume from after pop
     */
    public void writeSiblingPos(int scale, byte siblingPos) {
        if (scale >= 0 && scale < STACK_SIZE) {
            siblingPosStack[scale] = siblingPos;
        }
    }

    /**
     * Read sibling position from stack at given scale level.
     *
     * @param scale Scale level
     * @return Sibling position (0-3), or 0 if invalid
     */
    public byte readSiblingPos(int scale) {
        if (scale >= 0 && scale < STACK_SIZE) {
            return siblingPosStack[scale];
        }
        return 0;
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
     */
    public void copyFrom(ESVTStack other) {
        System.arraycopy(other.nodeStack, 0, this.nodeStack, 0, STACK_SIZE);
        System.arraycopy(other.tMaxStack, 0, this.tMaxStack, 0, STACK_SIZE);
        System.arraycopy(other.typeStack, 0, this.typeStack, 0, STACK_SIZE);
        System.arraycopy(other.entryFaceStack, 0, this.entryFaceStack, 0, STACK_SIZE);
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
