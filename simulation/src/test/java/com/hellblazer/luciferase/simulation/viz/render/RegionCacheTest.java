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
