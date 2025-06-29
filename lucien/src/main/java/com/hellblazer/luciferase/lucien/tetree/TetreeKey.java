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

import java.util.Objects;

/**
 * Full spatial key implementation for Tetree structures using 128-bit representation for all levels 0-21. This extends
 * CompactTetreeKey to add the high bits needed for levels > 10.
 *
 * Unlike Morton codes used in Octrees, Tetree SFC indices are NOT unique across levels. The same index value can
 * represent different tetrahedra at different levels. This key implementation combines both the level and the SFC index
 * to ensure uniqueness.
 *
 * The TM-index is represented using two longs (128 bits total), which is sufficient for levels 0-21. The comparison
 * ordering ensures spatial locality within each level.
 *
 * @author hal.hildebrand
 */
public class TetreeKey extends CompactTetreeKey {

    private final long highBits; // Upper 64 bits (levels 10-20, 6 bits per level)

    /**
     * Create a new TetreeKey using 128-bit representation.
     *
     * @param level    the hierarchical level (0-based)
     * @param lowBits  the lower 64 bits of the TM-index (levels 0-9)
     * @param highBits the upper 64 bits of the TM-index (levels 10-20)
     */
    public TetreeKey(byte level, long lowBits, long highBits) {
        super(level, lowBits, true); // Use protected constructor to skip level validation
        this.highBits = highBits;
    }

    /**
     * Create a TetreeKey from a CompactTetreeKey.
     *
     * @param compactKey the compact key to convert
     * @return equivalent TetreeKey
     */
    public static TetreeKey fromCompactKey(CompactTetreeKey compactKey) {
        return new TetreeKey(compactKey.getLevel(), compactKey.getLowBits(), 0L);
    }

    @Override
    public int compareTo(BaseTetreeKey other) {
        Objects.requireNonNull(other, "Cannot compare to null TetreeKey");

        // If levels differ, compare by level first (shallower nodes come first)
        if (getLevel() != other.getLevel()) {
            return Byte.compare(getLevel(), other.getLevel());
        }

        // Same level, compare high bits first
        int highComparison = Long.compareUnsigned(this.highBits, other.getHighBits());
        if (highComparison != 0) {
            return highComparison;
        }

        // High bits equal, compare low bits
        return Long.compareUnsigned(getLowBits(), other.getLowBits());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TetreeKey tetreeKey)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        return highBits == tetreeKey.highBits;
    }

    @Override
    public long getHighBits() {
        return highBits;
    }

    @Override
    public boolean isValid() {
        // Check basic level validity
        if (level < 0 || level > 21) {
            return false;
        }

        // For level 0 (root), should have no bits set
        if (level == 0) {
            return getLowBits() == 0 && highBits == 0;
        }

        // For levels 1-10, only low bits should be used (high bits should be 0)
        if (level <= 10) {
            if (highBits != 0) {
                return false;
            }
            // Check that we don't have bits set beyond what's needed
            long maxBitsForLevel = (1L << (level * BITS_PER_LEVEL)) - 1;
            return (getLowBits() & ~maxBitsForLevel) == 0;
        }

        // For levels 11-21, both low and high bits may be used
        // Low bits can use up to 60 bits (10 levels * 6 bits per level)
        // High bits usage depends on the level
        int highLevels = level - 10;
        int bitsNeeded = highLevels * BITS_PER_LEVEL;

        // Handle case where we need all 64 bits or more
        if (bitsNeeded >= 64) {
            // For level 21: highLevels=11, bitsNeeded=66
            // We can use all 64 bits in highBits, so no validation needed
            return true;
        }

        long maxHighBitsForLevel = (1L << bitsNeeded) - 1;
        return (highBits & ~maxHighBitsForLevel) == 0;
    }

    @Override
    public BaseTetreeKey<? extends BaseTetreeKey> parent() {
        if (level == 0) {
            return null; // Root has no parent
        }

        // Calculate parent by removing the last 6-bit tuple
        byte parentLevel = (byte) (level - 1);

        // The tm-index structure stores levels 0-9 in lowBits (60 bits used)
        // and levels 10-20 in highBits (66 bits used)
        long parentLowBits;
        long parentHighBits;

        if (level <= 10) {
            // Current key uses only lowBits, parent also uses only lowBits
            parentLowBits = getLowBits() >>> BITS_PER_LEVEL;
            parentHighBits = 0L;
        } else {
            // Current key uses both lowBits and highBits
            // When level > 10, the encoding changes:
            // - lowBits contains levels 0-9 (unchanged)
            // - highBits contains levels 10 and up

            // For parent, we need to remove 6 bits from the highBits
            parentHighBits = highBits >>> BITS_PER_LEVEL;
            // lowBits remains the same for levels 0-9
            parentLowBits = getLowBits();
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
            return String.format("TetreeKey[level=%d, tm-index=0x%X]", level, getLowBits());
        } else {
            return String.format("TetreeKey[level=%d, tm-index=0x%X%016X]", level, highBits, getLowBits());
        }
    }

}
