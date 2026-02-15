package com.hellblazer.luciferase.simulation.viz.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Weigher;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
    private final AtomicBoolean emergencyEvicting; // C4: Concurrency guard
    private final AtomicLong pinnedAccessCount; // P3: Track accesses for sampling
    private final long maxMemoryBytes;
    private final Duration ttl;
    private volatile boolean closed;
    private volatile Clock clock = Clock.system(); // For deterministic testing

    // P3: Update timestamp only on 1% of accesses (reduces allocation by ~99%)
    private static final int ACCESS_SAMPLING_RATE = 100;

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
        this.pinnedAccessCount = new AtomicLong(0); // P3: Initialize access counter
        this.emergencyEvicting = new AtomicBoolean(false); // C4: Initially not evicting
        this.closed = false;

        // Configure Caffeine with 90% of maxMemory (10% headroom for pinned regions)
        long unpinnedMaxWeight = (long) (maxMemoryBytes * 0.9);

        this.unpinnedCache = Caffeine.newBuilder()
                .maximumWeight(unpinnedMaxWeight)
                .weigher((Weigher<CacheKey, CachedRegion>) (key, value) ->
                    (int) Math.min(value.sizeBytes(), Integer.MAX_VALUE))
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
     * Set clock for deterministic testing.
     * <p>
     * Note: Caffeine's expireAfterAccess() uses system time internally and cannot be overridden.
     * This clock only affects lastAccessedMs tracking for pinned regions. For fully deterministic
     * cache expiration tests, consider using short TTL values and Thread.sleep() instead.
     *
     * @param clock Clock instance
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Get cached region by key.
     *
     * <p>Checks pinned cache first (O(1)), then Caffeine unpinned cache (O(1) amortized).
     *
     * <p>C2 FIX: Updates lastAccessedMs timestamp on cache hits using atomic computeIfPresent
     * for pinned regions. Caffeine automatically tracks access time for unpinned regions.
     *
     * <p>P3 OPTIMIZATION: Samples timestamp updates (1 in 100 accesses) to reduce allocation
     * overhead for pinned regions while maintaining access tracking.
     *
     * @param key Cache key (RegionId + LOD level)
     * @return Cached region if present, empty otherwise
     */
    public Optional<CachedRegion> get(CacheKey key) {
        if (closed) {
            return Optional.empty();
        }

        // Check pinned cache first
        var pinned = pinnedCache.get(key);
        if (pinned != null) {
            // P3: Update timestamp only on sampled accesses (1% rate = 99% allocation reduction)
            long accessNumber = pinnedAccessCount.incrementAndGet();
            if (accessNumber % ACCESS_SAMPLING_RATE == 0) {
                var updated = pinnedCache.computeIfPresent(key, (k, region) ->
                    region.withAccess(clock.currentTimeMillis())
                );
                return Optional.ofNullable(updated);
            }
            return Optional.of(pinned);
        }

        // Check unpinned Caffeine cache (already tracks access time internally)
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
     * <p>C3 FIX: Uses putIfAbsent for atomic claim - only the winning thread
     * invalidates the unpinned cache and updates memory tracking. This prevents
     * double-counting of pinnedMemoryBytes in concurrent pin operations.
     *
     * @param key Cache key
     * @return true if region was pinned, false if not found
     */
    public boolean pin(CacheKey key) {
        if (closed) {
            return false;
        }

        // Check if already pinned
        var existing = pinnedCache.get(key);
        if (existing != null) {
            return true;
        }

        // Get from unpinned cache
        var region = unpinnedCache.getIfPresent(key);
        if (region != null) {
            // Atomic claim - only one thread wins the race
            var winner = pinnedCache.putIfAbsent(key, region);
            if (winner == null) {
                // Only the winner invalidates unpinned cache and updates memory
                unpinnedCache.invalidate(key);
                unpinnedCache.cleanUp(); // Force immediate weight update
                pinnedMemoryBytes.addAndGet(region.sizeBytes());

                log.debug("Pinned region {} LOD {} ({} bytes)",
                        key.regionId(), key.lodLevel(), region.sizeBytes());
            }
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
     * <p>C3 NOTE: ConcurrentHashMap.remove() is atomic - if multiple threads
     * call unpin() concurrently, only one will receive the region (others get null).
     * This prevents double-subtraction from pinnedMemoryBytes.
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
     * <p>Uses forceAccurate=false for low-overhead monitoring (bkji).
     *
     * @return Cache stats including Caffeine hit rate
     */
    public CacheStats getStats() {
        var caffeineStats = unpinnedCache.stats();
        long totalMemory = getTotalMemoryBytes(false); // bkji: Use fast path for monitoring

        return new CacheStats(
                pinnedCache.size(),
                unpinnedCache.estimatedSize(),
                totalMemory,
                caffeineStats.hitRate(),
                caffeineStats.missRate(),
                caffeineStats.evictionCount(),
                (double) totalMemory / maxMemoryBytes
        );
    }

    /**
     * Get total memory usage (pinned + unpinned).
     *
     * <p>Defaults to forceAccurate=true for backward compatibility.
     *
     * @return Total bytes used by cache
     */
    public long getTotalMemoryBytes() {
        return getTotalMemoryBytes(true);
    }

    /**
     * Get total memory usage (pinned + unpinned) with configurable accuracy.
     *
     * <p>bkji: Reduce cleanUp() overhead for monitoring vs critical decisions.
     *
     * @param forceAccurate If true, calls cleanUp() for accurate weight; if false, skips cleanUp() for low-overhead monitoring
     * @return Total bytes used by cache
     */
    public long getTotalMemoryBytes(boolean forceAccurate) {
        return pinnedMemoryBytes.get() + getUnpinnedMemoryBytes(forceAccurate);
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
     * <p>Triggers Caffeine maintenance to ensure weightedSize is up-to-date.
     * Caffeine updates weightedSize asynchronously, so we force a cleanup cycle
     * to get the current accurate value.
     *
     * <p>Defaults to forceAccurate=true for backward compatibility.
     *
     * @return Bytes used by unpinned regions (Caffeine)
     */
    public long getUnpinnedMemoryBytes() {
        return getUnpinnedMemoryBytes(true);
    }

    /**
     * Get unpinned memory usage with configurable accuracy (bkji).
     *
     * <p>bkji: Reduce cleanUp() overhead for monitoring vs critical decisions.
     * <ul>
     *   <li>forceAccurate=true: Calls cleanUp() for accurate weight (use in emergencyEvict())</li>
     *   <li>forceAccurate=false: Skips cleanUp() for low-overhead monitoring (use in getStats())</li>
     * </ul>
     *
     * @param forceAccurate If true, calls cleanUp() for accurate weight; if false, returns potentially stale value
     * @return Bytes used by unpinned regions (Caffeine)
     */
    public long getUnpinnedMemoryBytes(boolean forceAccurate) {
        if (forceAccurate) {
            // Force Caffeine to update internal eviction policy weights
            unpinnedCache.cleanUp();
        }

        return unpinnedCache.policy().eviction()
                .map(eviction -> eviction.weightedSize().getAsLong())
                .orElse(0L);
    }

    /**
     * Emergency eviction (M1) with C4 concurrency guard.
     *
     * <p>Triggered when total memory exceeds 90% of maxMemoryBytes.
     * Evicts oldest pinned regions until memory drops below 75% target.
     *
     * <p><strong>C4 Guard:</strong> Uses AtomicBoolean compareAndSet to ensure only one thread
     * performs emergency eviction at a time, preventing over-eviction.
     *
     * <p><strong>Algorithm:</strong>
     * <ol>
     *   <li>Acquire C4 guard via compareAndSet (false → true)</li>
     *   <li>Force Caffeine cleanup to evict expired/LRU entries first</li>
     *   <li>If still over 75%, evict oldest pinned regions by lastAccessedMs</li>
     *   <li>Release C4 guard (true → false)</li>
     * </ol>
     *
     * @return Number of pinned regions evicted (0 if guard acquisition failed or Caffeine cleanup sufficient)
     */
    public int emergencyEvict() {
        if (closed) {
            return 0;
        }

        // C4: Acquire concurrency guard (only one thread can evict at a time)
        if (!emergencyEvicting.compareAndSet(false, true)) {
            log.debug("Emergency eviction already in progress, skipping");
            return 0;
        }

        try {
            // bkji: Use forceAccurate=true for critical emergency eviction decisions
            long totalMemory = getTotalMemoryBytes(true);
            long emergencyThreshold = (long) (maxMemoryBytes * 0.9); // 90% triggers emergency
            long targetMemory = (long) (maxMemoryBytes * 0.75); // Evict down to 75%

            if (totalMemory < emergencyThreshold) {
                log.debug("Memory {} below emergency threshold {}, no eviction needed",
                        totalMemory, emergencyThreshold);
                return 0;
            }

            log.warn("Emergency eviction triggered: {} bytes / {} max ({}%)",
                    totalMemory, maxMemoryBytes, (totalMemory * 100 / maxMemoryBytes));

            // Step 1: Force Caffeine to cleanup expired/LRU entries
            unpinnedCache.cleanUp();

            // Re-check memory after Caffeine cleanup (bkji: forceAccurate=true for critical decision)
            totalMemory = getTotalMemoryBytes(true);
            if (totalMemory < targetMemory) {
                log.info("Caffeine cleanup sufficient, memory now {} bytes", totalMemory);
                return 0;
            }

            // Step 2: Evict oldest pinned regions if still over target
            // mauo: Use min-heap for O(n + k log n) complexity instead of O(n log n) sort
            long bytesToEvict = totalMemory - targetMemory;

            // Build min-heap ordered by lastAccessedMs (oldest first)
            var evictionQueue = new java.util.PriorityQueue<java.util.Map.Entry<CacheKey, CachedRegion>>(
                    pinnedCache.size(),
                    (e1, e2) -> Long.compare(e1.getValue().lastAccessedMs(), e2.getValue().lastAccessedMs())
            );

            // Add all pinned entries to heap - O(n)
            for (var entry : pinnedCache.entrySet()) {
                if (entry.getValue() != null) {
                    evictionQueue.offer(entry);
                }
            }

            int evictedCount = 0;
            long bytesEvicted = 0;

            // Poll oldest entries until bytesToEvict reached - O(k log n)
            while (!evictionQueue.isEmpty() && bytesEvicted < bytesToEvict) {
                var entry = evictionQueue.poll();
                var key = entry.getKey();
                var region = entry.getValue();

                // C4 FIX: Null check for concurrent removals
                if (region == null) {
                    continue;
                }

                // C4 FIX: Atomic remove - only removes if value matches
                // Prevents double-eviction if another thread removes concurrently
                if (pinnedCache.remove(key, region)) {
                    pinnedMemoryBytes.addAndGet(-region.sizeBytes());
                    bytesEvicted += region.sizeBytes();
                    evictedCount++;

                    log.debug("Emergency evicted pinned region {} LOD {} ({} bytes)",
                            key.regionId(), key.lodLevel(), region.sizeBytes());
                }
            }

            log.warn("Emergency eviction complete: evicted {} pinned regions ({} bytes), " +
                            "memory now {} bytes",
                    evictedCount, bytesEvicted, getTotalMemoryBytes());

            return evictedCount;

        } finally {
            // C4: Release guard
            emergencyEvicting.set(false);
        }
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
            long sizeBytes = builtRegion.estimatedSizeBytes();

            // C1: Warn if region size exceeds Integer.MAX_VALUE (Caffeine weigher will clamp)
            if (sizeBytes > Integer.MAX_VALUE) {
                log.warn("Region {} LOD {} size {} exceeds Integer.MAX_VALUE. " +
                        "Caffeine weigher will clamp to {} for eviction priority. " +
                        "This region will appear lower priority than its actual size suggests.",
                        builtRegion.regionId(), builtRegion.lodLevel(),
                        sizeBytes, Integer.MAX_VALUE);
            }

            return new CachedRegion(
                    builtRegion,
                    currentTimeMs,
                    currentTimeMs,
                    sizeBytes
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
            long caffeineEvictionCount,
            double memoryPressure
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
