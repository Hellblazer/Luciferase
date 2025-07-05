/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

/**
 * Lookup tables for fast Tetree tm-index encoding/decoding.
 *
 * Similar to MortonCurve's LUT approach, but adapted for Tetree's 6-bit groups (3 bits coordinate + 3 bits type per
 * level).
 *
 * This class provides precomputed lookup tables to accelerate the bit interleaving operations required for tm-index
 * computation.
 *
 * @author hal.hildebrand
 */
public class TetreeLUT {

    // For encoding: Given 8-bit coordinate values, spread them out for interleaving
    // Each byte of coordinate gets spread to prepare for 3-way interleaving
    static final long[] SPREAD_X_256 = new long[256];
    static final long[] SPREAD_Y_256 = new long[256];
    static final long[] SPREAD_Z_256 = new long[256];

    // For decoding: Extract coordinate values from interleaved bits
    static final byte[] EXTRACT_X_512 = new byte[512];
    static final byte[] EXTRACT_Y_512 = new byte[512];
    static final byte[] EXTRACT_Z_512 = new byte[512];

    // Combine coordinate bits (3 bits) with type bits (3 bits) into 6-bit groups
    // Index: (coordBits << 3) | typeBits, Value: 6-bit combined value
    private static final byte[] COMBINE_COORD_TYPE = new byte[64];

    // Extract coordinate bits and type bits from 6-bit groups
    private static final byte[] EXTRACT_COORDS = new byte[64];
    private static final byte[] EXTRACT_TYPE   = new byte[64];

    static {
        // Initialize spread tables for encoding
        for (int i = 0; i < 256; i++) {
            long spreadX = 0, spreadY = 0, spreadZ = 0;

            for (int bit = 0; bit < 8; bit++) {
                if ((i & (1 << bit)) != 0) {
                    // X occupies positions 0, 3, 6, 9, ...
                    spreadX |= 1L << (bit * 3);
                    // Y occupies positions 1, 4, 7, 10, ...
                    spreadY |= 1L << (bit * 3 + 1);
                    // Z occupies positions 2, 5, 8, 11, ...
                    spreadZ |= 1L << (bit * 3 + 2);
                }
            }

            SPREAD_X_256[i] = spreadX;
            SPREAD_Y_256[i] = spreadY;
            SPREAD_Z_256[i] = spreadZ;
        }

        // Initialize extract tables for decoding
        for (int i = 0; i < 512; i++) {
            byte x = 0, y = 0, z = 0;

            // Extract every 3rd bit starting at different offsets
            for (int bit = 0; bit < 8; bit++) {
                if ((i & (1 << (bit * 3))) != 0) {
                    x |= (1 << bit);
                }
                if ((i & (1 << (bit * 3 + 1))) != 0) {
                    y |= (1 << bit);
                }
                if ((i & (1 << (bit * 3 + 2))) != 0) {
                    z |= (1 << bit);
                }
            }

            EXTRACT_X_512[i] = x;
            EXTRACT_Y_512[i] = y;
            EXTRACT_Z_512[i] = z;
        }

        // Initialize 6-bit combination tables
        for (int i = 0; i < 64; i++) {
            // Upper 3 bits are coordinates, lower 3 bits are type
            int coordBits = (i >> 3) & 0x7;
            int typeBits = i & 0x7;

            COMBINE_COORD_TYPE[i] = (byte) ((coordBits << 3) | typeBits);
            EXTRACT_COORDS[i] = (byte) ((i >> 3) & 0x7);
            EXTRACT_TYPE[i] = (byte) (i & 0x7);
        }
    }

    /**
     * Optimized tm-index computation using lookup tables and bit manipulation. This method should be significantly
     * faster than the bit-by-bit approach.
     *
     * @param x         X coordinate (already shifted/aligned)
     * @param y         Y coordinate (already shifted/aligned)
     * @param z         Z coordinate (already shifted/aligned)
     * @param typeArray Type values for each level
     * @param level     The tetrahedron level
     * @return ExtendedTetreeKey with computed tm-index
     */
    public static ExtendedTetreeKey computeTmIndexOptimized(int x, int y, int z, int[] typeArray, byte level) {
        // Use LUT for fast encoding
        long[] result = encodeTmIndexLUT(x, y, z, typeArray, level);
        return new ExtendedTetreeKey(level, result[0], result[1]);
    }

    /**
     * Extract coordinate values from interleaved bits using LUT.
     *
     * @param interleaved The interleaved coordinate bits
     * @return Array of [x, y, z] coordinates
     */
    public static int[] deinterleaveCoordinatesLUT(long interleaved) {
        int x = 0, y = 0, z = 0;

        // Process 9-bit chunks (for 512-entry LUT)
        for (int i = 0; i < 7; i++) {
            int chunk = (int) ((interleaved >> (i * 9)) & 0x1FF);
            x |= EXTRACT_X_512[chunk] << (i * 3);
            y |= EXTRACT_Y_512[chunk] << (i * 3);
            z |= EXTRACT_Z_512[chunk] << (i * 3);
        }

        return new int[] { x, y, z };
    }

    /**
     * Encode coordinates and type information into tm-index components using LUT.
     *
     * @param x         X coordinate (shifted and aligned)
     * @param y         Y coordinate (shifted and aligned)
     * @param z         Z coordinate (shifted and aligned)
     * @param typeArray Array of type values for each level
     * @param level     The level of this tetrahedron
     * @return Array of [lowBits, highBits] for 128-bit tm-index
     */
    public static long[] encodeTmIndexLUT(int x, int y, int z, int[] typeArray, byte level) {
        long lowBits = 0L;
        long highBits = 0L;

        // Process 8-bit chunks for more efficient LUT usage
        // We can process multiple levels at once using the spread tables

        // For each byte of coordinates, create interleaved pattern
        for (int byteIdx = 0; byteIdx < 3; byteIdx++) { // Process up to 24 bits (3 bytes)
            int shift = byteIdx * 8;
            int xByte = (x >> shift) & 0xFF;
            int yByte = (y >> shift) & 0xFF;
            int zByte = (z >> shift) & 0xFF;

            // Get spread patterns
            long xSpread = SPREAD_X_256[xByte];
            long ySpread = SPREAD_Y_256[yByte];
            long zSpread = SPREAD_Z_256[zByte];

            // Combine to get interleaved coordinate bits
            long coordPattern = xSpread | ySpread | zSpread;

            // Now we need to combine with type information
            // Process 6-bit groups (3 coord bits + 3 type bits)
            for (int groupIdx = 0; groupIdx < 8 && (byteIdx * 8 + groupIdx) < level; groupIdx++) {
                int levelIdx = byteIdx * 8 + groupIdx;

                // Extract 3 coordinate bits for this level
                int coordBits = (int) ((coordPattern >> (groupIdx * 3)) & 0x7);

                // Get type for this level
                int typeBits = typeArray[levelIdx] & 0x7;

                // Combine into 6-bit group
                int sixBits = (coordBits << 3) | typeBits;

                // Pack into appropriate long
                if (levelIdx < 10) {
                    lowBits |= ((long) sixBits) << (6 * levelIdx);
                } else {
                    highBits |= ((long) sixBits) << (6 * (levelIdx - 10));
                }
            }
        }

        return new long[] { lowBits, highBits };
    }

    /**
     * Fast interleaving of coordinate bits using precomputed tables. Processes 8 bits at a time for better
     * performance.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Interleaved coordinate bits (every group of 3 bits represents one level)
     */
    public static long interleaveCoordinatesLUT(int x, int y, int z) {
        long result = 0L;

        // Process in 8-bit chunks
        result |= SPREAD_X_256[x & 0xFF] | SPREAD_Y_256[y & 0xFF] | SPREAD_Z_256[z & 0xFF];
        result |= (SPREAD_X_256[(x >> 8) & 0xFF] | SPREAD_Y_256[(y >> 8) & 0xFF] | SPREAD_Z_256[(z >> 8) & 0xFF]) << 24;
        result |= (SPREAD_X_256[(x >> 16) & 0xFF] | SPREAD_Y_256[(y >> 16) & 0xFF] | SPREAD_Z_256[(z >> 16) & 0xFF])
        << 48;

        return result;
    }
}
