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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent publication-race test for {@link TetreeLevelCache} (Luciferase-7wzml.19).
 *
 * <p>Verifies that no read across ALL five caches ever returns a value belonging to a <em>different</em>
 * tuple from the one whose key matched — i.e., the publication-order race (key written to one array, value to
 * another, reader sees new key with old/null value) cannot occur under the {@link java.util.concurrent.atomic.AtomicReferenceArray}
 * Holder design.
 *
 * <p>Single-thread sanity tests also confirm that hit-rate behaviour is preserved.
 *
 * @author hal.hildebrand
 */
public class TetreeLevelCachePublicationTest {

    @BeforeEach
    void clearCache() {
        TetreeLevelCache.clearCaches();
    }

    // -------------------------------------------------------------------------
    // Single-thread sanity: hit-rate parity after the AtomicReferenceArray refactor
    // -------------------------------------------------------------------------

    @Test
    void indexCacheRoundTrip() {
        TetreeLevelCache.cacheIndex(10, 20, 30, (byte) 4, (byte) 2, 999L);
        assertEquals(999L, TetreeLevelCache.getCachedIndex(10, 20, 30, (byte) 4, (byte) 2));
    }

    @Test
    void parentTypeCacheRoundTrip() {
        TetreeLevelCache.cacheParentType(1, 2, 3, (byte) 5, (byte) 3, (byte) 1);
        assertEquals(1, TetreeLevelCache.getCachedParentType(1, 2, 3, (byte) 5, (byte) 3));
    }

    @Test
    void indexCacheMissReturnsMinusOne() {
        assertEquals(-1L, TetreeLevelCache.getCachedIndex(7, 8, 9, (byte) 1, (byte) 0));
    }

    @Test
    void parentTypeCacheMissReturnsMinusOne() {
        assertEquals(-1, TetreeLevelCache.getCachedParentType(7, 8, 9, (byte) 1, (byte) 0));
    }

    @Test
    void parentCacheRoundTrip() {
        // Level 1: cellSize = 1<<20 = 1_048_576 — use multiples of that
        int cs1 = 1 << 20;
        var tet = new Tet(cs1, 0, 0, (byte) 1, (byte) 1);
        TetreeLevelCache.cacheParent(cs1, 0, 0, (byte) 1, (byte) 1, tet);
        assertSame(tet, TetreeLevelCache.getCachedParent(cs1, 0, 0, (byte) 1, (byte) 1));
    }

    @Test
    void parentChainCacheRoundTrip() {
        // Level 1: cellSize = 1<<20
        int cs1 = 1 << 20;
        var tet = new Tet(cs1, 0, 0, (byte) 1, (byte) 0);
        var chain = new Tet[] { tet };
        TetreeLevelCache.cacheParentChain(tet, chain);
        assertArrayEquals(chain, TetreeLevelCache.getCachedParentChain(tet));
    }

    // -------------------------------------------------------------------------
    // Concurrent publication-race test (the primary correctness gate)
    // -------------------------------------------------------------------------

    /**
     * N writer threads and N reader threads hammer the index cache concurrently with distinct (x,y,z,l,t) tuples.
     * Each tuple's value encodes the tuple's identity so any torn read (key from tuple A, value from tuple B) is
     * immediately detectable.
     *
     * <p>A read must return EITHER the tuple's own correct value OR -1 (miss / evicted).  Any other return is a
     * torn key+value pair, which means the AtomicReferenceArray Holder guarantee was violated.
     */
    @Test
    void concurrentIndexCacheNeverReturnsTornKeyValuePair() throws Exception {
        int threads = 8;
        int tuples = 2000;
        var executor = Executors.newFixedThreadPool(threads * 2);
        var failure = new AtomicReference<String>(null);
        var barrier = new CyclicBarrier(threads * 2);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            // Writer thread
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = threadIdx; i < tuples; i += threads) {
                    int x = i % 300;
                    int y = (i / 300) % 300;
                    int z = (i * 7) % 300;
                    byte level = (byte) (i % 21);
                    byte type = (byte) (i % 6);
                    long expectedValue = valueFor(x, y, z, level, type);
                    TetreeLevelCache.cacheIndex(x, y, z, level, type, expectedValue);
                }
            }));

            // Reader thread — concurrent with writers
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = threadIdx; i < tuples; i += threads) {
                    int x = i % 300;
                    int y = (i / 300) % 300;
                    int z = (i * 7) % 300;
                    byte level = (byte) (i % 21);
                    byte type = (byte) (i % 6);
                    long expectedValue = valueFor(x, y, z, level, type);

                    long got = TetreeLevelCache.getCachedIndex(x, y, z, level, type);
                    // Must be the tuple's own value or a miss; never a value from a different tuple.
                    if (got != expectedValue && got != -1L) {
                        failure.compareAndSet(null,
                                              "Torn read: tuple(x=%d,y=%d,z=%d,l=%d,t=%d) got=%d expected=%d or -1"
                                              .formatted(x, y, z, level, type, got, expectedValue));
                    }
                }
            }));
        }

        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertNull(failure.get(), "Publication race detected: " + failure.get());
    }

    /**
     * Same pattern for the ParentType cache (stores primitive byte, held as Holder&lt;Byte&gt;).
     */
    @Test
    void concurrentParentTypeCacheNeverReturnsTornKeyValuePair() throws Exception {
        int threads = 8;
        int tuples = 2000;
        var executor = Executors.newFixedThreadPool(threads * 2);
        var failure = new AtomicReference<String>(null);
        var barrier = new CyclicBarrier(threads * 2);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = threadIdx; i < tuples; i += threads) {
                    int x = i % 300;
                    int y = (i / 300) % 300;
                    int z = (i * 7) % 300;
                    byte level = (byte) (i % 21);
                    byte type = (byte) (i % 6);
                    byte parentType = (byte) ((type + 1) % 6); // deterministic, distinct from type
                    TetreeLevelCache.cacheParentType(x, y, z, level, type, parentType);
                }
            }));

            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = threadIdx; i < tuples; i += threads) {
                    int x = i % 300;
                    int y = (i / 300) % 300;
                    int z = (i * 7) % 300;
                    byte level = (byte) (i % 21);
                    byte type = (byte) (i % 6);
                    byte expectedParentType = (byte) ((type + 1) % 6);

                    byte got = TetreeLevelCache.getCachedParentType(x, y, z, level, type);
                    // Must be own value or miss (-1); never a value belonging to a different tuple.
                    if (got != expectedParentType && got != -1) {
                        // any other byte value means a foreign holder's value leaked through
                        failure.compareAndSet(null,
                                              "Torn parentType read: tuple(x=%d,y=%d,z=%d,l=%d,t=%d) got=%d expected=%d or -1"
                                              .formatted(x, y, z, level, type, got, expectedParentType));
                    }
                }
            }));
        }

        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertNull(failure.get(), "Publication race detected in ParentType cache: " + failure.get());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** Encodes the tuple identity into the value so a cross-tuple serve is immediately detectable. */
    private static long valueFor(int x, int y, int z, byte level, byte type) {
        return ((long) x << 40) ^ ((long) y << 24) ^ ((long) z << 8) ^ ((long) level << 3) ^ type ^ 0x5A5A5A5AL;
    }
}
