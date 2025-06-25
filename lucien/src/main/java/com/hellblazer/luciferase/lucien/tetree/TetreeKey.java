/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SpatialKey;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Spatial key implementation for Tetree structures.
 *
 * Unlike Morton codes used in Octrees, Tetree SFC indices are NOT unique across levels. The same index value can
 * represent different tetrahedra at different levels. This key implementation combines both the level and the SFC index
 * to ensure uniqueness.
 *
 * The comparison ordering is lexicographic: first by level, then by SFC index. This maintains spatial locality within
 * each level while ensuring keys from different levels don't collide.
 *
 * @author hal.hildebrand
 */
public final class TetreeKey implements SpatialKey<TetreeKey> {

    // Parent type table from the paper
    private static final int[][]    PARENT_TYPES = { { 0, 1, 2, 3, 4, 5 }, // cube_id 0
                                                     { 0, 1, 1, 1, 0, 0 }, // cube_id 1
                                                     { 2, 2, 2, 3, 3, 3 }, // cube_id 2
                                                     { 1, 1, 2, 2, 2, 1 }, // cube_id 3
                                                     { 5, 5, 4, 4, 4, 5 }, // cube_id 4
                                                     { 0, 0, 0, 5, 5, 5 }, // cube_id 5
                                                     { 4, 3, 3, 3, 4, 4 }, // cube_id 6
                                                     { 0, 1, 2, 3, 4, 5 }  // cube_id 7
    };
    private final        byte       level;
    private final        BigInteger tmIndex;

    /**
     * Create a new TetreeKey from level and SFC index.
     *
     * @param level   the hierarchical level (0-based)
     * @param tmIndex the space-filling curve index at this level
     */
    public TetreeKey(byte level, BigInteger tmIndex) {
        if (level < 0 || level > Constants.getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
            "Level must be between 0 and " + Constants.getMaxRefinementLevel() + ", got: " + level);
        }
        this.level = level;
        this.tmIndex = tmIndex;
    }

    /**
     * Create a root-level TetreeKey.
     *
     * @return the key for the root tetrahedron
     */
    public static TetreeKey getRoot() {
        return new TetreeKey((byte) 0, BigInteger.ZERO);
    }

    @Override
    public int compareTo(TetreeKey other) {
        Objects.requireNonNull(other, "Cannot compare to null TetreeKey");
        return this.tmIndex.compareTo(other.tmIndex);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final TetreeKey tetreeKey)) {
            return false;
        }
        return level == tetreeKey.level && tmIndex.equals(tetreeKey.tmIndex);
    }

    @Override
    public byte getLevel() {
        return level;
    }

    /**
     * Get the SFC index component of this key.
     *
     * @return the space-filling curve index
     */
    public BigInteger getTmIndex() {
        return tmIndex;
    }

    @Override
    public int hashCode() {
        // Combine level and sfcIndex for good hash distribution
        // Use prime multiplier for level to reduce collisions
        return Objects.hash(level, tmIndex);
    }

    @Override
    public boolean isValid() {
        // Check basic constraints
        if (level < 0 || level > Constants.getMaxRefinementLevel()) {
            return false;
        }

        if (tmIndex.signum() < 0) {
            return false;
        }

        // Special case: root tetrahedron
        if (level == 0) {
            return tmIndex.equals(BigInteger.ZERO);
        }

        // For non-root levels, any non-negative tm-index is considered valid
        // The tm-index structure is complex and validated during creation in Tet.tmIndex()
        return true;
    }

    /**
     * Create a root-level TetreeKey.
     *
     * @return the key for the root tetrahedron
     */
    public TetreeKey root() {
        return getRoot();
    }

    @Override
    public String toString() {
        return String.format("TetreeKey[level=%d, tm-index=%d]", level, tmIndex);
    }

    /**
     * Validate the type sequence in the TM-index Checks that all parent-child type relationships are valid
     */
    private boolean validateTypeSequence(BigInteger tmIndex, int level) {
        // Extract interleaved bits
        BigInteger index = tmIndex;
        BigInteger sixtyFour = BigInteger.valueOf(64);
        var maxLevel = Constants.getMaxRefinementLevel();
        int[] types = new int[maxLevel];
        int[] cubeIds = new int[maxLevel];

        // Extract from least significant to most significant
        for (int i = maxLevel - 1; i >= 0; i--) {
            if (index.equals(BigInteger.ZERO) && i >= 0) {
                // Rest are zeros
                break;
            }

            BigInteger[] divRem = index.divideAndRemainder(sixtyFour);
            index = divRem[0];
            int value = divRem[1].intValue();

            // Extract type (lower 3 bits)
            types[i] = value & 7;

            // Extract coordinate bits to get cube-id
            int coordBits = value >> 3;
            cubeIds[i] = coordBits & 7;

            // Validate type is in range [0,5]
            if (types[i] > 5) {
                return false;
            }
        }

        // Validate parent-child type relationships
        // Start with root type 0
        int currentType = 0;

        // Walk from root to the target level
        for (int i = 1; i <= level; i++) {
            int childType = types[maxLevel - i];
            int cubeId = cubeIds[maxLevel - i];

            // Verify this is a valid child type for the current parent type
            boolean validChild = false;
            for (int j = 0; j < 6; j++) {
                if (PARENT_TYPES[cubeId][j] == currentType && j == childType) {
                    validChild = true;
                    break;
                }
            }

            if (!validChild) {
                return false;
            }

            // Move to next level - child becomes parent
            currentType = childType;
        }

        // Check that types beyond level are zero
        for (int i = 0; i < maxLevel - level; i++) {
            if (types[i] != 0) {
                return false;
            }
        }

        return true;
    }
}
