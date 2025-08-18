package com.dyada.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for DyAdaCache high-performance caching system.
 * Tests LRU eviction, TTL expiration, concurrent access, and performance characteristics.
 */
@DisplayName("DyAdaCache Tests")
class DyAdaCacheTest {

    private DyAdaCache<String, String> cache;
    private DyAdaCache<Integer, String> intCache;

    @BeforeEach
    void setUp() {
        cache = new DyAdaCache<>(3, Duration.ofSeconds(1)); // Small cache for testing
        intCache = DyAdaCache.createLRU(100); // LRU-only cache
    }

    @Nested
    @DisplayName("Basic Cache Operations")
    class BasicOperations {

        @Test
        @DisplayName("Put and get operations")
        void testPutAndGet() {
            cache.put("key1", "value1");
            
            assertEquals("value1", cache.get("key1"));
            assertEquals(1, cache.size());
            assertTrue(cache.containsKey("key1"));
        }

        @Test
        @DisplayName("Get with loader function")
        void testGetWithLoader() {
            String result = cache.get("key2", key -> "loaded-" + key);
            
            assertEquals("loaded-key2", result);
            assertEquals("loaded-key2", cache.get("key2")); // Should be cached now
        }

        @Test
        @DisplayName("Get non-existent key returns null")
        void testGetNonExistentKey() {
            assertNull(cache.get("nonexistent"));
            assertFalse(cache.containsKey("nonexistent"));
        }

        @Test
        @DisplayName("Invalidate removes entry")
        void testInvalidate() {
            cache.put("key3", "value3");
            assertTrue(cache.containsKey("key3"));
            
            cache.invalidate("key3");
            
            assertFalse(cache.containsKey("key3"));
            assertNull(cache.get("key3"));
            assertEquals(0, cache.size());
        }

        @Test
        @DisplayName("Clear removes all entries")
        void testClear() {
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            assertEquals(2, cache.size());
            
            cache.clear();
            
            assertEquals(0, cache.size());
            assertFalse(cache.containsKey("key1"));
            assertFalse(cache.containsKey("key2"));
        }
    }

    @Nested
    @DisplayName("Cache Factory Methods")
    class FactoryMethods {

        @Test
        @DisplayName("Create with TTL")
        void testCreateWithTTL() {
            var ttlCache = DyAdaCache.create(10, Duration.ofMillis(100));
            
            ttlCache.put("key", "value");
            assertEquals("value", ttlCache.get("key"));
            
            // Wait for expiration
            assertDoesNotThrow(() -> Thread.sleep(150));
            
            assertNull(ttlCache.get("key"));
        }

        @Test
        @DisplayName("Create LRU cache")
        void testCreateLRU() {
            var lruCache = DyAdaCache.<String, Integer>createLRU(2);
            
            lruCache.put("a", 1);
            lruCache.put("b", 2);
            lruCache.put("c", 3); // Should evict 'a'
            
            assertNull(lruCache.get("a"));
            assertEquals(2, lruCache.get("b"));
            assertEquals(3, lruCache.get("c"));
        }
    }

    @Nested
    @DisplayName("LRU Eviction Policy")
    class LRUEviction {

        @Test
        @DisplayName("LRU eviction when cache is full")
        void testLRUEviction() {
            // Fill cache to max size (3)
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");
            assertEquals(3, cache.size());
            
            // Access key1 to make it recently used
            cache.get("key1");
            
            // Add new key, should evict key2 (least recently used)
            cache.put("key4", "value4");
            
            assertTrue(cache.containsKey("key1")); // Recently accessed
            assertFalse(cache.containsKey("key2")); // Evicted
            assertTrue(cache.containsKey("key3"));
            assertTrue(cache.containsKey("key4"));
        }

        @Test
        @DisplayName("Access updates LRU order")
        void testAccessUpdatesLRUOrder() {
            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("c", "3");
            
            // Access 'a' to make it most recently used
            cache.get("a");
            
            // Add new entry, 'b' should be evicted (oldest unaccessed)
            cache.put("d", "4");
            
            assertTrue(cache.containsKey("a")); // Recently accessed
            assertFalse(cache.containsKey("b")); // Evicted
            assertTrue(cache.containsKey("c"));
            assertTrue(cache.containsKey("d"));
        }

        @Test
        @DisplayName("Loader function access updates LRU")
        void testLoaderAccessUpdatesLRU() {
            cache.put("x", "1");
            cache.put("y", "2");
            cache.put("z", "3");
            
            // Use loader to access 'x'
            cache.get("x", key -> "new-value"); // Should return cached value
            
            cache.put("w", "4"); // Should evict 'y'
            
            assertTrue(cache.containsKey("x"));
            assertFalse(cache.containsKey("y"));
            assertTrue(cache.containsKey("z"));
            assertTrue(cache.containsKey("w"));
        }
    }

    @Nested
    @DisplayName("TTL Expiration")
    class TTLExpiration {

        @Test
        @DisplayName("Entries expire after TTL")
        void testTTLExpiration() throws InterruptedException {
            var shortTtlCache = DyAdaCache.create(10, Duration.ofMillis(100));
            
            shortTtlCache.put("expiring", "value");
            assertEquals("value", shortTtlCache.get("expiring"));
            
            // Wait for expiration
            Thread.sleep(150);
            
            assertNull(shortTtlCache.get("expiring"));
            assertFalse(shortTtlCache.containsKey("expiring"));
        }

        @Test
        @DisplayName("Cleanup removes expired entries")
        void testCleanup() throws InterruptedException {
            var shortTtlCache = DyAdaCache.create(10, Duration.ofMillis(50));
            
            shortTtlCache.put("temp1", "value1");
            shortTtlCache.put("temp2", "value2");
            assertEquals(2, shortTtlCache.size());
            
            // Wait for expiration
            Thread.sleep(100);
            
            // Size is still 2 until cleanup
            assertEquals(2, shortTtlCache.size());
            
            shortTtlCache.cleanup();
            
            // Now expired entries are removed
            assertEquals(0, shortTtlCache.size());
        }

        @Test
        @DisplayName("Non-expired entries survive cleanup")
        void testCleanupPreservesValidEntries() throws InterruptedException {
            var mixedTtlCache = DyAdaCache.create(10, Duration.ofMillis(100));
            
            mixedTtlCache.put("short", "value");
            Thread.sleep(50); // Partial expiration time
            mixedTtlCache.put("fresh", "value");
            Thread.sleep(60); // Now "short" should be expired but "fresh" should not
            
            mixedTtlCache.cleanup();
            
            assertFalse(mixedTtlCache.containsKey("short"));
            assertTrue(mixedTtlCache.containsKey("fresh"));
        }
    }

    @Nested
    @DisplayName("Cache Statistics")
    class CacheStatistics {

        @Test
        @DisplayName("Hit rate calculation")
        void testHitRateCalculation() {
            cache.put("hit1", "value1");
            cache.put("hit2", "value2");
            
            // Generate hits
            cache.get("hit1"); // hit
            cache.get("hit2"); // hit
            cache.get("miss1"); // miss
            cache.get("miss2"); // miss
            
            var stats = cache.getStats();
            
            assertEquals(2, stats.hits());
            assertEquals(2, stats.misses());
            assertEquals(0.5, stats.hitRate(), 0.01); // 50% hit rate
        }

        @Test
        @DisplayName("Eviction statistics")
        void testEvictionStatistics() {
            // Fill cache beyond capacity to trigger evictions
            cache.put("1", "a");
            cache.put("2", "b");
            cache.put("3", "c");
            cache.put("4", "d"); // Should trigger eviction
            cache.put("5", "e"); // Should trigger another eviction
            
            var stats = cache.getStats();
            
            assertEquals(2, stats.evictions()); // Two evictions occurred
            assertEquals(3, stats.currentSize()); // Cache still at max capacity
            assertEquals(3, stats.maxSize());
        }

        @Test
        @DisplayName("Stats format string")
        void testStatsFormat() {
            cache.put("test", "value");
            cache.get("test"); // hit
            cache.get("miss"); // miss
            
            var stats = cache.getStats();
            String formatted = stats.format();
            
            assertTrue(formatted.contains("50.00%")); // Hit rate
            assertTrue(formatted.contains("1/3")); // Current/max size
            assertTrue(formatted.contains("1 hits"));
            assertTrue(formatted.contains("1 misses"));
        }

        @Test
        @DisplayName("Clear resets statistics")
        void testClearResetsStatistics() {
            cache.put("key", "value");
            cache.get("key"); // Generate some stats
            cache.get("miss");
            
            var stats = cache.getStats();
            assertTrue(stats.hits() > 0 || stats.misses() > 0);
            
            cache.clear();
            
            var clearedStats = cache.getStats();
            assertEquals(0, clearedStats.hits());
            assertEquals(0, clearedStats.misses());
            assertEquals(0, clearedStats.evictions());
            assertEquals(0.0, clearedStats.hitRate());
        }
    }

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccess {

        @Test
        @DisplayName("Concurrent puts and gets")
        void testConcurrentPutsAndGets() throws InterruptedException {
            var concurrentCache = DyAdaCache.<Integer, String>createLRU(1000);
            int numThreads = 10;
            int operationsPerThread = 100;
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch latch = new CountDownLatch(numThreads);
            
            for (int t = 0; t < numThreads; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            int key = threadId * 1000 + i;
                            concurrentCache.put(key, "value-" + key);
                            assertEquals("value-" + key, concurrentCache.get(key));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1000, concurrentCache.size()); // Should be at capacity
            
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Concurrent get with loader")
        void testConcurrentGetWithLoader() throws InterruptedException {
            var loaderCache = DyAdaCache.<String, String>createLRU(100);
            int numThreads = 20;
            CountDownLatch latch = new CountDownLatch(numThreads);
            AtomicInteger loaderCallCount = new AtomicInteger(0);
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            
            for (int t = 0; t < numThreads; t++) {
                executor.submit(() -> {
                    try {
                        // All threads try to load the same key
                        String result = loaderCache.get("shared-key", key -> {
                            loaderCallCount.incrementAndGet();
                            return "loaded-value";
                        });
                        assertEquals("loaded-value", result);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            
            // Loader should be called multiple times due to race conditions
            // but all should get consistent results
            assertEquals("loaded-value", loaderCache.get("shared-key"));
            
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCases {

        @Test
        @DisplayName("Null key handling")
        void testNullKeyHandling() {
            // ConcurrentHashMap doesn't support null keys, so expect NPE
            assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
            assertThrows(NullPointerException.class, () -> cache.get(null));
            assertThrows(NullPointerException.class, () -> cache.containsKey(null));
            assertThrows(NullPointerException.class, () -> cache.invalidate(null));
        }

        @Test
        @DisplayName("Null value handling")
        void testNullValueHandling() {
            assertDoesNotThrow(() -> cache.put("key", null));
            assertNull(cache.get("key"));
            assertTrue(cache.containsKey("key"));
        }

        @Test
        @DisplayName("Loader returning null")
        void testLoaderReturningNull() {
            String result = cache.get("null-key", key -> null);
            assertNull(result);
            assertTrue(cache.containsKey("null-key")); // null is a valid cached value
        }

        @Test
        @DisplayName("Loader throwing exception")
        void testLoaderException() {
            RuntimeException expectedException = new RuntimeException("Loader failed");
            
            assertThrows(RuntimeException.class, () -> {
                cache.get("error-key", key -> {
                    throw expectedException;
                });
            });
            
            // Failed load should not cache anything
            assertFalse(cache.containsKey("error-key"));
        }

        @Test
        @DisplayName("Zero capacity cache")
        void testZeroCapacityCache() {
            var zeroCache = new DyAdaCache<String, String>(0, Duration.ofMinutes(1));
            
            zeroCache.put("key", "value");
            assertEquals(0, zeroCache.size()); // Should not store anything
            assertNull(zeroCache.get("key"));
        }
    }

    @Nested
    @DisplayName("Performance Characteristics")
    class PerformanceTests {

        @Test
        @DisplayName("High-volume operations performance")
        void testHighVolumePerformance() {
            var perfCache = DyAdaCache.<Integer, String>createLRU(10000);
            int operations = 50000;
            
            long startTime = System.nanoTime();
            
            // Mixed operations
            for (int i = 0; i < operations; i++) {
                if (i % 3 == 0) {
                    perfCache.put(i, "value-" + i);
                } else {
                    perfCache.get(i % 1000, key -> "generated-" + key);
                }
            }
            
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1_000_000;
            
            // Should complete reasonably quickly (less than 1 second)
            assertTrue(durationMs < 1000, "High-volume operations took too long: " + durationMs + "ms");
            
            var stats = perfCache.getStats();
            assertTrue(stats.hits() > 0);
            assertTrue(stats.misses() > 0);
            assertTrue(stats.evictions() > 0);
        }

        @RepeatedTest(3)
        @DisplayName("Consistent performance under load")
        void testConsistentPerformance() {
            var loadCache = DyAdaCache.<String, Integer>createLRU(1000);
            
            long startTime = System.nanoTime();
            
            IntStream.range(0, 10000).parallel().forEach(i -> {
                loadCache.get("key-" + (i % 100), key -> key.hashCode());
            });
            
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1_000_000;
            
            // Performance should be consistent across runs
            assertTrue(durationMs < 500, "Performance degraded: " + durationMs + "ms");
        }
    }
}