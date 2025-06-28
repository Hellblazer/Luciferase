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
 * Compact spatial key implementation for Tetree structures using single 64-bit representation for levels 0-10. This is
 * more memory efficient and faster than the full 128-bit TetreeKey for the common case where level <= 10.
 *
 * Memory layout: 64 bits total - 60 bits for TM-index (6 bits per level × 10 levels) - Level is stored in the parent
 * class
 *
 * @author hal.hildebrand
 */
public class CompactTetreeKey extends BaseTetreeKey<CompactTetreeKey> {

    // The TM-index data (up to 60 bits used)
    private final long tmIndex;

    /**
     * Create a new CompactTetreeKey.
     *
     * @param level   the hierarchical level (0-10)
     * @param tmIndex the TM-index bits (up to 60 bits)
     */
    public CompactTetreeKey(byte level, long tmIndex) {
        super(level);
        if (level > MAX_COMPACT_LEVEL) {
            throw new IllegalArgumentException(
            "CompactTetreeKey only supports levels 0-" + MAX_COMPACT_LEVEL + ", got: " + level);
        }
        this.tmIndex = tmIndex;
    }

    /**
     * Protected constructor for subclasses that need to support higher levels.
     * Used by TetreeKey to extend CompactTetreeKey for levels > 10.
     *
     * @param level   the hierarchical level (no validation)
     * @param tmIndex the TM-index bits
     * @param skipValidation marker parameter to distinguish from public constructor
     */
    protected CompactTetreeKey(byte level, long tmIndex, boolean skipValidation) {
        super(level);
        this.tmIndex = tmIndex;
    }

    @Override
    public int compareTo(BaseTetreeKey other) {
        Objects.requireNonNull(other, "Cannot compare to null TetreeKey");

        // If levels differ, compare by level first (shallower nodes come first)
        if (level != other.getLevel()) {
            return Byte.compare(level, other.getLevel());
        }

        // Same level, compare tm-index values (unsigned comparison)
        // First compare low bits
        int lowComparison = Long.compareUnsigned(tmIndex, other.getLowBits());
        if (lowComparison != 0) {
            return lowComparison;
        }

        // If low bits are equal, compare high bits (this key has no high bits)
        return Long.compareUnsigned(0L, other.getHighBits());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CompactTetreeKey that)) {
            return false;
        }
        return level == that.level && tmIndex == that.tmIndex;
    }

    @Override
    public long getHighBits() {
        return 0L; // Compact key has no high bits
    }

    @Override
    public long getLowBits() {
        return tmIndex;
    }

    /**
     * Get the raw TM-index value.
     *
     * @return the TM-index
     */
    public long getTmIndex() {
        return tmIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, tmIndex);
    }

    @Override
    public boolean isValid() {
        if (!super.isValid()) {
            return false;
        }

        // For non-root levels, check that we don't have bits set beyond what's needed
        if (level > 0) {
            long maxBitsForLevel = (1L << (level * BITS_PER_LEVEL)) - 1;
            return (tmIndex & ~maxBitsForLevel) == 0;
        }

        return true;
    }

    @Override
    public BaseTetreeKey<? extends BaseTetreeKey> parent() {
        if (level == 0) {
            return null; // Root has no parent
        }

        // Calculate parent by removing the last 6-bit tuple
        byte parentLevel = (byte) (level - 1);

        // Shift right by 6 bits to remove the last level's data
        long parentTmIndex = tmIndex >>> BITS_PER_LEVEL;

        return new CompactTetreeKey(parentLevel, parentTmIndex);
    }

    /**
     * Convert to full TetreeKey representation.
     *
     * @return equivalent TetreeKey
     */
    public TetreeKey toFullKey() {
        return new TetreeKey(level, tmIndex, 0L);
    }

    @Override
    public String toString() {
        if (level == 0) {
            return "CompactTetreeKey[level=0, tm-index=0]";
        }
        return String.format("CompactTetreeKey[level=%d, tm-index=0x%X]", level, tmIndex);
    }
}
