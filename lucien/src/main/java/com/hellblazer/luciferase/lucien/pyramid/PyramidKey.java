/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SpatialKey;

import javax.vecmath.Point3f;
import java.util.Objects;

/**
 * 128-bit spatial key for the pyramid space-filling curve (RDR-010, Knapp 2026). The key encodes
 * the pyramid index {@code m_P(P) = Z &darr; Y &darr; X &darr; B&sup2; &darr; B&sup1; &darr; B&sup0;}
 * (Knapp Eq 3.4), a 6-dimensional Morton interleaving of three anchor-coordinate tuples
 * {@code (Z,Y,X)} and three type-representing tuples {@code (B&sup2;,B&sup1;,B&sup0;)}. Per refinement
 * step the six interleaved bits form a contiguous group laid out MSB&rarr;LSB as
 * {@code [Z,Y,X,B&sup2;,B&sup1;,B&sup0;]} = 3 coordinate bits followed by 3 type bits. The type bits
 * are the binary digits of the element type {@code 0..7}; the per-step bit budget matches the Tetree
 * extended-key layout, but the underlying mathematics (a bijection with the 6D cube Morton index;
 * Knapp Theorem 3.5) is distinct.
 *
 * <p>{@code PyramidKey} is a sibling of {@code TetreeKey}, not a subclass: it implements
 * {@link SpatialKey} directly and inherits the default {@link SpatialKey#sfcRangesForKNN} (empty
 * &mdash; k-NN falls back to breadth-first search until a {@code locate} primitive lands in a later
 * phase).
 *
 * <h3>Bit layout &mdash; canonical (coarse-dominant) ordering</h3>
 * The index is built MortonKey-style: each deeper refinement step is appended at the least
 * significant end (the shallowest step migrates to the most significant bits). The full index is a
 * uniform {@code 6 * level}-bit value held across two longs &mdash; {@code lowBits} are bits 0..63,
 * {@code highBits} bits 64..125. With {@code 6 * 21 = 126} bits, all 21 refinement levels fit in two
 * longs with no special split-bit encoding. {@link #compareTo} compares {@code level} first, then the
 * 128-bit value as {@code (highBits, lowBits)} unsigned &mdash; so the shallowest (coarsest) step
 * dominates, reproducing the {@code m_P} total order.
 *
 * @author hal.hildebrand
 */
public final class PyramidKey implements SpatialKey<PyramidKey> {

    /** Bits per refinement step (3 coordinate + 3 type). */
    public static final  int  BITS_PER_LEVEL    = 6;
    /** Highest level supported (21 * 6 = 126 bits, fits two longs). */
    public static final  byte MAX_PYRAMID_LEVEL = 21;
    private static final long GROUP_MASK        = 0x3FL;

    private final byte level;
    private final long lowBits;
    private final long highBits;

    /**
     * Construct a key directly from its packed bits.
     *
     * @param level    refinement level, 0..{@value #MAX_PYRAMID_LEVEL}
     * @param lowBits  index bits 0..63
     * @param highBits index bits 64..125
     */
    public PyramidKey(byte level, long lowBits, long highBits) {
        if (level < 0 || level > MAX_PYRAMID_LEVEL) {
            throw new IllegalArgumentException(
            "PyramidKey level must be between 0 and " + MAX_PYRAMID_LEVEL + ", got: " + level);
        }
        this.level = level;
        this.lowBits = lowBits;
        this.highBits = highBits;
    }

    /**
     * Build a key from per-step coordinate and type bits. Both arrays are indexed by refinement step
     * {@code 1..level} (index 0 is unused; the root contributes no bits). Step 1 is the shallowest
     * (coarsest) refinement; it is appended first and ends in the most significant bits.
     *
     * @param level      refinement level
     * @param coordBits  per-step 3-bit coordinate value {@code (Z<<2)|(Y<<1)|X}, length &gt; level
     * @param typeBits   per-step 3-bit type value {@code 0..7}, length &gt; level
     * @return the packed key
     */
    public static PyramidKey fromLevels(byte level, int[] coordBits, int[] typeBits) {
        if (level < 0 || level > MAX_PYRAMID_LEVEL) {
            throw new IllegalArgumentException("level out of range: " + level);
        }
        Objects.requireNonNull(coordBits, "coordBits");
        Objects.requireNonNull(typeBits, "typeBits");
        if (coordBits.length <= level || typeBits.length <= level) {
            throw new IllegalArgumentException("coordBits/typeBits must have length > level");
        }
        long low = 0L;
        long high = 0L;
        for (int l = 1; l <= level; l++) {
            // Append this step's group at the LSB (shift the running value left by 6, then OR in).
            high = (high << BITS_PER_LEVEL) | (low >>> (64 - BITS_PER_LEVEL));
            low = low << BITS_PER_LEVEL;
            long group = ((coordBits[l] & 0x7L) << 3) | (typeBits[l] & 0x7L);
            low |= group;
        }
        return new PyramidKey(level, low, high);
    }

    public static PyramidKey getRoot() {
        return new PyramidKey((byte) 0, 0L, 0L);
    }

    public long getLowBits() {
        return lowBits;
    }

    public long getHighBits() {
        return highBits;
    }

    /**
     * The 3-bit type value at refinement step {@code l} (1..level).
     */
    public byte getTypeAtLevel(int l) {
        return (byte) (rawGroup(l) & 0x7);
    }

    /**
     * The 3-bit coordinate value {@code (Z<<2)|(Y<<1)|X} at refinement step {@code l} (1..level).
     */
    public byte getCoordBitsAtLevel(int l) {
        return (byte) ((rawGroup(l) >> 3) & 0x7);
    }

    /**
     * The 6-bit group for step {@code l}. Step {@code level} (deepest) is at bit offset 0; step 1
     * (shallowest) is at offset {@code (level-1)*6}. May straddle the low/high long boundary.
     */
    private long rawGroup(int l) {
        if (l < 1 || l > level) {
            throw new IllegalArgumentException("step must be in [1, " + level + "], got: " + l);
        }
        int bit = (level - l) * BITS_PER_LEVEL; // offset from LSB
        if (bit >= 64) {
            return (highBits >>> (bit - 64)) & GROUP_MASK;
        }
        if (bit + BITS_PER_LEVEL <= 64) {
            return (lowBits >>> bit) & GROUP_MASK;
        }
        // Straddles the boundary: low part from lowBits, high part from highBits.
        long lowPart = lowBits >>> bit;
        long highPart = highBits << (64 - bit);
        return (lowPart | highPart) & GROUP_MASK;
    }

    @Override
    public byte getLevel() {
        return level;
    }

    @Override
    public int compareTo(PyramidKey other) {
        Objects.requireNonNull(other, "Cannot compare to null PyramidKey");
        int levelCmp = Byte.compare(this.level, other.level);
        if (levelCmp != 0) {
            return levelCmp;
        }
        // (highBits, lowBits) as a 128-bit unsigned value. The shallowest step occupies the most
        // significant bits, so this reproduces the coarse-dominant m_P total order (Knapp Eq 3.4).
        int highCmp = Long.compareUnsigned(this.highBits, other.highBits);
        if (highCmp != 0) {
            return highCmp;
        }
        return Long.compareUnsigned(this.lowBits, other.lowBits);
    }

    @Override
    public PyramidKey parent() {
        if (level == 0) {
            return null;
        }
        // Drop the deepest step (the LSB group): shift the 128-bit value right by 6.
        long parentLow = (lowBits >>> BITS_PER_LEVEL) | (highBits << (64 - BITS_PER_LEVEL));
        long parentHigh = highBits >>> BITS_PER_LEVEL;
        return new PyramidKey((byte) (level - 1), parentLow, parentHigh);
    }

    @Override
    public PyramidKey root() {
        return getRoot();
    }

    @Override
    public boolean isValid() {
        if (level < 0 || level > MAX_PYRAMID_LEVEL) {
            return false;
        }
        int usedBits = level * BITS_PER_LEVEL;
        if (usedBits <= 64) {
            long lowMask = (usedBits == 64) ? -1L : (1L << usedBits) - 1;
            return (lowBits & ~lowMask) == 0L && highBits == 0L;
        }
        int usedHigh = usedBits - 64;
        long highMask = (usedHigh >= 64) ? -1L : (1L << usedHigh) - 1;
        return (highBits & ~highMask) == 0L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PyramidKey pk)) {
            return false;
        }
        return level == pk.level && lowBits == pk.lowBits && highBits == pk.highBits;
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, lowBits, highBits);
    }

    @Override
    public String toString() {
        return "PyramidKey[L" + level + ",lo:" + Long.toHexString(lowBits)
        + (highBits != 0 ? ",hi:" + Long.toHexString(highBits) : "") + "]";
    }

    // ===== SFC Range Estimation (parity with MortonKey/TetreeKey) =====

    /**
     * A range of pyramid keys for pruning spatial queries via
     * {@code ConcurrentSkipListMap.subMap(lower, upper)}.
     */
    public record SFCRange(PyramidKey lower, PyramidKey upper) {
        public SFCRange {
            Objects.requireNonNull(lower, "lower");
            Objects.requireNonNull(upper, "upper");
        }
    }

    /**
     * Estimate the level whose cell diagonal first covers {@code radius} (coarse cells for large
     * radii), mirroring {@code MortonKey.estimateSFCDepth} / {@code TetreeKey.estimateSFCDepth}.
     */
    public static byte estimateSFCDepth(float radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("Search radius must be positive, got: " + radius);
        }
        for (byte l = MAX_PYRAMID_LEVEL; l >= 0; l--) {
            float cellSize = Constants.lengthAtLevel(l);
            float diagonal = (float) (cellSize * Math.sqrt(3.0));
            if (diagonal >= radius) {
                return l;
            }
        }
        return 0;
    }

    /**
     * Conservative SFC range covering a query sphere. Until a {@code locate(point)} primitive exists
     * (a later RDR-010 phase), this returns the full key band at the radius-derived level
     * {@code [(level,0,0), (level,maxLow,maxHigh)]} &mdash; correctly ordered and inclusive of every
     * key at that level, so callers may scan it and filter by actual distance. Mirrors the static
     * {@code estimateSFCRange} entry point on {@code MortonKey}/{@code TetreeKey}.
     *
     * @param center query centre (non-negative coordinates)
     * @param radius search radius (positive)
     * @return a conservative, correctly ordered range
     */
    public static SFCRange estimateSFCRange(Point3f center, float radius) {
        Objects.requireNonNull(center, "center");
        if (radius <= 0) {
            throw new IllegalArgumentException("Search radius must be positive, got: " + radius);
        }
        if (center.x < 0 || center.y < 0 || center.z < 0) {
            throw new IllegalArgumentException("Negative center coordinates not supported: " + center);
        }
        byte level = estimateSFCDepth(radius);
        var lower = new PyramidKey(level, 0L, 0L);
        int usedBits = level * BITS_PER_LEVEL;
        long maxLow;
        long maxHigh;
        if (usedBits <= 64) {
            maxLow = (usedBits == 64) ? -1L : (1L << usedBits) - 1;
            maxHigh = 0L;
        } else {
            maxLow = -1L;
            int usedHigh = usedBits - 64;
            maxHigh = (usedHigh >= 64) ? -1L : (1L << usedHigh) - 1;
        }
        var upper = new PyramidKey(level, maxLow, maxHigh);
        return new SFCRange(lower, upper);
    }
}
