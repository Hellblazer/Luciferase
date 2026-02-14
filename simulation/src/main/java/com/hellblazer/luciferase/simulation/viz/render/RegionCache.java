package com.hellblazer.luciferase.simulation.viz.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Weigher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hybrid cache for built ESVO/ESVT regions with Caffeine LRU and manual pinning.
 *
 * <p><strong>V2 Architecture (C2 Fix):</strong> Caffeine 3.1.8 replaces ConcurrentHashMap+ConcurrentLinkedDeque
 * for O(1) amortized LRU via window-TinyLFU algorithm.
 *
 * <p><strong>Hybrid Design:</strong>
 * <ul>
 *   <li>Unpinned regions: Caffeine Cache with window-TinyLFU eviction policy</li>
 *   <li>Pinned regions: ConcurrentHashMap (exempt from Caffeine eviction)</li>
 * </ul>
 *
 * <p><strong>Caffeine Configuration:</strong>
 * <ul>
 *   <li>maximumWeight: 90% of maxMemoryBytes (10% headroom for pinned regions)</li>
 *   <li>weigher: CachedRegion.sizeBytes()</li>
 *   <li>expireAfterAccess: TTL (default 5 minutes)</li>
 *   <li>removalListener: Logs evictions</li>
 *   <li>recordStats: Tracks hit/miss rates</li>
 * </ul>
 *
 * <p><strong>Memory Tracking:</strong>
 * <ul>
 *   <li>Caffeine: policy().eviction().get().weightedSize().getAsLong()</li>
 *   <li>Pinned: Manual tracking via AtomicLong</li>
 * </ul>
 *
 * <p><strong>Concurrency:</strong> Thread-safe via Caffeine's concurrent implementation and ConcurrentHashMap.
 */
public class RegionCache implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RegionCache.class);

    private final Cache<CacheKey, CachedRegion> unpinnedCache;
    private final ConcurrentHashMap<CacheKey, CachedRegion> pinnedCache;
    private final AtomicLong pinnedMemoryBytes;
    private final long maxMemoryBytes;
    private final Duration ttl;
    private volatile boolean closed;

    /**
     * Create RegionCache with Caffeine-based LRU eviction.
     *
     * @param maxMemoryBytes Maximum total memory (90% for unpinned, 10% headroom for pinned)
     * @param ttl Time-to-live for unpinned regions (expireAfterAccess)
     */
    public RegionCache(long maxMemoryBytes, Duration ttl) {
        this.maxMemoryBytes = maxMemoryBytes;
        this.ttl = ttl;
        this.pinnedMemoryBytes = new AtomicLong(0);
        this.pinnedCache = new ConcurrentHashMap<>();
        this.closed = false;

        // Configure Caffeine with 90% of maxMemory (10% headroom for pinned regions)
        long unpinnedMaxWeight = (long) (maxMemoryBytes * 0.9);

        this.unpinnedCache = Caffeine.newBuilder()
                .maximumWeight(unpinnedMaxWeight)
                .weigher((Weigher<CacheKey, CachedRegion>) (key, value) -> (int) value.sizeBytes())
                .expireAfterAccess(ttl)
                .removalListener((CacheKey key, CachedRegion value, RemovalCause cause) -> {
                    if (value != null) {
                        log.debug("Evicted region {} LOD {} ({} bytes, cause: {})",
                                key.regionId(), key.lodLevel(), value.sizeBytes(), cause);
                    }
                })
                .recordStats()
                .build();

        log.info("RegionCache created: maxMemory={} bytes, ttl={}, unpinnedMaxWeight={} bytes",
                maxMemoryBytes, ttl, unpinnedMaxWeight);
    }

    /**
     * Get cached region by key.
     *
     * <p>Checks pinned cache first (O(1)), then Caffeine unpinned cache (O(1) amortized).
     *
     * @param key Cache key (RegionId + LOD level)
     * @return Cached region if present, empty otherwise
     */
    public Optional<CachedRegion> get(CacheKey key) {
        if (closed) {
            return Optional.empty();
        }

        // Check pinned first
        var pinned = pinnedCache.get(key);
        if (pinned != null) {
            return Optional.of(pinned);
        }

        // Check unpinned Caffeine cache
        var unpinned = unpinnedCache.getIfPresent(key);
        return Optional.ofNullable(unpinned);
    }

    /**
     * Put region into cache.
     *
     * <p>Stores in unpinned Caffeine cache (subject to LRU eviction).
     * Use pin() to exempt from eviction.
     *
     * @param key Cache key
     * @param region Built region to cache
     */
    public void put(CacheKey key, CachedRegion region) {
        if (closed) {
            throw new IllegalStateException("RegionCache is closed");
        }

        unpinnedCache.put(key, region);

        log.debug("Cached region {} LOD {} ({} bytes)",
                key.regionId(), key.lodLevel(), region.sizeBytes());
    }

    /**
     * Pin region to prevent eviction.
     *
     * <p>Moves region from unpinned Caffeine cache to pinned ConcurrentHashMap.
     * Pinned regions are exempt from LRU eviction.
     *
     * @param key Cache key
     * @return true if region was pinned, false if not found
     */
    public boolean pin(CacheKey key) {
        if (closed) {
            return false;
        }

        // Check if already pinned
        if (pinnedCache.containsKey(key)) {
            return true;
        }

        // Get from unpinned cache and move to pinned
        var region = unpinnedCache.getIfPresent(key);
        if (region != null) {
            unpinnedCache.invalidate(key);
            pinnedCache.put(key, region);
            pinnedMemoryBytes.addAndGet(region.sizeBytes());

            log.debug("Pinned region {} LOD {} ({} bytes)",
                    key.regionId(), key.lodLevel(), region.sizeBytes());
            return true;
        }

        return false;
    }

    /**
     * Unpin region to allow eviction.
     *
     * <p>Moves region from pinned ConcurrentHashMap to unpinned Caffeine cache.
     * Region becomes subject to LRU eviction.
     *
     * @param key Cache key
     * @return true if region was unpinned, false if not found
     */
    public boolean unpin(CacheKey key) {
        if (closed) {
            return false;
        }

        var region = pinnedCache.remove(key);
        if (region != null) {
            pinnedMemoryBytes.addAndGet(-region.sizeBytes());
            unpinnedCache.put(key, region);

            log.debug("Unpinned region {} LOD {} ({} bytes)",
                    key.regionId(), key.lodLevel(), region.sizeBytes());
            return true;
        }

        return false;
    }

    /**
     * Invalidate (remove) region from cache.
     *
     * <p>Removes from both pinned and unpinned caches.
     *
     * @param key Cache key
     */
    public void invalidate(CacheKey key) {
        if (closed) {
            return;
        }

        // Remove from pinned
        var pinned = pinnedCache.remove(key);
        if (pinned != null) {
            pinnedMemoryBytes.addAndGet(-pinned.sizeBytes());
            log.debug("Invalidated pinned region {} LOD {}", key.regionId(), key.lodLevel());
        }

        // Remove from unpinned
        unpinnedCache.invalidate(key);
    }

    /**
     * Get current cache statistics.
     *
     * @return Cache stats including Caffeine hit rate
     */
    public CacheStats getStats() {
        var caffeineStats = unpinnedCache.stats();

        return new CacheStats(
                pinnedCache.size(),
                unpinnedCache.estimatedSize(),
                getTotalMemoryBytes(),
                caffeineStats.hitRate(),
                caffeineStats.missRate(),
                caffeineStats.evictionCount()
        );
    }

    /**
     * Get total memory usage (pinned + unpinned).
     *
     * @return Total bytes used by cache
     */
    public long getTotalMemoryBytes() {
        long unpinnedBytes = unpinnedCache.policy().eviction()
                .map(eviction -> eviction.weightedSize().getAsLong())
                .orElse(0L);

        return pinnedMemoryBytes.get() + unpinnedBytes;
    }

    /**
     * Get pinned memory usage.
     *
     * @return Bytes used by pinned regions
     */
    public long getPinnedMemoryBytes() {
        return pinnedMemoryBytes.get();
    }

    /**
     * Get unpinned memory usage.
     *
     * @return Bytes used by unpinned regions (Caffeine)
     */
    public long getUnpinnedMemoryBytes() {
        return unpinnedCache.policy().eviction()
                .map(eviction -> eviction.weightedSize().getAsLong())
                .orElse(0L);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            unpinnedCache.invalidateAll();
            pinnedCache.clear();
            pinnedMemoryBytes.set(0);
            log.info("RegionCache closed");
        }
    }

    // ===== Inner Records =====

    /**
     * Cache key combining RegionId and LOD level.
     *
     * <p>Allows same region at different LODs to be cached separately.
     */
    public record CacheKey(
            RegionId regionId,
            int lodLevel
    ) implements Comparable<CacheKey> {

        @Override
        public int compareTo(CacheKey other) {
            int regionCmp = this.regionId.compareTo(other.regionId);
            if (regionCmp != 0) {
                return regionCmp;
            }
            return Integer.compare(this.lodLevel, other.lodLevel);
        }
    }

    /**
     * Cached region with metadata.
     *
     * <p>Wraps BuiltRegion with caching timestamps and size.
     */
    public record CachedRegion(
            RegionBuilder.BuiltRegion builtRegion,
            long cachedAtMs,
            long lastAccessedMs,
            long sizeBytes
    ) {
        /**
         * Create cached region from built region.
         */
        public static CachedRegion from(RegionBuilder.BuiltRegion builtRegion, long currentTimeMs) {
            return new CachedRegion(
                    builtRegion,
                    currentTimeMs,
                    currentTimeMs,
                    builtRegion.estimatedSizeBytes()
            );
        }

        /**
         * Update last accessed timestamp.
         */
        public CachedRegion withAccess(long currentTimeMs) {
            return new CachedRegion(
                    builtRegion,
                    cachedAtMs,
                    currentTimeMs,
                    sizeBytes
            );
        }
    }

    /**
     * Cache statistics including Caffeine metrics.
     */
    public record CacheStats(
            long pinnedCount,
            long unpinnedCount,
            long totalMemoryBytes,
            double caffeineHitRate,
            double caffeineMissRate,
            long caffeineEvictionCount
    ) {
        /**
         * Get total cached region count.
         */
        public long totalCount() {
            return pinnedCount + unpinnedCount;
        }

        /**
         * Get overall hit rate (Caffeine only, pinned always hits).
         */
        public double overallHitRate() {
            return caffeineHitRate;
        }
    }
}
