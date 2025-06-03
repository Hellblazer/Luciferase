package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory optimization utilities for Octree spatial operations
 * Provides memory-efficient data structures, object pooling, and memory monitoring
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class MemoryOptimizer {

    /**
     * Memory usage statistics and monitoring
     */
    public static class MemoryUsageStats {
        public final long heapUsedBytes;
        public final long heapMaxBytes;
        public final long heapCommittedBytes;
        public final long nonHeapUsedBytes;
        public final long nonHeapMaxBytes;
        public final double heapUsagePercentage;
        public final long gcCollections;
        public final long gcTimeMillis;
        public final long timestamp;
        
        public MemoryUsageStats(long heapUsedBytes, long heapMaxBytes, long heapCommittedBytes,
                              long nonHeapUsedBytes, long nonHeapMaxBytes, double heapUsagePercentage,
                              long gcCollections, long gcTimeMillis, long timestamp) {
            this.heapUsedBytes = heapUsedBytes;
            this.heapMaxBytes = heapMaxBytes;
            this.heapCommittedBytes = heapCommittedBytes;
            this.nonHeapUsedBytes = nonHeapUsedBytes;
            this.nonHeapMaxBytes = nonHeapMaxBytes;
            this.heapUsagePercentage = heapUsagePercentage;
            this.gcCollections = gcCollections;
            this.gcTimeMillis = gcTimeMillis;
            this.timestamp = timestamp;
        }
        
        public boolean isMemoryPressure() {
            return heapUsagePercentage > 80.0;
        }
        
        public boolean isCriticalMemory() {
            return heapUsagePercentage > 95.0;
        }
        
        @Override
        public String toString() {
            return String.format("MemoryStats[heap=%.1f%% (%dMB/%dMB), nonHeap=%dMB, gc=%d collections/%dms]",
                heapUsagePercentage, heapUsedBytes / (1024 * 1024), heapMaxBytes / (1024 * 1024),
                nonHeapUsedBytes / (1024 * 1024), gcCollections, gcTimeMillis);
        }
    }

    /**
     * Memory-efficient spatial point storage with compression
     */
    public static class CompressedPointStorage {
        private final float[] coordinates; // Packed x,y,z coordinates
        private final byte[] compressionInfo; // Compression metadata
        private final int capacity;
        private int size;
        private final float compressionScale;
        
        public CompressedPointStorage(int capacity, float compressionScale) {
            this.capacity = capacity;
            this.compressionScale = compressionScale;
            this.coordinates = new float[capacity * 3]; // 3 coordinates per point
            this.compressionInfo = new byte[capacity];
            this.size = 0;
        }
        
        public boolean addPoint(Point3f point) {
            validatePositiveCoordinates(point, "point");
            
            if (size >= capacity) {
                return false;
            }
            
            int index = size * 3;
            coordinates[index] = point.x;
            coordinates[index + 1] = point.y;
            coordinates[index + 2] = point.z;
            
            // Simple compression flag (could be enhanced with actual compression)
            compressionInfo[size] = (byte) (point.x < compressionScale && 
                                           point.y < compressionScale && 
                                           point.z < compressionScale ? 1 : 0);
            
            size++;
            return true;
        }
        
        public Point3f getPoint(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            
            int coordIndex = index * 3;
            return new Point3f(coordinates[coordIndex], coordinates[coordIndex + 1], coordinates[coordIndex + 2]);
        }
        
        public int size() {
            return size;
        }
        
        public int capacity() {
            return capacity;
        }
        
        public long getMemoryFootprintBytes() {
            return (long) capacity * 3 * Float.BYTES + capacity * Byte.BYTES + 64; // Object overhead
        }
        
        public void clear() {
            size = 0;
            // Don't null the arrays to avoid GC pressure
        }
        
        public double getCompressionRatio() {
            if (size == 0) return 1.0;
            
            int compressedCount = 0;
            for (int i = 0; i < size; i++) {
                if (compressionInfo[i] == 1) {
                    compressedCount++;
                }
            }
            return (double) compressedCount / size;
        }
    }

    /**
     * Object pool for reducing allocation overhead
     */
    public static class ObjectPool<T> {
        private final Queue<T> pool;
        private final ObjectFactory<T> factory;
        private final ObjectValidator<T> validator;
        private final int maxSize;
        private final AtomicInteger createdCount = new AtomicInteger(0);
        private final AtomicInteger pooledCount = new AtomicInteger(0);
        private final AtomicInteger borrowedCount = new AtomicInteger(0);
        
        public ObjectPool(ObjectFactory<T> factory, ObjectValidator<T> validator, int maxSize) {
            this.factory = factory;
            this.validator = validator;
            this.maxSize = maxSize;
            this.pool = new ArrayDeque<>(maxSize);
        }
        
        public T borrow() {
            T object = pool.poll();
            if (object == null) {
                object = factory.create();
                createdCount.incrementAndGet();
            } else {
                pooledCount.decrementAndGet();
            }
            borrowedCount.incrementAndGet();
            return object;
        }
        
        public void returnObject(T object) {
            if (object != null && validator.isValid(object) && pool.size() < maxSize) {
                factory.reset(object);
                pool.offer(object);
                pooledCount.incrementAndGet();
            }
            borrowedCount.decrementAndGet();
        }
        
        public PoolStatistics getStatistics() {
            return new PoolStatistics(createdCount.get(), pooledCount.get(), 
                borrowedCount.get(), pool.size(), maxSize);
        }
        
        public void clear() {
            pool.clear();
            pooledCount.set(0);
        }
        
        public interface ObjectFactory<T> {
            T create();
            void reset(T object);
        }
        
        public interface ObjectValidator<T> {
            boolean isValid(T object);
        }
        
        public static class PoolStatistics {
            public final int totalCreated;
            public final int currentPooled;
            public final int currentBorrowed;
            public final int currentPoolSize;
            public final int maxPoolSize;
            
            public PoolStatistics(int totalCreated, int currentPooled, int currentBorrowed,
                                int currentPoolSize, int maxPoolSize) {
                this.totalCreated = totalCreated;
                this.currentPooled = currentPooled;
                this.currentBorrowed = currentBorrowed;
                this.currentPoolSize = currentPoolSize;
                this.maxPoolSize = maxPoolSize;
            }
            
            public double getHitRate() {
                return totalCreated > 0 ? (double) currentPooled / totalCreated : 0.0;
            }
            
            @Override
            public String toString() {
                return String.format("PoolStats[created=%d, pooled=%d, borrowed=%d, hitRate=%.2f%%]",
                    totalCreated, currentPooled, currentBorrowed, getHitRate() * 100);
            }
        }
    }

    /**
     * Memory-aware cache with automatic cleanup under memory pressure
     */
    public static class MemoryAwareCache<K, V> {
        private final Map<K, CacheEntry<V>> cache;
        private final int maxSize;
        private final long maxMemoryBytes;
        private final AtomicLong currentMemoryUsage = new AtomicLong(0);
        private final AtomicInteger hits = new AtomicInteger(0);
        private final AtomicInteger misses = new AtomicInteger(0);
        private final MemoryEstimator<V> memoryEstimator;
        
        public MemoryAwareCache(int maxSize, long maxMemoryBytes, MemoryEstimator<V> memoryEstimator) {
            this.maxSize = maxSize;
            this.maxMemoryBytes = maxMemoryBytes;
            this.memoryEstimator = memoryEstimator;
            this.cache = new ConcurrentHashMap<>(maxSize);
        }
        
        public V get(K key) {
            CacheEntry<V> entry = cache.get(key);
            if (entry != null) {
                V value = entry.getValue();
                if (value != null) {
                    entry.updateAccessTime();
                    hits.incrementAndGet();
                    return value;
                } else {
                    // Soft reference was cleared
                    cache.remove(key);
                }
            }
            misses.incrementAndGet();
            return null;
        }
        
        public void put(K key, V value) {
            if (value == null) return;
            
            long valueSize = memoryEstimator.estimateSize(value);
            
            // Check memory pressure and cleanup if needed
            if (shouldCleanup(valueSize)) {
                cleanup();
            }
            
            CacheEntry<V> entry = new CacheEntry<>(value, valueSize);
            CacheEntry<V> previous = cache.put(key, entry);
            
            if (previous != null) {
                currentMemoryUsage.addAndGet(-previous.memorySize);
            }
            currentMemoryUsage.addAndGet(valueSize);
            
            // Size-based cleanup
            if (cache.size() > maxSize) {
                evictOldestEntries();
            }
        }
        
        private boolean shouldCleanup(long newValueSize) {
            return currentMemoryUsage.get() + newValueSize > maxMemoryBytes ||
                   getSystemMemoryUsage().isMemoryPressure();
        }
        
        private void cleanup() {
            // Remove entries with cleared soft references
            cache.entrySet().removeIf(entry -> entry.getValue().getValue() == null);
            
            // If still under pressure, remove oldest entries
            if (currentMemoryUsage.get() > maxMemoryBytes * 0.8) {
                evictOldestEntries();
            }
        }
        
        private void evictOldestEntries() {
            List<Map.Entry<K, CacheEntry<V>>> entries = new ArrayList<>(cache.entrySet());
            entries.sort(Comparator.comparing(e -> e.getValue().lastAccessTime));
            
            int toRemove = Math.max(1, cache.size() / 4); // Remove 25% of entries
            for (int i = 0; i < toRemove && !entries.isEmpty(); i++) {
                Map.Entry<K, CacheEntry<V>> oldest = entries.get(i);
                cache.remove(oldest.getKey());
                currentMemoryUsage.addAndGet(-oldest.getValue().memorySize);
            }
        }
        
        public CacheStatistics getStatistics() {
            long totalMemory = currentMemoryUsage.get();
            int validEntries = (int) cache.values().stream()
                .mapToInt(entry -> entry.getValue() != null ? 1 : 0)
                .sum();
            
            return new CacheStatistics(cache.size(), validEntries, hits.get(), misses.get(),
                totalMemory, maxMemoryBytes);
        }
        
        public void clear() {
            cache.clear();
            currentMemoryUsage.set(0);
            hits.set(0);
            misses.set(0);
        }
        
        private static class CacheEntry<V> {
            private final SoftReference<V> valueRef;
            private final long memorySize;
            private volatile long lastAccessTime;
            
            CacheEntry(V value, long memorySize) {
                this.valueRef = new SoftReference<>(value);
                this.memorySize = memorySize;
                this.lastAccessTime = System.currentTimeMillis();
            }
            
            V getValue() {
                return valueRef.get();
            }
            
            void updateAccessTime() {
                this.lastAccessTime = System.currentTimeMillis();
            }
        }
        
        public interface MemoryEstimator<V> {
            long estimateSize(V value);
        }
        
        public static class CacheStatistics {
            public final int totalEntries;
            public final int validEntries;
            public final int hits;
            public final int misses;
            public final long memoryUsageBytes;
            public final long maxMemoryBytes;
            
            public CacheStatistics(int totalEntries, int validEntries, int hits, int misses,
                                 long memoryUsageBytes, long maxMemoryBytes) {
                this.totalEntries = totalEntries;
                this.validEntries = validEntries;
                this.hits = hits;
                this.misses = misses;
                this.memoryUsageBytes = memoryUsageBytes;
                this.maxMemoryBytes = maxMemoryBytes;
            }
            
            public double getHitRate() {
                int total = hits + misses;
                return total > 0 ? (double) hits / total : 0.0;
            }
            
            public double getMemoryUsagePercentage() {
                return maxMemoryBytes > 0 ? (double) memoryUsageBytes / maxMemoryBytes * 100 : 0.0;
            }
            
            @Override
            public String toString() {
                return String.format("CacheStats[entries=%d/%d, hits=%d, misses=%d, hitRate=%.2f%%, memory=%.1f%%]",
                    validEntries, totalEntries, hits, misses, getHitRate() * 100, getMemoryUsagePercentage());
            }
        }
    }

    /**
     * Memory-efficient spatial data structures
     */
    public static class MemoryEfficientSpatialStructures {
        
        /**
         * Compact spatial bounds representation
         */
        public static class CompactBounds {
            public final short minX, minY, minZ; // 16-bit coordinates for memory efficiency
            public final short maxX, maxY, maxZ;
            private final float scale; // Scale factor for conversion
            
            public CompactBounds(Point3f min, Point3f max, float scale) {
                validatePositiveCoordinates(min, "min");
                validatePositiveCoordinates(max, "max");
                
                this.scale = scale;
                this.minX = (short) (min.x / scale);
                this.minY = (short) (min.y / scale);
                this.minZ = (short) (min.z / scale);
                this.maxX = (short) (max.x / scale);
                this.maxY = (short) (max.y / scale);
                this.maxZ = (short) (max.z / scale);
            }
            
            public Point3f getMin() {
                return new Point3f(minX * scale, minY * scale, minZ * scale);
            }
            
            public Point3f getMax() {
                return new Point3f(maxX * scale, maxY * scale, maxZ * scale);
            }
            
            public boolean contains(Point3f point) {
                validatePositiveCoordinates(point, "point");
                
                short x = (short) (point.x / scale);
                short y = (short) (point.y / scale);
                short z = (short) (point.z / scale);
                
                return x >= minX && x <= maxX &&
                       y >= minY && y <= maxY &&
                       z >= minZ && z <= maxZ;
            }
            
            public float getVolume() {
                return (maxX - minX) * (maxY - minY) * (maxZ - minZ) * scale * scale * scale;
            }
        }
        
        /**
         * Memory-efficient point list with automatic capacity management
         */
        public static class AdaptivePointList {
            private Point3f[] points;
            private int size;
            private int capacity;
            private final float growthFactor;
            private final int maxCapacity;
            
            public AdaptivePointList(int initialCapacity, float growthFactor, int maxCapacity) {
                this.capacity = initialCapacity;
                this.growthFactor = growthFactor;
                this.maxCapacity = maxCapacity;
                this.points = new Point3f[initialCapacity];
                this.size = 0;
            }
            
            public boolean add(Point3f point) {
                validatePositiveCoordinates(point, "point");
                
                if (size >= capacity) {
                    if (!expandCapacity()) {
                        return false; // Cannot expand further
                    }
                }
                
                points[size++] = new Point3f(point); // Defensive copy
                return true;
            }
            
            public Point3f get(int index) {
                if (index < 0 || index >= size) {
                    throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
                }
                return points[index];
            }
            
            public int size() {
                return size;
            }
            
            public void clear() {
                for (int i = 0; i < size; i++) {
                    points[i] = null; // Help GC
                }
                size = 0;
            }
            
            public void trimToSize() {
                if (size < capacity) {
                    Point3f[] newPoints = new Point3f[size];
                    System.arraycopy(points, 0, newPoints, 0, size);
                    points = newPoints;
                    capacity = size;
                }
            }
            
            private boolean expandCapacity() {
                int newCapacity = Math.min(maxCapacity, (int) (capacity * growthFactor));
                if (newCapacity <= capacity) {
                    return false;
                }
                
                Point3f[] newPoints = new Point3f[newCapacity];
                System.arraycopy(points, 0, newPoints, 0, size);
                points = newPoints;
                capacity = newCapacity;
                return true;
            }
            
            public long getMemoryFootprintBytes() {
                return (long) capacity * 8 + size * 12 * Float.BYTES; // Reference + Point3f data
            }
        }
    }

    /**
     * Memory monitoring and management utilities
     */
    public static class MemoryMonitor {
        private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private final List<MemoryUsageStats> history = new ArrayList<>();
        private final int maxHistorySize;
        
        public MemoryMonitor(int maxHistorySize) {
            this.maxHistorySize = maxHistorySize;
        }
        
        public MemoryUsageStats getCurrentMemoryUsage() {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
            
            long heapUsed = heapUsage.getUsed();
            long heapMax = heapUsage.getMax() > 0 ? heapUsage.getMax() : heapUsage.getCommitted();
            long heapCommitted = heapUsage.getCommitted();
            long nonHeapUsed = nonHeapUsage.getUsed();
            long nonHeapMax = nonHeapUsage.getMax() > 0 ? nonHeapUsage.getMax() : nonHeapUsage.getCommitted();
            
            double heapPercentage = (double) heapUsed / heapMax * 100;
            
            // Get GC stats (simplified)
            long gcCollections = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gcBean -> gcBean.getCollectionCount())
                .sum();
            
            long gcTime = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gcBean -> gcBean.getCollectionTime())
                .sum();
            
            MemoryUsageStats stats = new MemoryUsageStats(heapUsed, heapMax, heapCommitted,
                nonHeapUsed, nonHeapMax, heapPercentage, gcCollections, gcTime, System.currentTimeMillis());
            
            recordUsage(stats);
            return stats;
        }
        
        private void recordUsage(MemoryUsageStats stats) {
            synchronized (history) {
                history.add(stats);
                if (history.size() > maxHistorySize) {
                    history.remove(0);
                }
            }
        }
        
        public List<MemoryUsageStats> getHistory() {
            synchronized (history) {
                return new ArrayList<>(history);
            }
        }
        
        public MemoryTrend analyzeTrend() {
            synchronized (history) {
                if (history.size() < 2) {
                    return new MemoryTrend(0.0, false, false);
                }
                
                double totalChange = 0.0;
                boolean increasing = false;
                boolean memoryPressure = false;
                
                for (int i = 1; i < history.size(); i++) {
                    double change = history.get(i).heapUsagePercentage - history.get(i - 1).heapUsagePercentage;
                    totalChange += change;
                }
                
                double averageChange = totalChange / (history.size() - 1);
                increasing = averageChange > 1.0; // More than 1% increase on average
                
                MemoryUsageStats latest = history.get(history.size() - 1);
                memoryPressure = latest.isMemoryPressure();
                
                return new MemoryTrend(averageChange, increasing, memoryPressure);
            }
        }
        
        public void clearHistory() {
            synchronized (history) {
                history.clear();
            }
        }
        
        public static class MemoryTrend {
            public final double averageChangePercentage;
            public final boolean increasing;
            public final boolean memoryPressure;
            
            public MemoryTrend(double averageChangePercentage, boolean increasing, boolean memoryPressure) {
                this.averageChangePercentage = averageChangePercentage;
                this.increasing = increasing;
                this.memoryPressure = memoryPressure;
            }
            
            @Override
            public String toString() {
                return String.format("MemoryTrend[change=%.2f%%, increasing=%s, pressure=%s]",
                    averageChangePercentage, increasing, memoryPressure);
            }
        }
    }

    /**
     * Memory optimization strategies for different scenarios
     */
    public static class MemoryOptimizationStrategies {
        
        public static void optimizeForLowMemory() {
            System.gc();
            Runtime.getRuntime().runFinalization();
        }
        
        public static boolean shouldTriggerCleanup(MemoryUsageStats stats) {
            return stats.heapUsagePercentage > 75.0;
        }
        
        public static void aggressiveCleanup() {
            // Multiple GC passes for thorough cleanup
            for (int i = 0; i < 3; i++) {
                System.gc();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Runtime.getRuntime().runFinalization();
        }
        
        public static long estimateObjectSize(Object obj) {
            if (obj == null) return 0;
            
            // Simplified object size estimation
            if (obj instanceof Point3f) {
                return 32; // Object header + 3 floats
            } else if (obj instanceof String) {
                return 40 + ((String) obj).length() * 2; // Object + char array
            } else if (obj instanceof List) {
                return 64 + ((List<?>) obj).size() * 8; // ArrayList overhead + references
            } else {
                return 64; // Default object size estimate
            }
        }
    }

    // Utility methods
    
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }
    
    public static MemoryUsageStats getSystemMemoryUsage() {
        MemoryMonitor monitor = new MemoryMonitor(1);
        return monitor.getCurrentMemoryUsage();
    }
}