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
 * Abstract base class for Tetree spatial keys. Provides common functionality for both compact (single-long) and full
 * (two-long) representations.
 *
 * This class handles the common case where level <= 10 efficiently with a single long. Subclasses extend this for
 * levels > 10.
 *
 * @param <K> The concrete key type
 * @author hal.hildebrand
 */
public abstract class BaseTetreeKey<K extends BaseTetreeKey<K>>
implements SpatialKey<BaseTetreeKey<? extends BaseTetreeKey>> {

    // Bit layout constants
    protected static final int BITS_PER_LEVEL    = 6;
    protected static final int MAX_COMPACT_LEVEL = 10;

    // Cached root instance - root is always compact
    private static final CompactTetreeKey ROOT = new CompactTetreeKey((byte) 0, 0L);

    // The level stored separately for all key types
    protected final byte level;

    /**
     * Create a new BaseTetreeKey.
     *
     * @param level the hierarchical level
     */
    protected BaseTetreeKey(byte level) {
        if (level < 0 || level > Constants.getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
            "Level must be between 0 and " + Constants.getMaxRefinementLevel() + ", got: " + level);
        }
        this.level = level;
    }

    /**
     * Create an appropriate TetreeKey based on the level.
     *
     * @param level    the level
     * @param lowBits  the low 64 bits
     * @param highBits the high 64 bits (ignored for levels <= 10)
     * @return CompactTetreeKey for levels <= 10, TetreeKey for levels > 10
     */
    public static BaseTetreeKey<? extends BaseTetreeKey> create(byte level, long lowBits, long highBits) {
        if (level <= MAX_COMPACT_LEVEL) {
            return new CompactTetreeKey(level, lowBits);
        } else {
            return new TetreeKey(level, lowBits, highBits);
        }
    }

    /**
     * Get the root key as a compact key.
     *
     * @return CompactTetreeKey root
     */
    public static CompactTetreeKey getRootCompact() {
        return ROOT;
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

    @Override
    public boolean isValid() {
        // Check basic constraints
        if (level < 0 || level > Constants.getMaxRefinementLevel()) {
            return false;
        }

        // Special case: root tetrahedron
        if (level == 0) {
            return getLowBits() == 0L && getHighBits() == 0L;
        }

        // Subclasses may add additional validation
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final K root() {
        // Root is always level 0 and always fits in compact representation
        // This cast is safe because all implementations must accept CompactTetreeKey as a valid key
        return (K) ROOT;
    }
}
