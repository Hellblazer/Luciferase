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

import java.util.Objects;

/**
 * Extended spatial key implementation for Tetree structures using 128-bit representation supporting all levels 0-21.
 * This provides full Octree-equivalent refinement capacity.
 *
 * <h3>Bit layout (coarsest-at-MSB, Luciferase-tkvb)</h3>
 * <ul>
 * <li><b>Total Storage</b>: 128 bits (two longs: lowBits = bits 0-63, highBits = bits 64-125)</li>
 * <li><b>Uniform Encoding</b>: 6 bits per level (3 coordinate + 3 type), all levels 1-21 alike</li>
 * <li><b>No Split</b>: 21 * 6 = 126 bits fit two longs with no special level-21 packing</li>
 * <li><b>Ordering</b>: the shallowest step occupies the most significant bits; the leaf is at bits
 *     0-5. {@link #compareTo} compares {@code (highBits, lowBits)} unsigned, giving the
 *     coarse-dominant SFC order (matches {@code PyramidKey})</li>
 * </ul>
 *
 * <h3>Key Features</h3>
 * <ul>
 * <li><b>Global Uniqueness</b>: Level + tmIndex tuple ensures uniqueness across all levels</li>
 * <li><b>Spatial Locality</b>: SFC ordering maintains spatial proximity in key space</li>
 * <li><b>Octree Parity</b>: Full 21-level support matches MortonKey capacity</li>
 * <li><b>Efficient Operations</b>: Optimized parent/child computation with bit manipulation</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ExtendedTetreeKey extends CompactTetreeKey {

    private final long highBits; // Index bits 64-125 (coarsest-at-MSB uniform layout)

    /**
     * Create a new ExtendedTetreeKey using 128-bit representation. Coarsest-at-MSB uniform layout
     * (Luciferase-tkvb): 6 bits per level, leaf at the LSB, no level-21 split.
     *
     * @param level    the hierarchical level (0-based, 0-21)
     * @param lowBits  index bits 0-63 (the deeper refinement steps; leaf at bits 0-5)
     * @param highBits index bits 64-125 (the shallower refinement steps)
     */
    public ExtendedTetreeKey(byte level, long lowBits, long highBits) {
        super(level, lowBits, true); // Use protected constructor to skip level validation
        this.highBits = highBits;
    }

    /**
     * Create a ExtendedTetreeKey from a CompactTetreeKey.
     *
     * @param compactKey the compact key to convert
     * @return equivalent ExtendedTetreeKey
     */
    public static ExtendedTetreeKey fromCompactKey(CompactTetreeKey compactKey) {
        return new ExtendedTetreeKey(compactKey.getLevel(), compactKey.getLowBits(), 0L);
    }

    /**
     * Create a level-21 ExtendedTetreeKey in the coarsest-at-MSB uniform layout (Luciferase-tkvb).
     * {@code (baseHighBits, baseLowBits)} are the 120-bit packed index of the level-20 parent
     * (deepest group at the LSB); the level-21 leaf group is appended at the least-significant end.
     *
     * @param baseLowBits  the level-20 parent's low 64 bits
     * @param baseHighBits the level-20 parent's high bits
     * @param level21Bits  the 6-bit leaf group {@code (coord << 3) | type}
     * @return the level-21 key
     */
    public static ExtendedTetreeKey createLevel21Key(long baseLowBits, long baseHighBits, byte level21Bits) {
        long finalHighBits = (baseHighBits << BITS_PER_LEVEL) | (baseLowBits >>> (64 - BITS_PER_LEVEL));
        long finalLowBits = (baseLowBits << BITS_PER_LEVEL) | (level21Bits & 0x3FL);
        return new ExtendedTetreeKey((byte) 21, finalLowBits, finalHighBits);
    }

    @Override
    public int compareTo(TetreeKey other) {
        Objects.requireNonNull(other, "Cannot compare to null TetreeKey");

        // CRITICAL: First compare level - essential for SFC ordering across levels
        int levelComparison = Byte.compare(this.level, other.getLevel());
        if (levelComparison != 0) {
            return levelComparison;
        }
        
        // Levels are equal, now compare TM-index bits
        // First compare high bits
        int highComparison = Long.compareUnsigned(this.highBits, other.getHighBits());
        if (highComparison != 0) {
            return highComparison;
        }

        // High bits equal, compare low bits
        return Long.compareUnsigned(getLowBits(), other.getLowBits());
    }

    // equals()/hashCode() are final in TetreeKey (Luciferase-567m): uniform (level, lowBits, highBits) across all
    // implementations. The old instanceof-ExtendedTetreeKey equals was asymmetric vs CompactTetreeKey, and the
    // inherited Compact hashCode ignored highBits.

    @Override
    public long getHighBits() {
        return highBits;
    }

    @Override
    public boolean isValid() {
        if (level < 0 || level > MortonCurve.MAX_REFINEMENT_LEVEL) {
            return false;
        }
        // Coarsest-at-MSB uniform layout (Luciferase-tkvb): a level-L key occupies the low
        // 6*L bits of the 128-bit (highBits, lowBits) value; all higher bits must be zero.
        int usedBits = level * BITS_PER_LEVEL;
        if (usedBits <= 64) {
            long lowMask = (usedBits == 64) ? -1L : (1L << usedBits) - 1;
            return (getLowBits() & ~lowMask) == 0L && highBits == 0L;
        }
        int usedHigh = usedBits - 64;
        long highMask = (usedHigh >= 64) ? -1L : (1L << usedHigh) - 1;
        return (highBits & ~highMask) == 0L;
    }

    @Override
    public TetreeKey<? extends TetreeKey<?>> parent() {
        if (level == 0) {
            return null; // Root has no parent
        }

        // Coarsest-at-MSB layout (Luciferase-tkvb): the leaf (deepest) group is at the LSB. The
        // parent drops that group by shifting the whole 128-bit (highBits, lowBits) value right by
        // one 6-bit group. Uniform across all levels - no level-21 split.
        byte parentLevel = (byte) (level - 1);
        long parentLowBits = (getLowBits() >>> BITS_PER_LEVEL) | (highBits << (64 - BITS_PER_LEVEL));
        long parentHighBits = highBits >>> BITS_PER_LEVEL;
        return new ExtendedTetreeKey(parentLevel, parentLowBits, parentHighBits);
    }

    // toString() inherited from TetreeKey base class provides appropriate format
    // Override only if ExtendedTetreeKey needs special handling

}
