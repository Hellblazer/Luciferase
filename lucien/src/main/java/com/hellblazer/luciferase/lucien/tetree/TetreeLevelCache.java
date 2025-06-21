/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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

/**
 * High-performance caching and lookup tables for Tetree operations. Converts O(log n) operations to O(1) through
 * precomputation.
 *
 * @author hal.hildebrand
 */
public final class TetreeLevelCache {

    // Maximum bits we need to handle (64-bit long)
    private static final int MAX_BITS = 64;

    // Lookup table for level from highest bit position
    // Since each level uses 3 bits, we can compute: level = (highBit / 3) + 1
    private static final byte[] HIGH_BIT_TO_LEVEL = new byte[MAX_BITS];

    // Lookup table for level from small indices (0-511 covers levels 0-3)
    // This handles the most common cases extremely fast
    private static final byte[] SMALL_INDEX_TO_LEVEL = new byte[512];

    // Cache for frequently used parent chains (index -> parent index at each level)
    // Key: packed (index, level), Value: array of parent indices
    private static final long[][] PARENT_CHAIN_CACHE = new long[1024][];
    private static final int      PARENT_CACHE_MASK  = 1023; // For fast modulo

    // Type transition cache: packed(startType, startLevel, endLevel) -> endType
    // Maximum value: (5 << 16) | (21 << 8) | 21 = 327680 + 5376 + 21 = 333077
    private static final byte[] TYPE_TRANSITION_CACHE = new byte[6 * 256 * 256]; // 6 types * 256 levels * 256 levels
    // De Bruijn lookup table for 64-bit integers
    private static final int[] DeBruijnTable = { 0, 1, 48, 2, 57, 49, 28, 3, 61, 58, 50, 42, 38, 29, 17, 4, 62, 55, 59,
                                                 36, 53, 51, 43, 22, 45, 39, 33, 30, 24, 18, 12, 5, 63, 47, 56, 27, 60,
                                                 41, 37, 16, 54, 35, 52, 21, 44, 32, 23, 11, 46, 26, 40, 15, 34, 20, 31,
                                                 10, 25, 14, 19, 9, 13, 8, 7, 6 };
    /**
     * Cache an SFC index computation result. For frequently accessed tetrahedra, this converts O(level) to O(1).
     */
    private static final int    INDEX_CACHE_SIZE   = 4096;
    private static final long[] INDEX_CACHE_KEYS   = new long[INDEX_CACHE_SIZE];
    private static final long[] INDEX_CACHE_VALUES = new long[INDEX_CACHE_SIZE];

    static {
        initializeLevelTables();
        initializeTypeCaches();
    }

    public static void cacheIndex(int x, int y, int z, byte level, byte type, long index) {
        // Use hash function for full 32-bit coordinate support
        long key = generateCacheKey(x, y, z, level, type);
        int slot = (int) (key & (INDEX_CACHE_SIZE - 1));
        INDEX_CACHE_KEYS[slot] = key;
        INDEX_CACHE_VALUES[slot] = index;
    }

    /**
     * Cache a computed parent chain for future lookups.
     */
    public static void cacheParentChain(long index, byte level, long[] chain) {
        if (chain == null || chain.length != level + 1 || chain[0] != index) {
            throw new IllegalArgumentException("Invalid parent chain");
        }

        // Simple hash for cache slot
        int cacheSlot = (int) ((index ^ level) & PARENT_CACHE_MASK);
        PARENT_CHAIN_CACHE[cacheSlot] = chain.clone(); // Clone to prevent external modifications
    }

    private static byte computeTypeTransition(byte startType, byte startLevel, byte endLevel) {
        // This simulates the computeType loop without actually looping
        // In practice, this would use the connectivity tables
        byte type = startType;
        // Simplified for now - actual implementation would use TetreeConnectivity
        return type;
    }

    /**
     * Fast O(1) highest bit position using De Bruijn sequence. Faster than Long.numberOfLeadingZeros on most CPUs.
     */
    private static int fastHighestBit(long v) {
        // De Bruijn sequence for 64-bit
        final long debruijn = 0x03f79d71b4cb0a89L;

        // Round down to highest power of 2
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v |= v >> 32;

        // Use De Bruijn multiplication to get bit position
        return DeBruijnTable[(int) ((v * debruijn) >>> 58)];
    }

    public static long getCachedIndex(int x, int y, int z, byte level, byte type) {
        // Use hash function for full 32-bit coordinate support
        long key = generateCacheKey(x, y, z, level, type);
        int slot = (int) (key & (INDEX_CACHE_SIZE - 1));

        // Check cache hit
        if (INDEX_CACHE_KEYS[slot] == key) {
            return INDEX_CACHE_VALUES[slot];
        }

        // Cache miss - would compute actual index here
        return -1; // Indicates cache miss
    }

    /**
     * Get the level from a Tet SFC index in O(1) time.
     * 
     * For Tet SFC, the level is encoded in the index itself:
     * - Level 0: index = 0
     * - Level 1: indices 1-7 (8^1 - 1)
     * - Level 2: indices 8-63 (8^2 - 1)
     * - Level 3: indices 64-511 (8^3 - 1)
     * - Level n: indices 8^(n-1) to 8^n - 1
     */
    public static byte getLevelFromIndex(long index) {
        if (index < 0) {
            throw new IllegalArgumentException("Index must be non-negative: " + index);
        }

        // Fast path for small indices (most common case)
        if (index < 512) {
            return SMALL_INDEX_TO_LEVEL[(int) index];
        }

        // For Tet SFC: level = floor(log8(index + 1))
        // Since log8(x) = log2(x) / 3, we can use:
        // level = floor(log2(index + 1) / 3)
        
        // However, since indices are in ranges [8^(n-1), 8^n - 1],
        // we need to find which power of 8 range the index falls into
        
        // Use numberOfLeadingZeros for correctness (we'll optimize later if needed)
        if (index == 0) return 0;
        
        // For index > 0, find the level by checking which power of 8 range it falls into
        // This is essentially finding floor(log8(index + 1))
        int bits = 64 - Long.numberOfLeadingZeros(index);
        byte level = (byte) ((bits + 2) / 3);  // +2 for proper rounding of log8
        
        // Clamp to max level
        return level > Constants.getMaxRefinementLevel() ? Constants.getMaxRefinementLevel() : level;
    }

    /**
     * Get cached parent chain for an index, or compute and cache it. Converts O(level) parent traversal to O(1) for
     * cached entries.
     */
    public static long[] getParentChain(long index, byte level) {
        // Simple hash for cache lookup
        int cacheSlot = (int) ((index ^ level) & PARENT_CACHE_MASK);
        long[] cached = PARENT_CHAIN_CACHE[cacheSlot];

        // Check if this is the right entry (simple validation)
        if (cached != null && cached.length > 0 && cached[0] == index) {
            return cached;
        }

        // Return null to indicate cache miss - caller will compute and cache
        return null;
    }

    /**
     * Get the type at a different level using cached transitions. Converts O(level) loop to O(1) lookup.
     */
    public static byte getTypeAtLevel(byte startType, byte startLevel, byte targetLevel) {
        if (targetLevel > startLevel) {
            throw new IllegalArgumentException("Target level must be <= start level");
        }

        int packed = packTypeTransition(startType, startLevel, targetLevel);
        if (packed >= TYPE_TRANSITION_CACHE.length) {
            return -1; // Indicates cache miss
        }
        return TYPE_TRANSITION_CACHE[packed];
    }

    private static void initializeLevelTables() {
        // Initialize high bit to level lookup
        for (int highBit = 0; highBit < MAX_BITS; highBit++) {
            HIGH_BIT_TO_LEVEL[highBit] = (byte) ((highBit / 3) + 1);
        }

        // Initialize small index to level lookup
        SMALL_INDEX_TO_LEVEL[0] = 0; // Index 0 is level 0

        // Level 1: indices 1-7 (3 bits)
        for (int i = 1; i <= 7; i++) {
            SMALL_INDEX_TO_LEVEL[i] = 1;
        }

        // Level 2: indices 8-63 (6 bits)
        for (int i = 8; i <= 63; i++) {
            SMALL_INDEX_TO_LEVEL[i] = 2;
        }

        // Level 3: indices 64-511 (9 bits)
        for (int i = 64; i <= 511; i++) {
            SMALL_INDEX_TO_LEVEL[i] = 3;
        }
    }

    private static void initializeTypeCaches() {
        // Initialize type transition cache
        // Pack: startType (8 bits) | startLevel (8 bits) | endLevel (8 bits)
        for (int startType = 0; startType < 6; startType++) {
            for (int startLevel = 0; startLevel <= 21; startLevel++) {
                for (int endLevel = 0; endLevel <= startLevel; endLevel++) {
                    int packed = packTypeTransition(startType, startLevel, endLevel);
                    byte resultType = computeTypeTransition((byte) startType, (byte) startLevel, (byte) endLevel);
                    TYPE_TRANSITION_CACHE[packed] = resultType;
                }
            }
        }
    }

    private static int packTypeTransition(int startType, int startLevel, int endLevel) {
        return (startType << 16) | (startLevel << 8) | endLevel;
    }
    
    /**
     * Generate a high-quality hash key for cache lookups.
     * This fixes the bit overlap issue in the original implementation.
     * Uses prime multipliers to ensure good distribution.
     */
    private static long generateCacheKey(int x, int y, int z, byte level, byte type) {
        // Use large primes to minimize collisions
        long hash = x * 0x9E3779B97F4A7C15L;    // Golden ratio prime
        hash ^= y * 0xBF58476D1CE4E5B9L;        // Another large prime
        hash ^= z * 0x94D049BB133111EBL;        // Another large prime  
        hash ^= level * 0x2127599BF4325C37L;    // Another large prime
        hash ^= type * 0xFD5167A1D8E52FB7L;     // Another large prime
        
        // Mix the bits for better distribution
        hash ^= (hash >>> 32);
        hash *= 0xD6E8FEB86659FD93L;
        hash ^= (hash >>> 32);
        hash *= 0xD6E8FEB86659FD93L;
        hash ^= (hash >>> 32);
        
        return hash;
    }
}
