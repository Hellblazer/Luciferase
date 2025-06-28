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

import java.util.Objects;

/**
 * Spatial key implementation for Tetree structures using 128-bit representation.
 *
 * Unlike Morton codes used in Octrees, Tetree SFC indices are NOT unique across levels. The same index value can
 * represent different tetrahedra at different levels. This key implementation combines both the level and the SFC index
 * to ensure uniqueness.
 *
 * The TM-index is represented using two longs (128 bits total), which is sufficient for levels 0-21.
 * The comparison ordering ensures spatial locality within each level.
 *
 * @author hal.hildebrand
 */
public class TetreeKey implements SpatialKey<TetreeKey> {

    private final byte level;
    private final long lowBits;  // Lower 64 bits (levels 0-9, 6 bits per level)
    private final long highBits; // Upper 64 bits (levels 10-20, 6 bits per level)

    /**
     * Create a new TetreeKey using 128-bit representation.
     *
     * @param level    the hierarchical level (0-based)
     * @param lowBits  the lower 64 bits of the TM-index (levels 0-9)
     * @param highBits the upper 64 bits of the TM-index (levels 10-20)
     */
    public TetreeKey(byte level, long lowBits, long highBits) {
        if (level < 0 || level > Constants.getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
                "Level must be between 0 and " + Constants.getMaxRefinementLevel() + ", got: " + level);
        }
        this.level = level;
        this.lowBits = lowBits;
        this.highBits = highBits;
    }


    /**
     * Create a root-level TetreeKey.
     *
     * @return the key for the root tetrahedron
     */
    public static TetreeKey getRoot() {
        return new TetreeKey((byte) 0, 0L, 0L);
    }

    @Override
    public int compareTo(TetreeKey other) {
        Objects.requireNonNull(other, "Cannot compare to null TetreeKey");
        
        // Compare high bits first (unsigned comparison)
        int highComparison = Long.compareUnsigned(this.highBits, other.highBits);
        if (highComparison != 0) {
            return highComparison;
        }
        
        // If high bits are equal, compare low bits (unsigned comparison)
        return Long.compareUnsigned(this.lowBits, other.lowBits);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final TetreeKey tetreeKey)) {
            return false;
        }
        return level == tetreeKey.level && lowBits == tetreeKey.lowBits && highBits == tetreeKey.highBits;
    }

    @Override
    public byte getLevel() {
        return level;
    }


    /**
     * Get the lower 64 bits of the 128-bit representation.
     * Only valid when constructed with the 128-bit constructor.
     *
     * @return the lower 64 bits (levels 0-9)
     */
    public long getLowBits() {
        return lowBits;
    }

    /**
     * Get the upper 64 bits of the 128-bit representation.
     * Only valid when constructed with the 128-bit constructor.
     *
     * @return the upper 64 bits (levels 10-20)
     */
    public long getHighBits() {
        return highBits;
    }


    @Override
    public int hashCode() {
        // Combine level and both longs for good hash distribution
        // Use prime multiplier for level to reduce collisions
        return Objects.hash(level, lowBits, highBits);
    }

    @Override
    public boolean isValid() {
        // Check basic constraints
        if (level < 0 || level > Constants.getMaxRefinementLevel()) {
            return false;
        }

        // Special case: root tetrahedron
        if (level == 0) {
            return lowBits == 0L && highBits == 0L;
        }

        // For non-root levels, the tm-index structure is complex and validated during creation in Tet.tmIndex()
        // We trust that if it was created, it's valid
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
    public TetreeKey parent() {
        if (level == 0) {
            return null; // Root has no parent
        }
        
        // Calculate parent by removing the last 6-bit tuple
        // Each level in Tetree uses 6 bits (3 for child index, 3 for type)
        byte parentLevel = (byte) (level - 1);
        
        // The tm-index structure stores levels 0-9 in lowBits (60 bits used)
        // and levels 10-20 in highBits (66 bits used)
        long parentLowBits;
        long parentHighBits;
        
        if (level <= 10) {
            // Current key uses only lowBits, parent also uses only lowBits
            parentLowBits = lowBits >>> 6;
            parentHighBits = 0L;
        } else {
            // Current key uses both lowBits and highBits
            // When level > 10, the encoding changes:
            // - lowBits contains levels 0-9 (unchanged)
            // - highBits contains levels 10 and up
            
            // For parent, we need to remove 6 bits from the highBits
            parentHighBits = highBits >>> 6;
            // lowBits remains the same for levels 0-9
            parentLowBits = lowBits;
        }
        
        return new TetreeKey(parentLevel, parentLowBits, parentHighBits);
    }

    @Override
    public String toString() {
        // For display, show the 128-bit value in a readable format
        if (level == 0) {
            return "TetreeKey[level=0, tm-index=0]";
        }
        
        // For debugging, show hex representation
        if (highBits == 0L) {
            return String.format("TetreeKey[level=%d, tm-index=0x%X]", level, lowBits);
        } else {
            return String.format("TetreeKey[level=%d, tm-index=0x%X%016X]", level, highBits, lowBits);
        }
    }

}
