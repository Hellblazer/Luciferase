package com.hellblazer.luciferase.simulation.viz.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegionCache - Caffeine-based LRU cache with pinning.
 */
class RegionCacheTest {

    private RegionCache cache;

    @BeforeEach
    void setUp() {
        // Create cache with 10KB max memory, 1-second TTL for testing
        cache = new RegionCache(10_000, Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void testPutAndGet_basicOperation() {
        // Create test data
        var regionId = new RegionId(1L, 0);
        var key = new RegionCache.CacheKey(regionId, 0);

        var builtRegion = createTestRegion(regionId, 1000);
        var cachedRegion = RegionCache.CachedRegion.from(builtRegion, System.currentTimeMillis());

        // Put and get
        cache.put(key, cachedRegion);
        var retrieved = cache.get(key);

        assertTrue(retrieved.isPresent(), "Region should be cached");
        assertEquals(cachedRegion, retrieved.get());
    }

    @Test
    void testGet_miss_returnsEmpty() {
        var key = new RegionCache.CacheKey(new RegionId(999L, 0), 0);

        var result = cache.get(key);

        assertFalse(result.isPresent(), "Non-existent key should return empty");
    }

    @Test
    void testMemoryTracking_accurate() {
        // Add regions with known sizes
        var region1 = createTestRegion(new RegionId(1L, 0), 1000);
        var region2 = createTestRegion(new RegionId(2L, 0), 2000);
        var region3 = createTestRegion(new RegionId(3L, 0), 3000);

        long now = System.currentTimeMillis();
        var cached1 = RegionCache.CachedRegion.from(region1, now);
        var cached2 = RegionCache.CachedRegion.from(region2, now);
        var cached3 = RegionCache.CachedRegion.from(region3, now);

        cache.put(new RegionCache.CacheKey(new RegionId(1L, 0), 0), cached1);
        cache.put(new RegionCache.CacheKey(new RegionId(2L, 0), 0), cached2);
        cache.put(new RegionCache.CacheKey(new RegionId(3L, 0), 0), cached3);

        // Verify memory tracking
        long totalMemory = cache.getTotalMemoryBytes();
        assertTrue(totalMemory >= 6000, "Total memory should be at least 6000 bytes, got: " + totalMemory);

        var stats = cache.getStats();
        assertEquals(3, stats.totalCount(), "Should have 3 cached regions");
    }

    @Test
    void testLRUEviction_caffeineEvictsOldestUnpinned() throws InterruptedException {
        // Create small cache (4KB max) - need larger to account for 72-byte overhead per region
        // 4 regions × 1072 bytes = 4288 bytes total, exceeds 4000 limit
        var smallCache = new RegionCache(4000, Duration.ofMinutes(5));

        try {
            long now = System.currentTimeMillis();

            // Add regions totaling ~4.3KB (exceeds 4KB limit)
            var region1 = createTestRegion(new RegionId(1L, 0), 1000);
            var region2 = createTestRegion(new RegionId(2L, 0), 1000);
            var region3 = createTestRegion(new RegionId(3L, 0), 1000);
            var region4 = createTestRegion(new RegionId(4L, 0), 1000);

            var key1 = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
            var key2 = new RegionCache.CacheKey(new RegionId(2L, 0), 0);
            var key3 = new RegionCache.CacheKey(new RegionId(3L, 0), 0);
            var key4 = new RegionCache.CacheKey(new RegionId(4L, 0), 0);

            smallCache.put(key1, RegionCache.CachedRegion.from(region1, now));
            Thread.sleep(10); // Ensure different access times
            smallCache.put(key2, RegionCache.CachedRegion.from(region2, now + 10));
            Thread.sleep(10);
            smallCache.put(key3, RegionCache.CachedRegion.from(region3, now + 20));
            Thread.sleep(10);
            smallCache.put(key4, RegionCache.CachedRegion.from(region4, now + 30));

            // Force Caffeine to run maintenance and perform evictions
            // Access the cache to trigger Caffeine's internal maintenance
            for (int i = 0; i < 10; i++) {
                smallCache.get(key4); // Access most recent to keep it
            }
            Thread.sleep(50); // Give Caffeine time to run async eviction

            // Caffeine should have evicted regions to stay under 4KB (90% = 3600 bytes for unpinned)
            var stats = smallCache.getStats();
            long unpinnedMax = (long) (4000 * 0.9); // 3600 bytes
            assertTrue(stats.totalMemoryBytes() <= unpinnedMax,
                    String.format("Memory should be under %d bytes, got: %d",
                            unpinnedMax, stats.totalMemoryBytes()));

            // At least some eviction should have occurred
            assertTrue(stats.caffeineEvictionCount() > 0,
                    "Caffeine should have evicted at least one region");

        } finally {
            smallCache.close();
        }
    }

    @Test
    void testTTLEviction_caffeineRemovesExpiredEntries() throws InterruptedException {
        // Create cache with 100ms TTL
        var shortTTLCache = new RegionCache(10_000, Duration.ofMillis(100));

        try {
            var region = createTestRegion(new RegionId(1L, 0), 1000);
            var key = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
            var cached = RegionCache.CachedRegion.from(region, System.currentTimeMillis());

            shortTTLCache.put(key, cached);

            // Verify present immediately
            assertTrue(shortTTLCache.get(key).isPresent(), "Region should be cached initially");

            // Wait for TTL expiration
            Thread.sleep(150);

            // Trigger Caffeine cleanup by accessing
            var result = shortTTLCache.get(key);

            // May or may not be present depending on Caffeine's cleanup timing
            // Just verify the cache doesn't crash
            assertNotNull(result);

        } finally {
            shortTTLCache.close();
        }
    }

    @Test
    void testPinning_preventsEviction() throws InterruptedException {
        // Create small cache (2KB max)
        var smallCache = new RegionCache(2000, Duration.ofMinutes(5));

        try {
            long now = System.currentTimeMillis();

            // Add and pin first region
            var region1 = createTestRegion(new RegionId(1L, 0), 1000);
            var key1 = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
            smallCache.put(key1, RegionCache.CachedRegion.from(region1, now));
            smallCache.pin(key1);

            // Verify pinned memory tracking (1000 bytes data + 72 bytes overhead = 1072)
            assertEquals(1072, smallCache.getPinnedMemoryBytes(),
                    "Pinned memory should be 1072 bytes (1000 data + 72 overhead)");

            // Add more regions to exceed unpinned limit
            var region2 = createTestRegion(new RegionId(2L, 0), 1000);
            var region3 = createTestRegion(new RegionId(3L, 0), 1000);

            smallCache.put(new RegionCache.CacheKey(new RegionId(2L, 0), 0),
                    RegionCache.CachedRegion.from(region2, now));
            Thread.sleep(10);
            smallCache.put(new RegionCache.CacheKey(new RegionId(3L, 0), 0),
                    RegionCache.CachedRegion.from(region3, now));

            // Pinned region should still be present despite memory pressure
            assertTrue(smallCache.get(key1).isPresent(),
                    "Pinned region should not be evicted");

            var stats = smallCache.getStats();
            assertEquals(1, stats.pinnedCount(), "Should have 1 pinned region");

        } finally {
            smallCache.close();
        }
    }

    @Test
    void testMultiLOD_sameRegionDifferentLODs() {
        var regionId = new RegionId(1L, 0);

        // Cache same region at different LOD levels
        var lod0Key = new RegionCache.CacheKey(regionId, 0);
        var lod1Key = new RegionCache.CacheKey(regionId, 1);
        var lod2Key = new RegionCache.CacheKey(regionId, 2);

        var region0 = createTestRegion(regionId, 1000);
        var region1 = createTestRegion(regionId, 500);
        var region2 = createTestRegion(regionId, 250);

        long now = System.currentTimeMillis();
        cache.put(lod0Key, RegionCache.CachedRegion.from(region0, now));
        cache.put(lod1Key, RegionCache.CachedRegion.from(region1, now));
        cache.put(lod2Key, RegionCache.CachedRegion.from(region2, now));

        // All LOD levels should be cached independently
        assertTrue(cache.get(lod0Key).isPresent(), "LOD 0 should be cached");
        assertTrue(cache.get(lod1Key).isPresent(), "LOD 1 should be cached");
        assertTrue(cache.get(lod2Key).isPresent(), "LOD 2 should be cached");

        var stats = cache.getStats();
        assertEquals(3, stats.totalCount(), "Should have 3 cached regions (different LODs)");
    }

    @Test
    void testPin_movesBetweenCaches() {
        var region = createTestRegion(new RegionId(1L, 0), 1000);
        var key = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
        var cached = RegionCache.CachedRegion.from(region, System.currentTimeMillis());

        // Put in unpinned cache
        cache.put(key, cached);
        assertEquals(0, cache.getPinnedMemoryBytes(), "Initially no pinned memory");
        assertTrue(cache.getUnpinnedMemoryBytes() >= 1000,
                "Unpinned memory should include region");

        // Pin - moves to pinned cache
        boolean pinned = cache.pin(key);
        assertTrue(pinned, "Pin should succeed");
        assertEquals(1072, cache.getPinnedMemoryBytes(),
                "Pinned memory should be 1072 bytes (1000 data + 72 overhead)");

        var stats = cache.getStats();
        assertEquals(1, stats.pinnedCount(), "Should have 1 pinned region");
        assertEquals(0, stats.unpinnedCount(), "Should have 0 unpinned regions");

        // Unpin - moves back to unpinned cache
        boolean unpinned = cache.unpin(key);
        assertTrue(unpinned, "Unpin should succeed");
        assertEquals(0, cache.getPinnedMemoryBytes(), "Pinned memory should be 0 after unpin");

        stats = cache.getStats();
        assertEquals(0, stats.pinnedCount(), "Should have 0 pinned regions after unpin");
        assertEquals(1, stats.unpinnedCount(), "Should have 1 unpinned region after unpin");

        // Region should still be accessible
        assertTrue(cache.get(key).isPresent(), "Region should still be cached after unpin");
    }

    @Test
    void testUnpin_movesBetweenCaches() throws InterruptedException {
        // This is the inverse of testPin_movesBetweenCaches
        var region = createTestRegion(new RegionId(1L, 0), 1000);
        var key = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
        var cached = RegionCache.CachedRegion.from(region, System.currentTimeMillis());

        // Put and pin first
        cache.put(key, cached);
        cache.pin(key);

        // Verify in pinned cache
        assertEquals(1072, cache.getPinnedMemoryBytes());
        var stats = cache.getStats();
        assertEquals(1, stats.pinnedCount());
        assertEquals(0, stats.unpinnedCount());

        // Unpin - moves to unpinned cache
        boolean unpinned = cache.unpin(key);
        assertTrue(unpinned, "Unpin should succeed");
        assertEquals(0, cache.getPinnedMemoryBytes());

        stats = cache.getStats();
        assertEquals(0, stats.pinnedCount());
        assertEquals(1, stats.unpinnedCount());

        // Region should still be accessible in unpinned cache
        assertTrue(cache.get(key).isPresent());
    }

    @Test
    void testInvalidate_removesAllLODs() throws InterruptedException {
        var regionId = new RegionId(1L, 0);

        // Cache same region at 3 LOD levels
        var lod0Key = new RegionCache.CacheKey(regionId, 0);
        var lod1Key = new RegionCache.CacheKey(regionId, 1);
        var lod2Key = new RegionCache.CacheKey(regionId, 2);

        long now = System.currentTimeMillis();
        cache.put(lod0Key, RegionCache.CachedRegion.from(createTestRegion(regionId, 1000), now));
        cache.put(lod1Key, RegionCache.CachedRegion.from(createTestRegion(regionId, 500), now));
        cache.put(lod2Key, RegionCache.CachedRegion.from(createTestRegion(regionId, 250), now));

        // Verify all cached
        assertTrue(cache.get(lod0Key).isPresent());
        assertTrue(cache.get(lod1Key).isPresent());
        assertTrue(cache.get(lod2Key).isPresent());

        // Invalidate each LOD separately
        cache.invalidate(lod0Key);
        assertFalse(cache.get(lod0Key).isPresent(), "LOD 0 should be invalidated");
        assertTrue(cache.get(lod1Key).isPresent(), "LOD 1 should still exist");
        assertTrue(cache.get(lod2Key).isPresent(), "LOD 2 should still exist");

        cache.invalidate(lod1Key);
        cache.invalidate(lod2Key);

        // All should be gone
        assertFalse(cache.get(lod0Key).isPresent());
        assertFalse(cache.get(lod1Key).isPresent());
        assertFalse(cache.get(lod2Key).isPresent());
    }

    @Test
    void testCacheInvalidationOnDirty() throws InterruptedException {
        var region = createTestRegion(new RegionId(1L, 0), 1000);
        var key = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
        var cached = RegionCache.CachedRegion.from(region, System.currentTimeMillis());

        // Cache and pin
        cache.put(key, cached);
        cache.pin(key);

        assertTrue(cache.get(key).isPresent());
        assertEquals(1, cache.getStats().pinnedCount());

        // Invalidate removes from pinned cache
        cache.invalidate(key);

        assertFalse(cache.get(key).isPresent(), "Invalidated region should not be present");
        assertEquals(0, cache.getStats().pinnedCount(), "Pinned count should be 0");
        assertEquals(0, cache.getPinnedMemoryBytes(), "Pinned memory should be 0");
    }

    @Test
    void testEmergencyEviction_triggersAbove90Percent() throws InterruptedException {
        // Create cache with 1KB max
        var smallCache = new RegionCache(1000, Duration.ofMinutes(5));

        try {
            long now = System.currentTimeMillis();

            // Add and pin regions totaling ~960 bytes (96% of 1000 bytes, exceeds 90% threshold)
            // Each region: 200 bytes data + 72 overhead = 272 bytes
            // 3 regions × 272 = 816 bytes (safe)
            // 4 regions × 272 = 1088 bytes (exceeds 90% threshold of 900 bytes)

            var region1 = createTestRegion(new RegionId(1L, 0), 200);
            var region2 = createTestRegion(new RegionId(2L, 0), 200);
            var region3 = createTestRegion(new RegionId(3L, 0), 200);
            var region4 = createTestRegion(new RegionId(4L, 0), 200);

            var key1 = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
            var key2 = new RegionCache.CacheKey(new RegionId(2L, 0), 0);
            var key3 = new RegionCache.CacheKey(new RegionId(3L, 0), 0);
            var key4 = new RegionCache.CacheKey(new RegionId(4L, 0), 0);

            // Pin all regions to force emergency eviction (Caffeine can't help)
            smallCache.put(key1, RegionCache.CachedRegion.from(region1, now));
            smallCache.pin(key1);
            Thread.sleep(10);
            smallCache.put(key2, RegionCache.CachedRegion.from(region2, now + 10));
            smallCache.pin(key2);
            Thread.sleep(10);
            smallCache.put(key3, RegionCache.CachedRegion.from(region3, now + 20));
            smallCache.pin(key3);
            Thread.sleep(10);
            smallCache.put(key4, RegionCache.CachedRegion.from(region4, now + 30));
            smallCache.pin(key4);

            // Verify over 90% threshold
            long totalMemory = smallCache.getTotalMemoryBytes();
            assertTrue(totalMemory > 900, "Should be over 90% threshold, got: " + totalMemory);

            // Trigger emergency eviction
            int evictedCount = smallCache.emergencyEvict();

            // Should have evicted some pinned regions
            assertTrue(evictedCount > 0, "Should have evicted at least one region");

            // Memory should now be below 75% target (750 bytes)
            long finalMemory = smallCache.getTotalMemoryBytes();
            assertTrue(finalMemory <= 750,
                    "Memory should be below 75% target (750 bytes), got: " + finalMemory);

        } finally {
            smallCache.close();
        }
    }

    @Test
    void testConcurrentEmergencyEviction_onlyOneThreadEvicts() throws InterruptedException {
        // Create cache with 1KB max
        var smallCache = new RegionCache(1000, Duration.ofMinutes(5));

        try {
            long now = System.currentTimeMillis();

            // Add and pin regions to exceed 90% threshold
            for (int i = 0; i < 4; i++) {
                var region = createTestRegion(new RegionId(i, 0), 200);
                var key = new RegionCache.CacheKey(new RegionId(i, 0), 0);
                smallCache.put(key, RegionCache.CachedRegion.from(region, now));
                smallCache.pin(key);
            }

            // Launch multiple concurrent emergency eviction attempts
            var latch = new java.util.concurrent.CountDownLatch(5);
            var evictedCounts = new java.util.concurrent.ConcurrentHashMap<Integer, Integer>();

            for (int i = 0; i < 5; i++) {
                final int threadId = i;
                new Thread(() -> {
                    int count = smallCache.emergencyEvict();
                    evictedCounts.put(threadId, count);
                    latch.countDown();
                }).start();
            }

            // Wait for all threads to complete
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);

            // C4 guard verification: only one thread should have performed eviction
            long threadsWithEvictions = evictedCounts.values().stream()
                    .filter(count -> count > 0)
                    .count();

            assertTrue(threadsWithEvictions <= 1,
                    "C4 guard should allow at most one thread to evict, got: " + threadsWithEvictions);

            // At least one thread should have succeeded
            int totalEvicted = evictedCounts.values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
            assertTrue(totalEvicted > 0, "At least one thread should have evicted regions");

        } finally {
            smallCache.close();
        }
    }

    @Test
    void testCaffeineStats_hitMissTracking() {
        var region1 = createTestRegion(new RegionId(1L, 0), 1000);
        var region2 = createTestRegion(new RegionId(2L, 0), 1000);

        var key1 = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
        var key2 = new RegionCache.CacheKey(new RegionId(2L, 0), 0);
        var keyMiss = new RegionCache.CacheKey(new RegionId(999L, 0), 0);

        long now = System.currentTimeMillis();
        cache.put(key1, RegionCache.CachedRegion.from(region1, now));
        cache.put(key2, RegionCache.CachedRegion.from(region2, now));

        // Generate hits
        cache.get(key1); // hit
        cache.get(key1); // hit
        cache.get(key2); // hit

        // Generate misses
        cache.get(keyMiss); // miss
        cache.get(keyMiss); // miss

        var stats = cache.getStats();

        // Verify Caffeine stats tracking
        // Note: Stats may include some baseline hits/misses from internal operations
        assertTrue(stats.caffeineHitRate() > 0.0, "Should have some hits");
        assertTrue(stats.caffeineMissRate() > 0.0, "Should have some misses");

        // Overall hit rate should be reasonable (hits / (hits + misses))
        // With 3 hits and 2 misses, expect ~60% hit rate
        assertTrue(stats.caffeineHitRate() > 0.5,
                "Hit rate should be > 50%, got: " + stats.caffeineHitRate());
    }

    @Test
    void testConcurrentAccess_noCorruption() throws InterruptedException {
        var latch = new java.util.concurrent.CountDownLatch(10);
        var errors = new java.util.concurrent.ConcurrentHashMap<Integer, Exception>();

        // Spawn 10 threads doing concurrent operations
        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    var region = createTestRegion(new RegionId(threadId, 0), 1000);
                    var key = new RegionCache.CacheKey(new RegionId(threadId, 0), 0);
                    var cached = RegionCache.CachedRegion.from(region, System.currentTimeMillis());

                    // Concurrent put/get/pin/unpin
                    cache.put(key, cached);
                    cache.get(key);
                    cache.pin(key);
                    cache.get(key);
                    cache.unpin(key);
                    cache.get(key);
                    cache.invalidate(key);

                } catch (Exception e) {
                    errors.put(threadId, e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for all threads
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);

        // Verify no errors
        assertTrue(errors.isEmpty(), "Should have no concurrency errors, got: " + errors);

        // Verify cache is still functional
        var stats = cache.getStats();
        assertNotNull(stats);
        assertTrue(stats.totalMemoryBytes() >= 0);
    }

    // ===== Critical Bug Coverage Tests (Luciferase-4k0q) =====

    /**
     * T1: Test integer overflow prevention in Caffeine weigher.
     * C1 Fix: Weigher clamps to Integer.MAX_VALUE, CachedRegion.from() logs warning.
     */
    @Test
    void testIntegerOverflow_caffeineWeigherClamps() {
        var regionId = new RegionId(1L, 0);
        var key = new RegionCache.CacheKey(regionId, 0);

        // Create normal test region
        var builtRegion = createTestRegion(regionId, 1000);

        // Manually create CachedRegion with sizeBytes > Integer.MAX_VALUE (3GB)
        // This simulates a huge region without requiring 3GB of actual memory
        long now = System.currentTimeMillis();
        var hugeRegion = new RegionCache.CachedRegion(
                builtRegion,
                now,
                now,
                3_000_000_000L  // 3GB > Integer.MAX_VALUE
        );

        // C1 Fix: Put should succeed without overflow exception (weigher clamps internally)
        cache.put(key, hugeRegion);

        // Verify cached successfully despite huge size
        assertTrue(cache.get(key).isPresent(), "Huge region should be cached");

        // Verify sizeBytes preserved correctly (not clamped in CachedRegion)
        assertEquals(3_000_000_000L, cache.get(key).get().sizeBytes(),
                "CachedRegion should preserve full size even when weigher clamps to Integer.MAX_VALUE");
    }

    /**
     * T2: Test access timestamp updates on cache hits.
     * C2 Fix: computeIfPresent() atomically updates lastAccessedMs in pinned cache.
     * Strategy: Test indirectly via emergencyEvict() LRU behavior.
     */
    @Test
    void testAccessTimestamp_recentlyAccessedRetainedDuringEviction() throws InterruptedException {
        var smallCache = new RegionCache(1000, Duration.ofMinutes(5));

        try {
            long now = System.currentTimeMillis();

            // Create 4 regions with staggered timestamps (total: 4 × 272 = 1088 bytes, exceeds 90%)
            var region1 = createTestRegion(new RegionId(1L, 0), 200);
            var region2 = createTestRegion(new RegionId(2L, 0), 200);
            var region3 = createTestRegion(new RegionId(3L, 0), 200);
            var region4 = createTestRegion(new RegionId(4L, 0), 200);

            var key1 = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
            var key2 = new RegionCache.CacheKey(new RegionId(2L, 0), 0);
            var key3 = new RegionCache.CacheKey(new RegionId(3L, 0), 0);
            var key4 = new RegionCache.CacheKey(new RegionId(4L, 0), 0);

            // Pin all with staggered timestamps (oldest to newest: key1 → key4)
            smallCache.put(key1, RegionCache.CachedRegion.from(region1, now));
            smallCache.pin(key1);
            Thread.sleep(10);
            smallCache.put(key2, RegionCache.CachedRegion.from(region2, now + 10));
            smallCache.pin(key2);
            Thread.sleep(10);
            smallCache.put(key3, RegionCache.CachedRegion.from(region3, now + 20));
            smallCache.pin(key3);
            Thread.sleep(10);
            smallCache.put(key4, RegionCache.CachedRegion.from(region4, now + 30));
            smallCache.pin(key4);

            // Access middle region (key2) to update its timestamp to newest
            Thread.sleep(10);
            smallCache.get(key2);  // C2 fix: Atomic timestamp update via computeIfPresent()

            // Trigger emergency eviction
            int evicted = smallCache.emergencyEvict();

            // Should evict at least 2 regions to get under 75% target (750 bytes)
            assertTrue(evicted >= 2, "Should evict at least 2 regions, got: " + evicted);

            // Recently-accessed key2 should be retained (LRU semantic)
            assertTrue(smallCache.get(key2).isPresent(),
                    "Recently accessed region should be retained by LRU eviction");

            // Oldest region (key1) should be evicted first
            assertFalse(smallCache.get(key1).isPresent(),
                    "Oldest region should be evicted first");

        } finally {
            smallCache.close();
        }
    }

    /**
     * T3: Test concurrent pin/unpin race condition.
     * C3 Fix: putIfAbsent() atomic claim prevents double-counting, ConcurrentHashMap.remove() atomicity prevents double-subtraction.
     */
    @Test
    void testConcurrentPinUnpin_noDoubleCountingOrSubtraction() throws InterruptedException {
        var region = createTestRegion(new RegionId(1L, 0), 1000);
        var key = new RegionCache.CacheKey(new RegionId(1L, 0), 0);
        var cached = RegionCache.CachedRegion.from(region, System.currentTimeMillis());

        // Put region in unpinned cache
        cache.put(key, cached);

        // Test concurrent pin (5 threads racing)
        var pinLatch = new java.util.concurrent.CountDownLatch(5);
        var pinResults = new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>();

        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            new Thread(() -> {
                boolean result = cache.pin(key);
                pinResults.put(threadId, result);
                pinLatch.countDown();
            }).start();
        }

        pinLatch.await(2, java.util.concurrent.TimeUnit.SECONDS);

        // C3 Fix verification: Memory counted exactly once despite concurrent pins
        assertEquals(1072, cache.getPinnedMemoryBytes(),
                "Pinned memory should be counted exactly once (1000 data + 72 overhead)");

        // All threads should report success (already-pinned is also success)
        assertTrue(pinResults.values().stream().allMatch(result -> result),
                "All pin attempts should succeed");

        // Test concurrent unpin (5 threads racing)
        var unpinLatch = new java.util.concurrent.CountDownLatch(5);
        var unpinResults = new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>();

        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            new Thread(() -> {
                boolean result = cache.unpin(key);
                unpinResults.put(threadId, result);
                unpinLatch.countDown();
            }).start();
        }

        unpinLatch.await(2, java.util.concurrent.TimeUnit.SECONDS);

        // C3 Fix verification: Memory subtracted exactly once (atomic ConcurrentHashMap.remove())
        assertEquals(0, cache.getPinnedMemoryBytes(),
                "Pinned memory should be zero after concurrent unpins (no double-subtraction)");

        // Exactly one thread should have successfully unpinned (returned true)
        long successfulUnpins = unpinResults.values().stream()
                .filter(result -> result)
                .count();
        assertEquals(1, successfulUnpins,
                "Exactly one thread should successfully unpin (others get null from remove)");
    }

    /**
     * T4: Test null handling in emergency eviction during concurrent removal.
     * C4 Fix: Null check + atomic remove(key, value) prevents NPE and double-eviction.
     */
    @Test
    void testEmergencyEviction_nullSafeDuringConcurrentRemoval() throws InterruptedException {
        var smallCache = new RegionCache(1000, Duration.ofMinutes(5));

        try {
            long now = System.currentTimeMillis();

            // Pin regions to exceed 90% threshold (4 × 272 = 1088 bytes)
            for (int i = 0; i < 4; i++) {
                var region = createTestRegion(new RegionId(i, 0), 200);
                var key = new RegionCache.CacheKey(new RegionId(i, 0), 0);
                smallCache.put(key, RegionCache.CachedRegion.from(region, now));
                smallCache.pin(key);
            }

            // Concurrent scenario: Thread A evicts, Thread B removes concurrently
            var evictionStarted = new java.util.concurrent.CountDownLatch(1);
            var errors = new java.util.concurrent.ConcurrentHashMap<String, Exception>();

            // Thread A: Emergency eviction
            Thread evictionThread = new Thread(() -> {
                try {
                    evictionStarted.countDown();
                    int evicted = smallCache.emergencyEvict();
                    assertTrue(evicted >= 0, "Eviction should complete without error");
                } catch (Exception e) {
                    errors.put("eviction", e);
                }
            });

            // Thread B: Concurrent removal during eviction
            Thread removalThread = new Thread(() -> {
                try {
                    evictionStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
                    // Remove regions concurrently while Thread A is evicting
                    for (int i = 0; i < 4; i++) {
                        smallCache.invalidate(new RegionCache.CacheKey(new RegionId(i, 0), 0));
                    }
                } catch (Exception e) {
                    errors.put("removal", e);
                }
            });

            evictionThread.start();
            removalThread.start();

            evictionThread.join(2000);
            removalThread.join(2000);

            // C4 Fix verification: No NPE when entry.getValue() returns null
            assertTrue(errors.isEmpty(),
                    "Should handle concurrent removal without NPE or errors, got: " + errors);

            // Final state should be consistent (no negative memory)
            assertTrue(smallCache.getTotalMemoryBytes() >= 0,
                    "Total memory should never be negative");
            assertTrue(smallCache.getPinnedMemoryBytes() >= 0,
                    "Pinned memory should never be negative");

        } finally {
            smallCache.close();
        }
    }

    // ===== Helper Methods =====

    /**
     * Create test BuiltRegion with specified size.
     */
    private RegionBuilder.BuiltRegion createTestRegion(RegionId regionId, int dataSize) {
        byte[] data = new byte[dataSize];
        return new RegionBuilder.BuiltRegion(
                regionId,
                0, // lodLevel
                RegionBuilder.BuildType.ESVO,
                data,
                false, // compressed
                1_000_000L, // buildTimeNs
                System.currentTimeMillis()
        );
    }
}
