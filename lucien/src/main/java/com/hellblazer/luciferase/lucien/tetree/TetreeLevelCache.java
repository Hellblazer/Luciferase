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

    // Type transition cache: packed(startType, startLevel, endLevel) -> endType
    // Maximum value: (5 << 16) | (21 << 8) | 21 = 327680 + 5376 + 21 = 333077
    private static final byte[] TYPE_TRANSITION_CACHE = new byte[6 * 256 * 256]; // 6 types * 256 levels * 256 levels
    /**
     * Cache an SFC index computation result. For frequently accessed tetrahedra, this converts O(level) to O(1).
     */
    private static final int    INDEX_CACHE_SIZE      = 4096;
    private static final long[] INDEX_CACHE_KEYS      = new long[INDEX_CACHE_SIZE];
    private static final long[] INDEX_CACHE_VALUES    = new long[INDEX_CACHE_SIZE];

    /**
     * Cache complete TetreeKey objects to convert O(level) tmIndex() operations to O(1).
     * This is critical for performance as tmIndex() requires parent chain traversal.
     */
    private static final int         TETREE_KEY_CACHE_SIZE   = 65536; // 16x larger for better hit rate
    private static final long[]      TETREE_KEY_CACHE_KEYS   = new long[TETREE_KEY_CACHE_SIZE];
    private static final BaseTetreeKey[] TETREE_KEY_CACHE_VALUES = new BaseTetreeKey[TETREE_KEY_CACHE_SIZE];

    // Cache statistics for monitoring
    private static long cacheHits   = 0;
    private static long cacheMisses = 0;
    
    // Parent chain cache - Phase 3
    private static final int         PARENT_CHAIN_CACHE_SIZE   = 4096;
    private static final long[]      PARENT_CHAIN_KEYS         = new long[PARENT_CHAIN_CACHE_SIZE];
    private static final Tet[][]     PARENT_CHAIN_VALUES       = new Tet[PARENT_CHAIN_CACHE_SIZE][];
    private static long              parentChainHits           = 0;
    private static long              parentChainMisses         = 0;

    static {
        initializeLevelTables();
        initializeTypeCaches();
    }

    /**
     * Cache a TetreeKey for fast retrieval. This converts O(level) tmIndex() operations to O(1).
     *
     * @param x        the x coordinate
     * @param y        the y coordinate
     * @param z        the z coordinate
     * @param level    the level
     * @param type     the tetrahedron type
     * @param tetreeKey the TetreeKey to cache
     */
    public static void cacheTetreeKey(int x, int y, int z, byte level, byte type, BaseTetreeKey tetreeKey) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (TETREE_KEY_CACHE_SIZE - 1));
        TETREE_KEY_CACHE_KEYS[slot] = key;
        TETREE_KEY_CACHE_VALUES[slot] = tetreeKey;
    }

    public static void cacheIndex(int x, int y, int z, byte level, byte type, long index) {
        // Use hash function for full 32-bit coordinate support
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (INDEX_CACHE_SIZE - 1));
        INDEX_CACHE_KEYS[slot] = key;
        INDEX_CACHE_VALUES[slot] = index;
    }

    private static byte computeTypeTransition(byte startType, byte startLevel, byte endLevel) {
        if (endLevel > startLevel) {
            throw new IllegalArgumentException("End level must be <= start level");
        }
        if (endLevel == startLevel) {
            return startType;
        }
        if (endLevel == 0) {
            return 0; // Root is always type 0
        }
        
        // We need to walk up the tree to find the ancestor type
        // This is a simplified version that doesn't have access to actual coordinates
        // In a real implementation, we would need the actual tetrahedron to compute this
        // For now, return -1 to indicate we can't compute this without more information
        return -1;
    }

    /**
     * Generate a high-quality hash key for cache lookups. This fixes the bit overlap issue in the original
     * implementation. Uses prime multipliers to ensure good distribution.
     */
    private static long generateCacheKey(int x, int y, int z, byte level, byte type) {
        // Use large primes to minimize collisions
        var hash = x * 0x9E3779B97F4A7C15L;    // Golden ratio prime
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

    /**
     * Get a cached TetreeKey if available. This is the primary optimization for tmIndex() performance.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @param level the level
     * @param type  the tetrahedron type
     * @return the cached TetreeKey or null if not cached
     */
    public static BaseTetreeKey getCachedTetreeKey(int x, int y, int z, byte level, byte type) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (TETREE_KEY_CACHE_SIZE - 1));

        if (TETREE_KEY_CACHE_KEYS[slot] == key) {
            cacheHits++;
            return TETREE_KEY_CACHE_VALUES[slot];
        }

        cacheMisses++;
        return null;
    }

    /**
     * Get the cache hit rate for monitoring performance.
     *
     * @return the cache hit rate as a percentage (0.0 to 1.0)
     */
    public static double getCacheHitRate() {
        var total = cacheHits + cacheMisses;
        return total > 0 ? (double) cacheHits / total : 0.0;
    }

    /**
     * Reset cache statistics for benchmarking.
     */
    public static void resetCacheStats() {
        cacheHits = 0;
        cacheMisses = 0;
        parentChainHits = 0;
        parentChainMisses = 0;
    }
    
    /**
     * Get cached parent chain for a tetrahedron.
     * The chain includes all ancestors from the given tet up to the root.
     *
     * @param tet the tetrahedron
     * @return cached parent chain or null if not cached
     */
    public static Tet[] getCachedParentChain(Tet tet) {
        var key = generateCacheKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
        var slot = (int)(key & (PARENT_CHAIN_CACHE_SIZE - 1));
        
        if (PARENT_CHAIN_KEYS[slot] == key) {
            parentChainHits++;
            return PARENT_CHAIN_VALUES[slot];
        }
        
        parentChainMisses++;
        return null;
    }
    
    /**
     * Cache a parent chain for future lookups.
     *
     * @param tet the tetrahedron
     * @param chain the parent chain (including the tet itself)
     */
    public static void cacheParentChain(Tet tet, Tet[] chain) {
        var key = generateCacheKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
        var slot = (int)(key & (PARENT_CHAIN_CACHE_SIZE - 1));
        
        PARENT_CHAIN_KEYS[slot] = key;
        PARENT_CHAIN_VALUES[slot] = chain;
    }
    
    /**
     * Get parent chain cache statistics.
     *
     * @return hit rate as a percentage
     */
    public static double getParentChainHitRate() {
        long total = parentChainHits + parentChainMisses;
        return total > 0 ? (double) parentChainHits / total : 0.0;
    }

    public static long getCachedIndex(int x, int y, int z, byte level, byte type) {
        // Use hash function for full 32-bit coordinate support
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (INDEX_CACHE_SIZE - 1));

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
     * For Tet SFC, the level is encoded in the index itself: - Level 0: index = 0 - Level 1: indices 1-7 (8^1 - 1) -
     * Level 2: indices 8-63 (8^2 - 1) - Level 3: indices 64-511 (8^3 - 1) - Level n: indices 8^(n-1) to 8^n - 1
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
        if (index == 0) {
            return 0;
        }

        // For index > 0, find the level by checking which power of 8 range it falls into
        // This is essentially finding floor(log8(index + 1))
        var bits = 64 - Long.numberOfLeadingZeros(index);
        var level = (byte) ((bits + 2) / 3);  // +2 for proper rounding of log8

        // Clamp to max level
        return level > Constants.getMaxRefinementLevel() ? Constants.getMaxRefinementLevel() : level;
    }

    /**
     * Get the type at a different level using cached transitions. Converts O(level) loop to O(1) lookup.
     */
    public static byte getTypeAtLevel(byte startType, byte startLevel, byte targetLevel) {
        if (targetLevel > startLevel) {
            throw new IllegalArgumentException("Target level must be <= start level");
        }

        var packed = packTypeTransition(startType, startLevel, targetLevel);
        if (packed >= TYPE_TRANSITION_CACHE.length) {
            return -1; // Indicates cache miss
        }
        return TYPE_TRANSITION_CACHE[packed];
    }

    private static void initializeLevelTables() {
        // Initialize high bit to level lookup
        for (var highBit = 0; highBit < MAX_BITS; highBit++) {
            HIGH_BIT_TO_LEVEL[highBit] = (byte) ((highBit / 3) + 1);
        }

        // Initialize small index to level lookup
        SMALL_INDEX_TO_LEVEL[0] = 0; // Index 0 is level 0

        // Level 1: indices 1-7 (3 bits)
        for (var i = 1; i <= 7; i++) {
            SMALL_INDEX_TO_LEVEL[i] = 1;
        }

        // Level 2: indices 8-63 (6 bits)
        for (var i = 8; i <= 63; i++) {
            SMALL_INDEX_TO_LEVEL[i] = 2;
        }

        // Level 3: indices 64-511 (9 bits)
        for (var i = 64; i <= 511; i++) {
            SMALL_INDEX_TO_LEVEL[i] = 3;
        }
    }

    private static void initializeTypeCaches() {
        // Initialize type transition cache
        // Pack: startType (8 bits) | startLevel (8 bits) | endLevel (8 bits)
        for (var startType = 0; startType < 6; startType++) {
            for (var startLevel = 0; startLevel <= 21; startLevel++) {
                for (var endLevel = 0; endLevel <= startLevel; endLevel++) {
                    var packed = packTypeTransition(startType, startLevel, endLevel);
                    var resultType = computeTypeTransition((byte) startType, (byte) startLevel, (byte) endLevel);
                    TYPE_TRANSITION_CACHE[packed] = resultType;
                }
            }
        }
    }

    private static int packTypeTransition(int startType, int startLevel, int endLevel) {
        return (startType << 16) | (startLevel << 8) | endLevel;
    }
    
    /**
     * Clear all runtime caches. This is useful for testing to ensure consistent results.
     * Note: This does NOT clear the static lookup tables which are computed once at initialization.
     */
    public static void clearCaches() {
        // Clear index cache
        for (var i = 0; i < INDEX_CACHE_SIZE; i++) {
            INDEX_CACHE_KEYS[i] = 0;
            INDEX_CACHE_VALUES[i] = 0;
        }
        
        // Clear TetreeKey cache
        for (var i = 0; i < TETREE_KEY_CACHE_SIZE; i++) {
            TETREE_KEY_CACHE_KEYS[i] = 0;
            TETREE_KEY_CACHE_VALUES[i] = null;
        }
        
        // Clear parent chain cache
        for (var i = 0; i < PARENT_CHAIN_CACHE_SIZE; i++) {
            PARENT_CHAIN_KEYS[i] = 0;
            PARENT_CHAIN_VALUES[i] = null;
        }
    }
}
