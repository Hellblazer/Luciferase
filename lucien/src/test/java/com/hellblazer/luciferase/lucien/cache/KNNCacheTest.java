/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.cache;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for k-NN result caching
 *
 * @author hal.hildebrand
 */
@DisplayName("k-NN Cache Tests")
class KNNCacheTest {

    private KNNCache<MortonKey, LongEntityID> cache;
    private KNNQueryKey<MortonKey> key1;
    private KNNQueryKey<MortonKey> key2;
    private List<LongEntityID> ids1;
    private List<Float> distances1;

    @BeforeEach
    void setUp() {
        cache = new KNNCache<>(100);
        // Create composite query keys with k and maxDistance parameters
        key1 = new KNNQueryKey<>(new MortonKey(12345L, (byte) 10), 5, 100.0f);
        key2 = new KNNQueryKey<>(new MortonKey(67890L, (byte) 10), 5, 100.0f);

        ids1 = List.of(
            new LongEntityID(1),
            new LongEntityID(2),
            new LongEntityID(3)
        );
        distances1 = List.of(1.0f, 2.0f, 3.0f);
    }

    @Test
    @DisplayName("Basic cache put and get")
    void testBasicPutGet() {
        long version = 1;

        // Cache miss initially
        assertNull(cache.get(key1, version));
        assertEquals(0.0, cache.getHitRate());

        // Put result
        cache.put(key1, ids1, distances1, version);
        assertEquals(1, cache.size());

        // Cache hit with correct version
        var cached = cache.get(key1, version);
        assertNotNull(cached);
        assertEquals(ids1, cached.entityIds());
        assertEquals(distances1, cached.distances());
        assertEquals(version, cached.version());

        assertEquals(0.5, cache.getHitRate()); // 1 hit, 1 miss
    }

    @Test
    @DisplayName("Version-based invalidation")
    void testVersionInvalidation() {
        long version1 = 1;
        long version2 = 2;

        cache.put(key1, ids1, distances1, version1);

        // Hit with matching version
        assertNotNull(cache.get(key1, version1));

        // Miss with different version (stale cache)
        assertNull(cache.get(key1, version2));

        // Still in cache but stale
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("LRU eviction")
    void testLRUEviction() {
        var smallCache = new KNNCache<MortonKey, LongEntityID>(3);
        long version = 1;

        // Fill cache to capacity - use different k values to create distinct keys
        var key1 = new KNNQueryKey<>(new MortonKey(1L, (byte) 1), 5, 100.0f);
        var key2 = new KNNQueryKey<>(new MortonKey(2L, (byte) 1), 5, 100.0f);
        var key3 = new KNNQueryKey<>(new MortonKey(3L, (byte) 1), 5, 100.0f);
        var key4 = new KNNQueryKey<>(new MortonKey(4L, (byte) 1), 5, 100.0f);

        smallCache.put(key1, ids1, distances1, version);
        smallCache.put(key2, ids1, distances1, version);
        smallCache.put(key3, ids1, distances1, version);
        assertEquals(3, smallCache.size());

        // Access key1 to make it recently used
        assertNotNull(smallCache.get(key1, version));

        // Add key4, should evict least recently used (key2)
        smallCache.put(key4, ids1, distances1, version);
        assertEquals(3, smallCache.size());

        // key2 should be evicted
        assertNull(smallCache.get(key2, version));
        // key1, key3, key4 should still be present
        assertNotNull(smallCache.get(key1, version));
        assertNotNull(smallCache.get(key3, version));
        assertNotNull(smallCache.get(key4, version));
    }

    @Test
    @DisplayName("Explicit invalidation")
    void testExplicitInvalidation() {
        long version = 1;

        cache.put(key1, ids1, distances1, version);
        cache.put(key2, ids1, distances1, version);
        assertEquals(2, cache.size());

        // Invalidate one key
        cache.invalidate(key1);
        assertEquals(1, cache.size());
        assertNull(cache.get(key1, version));
        assertNotNull(cache.get(key2, version));

        // Invalidate all
        cache.invalidateAll();
        assertEquals(0, cache.size());
        assertNull(cache.get(key2, version));
    }

    @Test
    @DisplayName("Cache statistics")
    void testStatistics() {
        long version = 1;

        // Initial state
        var stats = cache.getStats();
        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
        assertEquals(0.0, stats.hitRate());

        // Misses
        cache.get(key1, version);
        cache.get(key2, version);

        // Puts and hits
        cache.put(key1, ids1, distances1, version);
        cache.get(key1, version); // hit
        cache.get(key1, version); // hit

        stats = cache.getStats();
        assertEquals(2, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(0.5, stats.hitRate());

        // Reset
        cache.resetStats();
        stats = cache.getStats();
        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
    }

    @Test
    @DisplayName("Invalidation statistics")
    void testInvalidationStats() {
        long version = 1;

        cache.put(key1, ids1, distances1, version);
        cache.put(key2, ids1, distances1, version);

        cache.invalidate(key1);
        var stats = cache.getStats();
        assertEquals(1, stats.invalidations());

        cache.invalidateAll();
        stats = cache.getStats();
        assertEquals(2, stats.invalidations()); // 1 + 1 from invalidateAll
    }

    @Test
    @DisplayName("Immutable cached results")
    void testImmutability() {
        long version = 1;

        var mutableIds = new ArrayList<>(ids1);
        var mutableDistances = new ArrayList<>(distances1);

        cache.put(key1, mutableIds, mutableDistances, version);

        // Modify original lists
        mutableIds.add(new LongEntityID(999));
        mutableDistances.add(999.0f);

        // Cached result should be unchanged
        var cached = cache.get(key1, version);
        assertEquals(3, cached.entityIds().size());
        assertEquals(3, cached.distances().size());

        // Cached result should be immutable
        assertThrows(UnsupportedOperationException.class,
            () -> cached.entityIds().add(new LongEntityID(888)));
        assertThrows(UnsupportedOperationException.class,
            () -> cached.distances().add(888.0f));
    }

    @Test
    @DisplayName("Size validation")
    void testSizeValidation() {
        long version = 1;

        var ids = List.of(new LongEntityID(1), new LongEntityID(2));
        var distances = List.of(1.0f);

        // Mismatched sizes should throw
        assertThrows(IllegalArgumentException.class,
            () -> cache.put(key1, ids, distances, version));
    }

    @Test
    @DisplayName("Thread-safe concurrent access")
    void testConcurrentAccess() throws InterruptedException {
        long version = 1;
        int threadCount = 10;
        int operationsPerThread = 1000;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Pre-populate some cache entries
        for (int i = 0; i < 50; i++) {
            var key = new KNNQueryKey<>(new MortonKey(i, (byte) 1), 5, 100.0f);
            cache.put(key, ids1, distances1, version);
        }

        // Concurrent reads and writes
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        var key = new KNNQueryKey<>(new MortonKey((threadId * operationsPerThread + i) % 100, (byte) 1), 5, 100.0f);

                        if (i % 3 == 0) {
                            cache.put(key, ids1, distances1, version);
                        } else if (i % 3 == 1) {
                            cache.get(key, version);
                        } else {
                            cache.invalidate(key);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent operations timed out");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Should complete without exceptions
        // Cache should be in valid state
        assertTrue(cache.size() >= 0);
        assertTrue(cache.size() <= 100);
    }

    @Test
    @DisplayName("Cache stats toString")
    void testStatsToString() {
        long version = 1;

        cache.put(key1, ids1, distances1, version);
        cache.get(key1, version); // hit
        cache.get(key2, version); // miss

        var stats = cache.getStats();
        var str = stats.toString();

        assertTrue(str.contains("hits=1"));
        assertTrue(str.contains("misses=1"));
        assertTrue(str.contains("hitRate=50.0%"));
        assertTrue(str.contains("size=1/100"));
    }

    @Test
    @DisplayName("Empty cache behavior")
    void testEmptyCache() {
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.getHitRate());

        var stats = cache.getStats();
        assertEquals(0, stats.currentSize());
        assertEquals(100, stats.maxSize());
    }

    @Test
    @DisplayName("Single entry cache")
    void testSingleEntryCache() {
        var tinyCache = new KNNCache<MortonKey, LongEntityID>(1);
        long version = 1;

        tinyCache.put(key1, ids1, distances1, version);
        assertEquals(1, tinyCache.size());

        // Adding second entry should evict first
        tinyCache.put(key2, ids1, distances1, version);
        assertEquals(1, tinyCache.size());

        assertNull(tinyCache.get(key1, version));
        assertNotNull(tinyCache.get(key2, version));
    }
}
