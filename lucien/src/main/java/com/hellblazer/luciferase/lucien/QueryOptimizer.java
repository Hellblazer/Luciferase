package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Query optimization strategies for Octree
 * Provides caching, spatial indexing, query planning, and performance monitoring for spatial queries
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class QueryOptimizer {

    /**
     * Query performance metrics
     */
    public static class QueryMetrics {
        public final String queryType;
        public final long executionTimeNanos;
        public final int resultCount;
        public final int nodesVisited;
        public final boolean cacheHit;
        public final float selectivity; // ratio of results to total nodes visited
        
        public QueryMetrics(String queryType, long executionTimeNanos, int resultCount, 
                          int nodesVisited, boolean cacheHit) {
            this.queryType = queryType;
            this.executionTimeNanos = executionTimeNanos;
            this.resultCount = resultCount;
            this.nodesVisited = nodesVisited;
            this.cacheHit = cacheHit;
            this.selectivity = nodesVisited > 0 ? (float) resultCount / nodesVisited : 0.0f;
        }
        
        @Override
        public String toString() {
            return String.format("QueryMetrics[%s: %.2fms, results=%d, nodes=%d, cache=%s, selectivity=%.3f]",
                queryType, executionTimeNanos / 1_000_000.0, resultCount, nodesVisited, 
                cacheHit ? "HIT" : "MISS", selectivity);
        }
    }

    /**
     * Spatial query result cache with LRU eviction
     */
    public static class SpatialQueryCache<Content> {
        private final Map<String, CachedQueryResult<Content>> cache;
        private final int maxSize;
        private long cacheHits = 0;
        private long cacheMisses = 0;
        
        public SpatialQueryCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new LinkedHashMap<String, CachedQueryResult<Content>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedQueryResult<Content>> eldest) {
                    return size() > SpatialQueryCache.this.maxSize;
                }
            };
        }
        
        public void put(String queryKey, List<Content> results, QueryMetrics metrics) {
            cache.put(queryKey, new CachedQueryResult<>(results, metrics, System.currentTimeMillis()));
        }
        
        public CachedQueryResult<Content> get(String queryKey) {
            CachedQueryResult<Content> result = cache.get(queryKey);
            if (result != null) {
                cacheHits++;
                return result;
            } else {
                cacheMisses++;
                return null;
            }
        }
        
        public void clear() {
            cache.clear();
            cacheHits = 0;
            cacheMisses = 0;
        }
        
        public double getCacheHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total : 0.0;
        }
        
        public int size() {
            return cache.size();
        }
        
        public CacheStatistics getStatistics() {
            return new CacheStatistics(cache.size(), maxSize, cacheHits, cacheMisses, getCacheHitRate());
        }
        
        public static class CachedQueryResult<Content> {
            public final List<Content> results;
            public final QueryMetrics metrics;
            public final long timestamp;
            
            public CachedQueryResult(List<Content> results, QueryMetrics metrics, long timestamp) {
                this.results = Collections.unmodifiableList(new ArrayList<>(results));
                this.metrics = metrics;
                this.timestamp = timestamp;
            }
            
            public boolean isExpired(long ttlMillis) {
                return System.currentTimeMillis() - timestamp > ttlMillis;
            }
        }
        
        public static class CacheStatistics {
            public final int currentSize;
            public final int maxSize;
            public final long hits;
            public final long misses;
            public final double hitRate;
            
            public CacheStatistics(int currentSize, int maxSize, long hits, long misses, double hitRate) {
                this.currentSize = currentSize;
                this.maxSize = maxSize;
                this.hits = hits;
                this.misses = misses;
                this.hitRate = hitRate;
            }
            
            @Override
            public String toString() {
                return String.format("CacheStats[size=%d/%d, hits=%d, misses=%d, hit_rate=%.2f%%]",
                    currentSize, maxSize, hits, misses, hitRate * 100);
            }
        }
    }

    /**
     * Query execution planner that optimizes query strategies based on data characteristics
     */
    public static class QueryExecutionPlanner {
        private final Map<String, QueryPlan> planCache = new HashMap<>();
        private final List<QueryMetrics> executionHistory = new ArrayList<>();
        
        /**
         * Create an optimized query plan based on query characteristics and data distribution
         */
        public QueryPlan createQueryPlan(String queryType, Point3f queryCenter, float queryRadius, 
                                       SpatialIndexOptimizer.SpatialDistributionStats dataStats) {
            validatePositiveCoordinates(queryCenter, "queryCenter");
            
            String planKey = String.format("%s_%.1f_%.1f_%.1f_%.1f", queryType, 
                queryCenter.x, queryCenter.y, queryCenter.z, queryRadius);
            
            QueryPlan cachedPlan = planCache.get(planKey);
            if (cachedPlan != null) {
                return cachedPlan;
            }
            
            QueryPlan plan = generateOptimalPlan(queryType, queryCenter, queryRadius, dataStats);
            planCache.put(planKey, plan);
            return plan;
        }
        
        private QueryPlan generateOptimalPlan(String queryType, Point3f queryCenter, float queryRadius,
                                            SpatialIndexOptimizer.SpatialDistributionStats dataStats) {
            QueryStrategy strategy;
            int recommendedLevel;
            boolean useParallelProcessing;
            boolean enableCaching;
            
            // Determine optimal strategy based on query characteristics
            float queryVolume = (4.0f / 3.0f) * (float) Math.PI * queryRadius * queryRadius * queryRadius;
            float dataVolume = dataStats.spanX * dataStats.spanY * dataStats.spanZ;
            float queryVolumeRatio = queryVolume / dataVolume;
            
            if (queryVolumeRatio > 0.5f) {
                // Large query covering significant portion of data space
                strategy = QueryStrategy.FULL_SCAN;
                recommendedLevel = Math.max(10, dataStats.recommendedLevel - 2);
                useParallelProcessing = dataStats.totalPoints > 1000;
                enableCaching = false; // Large queries shouldn't be cached
            } else if (queryVolumeRatio < 0.01f) {
                // Small, focused query
                strategy = QueryStrategy.SPATIAL_INDEX;
                recommendedLevel = Math.min(20, dataStats.recommendedLevel + 2);
                useParallelProcessing = false;
                enableCaching = true;
            } else if (dataStats.clusteredRegions > 2 && dataStats.uniformityScore < 0.3f) {
                // Clustered data - use adaptive strategy
                strategy = QueryStrategy.ADAPTIVE_HIERARCHICAL;
                recommendedLevel = dataStats.recommendedLevel;
                useParallelProcessing = dataStats.totalPoints > 500;
                enableCaching = true;
            } else {
                // Uniform or moderately clustered data
                strategy = QueryStrategy.MORTON_ORDERED;
                recommendedLevel = dataStats.recommendedLevel;
                useParallelProcessing = dataStats.totalPoints > 750;
                enableCaching = queryVolumeRatio < 0.1f;
            }
            
            return new QueryPlan(strategy, recommendedLevel, useParallelProcessing, 
                               enableCaching, calculateEstimatedCost(queryVolumeRatio, dataStats));
        }
        
        private float calculateEstimatedCost(float queryVolumeRatio, SpatialIndexOptimizer.SpatialDistributionStats dataStats) {
            // Simplified cost model: O(log n) for spatial index, O(n) for full scan
            float baseCost = queryVolumeRatio > 0.5f ? 
                dataStats.totalPoints : // Full scan cost
                Math.max(1, (float) Math.log(dataStats.totalPoints) * queryVolumeRatio * 100); // Index cost
            
            // Adjust for clustering
            if (dataStats.clusteredRegions > 2) {
                baseCost *= (1.0f + dataStats.clusteredRegions * 0.1f);
            }
            
            return baseCost;
        }
        
        public void recordExecution(QueryMetrics metrics) {
            executionHistory.add(metrics);
            
            // Keep only recent history to avoid memory bloat
            if (executionHistory.size() > 1000) {
                executionHistory.subList(0, 500).clear();
            }
        }
        
        public List<QueryMetrics> getExecutionHistory() {
            return Collections.unmodifiableList(executionHistory);
        }
        
        public QueryPlannerStatistics getStatistics() {
            return new QueryPlannerStatistics(planCache.size(), executionHistory.size(),
                calculateAverageExecutionTime(), calculateAverageSelectivity());
        }
        
        private double calculateAverageExecutionTime() {
            return executionHistory.stream()
                .mapToLong(m -> m.executionTimeNanos)
                .average()
                .orElse(0.0) / 1_000_000.0; // Convert to milliseconds
        }
        
        private double calculateAverageSelectivity() {
            return executionHistory.stream()
                .mapToDouble(m -> m.selectivity)
                .average()
                .orElse(0.0);
        }
        
        public static class QueryPlannerStatistics {
            public final int cachedPlans;
            public final int executedQueries;
            public final double averageExecutionTimeMs;
            public final double averageSelectivity;
            
            public QueryPlannerStatistics(int cachedPlans, int executedQueries, 
                                        double averageExecutionTimeMs, double averageSelectivity) {
                this.cachedPlans = cachedPlans;
                this.executedQueries = executedQueries;
                this.averageExecutionTimeMs = averageExecutionTimeMs;
                this.averageSelectivity = averageSelectivity;
            }
            
            @Override
            public String toString() {
                return String.format("QueryPlannerStats[plans=%d, queries=%d, avg_time=%.2fms, avg_selectivity=%.3f]",
                    cachedPlans, executedQueries, averageExecutionTimeMs, averageSelectivity);
            }
        }
    }

    /**
     * Query execution plan
     */
    public static class QueryPlan {
        public final QueryStrategy strategy;
        public final int recommendedLevel;
        public final boolean useParallelProcessing;
        public final boolean enableCaching;
        public final float estimatedCost;
        
        public QueryPlan(QueryStrategy strategy, int recommendedLevel, boolean useParallelProcessing,
                        boolean enableCaching, float estimatedCost) {
            this.strategy = strategy;
            this.recommendedLevel = recommendedLevel;
            this.useParallelProcessing = useParallelProcessing;
            this.enableCaching = enableCaching;
            this.estimatedCost = estimatedCost;
        }
        
        @Override
        public String toString() {
            return String.format("QueryPlan[strategy=%s, level=%d, parallel=%s, cache=%s, cost=%.1f]",
                strategy, recommendedLevel, useParallelProcessing, enableCaching, estimatedCost);
        }
    }

    /**
     * Query execution strategies
     */
    public enum QueryStrategy {
        SPATIAL_INDEX,          // Use spatial indexing for small, focused queries
        MORTON_ORDERED,         // Morton-order traversal for spatial locality
        ADAPTIVE_HIERARCHICAL,  // Adaptive hierarchical traversal for clustered data
        FULL_SCAN,             // Full scan for large queries
        PARALLEL_SPATIAL       // Parallel spatial query execution
    }

    /**
     * Optimized spatial query executor with caching and performance monitoring
     */
    public static class OptimizedSpatialQuery<Content> {
        private final Octree<Content> octree;
        private final SpatialQueryCache<Content> cache;
        private final QueryExecutionPlanner planner;
        private final SpatialIndexOptimizer.SpatialDistributionStats dataStats;
        
        public OptimizedSpatialQuery(Octree<Content> octree, int cacheSize) {
            this.octree = octree;
            this.cache = new SpatialQueryCache<>(cacheSize);
            this.planner = new QueryExecutionPlanner();
            this.dataStats = analyzeOctreeDistribution();
        }
        
        /**
         * Execute optimized radius query with caching and performance monitoring
         */
        public QueryResult<Content> radiusQuery(Point3f center, float radius) {
            validatePositiveCoordinates(center, "center");
            
            String queryKey = String.format("radius_%.3f_%.3f_%.3f_%.3f", 
                center.x, center.y, center.z, radius);
            
            // Check cache first
            SpatialQueryCache.CachedQueryResult<Content> cached = cache.get(queryKey);
            if (cached != null && !cached.isExpired(60000)) { // 1 minute TTL
                return new QueryResult<>(cached.results, cached.metrics, true);
            }
            
            // Create query plan
            QueryPlan plan = planner.createQueryPlan("radius", center, radius, dataStats);
            
            // Execute query with timing
            long startTime = System.nanoTime();
            List<Content> results = executeRadiusQuery(center, radius, plan);
            long endTime = System.nanoTime();
            
            // Create metrics
            QueryMetrics metrics = new QueryMetrics("radius", endTime - startTime, 
                results.size(), calculateNodesVisited(center, radius, plan), false);
            
            // Cache results if plan recommends it
            if (plan.enableCaching) {
                cache.put(queryKey, results, metrics);
            }
            
            // Record execution for future planning
            planner.recordExecution(metrics);
            
            return new QueryResult<>(results, metrics, false);
        }
        
        /**
         * Execute optimized range query with spatial bounds
         */
        public QueryResult<Content> rangeQuery(Point3f minBounds, Point3f maxBounds) {
            validatePositiveCoordinates(minBounds, "minBounds");
            validatePositiveCoordinates(maxBounds, "maxBounds");
            
            String queryKey = String.format("range_%.3f_%.3f_%.3f_%.3f_%.3f_%.3f", 
                minBounds.x, minBounds.y, minBounds.z, maxBounds.x, maxBounds.y, maxBounds.z);
            
            // Check cache
            SpatialQueryCache.CachedQueryResult<Content> cached = cache.get(queryKey);
            if (cached != null && !cached.isExpired(60000)) {
                return new QueryResult<>(cached.results, cached.metrics, true);
            }
            
            // Calculate query characteristics
            Point3f center = new Point3f(
                (minBounds.x + maxBounds.x) / 2.0f,
                (minBounds.y + maxBounds.y) / 2.0f,
                (minBounds.z + maxBounds.z) / 2.0f
            );
            float radius = calculateDistance(minBounds, maxBounds) / 2.0f;
            
            QueryPlan plan = planner.createQueryPlan("range", center, radius, dataStats);
            
            // Execute query
            long startTime = System.nanoTime();
            List<Content> results = executeRangeQuery(minBounds, maxBounds, plan);
            long endTime = System.nanoTime();
            
            QueryMetrics metrics = new QueryMetrics("range", endTime - startTime, 
                results.size(), calculateNodesVisited(center, radius, plan), false);
            
            if (plan.enableCaching) {
                cache.put(queryKey, results, metrics);
            }
            
            planner.recordExecution(metrics);
            
            return new QueryResult<>(results, metrics, false);
        }
        
        /**
         * Execute optimized nearest neighbor query
         */
        public QueryResult<Content> nearestNeighborQuery(Point3f queryPoint, int k) {
            validatePositiveCoordinates(queryPoint, "queryPoint");
            
            String queryKey = String.format("knn_%d_%.3f_%.3f_%.3f", k, 
                queryPoint.x, queryPoint.y, queryPoint.z);
            
            // Check cache
            SpatialQueryCache.CachedQueryResult<Content> cached = cache.get(queryKey);
            if (cached != null && !cached.isExpired(30000)) { // Shorter TTL for NN queries
                return new QueryResult<>(cached.results, cached.metrics, true);
            }
            
            // Estimate query radius based on data density
            float estimatedRadius = estimateKNNRadius(k);
            QueryPlan plan = planner.createQueryPlan("knn", queryPoint, estimatedRadius, dataStats);
            
            // Execute query
            long startTime = System.nanoTime();
            List<Content> results = executeNearestNeighborQuery(queryPoint, k, plan);
            long endTime = System.nanoTime();
            
            QueryMetrics metrics = new QueryMetrics("knn", endTime - startTime, 
                results.size(), calculateNodesVisited(queryPoint, estimatedRadius, plan), false);
            
            if (plan.enableCaching && k <= 10) { // Only cache small NN queries
                cache.put(queryKey, results, metrics);
            }
            
            planner.recordExecution(metrics);
            
            return new QueryResult<>(results, metrics, false);
        }
        
        private List<Content> executeRadiusQuery(Point3f center, float radius, QueryPlan plan) {
            // Implementation would depend on the chosen strategy
            // This is a simplified version that filters all octree contents
            List<Content> results = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                Point3f cubeCenter = new Point3f(
                    cube.originX() + cube.extent() / 2.0f,
                    cube.originY() + cube.extent() / 2.0f,
                    cube.originZ() + cube.extent() / 2.0f
                );
                
                if (calculateDistance(center, cubeCenter) <= radius) {
                    results.add(entry.getValue());
                }
            }
            
            return results;
        }
        
        private List<Content> executeRangeQuery(Point3f minBounds, Point3f maxBounds, QueryPlan plan) {
            List<Content> results = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                
                // Check if cube intersects with query bounds
                if (cube.originX() <= maxBounds.x && cube.originX() + cube.extent() >= minBounds.x &&
                    cube.originY() <= maxBounds.y && cube.originY() + cube.extent() >= minBounds.y &&
                    cube.originZ() <= maxBounds.z && cube.originZ() + cube.extent() >= minBounds.z) {
                    results.add(entry.getValue());
                }
            }
            
            return results;
        }
        
        private List<Content> executeNearestNeighborQuery(Point3f queryPoint, int k, QueryPlan plan) {
            // Simplified k-NN implementation using distance sorting
            List<Map.Entry<Float, Content>> candidates = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                Point3f cubeCenter = new Point3f(
                    cube.originX() + cube.extent() / 2.0f,
                    cube.originY() + cube.extent() / 2.0f,
                    cube.originZ() + cube.extent() / 2.0f
                );
                
                float distance = calculateDistance(queryPoint, cubeCenter);
                candidates.add(new AbstractMap.SimpleEntry<>(distance, entry.getValue()));
            }
            
            return candidates.stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(k)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        }
        
        private SpatialIndexOptimizer.SpatialDistributionStats analyzeOctreeDistribution() {
            List<Point3f> points = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                Point3f cubeCenter = new Point3f(
                    cube.originX() + cube.extent() / 2.0f,
                    cube.originY() + cube.extent() / 2.0f,
                    cube.originZ() + cube.extent() / 2.0f
                );
                points.add(cubeCenter);
            }
            
            return points.isEmpty() ? 
                new SpatialIndexOptimizer.SpatialDistributionStats(0, 0, 0, 0, 0, 0, 0, (byte) 10, 0, 1.0f) :
                SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(points);
        }
        
        private int calculateNodesVisited(Point3f center, float radius, QueryPlan plan) {
            // Simplified estimate based on strategy
            return switch (plan.strategy) {
                case SPATIAL_INDEX -> Math.max(1, (int) Math.log(octree.getMap().size()));
                case MORTON_ORDERED -> Math.max(1, octree.getMap().size() / 4);
                case ADAPTIVE_HIERARCHICAL -> Math.max(1, octree.getMap().size() / 3);
                case FULL_SCAN -> octree.getMap().size();
                case PARALLEL_SPATIAL -> Math.max(1, octree.getMap().size() / 2);
            };
        }
        
        private float estimateKNNRadius(int k) {
            if (dataStats.totalPoints == 0) return 100.0f;
            
            // Rough estimate based on uniform distribution assumption
            float volume = dataStats.spanX * dataStats.spanY * dataStats.spanZ;
            float density = dataStats.totalPoints / volume;
            return (float) Math.pow(k / (density * Math.PI * 4.0 / 3.0), 1.0/3.0);
        }
        
        public SpatialQueryCache.CacheStatistics getCacheStatistics() {
            return cache.getStatistics();
        }
        
        public QueryExecutionPlanner.QueryPlannerStatistics getPlannerStatistics() {
            return planner.getStatistics();
        }
        
        public void clearCache() {
            cache.clear();
        }
    }

    /**
     * Query result with performance metrics
     */
    public static class QueryResult<Content> {
        public final List<Content> results;
        public final QueryMetrics metrics;
        public final boolean fromCache;
        
        public QueryResult(List<Content> results, QueryMetrics metrics, boolean fromCache) {
            this.results = Collections.unmodifiableList(new ArrayList<>(results));
            this.metrics = metrics;
            this.fromCache = fromCache;
        }
    }

    /**
     * Query performance benchmark utilities
     */
    public static class QueryPerformanceBenchmark {
        
        public static class BenchmarkResult {
            public final String queryType;
            public final int dataSize;
            public final long totalTimeNanos;
            public final double averageTimeMs;
            public final double throughput; // queries per second
            public final int sampleSize;
            
            public BenchmarkResult(String queryType, int dataSize, long totalTimeNanos, int sampleSize) {
                this.queryType = queryType;
                this.dataSize = dataSize;
                this.totalTimeNanos = totalTimeNanos;
                this.sampleSize = sampleSize;
                this.averageTimeMs = totalTimeNanos / 1_000_000.0 / sampleSize;
                this.throughput = sampleSize > 0 ? 1_000_000_000.0 * sampleSize / totalTimeNanos : 0.0;
            }
            
            @Override
            public String toString() {
                return String.format("QueryBenchmark[%s: data=%d, samples=%d, avg=%.2fms, throughput=%.1f queries/sec]",
                    queryType, dataSize, sampleSize, averageTimeMs, throughput);
            }
        }
        
        public static BenchmarkResult benchmarkRadiusQueries(OptimizedSpatialQuery<?> queryEngine, 
                                                            List<Point3f> queryPoints, float radius) {
            long startTime = System.nanoTime();
            
            for (Point3f point : queryPoints) {
                queryEngine.radiusQuery(point, radius);
            }
            
            long endTime = System.nanoTime();
            return new BenchmarkResult("Radius Query", queryEngine.octree.getMap().size(), 
                                     endTime - startTime, queryPoints.size());
        }
        
        public static BenchmarkResult benchmarkKNNQueries(OptimizedSpatialQuery<?> queryEngine, 
                                                         List<Point3f> queryPoints, int k) {
            long startTime = System.nanoTime();
            
            for (Point3f point : queryPoints) {
                queryEngine.nearestNeighborQuery(point, k);
            }
            
            long endTime = System.nanoTime();
            return new BenchmarkResult("K-NN Query", queryEngine.octree.getMap().size(), 
                                     endTime - startTime, queryPoints.size());
        }
        
        public static List<Point3f> generateRandomQueryPoints(int count, float minCoord, float maxCoord) {
            List<Point3f> points = new ArrayList<>();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            
            for (int i = 0; i < count; i++) {
                float x = minCoord + random.nextFloat() * (maxCoord - minCoord);
                float y = minCoord + random.nextFloat() * (maxCoord - minCoord);
                float z = minCoord + random.nextFloat() * (maxCoord - minCoord);
                points.add(new Point3f(x, y, z));
            }
            
            return points;
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
}