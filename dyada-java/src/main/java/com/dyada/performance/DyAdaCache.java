package com.dyada.performance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.time.Instant;
import java.time.Duration;

/**
 * High-performance caching system for DyAda operations
 * Features LRU eviction, TTL expiration, and concurrent access.
 *
 * <p>Thread-safety: a single synchronized LinkedHashMap (accessOrder=true) with
 * removeEldestEntry provides an atomic size-check + eviction in one lock, eliminating
 * the cross-map race that existed when cache and accessTimes were separate maps.
 */
public final class DyAdaCache<K, V> {

    private final Map<K, CacheEntry<V>> cache;
    private final int maxSize;
    private final Duration ttl;

    // Statistics
    private final AtomicLong hits      = new AtomicLong(0);
    private final AtomicLong misses    = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    public DyAdaCache(int maxSize, Duration ttl) {
        this.maxSize = maxSize;
        this.ttl = ttl;
        // LinkedHashMap with access-order=true: get/put move the entry to tail.
        // removeEldestEntry fires atomically inside the map's put lock,
        // so size never exceeds maxSize + 1 transiently and we evict the LRU head.
        final AtomicLong evictCounter = this.evictions; // captured for inner class
        LinkedHashMap<K, CacheEntry<V>> lhm = new LinkedHashMap<>(
                Math.max(1, maxSize) * 4 / 3 + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                if (size() > maxSize) {
                    evictCounter.incrementAndGet();
                    return true;
                }
                return false;
            }
        };
        this.cache = Collections.synchronizedMap(lhm);
    }

    public static <K, V> DyAdaCache<K, V> create(int maxSize, Duration ttl) {
        return new DyAdaCache<>(maxSize, ttl);
    }

    public static <K, V> DyAdaCache<K, V> createLRU(int maxSize) {
        return new DyAdaCache<>(maxSize, Duration.ofDays(365)); // Effectively no TTL
    }

    /**
     * Returns the cached value for {@code key}, computing and storing it via {@code loader}
     * on a miss. The load is performed under the cache lock so concurrent callers for the same
     * key do not double-load (cache-stampede prevention).
     * <p>
     * <b>Caller contract:</b> {@code loader} MUST NOT re-entrantly call {@code get(...)} (or any
     * other mutating method) on this same {@code DyAdaCache} instance — the cache lock is held
     * for the loader's duration and is non-reentrant, so a re-entrant call deadlocks the thread.
     * A long-running loader also blocks all other operations on this cache for its duration.
     */
    public V get(K key, Function<K, V> loader) {
        if (key == null) throw new NullPointerException("key");
        // Hold the lock across the entire miss path so load+store is atomic.
        // Two concurrent callers for the same missing key both block here;
        // the second sees the value stored by the first and returns it without
        // re-invoking the loader (cache-stampede prevention).
        // Accept: an expensive loader blocks other cache ops for its duration.
        synchronized (cache) {
            var entry = cache.get(key); // updates LRU order
            var now = Instant.now();
            if (entry != null && !isExpired(entry, now)) {
                hits.incrementAndGet();
                return entry.value;
            }
            misses.incrementAndGet();
            var value = loader.apply(key);
            cache.put(key, new CacheEntry<>(value, now));
            return value;
        }
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (cache) {
            var entry = cache.get(key); // updates LRU order
            var now = Instant.now();
            if (entry != null && !isExpired(entry, now)) {
                hits.incrementAndGet();
                return entry.value;
            }
        }
        misses.incrementAndGet();
        return null;
    }

    public void put(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        synchronized (cache) {
            cache.put(key, new CacheEntry<>(value, Instant.now()));
            // removeEldestEntry fires inside LinkedHashMap.put while we hold the lock,
            // so size is bounded atomically — no separate evictLRU call needed.
        }
    }

    public void invalidate(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (cache) {
            cache.remove(key);
        }
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
        hits.set(0);
        misses.set(0);
        evictions.set(0);
    }

    public int size() {
        return cache.size();
    }

    public boolean containsKey(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (cache) {
            var entry = cache.get(key); // updates LRU order on hit
            return entry != null && !isExpired(entry, Instant.now());
        }
    }

    private boolean isExpired(CacheEntry<V> entry, Instant now) {
        return Duration.between(entry.createdAt, now).compareTo(ttl) > 0;
    }

    public CacheStats getStats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double hitRate = total > 0 ? (double) h / total : 0.0;

        return new CacheStats(h, m, evictions.get(), hitRate, cache.size(), maxSize);
    }

    /**
     * Cleanup expired entries.
     */
    public void cleanup() {
        var now = Instant.now();
        synchronized (cache) {
            cache.entrySet().removeIf(e -> isExpired(e.getValue(), now));
        }
    }

    private record CacheEntry<V>(V value, Instant createdAt) {}

    public record CacheStats(
        long hits,
        long misses,
        long evictions,
        double hitRate,
        int currentSize,
        int maxSize
    ) {
        public String format() {
            return String.format(
                "Cache Stats: %.2f%% hit rate, %d/%d entries, %d hits, %d misses, %d evictions",
                hitRate * 100, currentSize, maxSize, hits, misses, evictions);
        }
    }
}
