package com.dyada.performance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.time.Instant;
import java.time.Duration;

/**
 * High-performance caching system for DyAda operations
 * Features LRU eviction, TTL expiration, and concurrent access
 */
public final class DyAdaCache<K, V> {
    
    private final ConcurrentHashMap<K, CacheEntry<V>> cache;
    private final ConcurrentHashMap<K, AtomicLong> accessTimes;
    private final int maxSize;
    private final Duration ttl;
    private final AtomicLong currentTime = new AtomicLong(0);
    
    // Statistics
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    
    public DyAdaCache(int maxSize, Duration ttl) {
        this.maxSize = maxSize;
        this.ttl = ttl;
        this.cache = new ConcurrentHashMap<>(maxSize);
        this.accessTimes = new ConcurrentHashMap<>(maxSize);
    }
    
    public static <K, V> DyAdaCache<K, V> create(int maxSize, Duration ttl) {
        return new DyAdaCache<>(maxSize, ttl);
    }
    
    public static <K, V> DyAdaCache<K, V> createLRU(int maxSize) {
        return new DyAdaCache<>(maxSize, Duration.ofDays(365)); // Effectively no TTL
    }
    
    public V get(K key, Function<K, V> loader) {
        var entry = cache.get(key);
        var now = Instant.now();
        
        if (entry != null && !isExpired(entry, now)) {
            hits.incrementAndGet();
            accessTimes.put(key, new AtomicLong(currentTime.incrementAndGet()));
            return entry.value;
        }
        
        misses.incrementAndGet();
        
        // Load new value
        var value = loader.apply(key);
        put(key, value);
        return value;
    }
    
    public V get(K key) {
        var entry = cache.get(key);
        var now = Instant.now();
        
        if (entry != null && !isExpired(entry, now)) {
            hits.incrementAndGet();
            accessTimes.put(key, new AtomicLong(currentTime.incrementAndGet()));
            return entry.value;
        }
        
        misses.incrementAndGet();
        return null;
    }
    
    public void put(K key, V value) {
        var now = Instant.now();
        var entry = new CacheEntry<>(value, now);
        
        cache.put(key, entry);
        accessTimes.put(key, new AtomicLong(currentTime.incrementAndGet()));
        
        // Check if we need to evict
        if (cache.size() > maxSize) {
            evictLRU();
        }
    }
    
    public void invalidate(K key) {
        cache.remove(key);
        accessTimes.remove(key);
    }
    
    public void clear() {
        cache.clear();
        accessTimes.clear();
        hits.set(0);
        misses.set(0);
        evictions.set(0);
    }
    
    public int size() {
        return cache.size();
    }
    
    public boolean containsKey(K key) {
        var entry = cache.get(key);
        return entry != null && !isExpired(entry, Instant.now());
    }
    
    private boolean isExpired(CacheEntry<V> entry, Instant now) {
        return Duration.between(entry.createdAt, now).compareTo(ttl) > 0;
    }
    
    private void evictLRU() {
        // Find least recently used entry
        K lruKey = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (var entry : accessTimes.entrySet()) {
            long accessTime = entry.getValue().get();
            if (accessTime < oldestTime) {
                oldestTime = accessTime;
                lruKey = entry.getKey();
            }
        }
        
        if (lruKey != null) {
            cache.remove(lruKey);
            accessTimes.remove(lruKey);
            evictions.incrementAndGet();
        }
    }
    
    public CacheStats getStats() {
        long totalRequests = hits.get() + misses.get();
        double hitRate = totalRequests > 0 ? (double) hits.get() / totalRequests : 0.0;
        
        return new CacheStats(
            hits.get(),
            misses.get(),
            evictions.get(),
            hitRate,
            cache.size(),
            maxSize
        );
    }
    
    /**
     * Cleanup expired entries
     */
    public void cleanup() {
        var now = Instant.now();
        var toRemove = cache.entrySet().stream()
            .filter(entry -> isExpired(entry.getValue(), now))
            .map(entry -> entry.getKey())
            .toList();
        
        for (var key : toRemove) {
            cache.remove(key);
            accessTimes.remove(key);
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
                hitRate * 100, currentSize, maxSize, hits, misses, evictions
            );
        }
    }
}

/**
 * Specialized cache for Morton code operations
 */
final class MortonCache {
    private static final DyAdaCache<Long, int[]> DECODE_2D_CACHE = 
        DyAdaCache.createLRU(8192);
    private static final DyAdaCache<Long, int[]> DECODE_3D_CACHE = 
        DyAdaCache.createLRU(4096);
    private static final DyAdaCache<String, Long> ENCODE_CACHE = 
        DyAdaCache.createLRU(16384);
    
    public static int[] decode2DCached(long morton) {
        return DECODE_2D_CACHE.get(morton, m -> {
            int[] result = new int[2];
            result[0] = MortonOptimizer.decodeX2D(m);
            result[1] = MortonOptimizer.decodeY2D(m);
            return result;
        });
    }
    
    public static int[] decode3DCached(long morton) {
        return DECODE_3D_CACHE.get(morton, m -> {
            int[] result = new int[3];
            result[0] = MortonOptimizer.decodeX3D(m);
            result[1] = MortonOptimizer.decodeY3D(m);
            result[2] = MortonOptimizer.decodeZ3D(m);
            return result;
        });
    }
    
    public static long encode2DCached(int x, int y) {
        var key = x + "," + y;
        return ENCODE_CACHE.get(key, k -> MortonOptimizer.encode2D(x, y));
    }
    
    public static long encode3DCached(int x, int y, int z) {
        var key = x + "," + y + "," + z;
        return ENCODE_CACHE.get(key, k -> MortonOptimizer.encode3D(x, y, z));
    }
    
    public static void clearAll() {
        DECODE_2D_CACHE.clear();
        DECODE_3D_CACHE.clear();
        ENCODE_CACHE.clear();
    }
    
    public static String getStats() {
        return String.format(
            "Morton Cache Stats:\n2D Decode: %s\n3D Decode: %s\nEncode: %s",
            DECODE_2D_CACHE.getStats().format(),
            DECODE_3D_CACHE.getStats().format(),
            ENCODE_CACHE.getStats().format()
        );
    }
}

/**
 * Specialized cache for multiscale index operations
 */
final class MultiscaleIndexCache {
    private static final DyAdaCache<String, Integer> LEVEL_CACHE = 
        DyAdaCache.createLRU(4096);
    private static final DyAdaCache<String, int[]> PARENT_CACHE = 
        DyAdaCache.createLRU(8192);
    private static final DyAdaCache<String, int[][]> CHILDREN_CACHE = 
        DyAdaCache.createLRU(2048);
    
    public static int getLevelCached(byte[] dLevel) {
        var key = java.util.Arrays.toString(dLevel);
        return LEVEL_CACHE.get(key, k -> {
            // Calculate effective level based on dLevel array
            int maxLevel = 0;
            for (byte level : dLevel) {
                maxLevel = Math.max(maxLevel, level);
            }
            return maxLevel;
        });
    }
    
    public static int[] getParentCached(int[] indices, byte[] dLevel) {
        var key = java.util.Arrays.toString(indices) + ":" + java.util.Arrays.toString(dLevel);
        return PARENT_CACHE.get(key, k -> {
            // Calculate parent indices by dividing by 2
            int[] parent = new int[indices.length];
            for (int i = 0; i < indices.length; i++) {
                parent[i] = indices[i] / 2;
            }
            return parent;
        });
    }
    
    public static int[][] getChildrenCached(int[] indices, byte[] dLevel) {
        var key = java.util.Arrays.toString(indices) + ":" + java.util.Arrays.toString(dLevel);
        return CHILDREN_CACHE.get(key, k -> {
            // Calculate all children indices
            int numDimensions = indices.length;
            int numChildren = 1 << numDimensions; // 2^dimensions
            int[][] children = new int[numChildren][numDimensions];
            
            for (int child = 0; child < numChildren; child++) {
                for (int dim = 0; dim < numDimensions; dim++) {
                    int offset = (child >> dim) & 1;
                    children[child][dim] = indices[dim] * 2 + offset;
                }
            }
            return children;
        });
    }
    
    public static void clearAll() {
        LEVEL_CACHE.clear();
        PARENT_CACHE.clear();
        CHILDREN_CACHE.clear();
    }
    
    public static String getStats() {
        return String.format(
            "MultiscaleIndex Cache Stats:\nLevel: %s\nParent: %s\nChildren: %s",
            LEVEL_CACHE.getStats().format(),
            PARENT_CACHE.getStats().format(),
            CHILDREN_CACHE.getStats().format()
        );
    }
}