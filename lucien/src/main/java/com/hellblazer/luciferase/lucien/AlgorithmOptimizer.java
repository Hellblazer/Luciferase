package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Advanced algorithm optimizations for Octree spatial operations
 * Provides enhanced search algorithms, adaptive structures, and intelligent caching
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class AlgorithmOptimizer {

    /**
     * Advanced spatial search algorithms with adaptive optimization
     */
    public static class AdvancedSpatialSearch {
        
        /**
         * Adaptive k-NN search that selects optimal algorithm based on data characteristics
         */
        public static class AdaptiveKNearestNeighbor<Content> {
            private final Octree<Content> octree;
            private final SpatialIndexOptimizer.SpatialDistributionStats dataStats;
            private SearchStrategy lastUsedStrategy;
            private final Map<SearchStrategy, PerformanceMetrics> strategyMetrics;
            
            public AdaptiveKNearestNeighbor(Octree<Content> octree, SpatialIndexOptimizer.SpatialDistributionStats dataStats) {
                this.octree = octree;
                this.dataStats = dataStats;
                this.strategyMetrics = new EnumMap<>(SearchStrategy.class);
                this.lastUsedStrategy = SearchStrategy.HIERARCHICAL;
            }
            
            public KNNResult<Content> findKNearest(Point3f queryPoint, int k) {
                validatePositiveCoordinates(queryPoint, "queryPoint");
                
                long startTime = System.nanoTime();
                
                // Select optimal strategy based on data characteristics and query parameters
                SearchStrategy strategy = selectOptimalStrategy(k);
                
                List<DistanceEntry<Content>> results;
                switch (strategy) {
                    case MORTON_ORDERED:
                        results = mortonOrderedKNN(queryPoint, k);
                        break;
                    case HIERARCHICAL:
                        results = hierarchicalKNN(queryPoint, k);
                        break;
                    case HYBRID:
                        results = hybridKNN(queryPoint, k);
                        break;
                    default:
                        results = hierarchicalKNN(queryPoint, k); // Fallback
                }
                
                long endTime = System.nanoTime();
                
                // Update performance metrics
                PerformanceMetrics metrics = strategyMetrics.computeIfAbsent(strategy, 
                    s -> new PerformanceMetrics());
                metrics.recordExecution(endTime - startTime, results.size());
                
                lastUsedStrategy = strategy;
                
                return new KNNResult<>(results, strategy, endTime - startTime);
            }
            
            private SearchStrategy selectOptimalStrategy(int k) {
                // Use performance history to guide strategy selection
                if (strategyMetrics.size() >= 3) {
                    SearchStrategy fastest = strategyMetrics.entrySet().stream()
                        .min(Comparator.comparing(e -> e.getValue().getAverageTimeNanos()))
                        .map(Map.Entry::getKey)
                        .orElse(SearchStrategy.HIERARCHICAL);
                    
                    // Use fastest strategy if it's significantly better
                    PerformanceMetrics fastestMetrics = strategyMetrics.get(fastest);
                    PerformanceMetrics currentMetrics = strategyMetrics.get(lastUsedStrategy);
                    
                    if (currentMetrics != null && 
                        fastestMetrics.getAverageTimeNanos() < currentMetrics.getAverageTimeNanos() * 0.8) {
                        return fastest;
                    }
                }
                
                // Strategy selection based on data characteristics
                int dataSize = octree.getMap().size();
                
                if (dataSize < 1000) {
                    return SearchStrategy.HIERARCHICAL; // Simple hierarchical for small datasets
                }
                
                if (k > dataSize * 0.1) {
                    return SearchStrategy.MORTON_ORDERED; // Morton order for large k
                }
                
                if (dataStats.uniformityScore > 0.7) {
                    return SearchStrategy.MORTON_ORDERED; // Morton works well for uniform data
                }
                
                if (dataStats.clusteredRegions > 5) {
                    return SearchStrategy.HYBRID; // Hybrid for clustered data
                }
                
                return SearchStrategy.HIERARCHICAL; // Default choice
            }
            
            private List<DistanceEntry<Content>> mortonOrderedKNN(Point3f queryPoint, int k) {
                // Convert query point to Morton code for efficient spatial locality
                SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint compact = 
                    SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint.fromPoint3f(queryPoint, 1000.0f);
                long queryMorton = SpatialIndexOptimizer.OptimizedMortonCalculator.encodeMorton3D(
                    compact.x, compact.y, compact.z);
                
                PriorityQueue<DistanceEntry<Content>> candidates = new PriorityQueue<>(
                    k + 1, Comparator.comparing((DistanceEntry<Content> e) -> e.distance).reversed());
                
                // Search in Morton order for spatial locality
                Map<Long, Content> map = octree.getMap();
                
                // Start from query Morton and expand outward
                Long startKey = findClosestKey(map, queryMorton);
                if (startKey == null && !map.isEmpty()) {
                    startKey = map.keySet().iterator().next(); // Get any key as fallback
                }
                
                // Bidirectional search from starting point
                Set<Long> visited = new HashSet<>();
                Queue<Long> toVisit = new ArrayDeque<>();
                toVisit.offer(startKey);
                
                while (!toVisit.isEmpty() && visited.size() < Math.min(map.size(), k * 20)) {
                    Long currentKey = toVisit.poll();
                    if (currentKey == null || visited.contains(currentKey)) continue;
                    
                    visited.add(currentKey);
                    Content content = map.get(currentKey);
                    if (content != null) {
                        Spatial.Cube cube = Octree.toCube(currentKey);
                        Point3f cubeCenter = new Point3f(
                            cube.originX() + cube.extent() / 2.0f,
                            cube.originY() + cube.extent() / 2.0f,
                            cube.originZ() + cube.extent() / 2.0f
                        );
                        
                        float distance = calculateDistance(queryPoint, cubeCenter);
                        
                        if (candidates.size() < k) {
                            candidates.offer(new DistanceEntry<>(distance, content));
                        } else if (distance < candidates.peek().distance) {
                            candidates.poll();
                            candidates.offer(new DistanceEntry<>(distance, content));
                        }
                    }
                    
                    // Add neighbors to visit queue - find adjacent keys manually
                    Long higher = findHigherKey(map, currentKey);
                    Long lower = findLowerKey(map, currentKey);
                    if (higher != null && !visited.contains(higher)) toVisit.offer(higher);
                    if (lower != null && !visited.contains(lower)) toVisit.offer(lower);
                }
                
                List<DistanceEntry<Content>> result = new ArrayList<>(candidates);
                result.sort(Comparator.comparing(e -> e.distance));
                return result;
            }
            
            private List<DistanceEntry<Content>> hierarchicalKNN(Point3f queryPoint, int k) {
                PriorityQueue<DistanceEntry<Content>> result = new PriorityQueue<>(
                    k + 1, Comparator.comparing((DistanceEntry<Content> e) -> e.distance).reversed());
                
                for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                    Spatial.Cube cube = Octree.toCube(entry.getKey());
                    Point3f cubeCenter = new Point3f(
                        cube.originX() + cube.extent() / 2.0f,
                        cube.originY() + cube.extent() / 2.0f,
                        cube.originZ() + cube.extent() / 2.0f
                    );
                    
                    float distance = calculateDistance(queryPoint, cubeCenter);
                    
                    if (result.size() < k) {
                        result.offer(new DistanceEntry<>(distance, entry.getValue()));
                    } else if (distance < result.peek().distance) {
                        result.poll();
                        result.offer(new DistanceEntry<>(distance, entry.getValue()));
                    }
                }
                
                List<DistanceEntry<Content>> sortedResult = new ArrayList<>(result);
                sortedResult.sort(Comparator.comparing(e -> e.distance));
                return sortedResult;
            }
            
            private List<DistanceEntry<Content>> hybridKNN(Point3f queryPoint, int k) {
                // Use hierarchical for initial candidates, then refine with Morton ordering
                List<DistanceEntry<Content>> hierarchicalResults = hierarchicalKNN(queryPoint, Math.min(k * 3, 100));
                
                if (hierarchicalResults.size() <= k) {
                    return hierarchicalResults;
                }
                
                // Refine with Morton-based local search
                List<DistanceEntry<Content>> mortonRefined = mortonOrderedKNN(queryPoint, k);
                
                // Merge and deduplicate results
                Set<Content> seen = new HashSet<>();
                List<DistanceEntry<Content>> merged = new ArrayList<>();
                
                for (DistanceEntry<Content> entry : mortonRefined) {
                    if (!seen.contains(entry.content)) {
                        seen.add(entry.content);
                        merged.add(entry);
                        if (merged.size() >= k) break;
                    }
                }
                
                // Fill remaining slots from hierarchical results
                for (DistanceEntry<Content> entry : hierarchicalResults) {
                    if (merged.size() >= k) break;
                    if (!seen.contains(entry.content)) {
                        merged.add(entry);
                    }
                }
                
                merged.sort(Comparator.comparing(e -> e.distance));
                return merged.subList(0, Math.min(k, merged.size()));
            }
            
            public Map<SearchStrategy, PerformanceMetrics> getPerformanceMetrics() {
                return new EnumMap<>(strategyMetrics);
            }
        }
        
        /**
         * Intelligent range query with adaptive bounds optimization
         */
        public static class AdaptiveRangeQuery<Content> {
            private final Octree<Content> octree;
            private final BoundsOptimizer boundsOptimizer;
            
            public AdaptiveRangeQuery(Octree<Content> octree) {
                this.octree = octree;
                this.boundsOptimizer = new BoundsOptimizer();
            }
            
            public RangeQueryResult<Content> rangeQuery(Point3f minBounds, Point3f maxBounds) {
                validatePositiveCoordinates(minBounds, "minBounds");
                validatePositiveCoordinates(maxBounds, "maxBounds");
                
                long startTime = System.nanoTime();
                
                // Optimize query bounds based on data distribution
                OptimizedBounds optimizedBounds = boundsOptimizer.optimizeBounds(minBounds, maxBounds, octree);
                
                List<Content> results = new ArrayList<>();
                int nodesVisited = 0;
                
                for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                    nodesVisited++;
                    Spatial.Cube cube = Octree.toCube(entry.getKey());
                    
                    if (optimizedBounds.intersects(cube)) {
                        results.add(entry.getValue());
                    }
                    
                    // Early termination if bounds are highly selective
                    if (optimizedBounds.isHighlySelective() && nodesVisited > optimizedBounds.getExpectedResults() * 10) {
                        break;
                    }
                }
                
                long endTime = System.nanoTime();
                
                return new RangeQueryResult<>(results, optimizedBounds, endTime - startTime, nodesVisited);
            }
        }
        
        public enum SearchStrategy {
            MORTON_ORDERED,
            HIERARCHICAL,
            HYBRID
        }
        
        public static class DistanceEntry<Content> {
            public final float distance;
            public final Content content;
            
            public DistanceEntry(float distance, Content content) {
                this.distance = distance;
                this.content = content;
            }
        }
        
        public static class KNNResult<Content> {
            public final List<DistanceEntry<Content>> results;
            public final SearchStrategy strategyUsed;
            public final long executionTimeNanos;
            
            public KNNResult(List<DistanceEntry<Content>> results, SearchStrategy strategyUsed, long executionTimeNanos) {
                this.results = Collections.unmodifiableList(new ArrayList<>(results));
                this.strategyUsed = strategyUsed;
                this.executionTimeNanos = executionTimeNanos;
            }
            
            public double getThroughputItemsPerSecond() {
                return executionTimeNanos > 0 ? (results.size() * 1_000_000_000.0) / executionTimeNanos : 0.0;
            }
            
            @Override
            public String toString() {
                return String.format("KNNResult[items=%d, strategy=%s, time=%.2fms, throughput=%.1f items/sec]",
                    results.size(), strategyUsed, executionTimeNanos / 1_000_000.0, getThroughputItemsPerSecond());
            }
        }
        
        public static class RangeQueryResult<Content> {
            public final List<Content> results;
            public final OptimizedBounds bounds;
            public final long executionTimeNanos;
            public final int nodesVisited;
            
            public RangeQueryResult(List<Content> results, OptimizedBounds bounds, 
                                  long executionTimeNanos, int nodesVisited) {
                this.results = Collections.unmodifiableList(new ArrayList<>(results));
                this.bounds = bounds;
                this.executionTimeNanos = executionTimeNanos;
                this.nodesVisited = nodesVisited;
            }
            
            public double getSelectivity() {
                return nodesVisited > 0 ? (double) results.size() / nodesVisited : 0.0;
            }
            
            @Override
            public String toString() {
                return String.format("RangeResult[items=%d, visited=%d, selectivity=%.3f, time=%.2fms]",
                    results.size(), nodesVisited, getSelectivity(), executionTimeNanos / 1_000_000.0);
            }
        }
    }
    
    /**
     * Intelligent bounds optimization for range queries
     */
    public static class BoundsOptimizer {
        
        public OptimizedBounds optimizeBounds(Point3f minBounds, Point3f maxBounds, Octree<?> octree) {
            // Calculate query volume and expected selectivity
            float volume = (maxBounds.x - minBounds.x) * (maxBounds.y - minBounds.y) * (maxBounds.z - minBounds.z);
            
            // Estimate total space volume from octree data
            Map<Long, ?> map = octree.getMap();
            if (map.isEmpty()) {
                return new OptimizedBounds(minBounds, maxBounds, 0.0f, 0, false);
            }
            
            // Simple bounds optimization based on data distribution
            float selectivity = Math.min(1.0f, volume / estimateDataSpaceVolume(octree));
            int expectedResults = (int) (map.size() * selectivity);
            boolean highlySelective = selectivity < 0.1f;
            
            return new OptimizedBounds(minBounds, maxBounds, selectivity, expectedResults, highlySelective);
        }
        
        private float estimateDataSpaceVolume(Octree<?> octree) {
            // Simple heuristic: estimate from key range
            Map<Long, ?> map = octree.getMap();
            if (map.size() < 2) return 1.0f;
            
            // Find min and max keys since we don't have NavigableMap methods
            long firstKey = Long.MAX_VALUE;
            long lastKey = Long.MIN_VALUE;
            for (Long key : map.keySet()) {
                firstKey = Math.min(firstKey, key);
                lastKey = Math.max(lastKey, key);
            }
            
            // Estimate volume from Morton code range (simplified)
            return Math.max(1.0f, (lastKey - firstKey) / 1000.0f);
        }
    }
    
    public static class OptimizedBounds {
        private final Point3f minBounds;
        private final Point3f maxBounds;
        private final float selectivity;
        private final int expectedResults;
        private final boolean highlySelective;
        
        public OptimizedBounds(Point3f minBounds, Point3f maxBounds, float selectivity, 
                             int expectedResults, boolean highlySelective) {
            this.minBounds = new Point3f(minBounds);
            this.maxBounds = new Point3f(maxBounds);
            this.selectivity = selectivity;
            this.expectedResults = expectedResults;
            this.highlySelective = highlySelective;
        }
        
        public boolean intersects(Spatial.Cube cube) {
            return cube.originX() <= maxBounds.x && cube.originX() + cube.extent() >= minBounds.x &&
                   cube.originY() <= maxBounds.y && cube.originY() + cube.extent() >= minBounds.y &&
                   cube.originZ() <= maxBounds.z && cube.originZ() + cube.extent() >= minBounds.z;
        }
        
        public float getSelectivity() { return selectivity; }
        public int getExpectedResults() { return expectedResults; }
        public boolean isHighlySelective() { return highlySelective; }
        
        @Override
        public String toString() {
            return String.format("OptimizedBounds[selectivity=%.3f, expected=%d, selective=%s]",
                selectivity, expectedResults, highlySelective);
        }
    }
    
    /**
     * Performance metrics tracking for algorithm selection
     */
    public static class PerformanceMetrics {
        private long totalExecutions = 0;
        private long totalTimeNanos = 0;
        private long totalResults = 0;
        private long minTimeNanos = Long.MAX_VALUE;
        private long maxTimeNanos = Long.MIN_VALUE;
        
        public void recordExecution(long timeNanos, int resultCount) {
            totalExecutions++;
            totalTimeNanos += timeNanos;
            totalResults += resultCount;
            minTimeNanos = Math.min(minTimeNanos, timeNanos);
            maxTimeNanos = Math.max(maxTimeNanos, timeNanos);
        }
        
        public double getAverageTimeNanos() {
            return totalExecutions > 0 ? (double) totalTimeNanos / totalExecutions : 0.0;
        }
        
        public double getAverageThroughput() {
            return totalTimeNanos > 0 ? (totalResults * 1_000_000_000.0) / totalTimeNanos : 0.0;
        }
        
        public long getTotalExecutions() { return totalExecutions; }
        public long getMinTimeNanos() { return minTimeNanos == Long.MAX_VALUE ? 0 : minTimeNanos; }
        public long getMaxTimeNanos() { return maxTimeNanos == Long.MIN_VALUE ? 0 : maxTimeNanos; }
        
        @Override
        public String toString() {
            return String.format("PerformanceMetrics[executions=%d, avgTime=%.2fms, throughput=%.1f items/sec]",
                totalExecutions, getAverageTimeNanos() / 1_000_000.0, getAverageThroughput());
        }
    }
    
    /**
     * Advanced caching strategies with intelligent eviction
     */
    public static class IntelligentCache<K, V> {
        private final Map<K, CacheEntry<V>> cache;
        private final int maxSize;
        private final CacheEvictionStrategy evictionStrategy;
        private final Function<V, Long> sizeEstimator;
        private long totalSize = 0;
        private final long maxMemoryBytes;
        
        // Statistics
        private long hits = 0;
        private long misses = 0;
        private long evictions = 0;
        
        public IntelligentCache(int maxSize, long maxMemoryBytes, CacheEvictionStrategy evictionStrategy,
                              Function<V, Long> sizeEstimator) {
            this.maxSize = maxSize;
            this.maxMemoryBytes = maxMemoryBytes;
            this.evictionStrategy = evictionStrategy;
            this.sizeEstimator = sizeEstimator;
            this.cache = new LinkedHashMap<>(maxSize, 0.75f, evictionStrategy == CacheEvictionStrategy.LRU);
        }
        
        public V get(K key) {
            CacheEntry<V> entry = cache.get(key);
            if (entry != null) {
                entry.recordAccess();
                hits++;
                return entry.value;
            }
            misses++;
            return null;
        }
        
        public void put(K key, V value) {
            if (value == null) return;
            
            long valueSize = sizeEstimator.apply(value);
            
            // Check if we need to evict entries
            while ((cache.size() >= maxSize || totalSize + valueSize > maxMemoryBytes) && !cache.isEmpty()) {
                evictEntry();
            }
            
            CacheEntry<V> oldEntry = cache.put(key, new CacheEntry<>(value, valueSize));
            if (oldEntry != null) {
                totalSize -= oldEntry.size;
            }
            totalSize += valueSize;
        }
        
        private void evictEntry() {
            K keyToEvict = selectEvictionCandidate();
            if (keyToEvict != null) {
                CacheEntry<V> evicted = cache.remove(keyToEvict);
                if (evicted != null) {
                    totalSize -= evicted.size;
                    evictions++;
                }
            }
        }
        
        private K selectEvictionCandidate() {
            if (cache.isEmpty()) return null;
            
            switch (evictionStrategy) {
                case LRU:
                    return cache.keySet().iterator().next(); // LinkedHashMap handles LRU ordering
                case LFU:
                    return cache.entrySet().stream()
                        .min(Comparator.comparing(e -> e.getValue().accessCount))
                        .map(Map.Entry::getKey)
                        .orElse(null);
                case SIZE_BASED:
                    return cache.entrySet().stream()
                        .max(Comparator.comparing(e -> e.getValue().size))
                        .map(Map.Entry::getKey)
                        .orElse(null);
                case ADAPTIVE:
                    // Adaptive strategy combines access frequency and size
                    return cache.entrySet().stream()
                        .min(Comparator.comparing(e -> e.getValue().getAdaptiveScore()))
                        .map(Map.Entry::getKey)
                        .orElse(null);
                default:
                    return cache.keySet().iterator().next();
            }
        }
        
        public CacheStatistics getStatistics() {
            return new CacheStatistics(cache.size(), maxSize, hits, misses, evictions, 
                totalSize, maxMemoryBytes);
        }
        
        public void clear() {
            cache.clear();
            totalSize = 0;
            hits = 0;
            misses = 0;
            evictions = 0;
        }
        
        private static class CacheEntry<V> {
            final V value;
            final long size;
            final long creationTime;
            long lastAccessTime;
            int accessCount;
            
            CacheEntry(V value, long size) {
                this.value = value;
                this.size = size;
                this.creationTime = System.currentTimeMillis();
                this.lastAccessTime = creationTime;
                this.accessCount = 1;
            }
            
            void recordAccess() {
                this.lastAccessTime = System.currentTimeMillis();
                this.accessCount++;
            }
            
            double getAdaptiveScore() {
                long age = System.currentTimeMillis() - lastAccessTime;
                double accessFrequency = (double) accessCount / Math.max(1, (lastAccessTime - creationTime));
                double sizePenalty = Math.log(size + 1);
                return sizePenalty / (accessFrequency + 1) + age / 1000.0; // Lower is better
            }
        }
        
        public enum CacheEvictionStrategy {
            LRU,    // Least Recently Used
            LFU,    // Least Frequently Used  
            SIZE_BASED, // Largest items first
            ADAPTIVE    // Combination of frequency, recency, and size
        }
        
        public static class CacheStatistics {
            public final int currentSize;
            public final int maxSize;
            public final long hits;
            public final long misses;
            public final long evictions;
            public final long memoryUsageBytes;
            public final long maxMemoryBytes;
            
            public CacheStatistics(int currentSize, int maxSize, long hits, long misses, 
                                 long evictions, long memoryUsageBytes, long maxMemoryBytes) {
                this.currentSize = currentSize;
                this.maxSize = maxSize;
                this.hits = hits;
                this.misses = misses;
                this.evictions = evictions;
                this.memoryUsageBytes = memoryUsageBytes;
                this.maxMemoryBytes = maxMemoryBytes;
            }
            
            public double getHitRate() {
                long total = hits + misses;
                return total > 0 ? (double) hits / total : 0.0;
            }
            
            public double getMemoryUsagePercentage() {
                return maxMemoryBytes > 0 ? (double) memoryUsageBytes / maxMemoryBytes * 100 : 0.0;
            }
            
            @Override
            public String toString() {
                return String.format("CacheStats[size=%d/%d, hitRate=%.2f%%, memory=%.1f%%, evictions=%d]",
                    currentSize, maxSize, getHitRate() * 100, getMemoryUsagePercentage(), evictions);
            }
        }
    }
    
    // Utility methods
    
    private static float calculateDistance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }
    
    // Helper methods for Map navigation since we can't use NavigableMap methods
    
    private static <Content> Long findClosestKey(Map<Long, Content> map, long target) {
        if (map.isEmpty()) return null;
        
        Long closest = null;
        long minDistance = Long.MAX_VALUE;
        
        for (Long key : map.keySet()) {
            long distance = Math.abs(key - target);
            if (distance < minDistance) {
                minDistance = distance;
                closest = key;
            }
        }
        
        return closest;
    }
    
    private static <Content> Long findHigherKey(Map<Long, Content> map, long target) {
        Long higher = null;
        for (Long key : map.keySet()) {
            if (key > target && (higher == null || key < higher)) {
                higher = key;
            }
        }
        return higher;
    }
    
    private static <Content> Long findLowerKey(Map<Long, Content> map, long target) {
        Long lower = null;
        for (Long key : map.keySet()) {
            if (key < target && (lower == null || key > lower)) {
                lower = key;
            }
        }
        return lower;
    }
}