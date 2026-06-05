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

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-performance caching and lookup tables for Tetree operations. Converts O(log n) operations to O(1) through
 * precomputation.
 *
 * <p>Publication-order safety (Luciferase-7wzml.19): all five direct-mapped caches store an immutable
 * {@link Holder} record per slot via {@link AtomicReferenceArray}. A single volatile write publishes the
 * (key, value) pair atomically, so a reader can never observe a key with a stale or mismatched value from a
 * different tuple.  Readers load the holder once and verify {@code holder.key == wantedKey} before using
 * {@code holder.value}.
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
     * Stores Holder<Long> to guarantee atomic key+value publication.
     */
    private static final int                                INDEX_CACHE_SIZE = 4096;
    private static final AtomicReferenceArray<Holder<Long>> INDEX_CACHE      =
    new AtomicReferenceArray<>(INDEX_CACHE_SIZE);

    /**
     * Cache complete ExtendedTetreeKey objects to convert O(level) tmIndex() operations to O(1). This is critical for
     * performance as tmIndex() requires parent chain traversal.
     * Stores Holder<TetreeKey> to guarantee atomic key+value publication.
     */
    private static final int                                    TETREE_KEY_CACHE_SIZE = 1048576; // 1M entries for production workloads
    private static final AtomicReferenceArray<Holder<TetreeKey>> TETREE_KEY_CACHE      =
    new AtomicReferenceArray<>(TETREE_KEY_CACHE_SIZE);

    // Parent chain cache - Phase 3
    private static final int                                 PARENT_CHAIN_CACHE_SIZE = 65536;
    private static final AtomicReferenceArray<Holder<Tet[]>> PARENT_CHAIN_CACHE      =
    new AtomicReferenceArray<>(PARENT_CHAIN_CACHE_SIZE);

    // Direct parent cache for faster parent() calls
    private static final int                               PARENT_CACHE_SIZE = 131072;
    private static final AtomicReferenceArray<Holder<Tet>> PARENT_CACHE      =
    new AtomicReferenceArray<>(PARENT_CACHE_SIZE);

    // Parent type cache for computeParentType optimization
    // Stores Holder<Byte>; -1 is the "miss" sentinel so we never store -1 as a real value.
    private static final int                                PARENT_TYPE_CACHE_SIZE = 65536;
    private static final AtomicReferenceArray<Holder<Byte>> PARENT_TYPE_CACHE      =
    new AtomicReferenceArray<>(PARENT_TYPE_CACHE_SIZE);

    // Shallow level pre-computation tables for levels 0-5 (most frequent operations)
    private static final int                        MAX_SHALLOW_LEVEL   = 5;
    private static final Map<Integer, TetreeKey<?>> SHALLOW_LEVEL_CACHE = new HashMap<>();

    // Cache statistics for monitoring (Luciferase-7wzml.133).
    // Plain static long fields incremented from concurrent read paths caused torn reads and lost updates.
    // LongAdder provides wait-free, scalable increments with no false-sharing on hot paths, while
    // still giving accurate-enough statistics for monitoring purposes.
    private static final LongAdder cacheHits         = new LongAdder();
    private static final LongAdder cacheMisses       = new LongAdder();
    private static final LongAdder parentChainHits   = new LongAdder();
    private static final LongAdder parentChainMisses = new LongAdder();
    private static final LongAdder parentCacheHits   = new LongAdder();
    private static final LongAdder parentCacheMisses = new LongAdder();

    static {
        initializeLevelTables();
        initializeTypeCaches();
        initializeShallowLevelCache();
        // AtomicReferenceArrays initialise their elements to null — no extra fill needed.
    }

    // -------------------------------------------------------------------------
    // Immutable key+value holder for atomic per-slot publication
    // -------------------------------------------------------------------------

    /**
     * Immutable holder pairing a cache key with its associated value.  A single volatile write of a Holder
     * reference to an {@link AtomicReferenceArray} slot publishes both fields simultaneously, eliminating the
     * publication-order race that existed when key and value were written to separate arrays.
     */
    record Holder<V>(long key, V value) {
    }

    // -------------------------------------------------------------------------
    // Public cache write methods
    // -------------------------------------------------------------------------

    public static void cacheIndex(int x, int y, int z, byte level, byte type, long index) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (INDEX_CACHE_SIZE - 1));
        INDEX_CACHE.set(slot, new Holder<>(key, index));
    }

    /**
     * Cache a direct parent lookup.
     *
     * @param x      the x coordinate
     * @param y      the y coordinate
     * @param z      the z coordinate
     * @param level  the level
     * @param type   the tetrahedron type
     * @param parent the parent Tet to cache
     */
    public static void cacheParent(int x, int y, int z, byte level, byte type, Tet parent) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (PARENT_CACHE_SIZE - 1));
        PARENT_CACHE.set(slot, new Holder<>(key, parent));
    }

    /**
     * Cache a parent chain for future lookups.
     *
     * @param tet   the tetrahedron
     * @param chain the parent chain (including the tet itself)
     */
    public static void cacheParentChain(Tet tet, Tet[] chain) {
        var key = generateCacheKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
        var slot = (int) (key & (PARENT_CHAIN_CACHE_SIZE - 1));
        PARENT_CHAIN_CACHE.set(slot, new Holder<>(key, chain));
    }

    /**
     * Cache a parent type computation result.
     *
     * @param x          the x coordinate
     * @param y          the y coordinate
     * @param z          the z coordinate
     * @param level      the level
     * @param type       the tetrahedron type
     * @param parentType the computed parent type
     */
    public static void cacheParentType(int x, int y, int z, byte level, byte type, byte parentType) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (PARENT_TYPE_CACHE_SIZE - 1));
        PARENT_TYPE_CACHE.set(slot, new Holder<>(key, parentType));
    }

    /**
     * Cache a ExtendedTetreeKey for fast retrieval. This converts O(level) tmIndex() operations to O(1).
     *
     * @param x         the x coordinate
     * @param y         the y coordinate
     * @param z         the z coordinate
     * @param level     the level
     * @param type      the tetrahedron type
     * @param tetreeKey the ExtendedTetreeKey to cache
     */
    public static void cacheTetreeKey(int x, int y, int z, byte level, byte type, TetreeKey tetreeKey) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (TETREE_KEY_CACHE_SIZE - 1));
        TETREE_KEY_CACHE.set(slot, new Holder<>(key, tetreeKey));
    }

    /**
     * Clear all runtime caches. This is useful for testing to ensure consistent results. Note: This does NOT clear the
     * static lookup tables which are computed once at initialization.
     */
    public static void clearCaches() {
        for (var i = 0; i < INDEX_CACHE_SIZE; i++) {
            INDEX_CACHE.set(i, null);
        }
        for (var i = 0; i < TETREE_KEY_CACHE_SIZE; i++) {
            TETREE_KEY_CACHE.set(i, null);
        }
        for (var i = 0; i < PARENT_CHAIN_CACHE_SIZE; i++) {
            PARENT_CHAIN_CACHE.set(i, null);
        }
        for (var i = 0; i < PARENT_CACHE_SIZE; i++) {
            PARENT_CACHE.set(i, null);
        }
        for (var i = 0; i < PARENT_TYPE_CACHE_SIZE; i++) {
            PARENT_TYPE_CACHE.set(i, null);
        }
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

        // This is a simplified version that doesn't have access to actual coordinates
        // In a real implementation, we would need the actual tetrahedron to compute this
        // For now, return -1 to indicate we can't compute this without more information
        return -1;
    }

    /**
     * Generate a high-quality hash key for cache lookups. Optimized version with fast path for small coordinates which
     * are common in spatial indices.
     *
     * Performance: 10% faster than original implementation with identical distribution quality.
     */
    private static long generateCacheKey(int x, int y, int z, byte level, byte type) {
        // Fast path for small coordinates (common case ~80% in most spatial workloads)
        // This avoids expensive multiplication and bit mixing for the common case.
        // NOTE (Luciferase-egjwk): the (x|y|z) >= 0 test is NOT redundant — it routes any negative coordinate to
        // the hash path. The x<1024 bounds are also true for negatives (e.g. -5 < 1024), so without the sign
        // guard a negative coord would enter the pack and ((long) x << 28) would corrupt the key. Load-bearing.
        if ((x | y | z) >= 0 && x < 1024 && y < 1024 && z < 1024) {
            // Pack directly into long for small coordinates
            // 10 bits each for x,y,z (0-1023), 5 bits for level (0-31), 3 bits for type (0-7)
            // Total: 38 bits used out of 64
            return ((long) x << 28) | ((long) y << 18) | ((long) z << 8) | ((long) level << 3) | (long) type;
        }

        // Full hash for large coordinates using optimized mixing
        // Golden ratio multipliers provide excellent distribution
        var hash = x * 0x9E3779B97F4A7C15L;    // Golden ratio prime
        hash ^= y * 0xBF58476D1CE4E5B9L;        // Large prime
        hash ^= z * 0x94D049BB133111EBL;        // Large prime
        hash ^= level * 0x2127599BF4325C37L;    // Large prime
        hash ^= type * 0xFD5167A1D8E52FB7L;     // Large prime

        // Three-round avalanche mixing for uniform distribution
        hash ^= (hash >>> 32);
        hash *= 0xD6E8FEB86659FD93L;
        hash ^= (hash >>> 32);
        hash *= 0xD6E8FEB86659FD93L;
        hash ^= (hash >>> 32);

        return hash;
    }

    /**
     * Get the cache hit rate for monitoring performance.
     *
     * @return the cache hit rate as a percentage (0.0 to 1.0)
     */
    public static double getCacheHitRate() {
        long h = cacheHits.sum();
        long m = cacheMisses.sum();
        long total = h + m;
        return total > 0 ? (double) h / total : 0.0;
    }

    public static long getCachedIndex(int x, int y, int z, byte level, byte type) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (INDEX_CACHE_SIZE - 1));
        var holder = INDEX_CACHE.get(slot);
        if (holder != null && holder.key() == key) {
            return holder.value();
        }
        return -1; // Indicates cache miss
    }

    /**
     * Get a cached parent if available.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @param level the level
     * @param type  the tetrahedron type
     * @return the cached parent or null if not cached
     */
    public static Tet getCachedParent(int x, int y, int z, byte level, byte type) {
        if (level == 0) {
            return null; // No parent for root
        }

        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (PARENT_CACHE_SIZE - 1));
        var holder = PARENT_CACHE.get(slot);
        if (holder != null && holder.key() == key) {
            parentCacheHits.increment();
            return holder.value();
        }
        parentCacheMisses.increment();
        return null;
    }

    /**
     * Get cached parent chain for a tetrahedron. The chain includes all ancestors from the given tet up to the root.
     *
     * @param tet the tetrahedron
     * @return cached parent chain or null if not cached
     */
    public static Tet[] getCachedParentChain(Tet tet) {
        var key = generateCacheKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
        var slot = (int) (key & (PARENT_CHAIN_CACHE_SIZE - 1));
        var holder = PARENT_CHAIN_CACHE.get(slot);
        if (holder != null && holder.key() == key) {
            parentChainHits.increment();
            return holder.value();
        }
        parentChainMisses.increment();
        return null;
    }

    /**
     * Get a cached parent type if available.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @param level the level
     * @param type  the tetrahedron type
     * @return the cached parent type or -1 if not cached
     */
    public static byte getCachedParentType(int x, int y, int z, byte level, byte type) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (PARENT_TYPE_CACHE_SIZE - 1));
        var holder = PARENT_TYPE_CACHE.get(slot);
        if (holder != null && holder.key() == key) {
            return holder.value();
        }
        return -1; // Cache miss
    }

    /**
     * Get a cached ExtendedTetreeKey if available. This is the primary optimization for tmIndex() performance.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @param level the level
     * @param type  the tetrahedron type
     * @return the cached ExtendedTetreeKey or null if not cached
     */
    public static TetreeKey getCachedTetreeKey(int x, int y, int z, byte level, byte type) {
        var key = generateCacheKey(x, y, z, level, type);
        var slot = (int) (key & (TETREE_KEY_CACHE_SIZE - 1));
        var holder = TETREE_KEY_CACHE.get(slot);
        if (holder != null && holder.key() == key) {
            cacheHits.increment();
            return holder.value();
        }
        cacheMisses.increment();
        return null;
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
     * Get the parent cache hit rate for monitoring performance.
     *
     * @return the cache hit rate as a percentage (0.0 to 1.0)
     */
    public static double getParentCacheHitRate() {
        long h = parentCacheHits.sum();
        long m = parentCacheMisses.sum();
        long total = h + m;
        return total > 0 ? (double) h / total : 0.0;
    }

    /**
     * Get parent chain cache statistics.
     *
     * @return hit rate as a percentage
     */
    public static double getParentChainHitRate() {
        long h = parentChainHits.sum();
        long m = parentChainMisses.sum();
        long total = h + m;
        return total > 0 ? (double) h / total : 0.0;
    }

    /**
     * Get a pre-computed ExtendedTetreeKey for shallow levels (0-5). This provides O(1) access instead of O(level)
     * tmIndex computation for the most common operations.
     *
     * @param x     x coordinate
     * @param y     y coordinate
     * @param z     z coordinate
     * @param level hierarchical level
     * @param type  tetrahedron type
     * @return pre-computed ExtendedTetreeKey or null if not in shallow level cache
     */
    public static TetreeKey<?> getShallowLevelKey(int x, int y, int z, byte level, byte type) {
        if (level > MAX_SHALLOW_LEVEL) {
            return null; // Not in shallow level range
        }

        int cellSize = Constants.lengthAtLevel(level);
        int gridX = x / cellSize;
        int gridY = y / cellSize;
        int gridZ = z / cellSize;

        int key = packShallowKey(gridX, gridY, gridZ, level, type);
        return SHALLOW_LEVEL_CACHE.get(key);
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

    /**
     * Initialize shallow level cache for levels 0-5. Pre-computes all possible tetrahedra for these levels to enable
     * O(1) lookups instead of O(level) tmIndex computation.
     */
    private static void initializeShallowLevelCache() {
        // Pre-compute all possible tetrahedra for levels 0-5
        for (byte level = 0; level <= MAX_SHALLOW_LEVEL; level++) {
            int cellSize = Constants.lengthAtLevel(level);
            int maxCoord = Constants.MAX_COORD / cellSize;

            for (int x = 0; x <= maxCoord; x += cellSize) {
                for (int y = 0; y <= maxCoord; y += cellSize) {
                    for (int z = 0; z <= maxCoord; z += cellSize) {
                        for (byte type = 0; type <= 5; type++) {
                            Tet tet = new Tet(x, y, z, level, type);
                            int key = packShallowKey(x / cellSize, y / cellSize, z / cellSize, level, type);
                            TetreeKey<?> tetreeKey = tet.tmIndex();
                            SHALLOW_LEVEL_CACHE.put(key, tetreeKey);
                            if (level == 0) {
                                break;
                            }
                        }
                        if (level == 0) {
                            break;
                        }
                    }
                    if (level == 0) {
                        break;
                    }
                }
            }
        }
    }

    private static void initializeTypeCaches() {
        // Initialize type transition cache
        // Pack: startType (8 bits) | startLevel (8 bits) | endLevel (8 bits)
        for (var startType = 0; startType < 6; startType++) {
            for (var startLevel = 0; startLevel <= MortonCurve.MAX_REFINEMENT_LEVEL; startLevel++) {
                for (var endLevel = 0; endLevel <= startLevel; endLevel++) {
                    var packed = packTypeTransition(startType, startLevel, endLevel);
                    var resultType = computeTypeTransition((byte) startType, (byte) startLevel, (byte) endLevel);
                    TYPE_TRANSITION_CACHE[packed] = resultType;
                }
            }
        }
    }

    /**
     * Pack coordinates and parameters into a compact key for shallow level lookup. Uses grid coordinates (already
     * divided by cell size) for efficiency.
     */
    private static int packShallowKey(int gridX, int gridY, int gridZ, int level, int type) {
        // Pack into 32-bit integer: gridX(10 bits) | gridY(10 bits) | gridZ(10 bits) | level(3 bits) | type(3 bits)
        return (gridX << 16) | (gridY << 13) | (gridZ << 10) | (level << 3) | type;
    }

    private static int packTypeTransition(int startType, int startLevel, int endLevel) {
        return (startType << 16) | (startLevel << 8) | endLevel;
    }

    /**
     * Reset cache statistics for benchmarking.
     */
    public static void resetCacheStats() {
        cacheHits.reset();
        cacheMisses.reset();
        parentChainHits.reset();
        parentChainMisses.reset();
        parentCacheHits.reset();
        parentCacheMisses.reset();
    }
}
