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
        super(level, lowBits);
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

    /**
     * Create a root-level TetreeKey.
     *
     * @return the key for the root tetrahedron
     */
    public static TetreeKey getRoot() {
        return new TetreeKey((byte) 0, 0L, 0L);
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
        return super.isValid();

        // For non-root levels, the tm-index structure is complex and validated during creation in Tet.tmIndex()
        // We trust that if it was created, it's valid
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
