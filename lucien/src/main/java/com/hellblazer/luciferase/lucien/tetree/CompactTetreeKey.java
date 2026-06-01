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
 * Compact spatial key implementation for Tetree structures using single 64-bit representation for levels 0-10.
 * This implementation handles 95%+ of typical use cases with optimal performance and minimal memory overhead.
 *
 * <h3>Memory Layout</h3>
 * <ul>
 * <li><b>Total Storage</b>: 64 bits (single long)</li>
 * <li><b>TM-Index Data</b>: Up to 60 bits used (6 bits per level × 10 levels maximum)</li>
 * <li><b>Level Storage</b>: Stored separately in parent TetreeKey class</li>
 * <li><b>Unused Bits</b>: 4 bits remain unused (positions 60-63)</li>
 * </ul>
 *
 * <h3>Performance Characteristics</h3>
 * <ul>
 * <li><b>Memory Efficiency</b>: 50% less memory than ExtendedTetreeKey</li>
 * <li><b>Speed</b>: Faster operations due to single-long arithmetic</li>
 * <li><b>Cache Friendly</b>: Better CPU cache utilization</li>
 * </ul>
 *
 * <h3>Level Transition</h3>
 * When operations require levels beyond 10, the TetreeKey.create() factory method automatically
 * returns an ExtendedTetreeKey instance to handle the increased capacity requirements.
 *
 * @author hal.hildebrand
 */
public class CompactTetreeKey extends TetreeKey<CompactTetreeKey> {

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
     * Protected constructor for subclasses that need to support higher levels. Used by ExtendedTetreeKey to extend
     * CompactTetreeKey for levels > 10.
     *
     * @param level          the hierarchical level (no validation)
     * @param tmIndex        the TM-index bits
     * @param skipValidation marker parameter to distinguish from public constructor
     */
    protected CompactTetreeKey(byte level, long tmIndex, boolean skipValidation) {
        super(level);
        this.tmIndex = tmIndex;
    }

    @Override
    public int compareTo(TetreeKey other) {
        Objects.requireNonNull(other, "Cannot compare to null TetreeKey");
        
        // CRITICAL: First compare level - essential for SFC ordering across levels
        int levelComparison = Byte.compare(this.level, other.getLevel());
        if (levelComparison != 0) {
            return levelComparison;
        }
        
        // Levels are equal, now compare TM-index bits. Coarsest-at-MSB layout (Luciferase-tkvb):
        // the shallowest step occupies the most significant bits, so compare (highBits, lowBits) as
        // a 128-bit unsigned value - high bits first - to reproduce the coarse-dominant SFC order.
        // A CompactTetreeKey has no high bits (always 0).
        int highComparison = Long.compareUnsigned(0L, other.getHighBits());
        if (highComparison != 0) {
            return highComparison;
        }

        return Long.compareUnsigned(tmIndex, other.getLowBits());
    }

    // equals() is final in TetreeKey (Luciferase-567m): uniform (level, lowBits, highBits) comparison across all
    // implementations. The old instanceof-CompactTetreeKey form was asymmetric vs ExtendedTetreeKey.

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

    // hashCode() is final in TetreeKey (Luciferase-567m): Objects.hash(level, lowBits, highBits), aligned to the
    // final equals() so all implementations hash identically for equal keys.

    @Override
    public boolean isValid() {
        if (!super.isValid()) {
            return false;
        }

        // For non-root levels, check that we don't have bits set beyond what's needed
        if (level > 0) {
            // Each level uses 6 bits, so for level n we need n*6 bits
            int bitsNeeded = level * BITS_PER_LEVEL;
            if (bitsNeeded >= 64) {
                // All bits could be valid
                return true;
            }
            long maxValue = (1L << bitsNeeded) - 1;
            return tmIndex <= maxValue;
        }

        return true;
    }

    @Override
    public TetreeKey<? extends TetreeKey<?>> parent() {
        if (level == 0) {
            return null; // Root has no parent
        }

        // Calculate parent by removing the last 6-bit tuple
        byte parentLevel = (byte) (level - 1);

        // Shift right by 6 bits to remove the last level's data
        long parentTmIndex = tmIndex >>> BITS_PER_LEVEL;

        return new CompactTetreeKey(parentLevel, parentTmIndex);
    }
}
