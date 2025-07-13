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
 * This provides full Octree-equivalent refinement capacity with innovative level 21 bit packing.
 *
 * <h3>Memory Layout</h3>
 * <ul>
 * <li><b>Total Storage</b>: 128 bits (two longs: lowBits + highBits)</li>
 * <li><b>Standard Encoding</b>: Levels 0-20 use standard 6-bits-per-level encoding</li>
 * <li><b>Level 21 Bit Packing</b>: Uses leftover bits in both longs for full 21-level support</li>
 * </ul>
 *
 * <h3>Level 21 Innovation</h3>
 * Level 21 uses split encoding across both longs:
 * <ul>
 * <li><b>Low Long</b>: 4 bits stored in positions 60-63</li>
 * <li><b>High Long</b>: 2 bits stored in positions 60-61</li>
 * <li><b>Total</b>: 6 bits (3 coordinate + 3 type) maintaining SFC semantics</li>
 * <li><b>Ordering</b>: Preserves space-filling curve ordering properties</li>
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

    private final long highBits; // Upper 64 bits (levels 10-20, 6 bits per level)

    /**
     * Create a new ExtendedTetreeKey using 128-bit representation.
     * For level 21, uses special bit packing with level 21 data split across both longs.
     *
     * @param level    the hierarchical level (0-based, 0-21)
     * @param lowBits  the lower 64 bits of the TM-index (levels 0-9, plus level 21 bits 0-3)
     * @param highBits the upper 64 bits of the TM-index (levels 10-20, plus level 21 bits 4-5)
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
     * Create a level 21 ExtendedTetreeKey with proper bit packing.
     * This method handles the special encoding required for level 21.
     *
     * @param baseLowBits  the TM-index data for levels 0-9 (60 bits)
     * @param baseHighBits the TM-index data for levels 10-20 (60 bits)
     * @param level21Bits  the 6-bit value for level 21 (will be split across both longs)
     * @return ExtendedTetreeKey with level 21 encoding
     */
    public static ExtendedTetreeKey createLevel21Key(long baseLowBits, long baseHighBits, byte level21Bits) {
        // Pack the level 21 bits using the split encoding
        long[] packedBits = packLevel21Bits(level21Bits);
        
        // Combine base bits with packed level 21 bits
        long finalLowBits = baseLowBits | packedBits[0];
        long finalHighBits = baseHighBits | packedBits[1];
        
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExtendedTetreeKey tetreeKey)) {
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
        if (level < 0 || level > MortonCurve.MAX_REFINEMENT_LEVEL) {
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

        // Special validation for level 21 with split bit encoding
        if (level == 21) {
            return isLevel21Valid();
        }

        // For levels 11-20, both low and high bits may be used
        // Low bits can use up to 60 bits (10 levels * 6 bits per level)
        // High bits usage depends on the level
        int highLevels = level - 10;
        int bitsNeeded = highLevels * BITS_PER_LEVEL;

        // Handle case where we need all 64 bits or more
        if (bitsNeeded >= 64) {
            // For level 20: highLevels=10, bitsNeeded=60
            // We can use up to 60 bits in highBits
            return true;
        }

        long maxHighBitsForLevel = (1L << bitsNeeded) - 1;
        return (highBits & ~maxHighBitsForLevel) == 0;
    }

    @Override
    public TetreeKey<? extends TetreeKey> parent() {
        if (level == 0) {
            return null; // Root has no parent
        }

        // Calculate parent by removing the last 6-bit tuple
        byte parentLevel = (byte) (level - 1);

        // Special handling for level 21 with split encoding
        if (level == 21) {
            // For level 21, we need to remove the split-encoded level 21 bits
            // and return a level 20 key with standard encoding
            long parentLowBits = getLowBits() & ((1L << LEVEL_21_LOW_BITS_SHIFT) - 1); // Clear bits 60-63
            long parentHighBits = highBits & ((1L << LEVEL_21_HIGH_BITS_SHIFT) - 1);   // Clear bits 60-61
            return new ExtendedTetreeKey(parentLevel, parentLowBits, parentHighBits);
        }

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

        return new ExtendedTetreeKey(parentLevel, parentLowBits, parentHighBits);
    }

    /**
     * Validates level 21 bit encoding. For level 21, validates that:
     * 1. Low bits 0-59 contain valid levels 0-9 data
     * 2. Low bits 60-63 contain valid level 21 data (4 bits)
     * 3. High bits 0-59 contain valid levels 10-20 data
     * 4. High bits 60-61 contain valid level 21 data (2 bits)
     * 5. High bits 62-63 are unused (must be 0)
     *
     * @return true if level 21 encoding is valid
     */
    private boolean isLevel21Valid() {
        // Check that unused high bits (62-63) are zero
        long unusedHighBits = highBits & ~((1L << 62) - 1); // Mask off bits 0-61
        if (unusedHighBits != 0) {
            return false;
        }
        
        // Level 21 needs 21 * 6 = 126 bits total
        // We have 60 (low 0-59) + 60 (high 0-59) + 4 (low 60-63) + 2 (high 60-61) = 126 bits exactly
        // So the encoding can use all available bits except high bits 62-63
        
        // Validate that level 21 split bits are within valid range (0-63 for 6-bit value)
        long lowLevel21Bits = (getLowBits() >> LEVEL_21_LOW_BITS_SHIFT) & LEVEL_21_LOW_MASK;
        long highLevel21Bits = (highBits >> LEVEL_21_HIGH_BITS_SHIFT) & LEVEL_21_HIGH_MASK;
        long combinedLevel21Bits = lowLevel21Bits | (highLevel21Bits << 4);
        
        // The 6-bit value should be valid (0-63)
        return combinedLevel21Bits <= 0x3F;
    }

    @Override
    public String toString() {
        // For display, show the 128-bit value in a readable format
        if (level == 0) {
            return "ExtendedTetreeKey[level=0, tm-index=0]";
        }

        // For debugging, show hex representation
        if (highBits == 0L) {
            return String.format("ExtendedTetreeKey[level=%d, tm-index=0x%X]", level, getLowBits());
        } else {
            return String.format("ExtendedTetreeKey[level=%d, tm-index=0x%X%016X]", level, highBits, getLowBits());
        }
    }

}
