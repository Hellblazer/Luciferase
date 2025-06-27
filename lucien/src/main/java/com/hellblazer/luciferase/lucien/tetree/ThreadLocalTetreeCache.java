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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-local cache for TetreeKey values to reduce contention in heavily concurrent workloads.
 * Each thread gets its own cache instance, eliminating synchronization overhead.
 *
 * @author hal.hildebrand
 */
public class ThreadLocalTetreeCache {
    
    // Thread-local storage for per-thread caches
    private static final ThreadLocal<TetreeKeyCache> tlCache = 
        ThreadLocal.withInitial(() -> new TetreeKeyCache(4096));
    
    // Global statistics across all threads
    private static final AtomicLong globalHits = new AtomicLong();
    private static final AtomicLong globalMisses = new AtomicLong();
    
    /**
     * Get or compute TetreeKey for the given Tet using thread-local cache.
     *
     * @param tet the tetrahedron
     * @return the TetreeKey
     */
    public static TetreeKey getTetreeKey(Tet tet) {
        return tlCache.get().get(tet);
    }
    
    /**
     * Clear the current thread's cache.
     */
    public static void clearThreadCache() {
        tlCache.get().clear();
    }
    
    /**
     * Remove the current thread's cache entirely (for cleanup).
     */
    public static void removeThreadCache() {
        tlCache.remove();
    }
    
    /**
     * Get global statistics across all threads.
     *
     * @return cache statistics string
     */
    public static String getGlobalStatistics() {
        long hits = globalHits.get();
        long misses = globalMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total : 0.0;
        
        return String.format("ThreadLocal TetreeKey Cache - Hits: %d, Misses: %d, Hit Rate: %.2f%%",
                hits, misses, hitRate * 100);
    }
    
    /**
     * Reset global statistics.
     */
    public static void resetGlobalStatistics() {
        globalHits.set(0);
        globalMisses.set(0);
    }
    
    /**
     * Per-thread cache implementation.
     */
    private static class TetreeKeyCache {
        private final int cacheSize;
        private final long[] cacheKeys;
        private final TetreeKey[] cacheValues;
        private long hits = 0;
        private long misses = 0;
        
        TetreeKeyCache(int size) {
            this.cacheSize = size;
            this.cacheKeys = new long[size];
            this.cacheValues = new TetreeKey[size];
        }
        
        TetreeKey get(Tet tet) {
            long key = generateCacheKey(tet);
            int slot = (int)(key & (cacheSize - 1));
            
            // Check cache
            if (cacheKeys[slot] == key && cacheValues[slot] != null) {
                hits++;
                globalHits.incrementAndGet();
                return cacheValues[slot];
            }
            
            // Cache miss - compute value
            misses++;
            globalMisses.incrementAndGet();
            
            // Compute without using the global cache to avoid contention
            var result = computeTetreeKey(tet);
            
            // Store in thread-local cache
            cacheKeys[slot] = key;
            cacheValues[slot] = result;
            
            return result;
        }
        
        void clear() {
            for (int i = 0; i < cacheSize; i++) {
                cacheKeys[i] = 0;
                cacheValues[i] = null;
            }
            hits = 0;
            misses = 0;
        }
        
        private long generateCacheKey(Tet tet) {
            // Use hash function to avoid collisions
            long key = tet.x() * 0x9E3779B97F4A7C15L;
            key ^= tet.y() * 0xBF58476D1CE4E5B9L;
            key ^= tet.z() * 0x94D049BB133111EBL;
            key ^= tet.l() * 0x2545F4914F6CDD1DL;
            key ^= tet.type();
            return key;
        }
        
        private TetreeKey computeTetreeKey(Tet tet) {
            // Direct computation of tmIndex without using global cache
            // This duplicates the logic from Tet.tmIndex() to avoid cache contention
            
            if (tet.l() == 0) {
                return TetreeKey.getRoot();
            }
            
            // Build parent chain
            var current = tet;
            var types = new java.util.ArrayList<Byte>();
            
            // Collect types from parent up to root
            while (current.l() > 1) {
                current = current.parent();
                if (current != null) {
                    types.add(0, current.type());
                }
            }
            
            // Build type array
            int maxBits = tet.l();
            int[] typeArray = new int[maxBits];
            
            // Fill ancestor types
            for (int i = 0; i < types.size() && i < maxBits; i++) {
                typeArray[i] = types.get(i);
            }
            
            // Set current type at the least significant position
            if (tet.l() > 0 && types.size() < maxBits) {
                typeArray[maxBits - 1] = tet.type();
            }
            
            // Build TM-index using 128-bit representation
            long lowBits = 0L;
            long highBits = 0L;
            
            // Process each bit position
            for (int i = 0; i < maxBits; i++) {
                int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
                int xBit = (tet.x() >> bitPos) & 1;
                int yBit = (tet.y() >> bitPos) & 1;
                int zBit = (tet.z() >> bitPos) & 1;
                
                // Combine coordinate bits: z|y|x
                int coordBits = (zBit << 2) | (yBit << 1) | xBit;
                
                // Combine with type: upper 3 bits are coords, lower 3 bits are type
                int sixBits = (coordBits << 3) | typeArray[i];
                
                // Pack into appropriate long (10 levels per long, 6 bits per level)
                if (i < 10) {
                    lowBits |= ((long) sixBits) << (6 * i);
                } else {
                    highBits |= ((long) sixBits) << (6 * (i - 10));
                }
            }
            
            return new TetreeKey(tet.l(), lowBits, highBits);
        }
    }
}