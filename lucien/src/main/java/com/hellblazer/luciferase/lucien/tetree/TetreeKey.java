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

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.SpatialKey;

import java.util.Objects;

/**
 * Abstract base class for Tetree spatial keys. Provides common functionality for both compact (single-long) and full
 * (two-long) representations.
 *
 * This class handles the common case where level <= 10 efficiently with a single long. Subclasses extend this for
 * levels > 10.
 *
 * @param <K> The concrete key type
 * @author hal.hildebrand
 */
public abstract class TetreeKey<K extends TetreeKey<K>> implements SpatialKey<TetreeKey<? extends TetreeKey>> {

    public static final    byte MAX_REFINEMENT_LEVEL = MortonCurve.MAX_REFINEMENT_LEVEL;
    // Bit layout constants
    protected static final int  BITS_PER_LEVEL       = 6;
    protected static final int  MAX_COMPACT_LEVEL    = 10;

    // Cached root instance - root is always compact
    private static final CompactTetreeKey ROOT = new CompactTetreeKey((byte) 0, 0L);

    // The level stored separately for all key types
    protected final byte level;

    /**
     * Create a new TetreeKey.
     *
     * @param level the hierarchical level
     */
    protected TetreeKey(byte level) {
        if (level < 0 || level > MAX_REFINEMENT_LEVEL) {
            throw new IllegalArgumentException(
            "Level must be between 0 and " + MAX_REFINEMENT_LEVEL + ", got: " + level);
        }
        this.level = level;
    }

    /**
     * Create an appropriate ExtendedTetreeKey based on the level.
     *
     * @param level    the level
     * @param lowBits  the low 64 bits
     * @param highBits the high 64 bits (ignored for levels <= 10)
     * @return CompactTetreeKey for levels <= 10, ExtendedTetreeKey for levels > 10
     */
    public static TetreeKey<? extends TetreeKey> create(byte level, long lowBits, long highBits) {
        if (level <= MAX_COMPACT_LEVEL) {
            return new CompactTetreeKey(level, lowBits);
        } else {
            return new ExtendedTetreeKey(level, lowBits, highBits);
        }
    }

    public static TetreeKey<? extends TetreeKey> getRoot() {
        return ROOT;
    }

    /**
     * Checks if this key can be merged with another key in a range. Keys can be merged if they are adjacent or if this
     * key's end + 1 >= other key's start.
     *
     * @param other the key to check for mergeability
     * @return true if the keys can be merged, false otherwise
     */
    public boolean canMergeWith(TetreeKey<?> other) {
        if (other == null || this.level != other.level) {
            return false;
        }

        // Keys at the same level can be merged if they are adjacent or overlapping
        // Since we're dealing with ranges, we consider them mergeable if they're adjacent
        return this.isAdjacentTo(other) || this.equals(other);
    }

    /**
     * Extract the coordinate bits for a specific level from the tm-index.
     *
     * @param targetLevel the level to extract coordinates for (0 to current level)
     * @return the 3-bit coordinate value at that level
     */
    public byte getCoordBitsAtLevel(int targetLevel) {
        if (targetLevel < 0 || targetLevel > level) {
            throw new IllegalArgumentException("Target level must be between 0 and " + level);
        }

        // Determine which long contains this level's data
        if (targetLevel < 10) {
            // In low bits
            int shift = targetLevel * BITS_PER_LEVEL + 3;
            return (byte) ((getLowBits() >> shift) & 0x7);
        } else {
            // In high bits
            int shift = (targetLevel - 10) * BITS_PER_LEVEL + 3;
            return (byte) ((getHighBits() >> shift) & 0x7);
        }
    }

    /**
     * Get the high bits of the TM-index. For levels <= 10, this returns 0. For levels > 10, this contains levels 10+.
     *
     * @return the high bits of the TM-index
     */
    public abstract long getHighBits();

    @Override
    public byte getLevel() {
        return level;
    }

    /**
     * Get the low bits of the TM-index. For levels <= 10, this contains the entire TM-index. For levels > 10, this
     * contains levels 0-9.
     *
     * @return the low bits of the TM-index
     */
    public abstract long getLowBits();

    /**
     * Extract the type bits for a specific level from the tm-index.
     *
     * @param targetLevel the level to extract type for (0 to current level)
     * @return the 3-bit type value at that level
     */
    public byte getTypeAtLevel(int targetLevel) {
        if (targetLevel < 0 || targetLevel > level) {
            throw new IllegalArgumentException("Target level must be between 0 and " + level);
        }

        // Determine which long contains this level's data
        if (targetLevel < 10) {
            // In low bits
            int shift = targetLevel * BITS_PER_LEVEL;
            return (byte) ((getLowBits() >> shift) & 0x7);
        } else {
            // In high bits
            int shift = (targetLevel - 10) * BITS_PER_LEVEL;
            return (byte) ((getHighBits() >> shift) & 0x7);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, getLowBits(), getHighBits());
    }

    /**
     * Checks if this key is adjacent to another key in the space-filling curve. Two keys are considered adjacent if
     * they are at the same level and their indices differ by exactly 1.
     *
     * @param other the key to compare with
     * @return true if the keys are adjacent, false otherwise
     */
    public boolean isAdjacentTo(TetreeKey<?> other) {
        if (other == null || this.level != other.level) {
            return false;
        }

        // For keys at the same level, check if indices differ by 1
        // We need to handle the case where keys might span the boundary between low and high bits
        long thisLow = this.getLowBits();
        long thisHigh = this.getHighBits();
        long otherLow = other.getLowBits();
        long otherHigh = other.getHighBits();

        // Compare as 128-bit values
        if (thisHigh == otherHigh) {
            // High bits are equal, check if low bits differ by 1
            long diff = Math.abs(thisLow - otherLow);
            return diff == 1;
        } else if (Math.abs(thisHigh - otherHigh) == 1) {
            // High bits differ by 1, check for boundary crossing
            if (thisHigh < otherHigh) {
                // This key is smaller, check if it's at max low bits and other is at 0
                return thisLow == 0xFFFFFFFFFFFFFFFFL && otherLow == 0;
            } else {
                // Other key is smaller, check if it's at max low bits and this is at 0
                return otherLow == 0xFFFFFFFFFFFFFFFFL && thisLow == 0;
            }
        }

        return false;
    }

    public boolean isKuhn() {
        return false;
    }

    @Override
    public boolean isValid() {
        // Check basic constraints
        if (level < 0 || level > MAX_REFINEMENT_LEVEL) {
            return false;
        }

        // Special case: root tetrahedron
        if (level == 0) {
            return getLowBits() == 0L && getHighBits() == 0L;
        }

        // Subclasses may add additional validation
        return true;
    }

    /**
     * Returns the maximum of two TetreeKeys at the same level. This is used for determining the end of a merged range.
     *
     * @param other the other key to compare
     * @return the larger of the two keys
     * @throws IllegalArgumentException if keys are at different levels
     */
    public TetreeKey<?> max(TetreeKey<?> other) {
        if (other == null) {
            return this;
        }
        if (this.level != other.level) {
            throw new IllegalArgumentException("Cannot compare keys at different levels");
        }

        return this.compareTo(other) >= 0 ? this : other;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final K root() {
        // Root is always level 0 and always fits in compact representation
        // This cast is safe because all implementations must accept CompactTetreeKey as a valid key
        return (K) ROOT;
    }

}
