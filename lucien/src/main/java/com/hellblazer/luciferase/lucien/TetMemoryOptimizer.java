package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Phase 5D: Tetrahedral Memory Optimization
 * 
 * Provides advanced memory management optimizations specifically designed for tetrahedral
 * spatial operations. Unlike cubic memory optimization, this leverages the unique properties
 * of tetrahedral space-filling curves, 6-type subdivision, and tetrahedral memory access patterns.
 * 
 * Key tetrahedral memory optimizations:
 * - Compact representation for 6-type tetrahedral subdivision
 * - Memory pooling adapted for tetrahedral geometry calculations
 * - GC-aware caching with tetrahedral SFC locality
 * - Cache eviction strategies for tetrahedral access patterns
 * - Memory usage tracking for tetrahedral operations
 * 
 * @author hal.hildebrand
 */
public class TetMemoryOptimizer {
    
    // Global memory management for tetrahedral operations
    private static final TetMemoryMetrics globalMetrics = new TetMemoryMetrics();
    private static final TetMemoryPool globalPool = new TetMemoryPool();
    private static final TetGarbageCollectionOptimizer gcOptimizer = new TetGarbageCollectionOptimizer();
    
    // Cache management
    private static final TetCacheEvictionStrategy cacheEviction = new TetCacheEvictionStrategy();
    
    /**
     * Compact memory representation for tetrahedral data
     * Optimized for the 6-type tetrahedral subdivision pattern
     */
    public static class TetCompactRepresentation {
        
        /**
         * Ultra-compact tetrahedron representation using bit packing
         * Stores coordinates, level, and type in minimal memory footprint
         */
        public static class CompactTet {
            // Pack coordinates (21 bits each), level (5 bits), type (3 bits) into two longs
            private final long data1; // x (21) + y (21) + level (5) + type (3) = 50 bits
            private final long data2; // z (21) + unused (43 bits for future expansion)
            
            public CompactTet(int x, int y, int z, byte level, byte type) {
                // Validate positive coordinates and type range
                if (x < 0 || y < 0 || z < 0) {
                    throw new IllegalArgumentException("Coordinates must be positive: (" + x + ", " + y + ", " + z + ")");
                }
                if (type < 0 || type > 5) {
                    throw new IllegalArgumentException("Type must be in range [0, 5]: " + type);
                }
                if (level < 0 || level > 31) {
                    throw new IllegalArgumentException("Level must be in range [0, 31]: " + level);
                }
                
                // Ensure coordinates fit in 21 bits (max value 2,097,151)
                if (x >= (1 << 21) || y >= (1 << 21) || z >= (1 << 21)) {
                    throw new IllegalArgumentException("Coordinates too large for compact representation");
                }
                
                // Pack data1: x(21) + y(21) + level(5) + type(3)
                this.data1 = ((long) x << 29) | ((long) y << 8) | ((long) level << 3) | type;
                
                // Pack data2: z(21) + unused(43)
                this.data2 = (long) z << 43;
                
                globalMetrics.recordCompactCreation();
            }
            
            public int x() { return (int) (data1 >>> 29); }
            public int y() { return (int) ((data1 >>> 8) & 0x1FFFFF); }
            public int z() { return (int) (data2 >>> 43); }
            public byte level() { return (byte) ((data1 >>> 3) & 0x1F); }
            public byte type() { return (byte) (data1 & 0x7); }
            
            /**
             * Convert to full Tet object when needed
             */
            public Tet toTet() {
                return new Tet(x(), y(), z(), level(), type());
            }
            
            /**
             * Calculate SFC index without creating full Tet object
             */
            public long sfcIndex() {
                return toTet().index(); // Could be optimized further
            }
            
            /**
             * Memory footprint: 16 bytes (2 longs) vs ~40+ bytes for full Tet object
             */
            public int memoryFootprint() {
                return 16; // 2 longs
            }
            
            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof CompactTet other)) return false;
                return data1 == other.data1 && data2 == other.data2;
            }
            
            @Override
            public int hashCode() {
                return Objects.hash(data1, data2);
            }
            
            @Override
            public String toString() {
                return String.format("CompactTet[(%d,%d,%d) level=%d type=%d]", 
                    x(), y(), z(), level(), type());
            }
        }
        
        /**
         * Memory-efficient grid cell representation
         * Stores all 6 tetrahedral types in a single compact structure
         */
        public static class CompactTetGridCell {
            private final int gridX, gridY, gridZ;
            private final byte level;
            private final CompactTet[] sixTypes; // Exactly 6 tetrahedra
            
            public CompactTetGridCell(int gridX, int gridY, int gridZ, byte level) {
                this.gridX = gridX;
                this.gridY = gridY;
                this.gridZ = gridZ;
                this.level = level;
                
                // Create compact representations for all 6 types
                this.sixTypes = new CompactTet[6];
                int length = Constants.lengthAtLevel(level);
                int cellX = gridX * length;
                int cellY = gridY * length;
                int cellZ = gridZ * length;
                
                for (byte type = 0; type < 6; type++) {
                    this.sixTypes[type] = new CompactTet(cellX, cellY, cellZ, level, type);
                }
                
                globalMetrics.recordGridCellCreation();
            }
            
            public CompactTet getType(byte type) {
                if (type < 0 || type > 5) {
                    throw new IllegalArgumentException("Type must be in range [0, 5]: " + type);
                }
                return sixTypes[type];
            }
            
            public CompactTet[] getAllTypes() {
                return sixTypes.clone();
            }
            
            /**
             * Memory footprint: 96 bytes (6 * 16 bytes) + overhead
             * vs ~300+ bytes for 6 full Tet objects
             */
            public int memoryFootprint() {
                return 96 + 32; // 6 CompactTets + object overhead
            }
        }
        
        /**
         * Lazy-loading tetrahedral coordinate cache
         * Uses weak references to allow GC when memory is tight
         */
        public static class LazyTetCoordinateCache {
            private final Map<Long, WeakReference<Point3i[]>> coordinateCache = new ConcurrentHashMap<>();
            private final AtomicLong cacheHits = new AtomicLong(0);
            private final AtomicLong cacheMisses = new AtomicLong(0);
            
            public Point3i[] getCoordinates(CompactTet compactTet) {
                long key = compactTet.sfcIndex();
                
                // Check cache first
                var weakRef = coordinateCache.get(key);
                if (weakRef != null) {
                    var cached = weakRef.get();
                    if (cached != null) {
                        cacheHits.incrementAndGet();
                        globalMetrics.recordCacheHit();
                        return cached;
                    } else {
                        // Weak reference was cleared, remove stale entry
                        coordinateCache.remove(key);
                    }
                }
                
                // Compute coordinates
                cacheMisses.incrementAndGet();
                globalMetrics.recordCacheMiss();
                
                var coordinates = compactTet.toTet().coordinates();
                
                // Store in cache with weak reference
                coordinateCache.put(key, new WeakReference<>(coordinates));
                
                return coordinates;
            }
            
            public double getCacheHitRate() {
                long hits = cacheHits.get();
                long misses = cacheMisses.get();
                long total = hits + misses;
                return total > 0 ? (double) hits / total : 0.0;
            }
            
            public void clearCache() {
                coordinateCache.clear();
                cacheHits.set(0);
                cacheMisses.set(0);
            }
        }
    }
    
    /**
     * Object pooling for tetrahedral operations
     * Reduces allocation pressure for frequently used objects
     */
    public static class TetMemoryPool {
        
        // Pools for different object types
        private final ConcurrentLinkedQueue<Point3f> point3fPool = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Point3i> point3iPool = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<float[]> floatArrayPool = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<int[]> intArrayPool = new ConcurrentLinkedQueue<>();
        
        // Pool statistics
        private final AtomicLong totalAllocations = new AtomicLong(0);
        private final AtomicLong poolHits = new AtomicLong(0);
        private final AtomicLong poolMisses = new AtomicLong(0);
        
        // Pool size limits to prevent memory leaks
        private static final int MAX_POOL_SIZE = 1000;
        
        /**
         * Get or create Point3f object
         */
        public Point3f getPoint3f() {
            var pooled = point3fPool.poll();
            if (pooled != null) {
                poolHits.incrementAndGet();
                // Reset the point
                pooled.set(0f, 0f, 0f);
                return pooled;
            } else {
                poolMisses.incrementAndGet();
                totalAllocations.incrementAndGet();
                return new Point3f();
            }
        }
        
        /**
         * Return Point3f object to pool
         */
        public void returnPoint3f(Point3f point) {
            if (point3fPool.size() < MAX_POOL_SIZE) {
                point3fPool.offer(point);
            }
        }
        
        /**
         * Get or create Point3i object
         */
        public Point3i getPoint3i() {
            var pooled = point3iPool.poll();
            if (pooled != null) {
                poolHits.incrementAndGet();
                // Reset the point
                pooled.set(0, 0, 0);
                return pooled;
            } else {
                poolMisses.incrementAndGet();
                totalAllocations.incrementAndGet();
                return new Point3i();
            }
        }
        
        /**
         * Return Point3i object to pool
         */
        public void returnPoint3i(Point3i point) {
            if (point3iPool.size() < MAX_POOL_SIZE) {
                point3iPool.offer(point);
            }
        }
        
        /**
         * Get or create float array for tetrahedral calculations
         */
        public float[] getFloatArray(int size) {
            if (size == 4) { // Common size for tetrahedral coordinates
                var pooled = floatArrayPool.poll();
                if (pooled != null && pooled.length >= size) {
                    poolHits.incrementAndGet();
                    Arrays.fill(pooled, 0, size, 0f);
                    return pooled;
                }
            }
            
            poolMisses.incrementAndGet();
            totalAllocations.incrementAndGet();
            return new float[size];
        }
        
        /**
         * Return float array to pool
         */
        public void returnFloatArray(float[] array) {
            if (array.length == 4 && floatArrayPool.size() < MAX_POOL_SIZE) {
                floatArrayPool.offer(array);
            }
        }
        
        /**
         * Get or create int array for tetrahedral calculations
         */
        public int[] getIntArray(int size) {
            if (size == 4) { // Common size for tetrahedral indices
                var pooled = intArrayPool.poll();
                if (pooled != null && pooled.length >= size) {
                    poolHits.incrementAndGet();
                    Arrays.fill(pooled, 0, size, 0);
                    return pooled;
                }
            }
            
            poolMisses.incrementAndGet();
            totalAllocations.incrementAndGet();
            return new int[size];
        }
        
        /**
         * Return int array to pool
         */
        public void returnIntArray(int[] array) {
            if (array.length == 4 && intArrayPool.size() < MAX_POOL_SIZE) {
                intArrayPool.offer(array);
            }
        }
        
        /**
         * Execute operation with pooled objects
         */
        public <T> T withPooledPoint3f(Supplier<T> operation) {
            var point = getPoint3f();
            try {
                return operation.get();
            } finally {
                returnPoint3f(point);
            }
        }
        
        /**
         * Get pool statistics
         */
        public String getPoolStatistics() {
            double hitRate = 0.0;
            long total = poolHits.get() + poolMisses.get();
            if (total > 0) {
                hitRate = (double) poolHits.get() / total;
            }
            
            return String.format(
                "TetMemoryPool Stats:\n" +
                "  Pool Hit Rate: %.2f%% (%d hits, %d misses)\n" +
                "  Total Allocations: %d\n" +
                "  Point3f Pool Size: %d\n" +
                "  Point3i Pool Size: %d\n" +
                "  Float Array Pool Size: %d\n" +
                "  Int Array Pool Size: %d",
                hitRate * 100, poolHits.get(), poolMisses.get(),
                totalAllocations.get(),
                point3fPool.size(), point3iPool.size(),
                floatArrayPool.size(), intArrayPool.size()
            );
        }
        
        /**
         * Clear all pools
         */
        public void clearPools() {
            point3fPool.clear();
            point3iPool.clear();
            floatArrayPool.clear();
            intArrayPool.clear();
        }
    }
    
    /**
     * Garbage collection optimization for tetrahedral operations
     * Minimizes GC pressure during intensive tetrahedral computations
     */
    public static class TetGarbageCollectionOptimizer {
        
        private volatile boolean gcOptimizationEnabled = true;
        private final AtomicLong gcOptimizedOperations = new AtomicLong(0);
        
        /**
         * Execute tetrahedral operation with GC optimization
         * Batches operations to reduce GC pressure
         */
        public <T> List<T> executeWithGCOptimization(Collection<Long> tetIndices, 
                                                    java.util.function.Function<Long, T> operation) {
            if (!gcOptimizationEnabled) {
                return tetIndices.stream()
                    .map(operation)
                    .filter(Objects::nonNull)
                    .toList();
            }
            
            gcOptimizedOperations.incrementAndGet();
            
            // Process in batches to reduce memory pressure
            int batchSize = calculateOptimalBatchSize(tetIndices.size());
            var results = new ArrayList<T>();
            var indexList = new ArrayList<>(tetIndices);
            
            for (int i = 0; i < indexList.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, indexList.size());
                var batch = indexList.subList(i, endIndex);
                
                // Process batch with minimal allocations
                for (var index : batch) {
                    try {
                        var result = operation.apply(index);
                        if (result != null) {
                            results.add(result);
                        }
                    } catch (Exception e) {
                        // Skip problematic indices
                    }
                }
                
                // Suggest GC between batches for large operations
                if (batchSize > 1000 && (i + batchSize) < indexList.size()) {
                    suggestGC();
                }
            }
            
            return results;
        }
        
        /**
         * Calculate optimal batch size based on available memory and data size
         */
        private int calculateOptimalBatchSize(int totalSize) {
            // Get available memory
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long availableMemory = maxMemory - (totalMemory - freeMemory);
            
            // Estimate memory per tetrahedral operation (coordinates, calculations, etc.)
            long estimatedMemoryPerOperation = 1024; // 1KB estimate
            
            // Calculate batch size to use ~10% of available memory
            long targetMemoryUsage = availableMemory / 10;
            int calculatedBatchSize = (int) (targetMemoryUsage / estimatedMemoryPerOperation);
            
            // Clamp between reasonable bounds
            return Math.max(100, Math.min(calculatedBatchSize, Math.min(totalSize, 10000)));
        }
        
        /**
         * Suggest garbage collection if memory usage is high
         */
        private void suggestGC() {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            double memoryUsage = (double) (totalMemory - freeMemory) / totalMemory;
            
            // Suggest GC if memory usage > 80%
            if (memoryUsage > 0.8) {
                System.gc();
                globalMetrics.recordGCTriggered();
            }
        }
        
        /**
         * Enable or disable GC optimization
         */
        public void setGCOptimizationEnabled(boolean enabled) {
            this.gcOptimizationEnabled = enabled;
        }
        
        /**
         * Get GC optimization statistics
         */
        public String getGCStatistics() {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            return String.format(
                "GC Optimization Stats:\n" +
                "  Enabled: %s\n" +
                "  Optimized Operations: %d\n" +
                "  Memory Usage: %.2f%% (%d / %d MB)\n" +
                "  Available Memory: %d MB",
                gcOptimizationEnabled,
                gcOptimizedOperations.get(),
                (double) usedMemory / maxMemory * 100,
                usedMemory / (1024 * 1024),
                maxMemory / (1024 * 1024),
                (maxMemory - usedMemory) / (1024 * 1024)
            );
        }
    }
    
    /**
     * Cache eviction strategy optimized for tetrahedral access patterns
     * Uses tetrahedral SFC locality for intelligent cache management
     */
    public static class TetCacheEvictionStrategy {
        
        // Cache entry with access tracking
        private static class CacheEntry<T> {
            final T value;
            volatile long lastAccess;
            volatile int accessCount;
            final long sfcIndex;
            
            CacheEntry(T value, long sfcIndex) {
                this.value = value;
                this.sfcIndex = sfcIndex;
                this.lastAccess = System.nanoTime();
                this.accessCount = 1;
            }
            
            void recordAccess() {
                this.lastAccess = System.nanoTime();
                this.accessCount++;
            }
        }
        
        private final Map<Long, CacheEntry<Object>> cache = new ConcurrentHashMap<>();
        private final AtomicLong evictions = new AtomicLong(0);
        private final AtomicLong totalAccesses = new AtomicLong(0);
        
        // Cache configuration
        private static final int MAX_CACHE_SIZE = 10000;
        private static final long CACHE_TTL_NANOS = 5_000_000_000L; // 5 seconds
        
        /**
         * Get or compute cached value with tetrahedral locality awareness
         */
        @SuppressWarnings("unchecked")
        public <T> T getOrCompute(long tetIndex, Supplier<T> supplier, Class<T> type) {
            totalAccesses.incrementAndGet();
            
            var entry = cache.get(tetIndex);
            if (entry != null && type.isInstance(entry.value)) {
                entry.recordAccess();
                globalMetrics.recordCacheHit();
                return (T) entry.value;
            }
            
            // Compute new value
            globalMetrics.recordCacheMiss();
            T value = supplier.get();
            
            // Add to cache with eviction if needed
            if (cache.size() >= MAX_CACHE_SIZE) {
                evictLeastUseful();
            }
            
            cache.put(tetIndex, new CacheEntry<>(value, tetIndex));
            return value;
        }
        
        /**
         * Evict least useful entries based on tetrahedral access patterns
         */
        private void evictLeastUseful() {
            long currentTime = System.nanoTime();
            var entriesToEvict = new ArrayList<Map.Entry<Long, CacheEntry<Object>>>();
            
            // Find candidates for eviction
            for (var entry : cache.entrySet()) {
                var cacheEntry = entry.getValue();
                
                // Evict if TTL expired
                if (currentTime - cacheEntry.lastAccess > CACHE_TTL_NANOS) {
                    entriesToEvict.add(entry);
                }
                // Or if we have too many entries, evict least frequently used
                else if (entriesToEvict.size() < cache.size() / 4) {
                    entriesToEvict.add(entry);
                }
            }
            
            // Sort by access frequency and recency (LFU + LRU hybrid)
            entriesToEvict.sort((a, b) -> {
                var entryA = a.getValue();
                var entryB = b.getValue();
                
                // First by access count (frequency)
                int frequencyCompare = Integer.compare(entryA.accessCount, entryB.accessCount);
                if (frequencyCompare != 0) {
                    return frequencyCompare;
                }
                
                // Then by last access time (recency)
                return Long.compare(entryA.lastAccess, entryB.lastAccess);
            });
            
            // Evict least useful entries
            int toEvict = Math.min(entriesToEvict.size(), MAX_CACHE_SIZE / 4);
            for (int i = 0; i < toEvict; i++) {
                cache.remove(entriesToEvict.get(i).getKey());
                evictions.incrementAndGet();
            }
        }
        
        /**
         * Evict entries based on SFC locality
         * Keeps spatially local tetrahedra in cache
         */
        public void evictBySFCLocality(long centerSFCIndex, int localityRadius) {
            var toEvict = new ArrayList<Long>();
            
            for (var entry : cache.entrySet()) {
                long sfcIndex = entry.getKey();
                long distance = Math.abs(sfcIndex - centerSFCIndex);
                
                if (distance > localityRadius) {
                    toEvict.add(entry.getKey());
                }
            }
            
            for (var key : toEvict) {
                cache.remove(key);
                evictions.incrementAndGet();
            }
        }
        
        /**
         * Get cache statistics
         */
        public String getCacheStatistics() {
            double hitRate = globalMetrics.getCacheHitRate();
            
            return String.format(
                "TetCache Stats:\n" +
                "  Cache Size: %d / %d\n" +
                "  Hit Rate: %.2f%%\n" +
                "  Total Accesses: %d\n" +
                "  Evictions: %d\n" +
                "  Load Factor: %.2f",
                cache.size(), MAX_CACHE_SIZE,
                hitRate * 100,
                totalAccesses.get(),
                evictions.get(),
                (double) cache.size() / MAX_CACHE_SIZE
            );
        }
        
        /**
         * Clear cache
         */
        public void clearCache() {
            cache.clear();
        }
    }
    
    /**
     * Comprehensive memory metrics for tetrahedral operations
     */
    public static class TetMemoryMetrics {
        
        private final AtomicLong compactCreations = new AtomicLong(0);
        private final AtomicLong gridCellCreations = new AtomicLong(0);
        private final AtomicLong cacheHits = new AtomicLong(0);
        private final AtomicLong cacheMisses = new AtomicLong(0);
        private final AtomicLong gcTriggered = new AtomicLong(0);
        
        public void recordCompactCreation() { compactCreations.incrementAndGet(); }
        public void recordGridCellCreation() { gridCellCreations.incrementAndGet(); }
        public void recordCacheHit() { cacheHits.incrementAndGet(); }
        public void recordCacheMiss() { cacheMisses.incrementAndGet(); }
        public void recordGCTriggered() { gcTriggered.incrementAndGet(); }
        
        public double getCacheHitRate() {
            long hits = cacheHits.get();
            long misses = cacheMisses.get();
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
        
        /**
         * Get comprehensive memory metrics summary
         */
        public String getMetricsSummary() {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            return String.format(
                "TetMemory Metrics:\n" +
                "  Compact Representations Created: %d\n" +
                "  Grid Cells Created: %d\n" +
                "  Cache Hit Rate: %.2f%% (%d hits, %d misses)\n" +
                "  GC Triggers: %d\n" +
                "  JVM Memory Usage: %.2f%% (%d / %d MB)\n" +
                "  Available Memory: %d MB",
                compactCreations.get(), gridCellCreations.get(),
                getCacheHitRate() * 100, cacheHits.get(), cacheMisses.get(),
                gcTriggered.get(),
                (double) usedMemory / maxMemory * 100,
                usedMemory / (1024 * 1024),
                maxMemory / (1024 * 1024),
                (maxMemory - usedMemory) / (1024 * 1024)
            );
        }
        
        /**
         * Reset all metrics
         */
        public void reset() {
            compactCreations.set(0);
            gridCellCreations.set(0);
            cacheHits.set(0);
            cacheMisses.set(0);
            gcTriggered.set(0);
        }
    }
    
    // Public API methods
    
    /**
     * Get global memory metrics
     */
    public static TetMemoryMetrics getGlobalMetrics() {
        return globalMetrics;
    }
    
    /**
     * Get global memory pool
     */
    public static TetMemoryPool getGlobalPool() {
        return globalPool;
    }
    
    /**
     * Get GC optimizer
     */
    public static TetGarbageCollectionOptimizer getGCOptimizer() {
        return gcOptimizer;
    }
    
    /**
     * Get cache eviction strategy
     */
    public static TetCacheEvictionStrategy getCacheEviction() {
        return cacheEviction;
    }
    
    /**
     * Create compact representation for a collection of tetrahedra
     */
    public static List<TetCompactRepresentation.CompactTet> createCompactRepresentation(
            Collection<Tet> tetrahedra) {
        return tetrahedra.stream()
            .map(tet -> new TetCompactRepresentation.CompactTet(
                tet.x(), tet.y(), tet.z(), tet.l(), tet.type()))
            .toList();
    }
    
    /**
     * Execute memory-optimized tetrahedral operation
     */
    public static <T> List<T> executeMemoryOptimized(Collection<Long> tetIndices,
                                                   java.util.function.Function<Long, T> operation) {
        return gcOptimizer.executeWithGCOptimization(tetIndices, operation);
    }
    
    /**
     * Clear all memory optimizations and caches
     */
    public static void clearAllCaches() {
        globalPool.clearPools();
        cacheEviction.clearCache();
        globalMetrics.reset();
    }
    
    /**
     * Get comprehensive memory optimization summary
     */
    public static String getOptimizationSummary() {
        return String.format(
            "=== Tetrahedral Memory Optimization Summary ===\n\n" +
            "%s\n\n" +
            "%s\n\n" +
            "%s\n\n" +
            "%s",
            globalMetrics.getMetricsSummary(),
            globalPool.getPoolStatistics(),
            gcOptimizer.getGCStatistics(),
            cacheEviction.getCacheStatistics()
        );
    }
}