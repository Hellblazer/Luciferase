package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Query Optimizer functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class QueryOptimizerTest {

    private Octree<String> octree;
    private QueryOptimizer.OptimizedSpatialQuery<String> queryEngine;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new TreeMap<>());
        
        // Insert test data points in various spatial patterns
        insertTestData();
        
        // Create query engine with moderate cache size
        queryEngine = new QueryOptimizer.OptimizedSpatialQuery<>(octree, 100);
    }
    
    private void insertTestData() {
        // Dense cluster around (200, 200, 200)
        for (int i = 0; i < 20; i++) {
            float x = 180.0f + i * 2.0f;
            float y = 180.0f + i * 2.0f;
            float z = 180.0f + i * 2.0f;
            octree.insert(new Point3f(x, y, z), testLevel, "Dense_" + i);
        }
        
        // Sparse points distributed widely
        octree.insert(new Point3f(50.0f, 50.0f, 50.0f), testLevel, "Sparse_1");
        octree.insert(new Point3f(500.0f, 500.0f, 500.0f), testLevel, "Sparse_2");
        octree.insert(new Point3f(100.0f, 300.0f, 200.0f), testLevel, "Sparse_3");
        octree.insert(new Point3f(400.0f, 150.0f, 350.0f), testLevel, "Sparse_4");
        
        // Medium density cluster around (350, 350, 350)
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 15; i++) {
            float x = 320.0f + random.nextFloat() * 60.0f;
            float y = 320.0f + random.nextFloat() * 60.0f;
            float z = 320.0f + random.nextFloat() * 60.0f;
            octree.insert(new Point3f(x, y, z), testLevel, "Medium_" + i);
        }
    }

    @Test
    void testQueryMetrics() {
        QueryOptimizer.QueryMetrics metrics = new QueryOptimizer.QueryMetrics(
            "test", 1_000_000L, 10, 50, false);
        
        assertEquals("test", metrics.queryType);
        assertEquals(1_000_000L, metrics.executionTimeNanos);
        assertEquals(10, metrics.resultCount);
        assertEquals(50, metrics.nodesVisited);
        assertFalse(metrics.cacheHit);
        assertEquals(0.2f, metrics.selectivity, 0.001f);
        
        String metricsStr = metrics.toString();
        assertTrue(metricsStr.contains("test"));
        assertTrue(metricsStr.contains("1.00ms"));
        assertTrue(metricsStr.contains("results=10"));
        assertTrue(metricsStr.contains("MISS"));
    }

    @Test
    void testSpatialQueryCache() {
        QueryOptimizer.SpatialQueryCache<String> cache = new QueryOptimizer.SpatialQueryCache<>(5);
        
        // Initially empty
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.getCacheHitRate());
        
        // Add some cached results
        List<String> results1 = List.of("result1", "result2");
        QueryOptimizer.QueryMetrics metrics1 = new QueryOptimizer.QueryMetrics("test", 1000000L, 2, 10, false);
        cache.put("query1", results1, metrics1);
        
        assertEquals(1, cache.size());
        
        // Test cache hit
        QueryOptimizer.SpatialQueryCache.CachedQueryResult<String> cached = cache.get("query1");
        assertNotNull(cached);
        assertEquals(2, cached.results.size());
        assertEquals("result1", cached.results.get(0));
        assertFalse(cached.isExpired(60000)); // Not expired within 1 minute
        
        // Test cache miss
        assertNull(cache.get("nonexistent"));
        
        // Test hit rate calculation
        cache.get("query1"); // Another hit
        cache.get("missing"); // Another miss
        assertEquals(0.5, cache.getCacheHitRate(), 0.001); // 2 hits out of 4 total accesses
        
        // Test cache statistics
        QueryOptimizer.SpatialQueryCache.CacheStatistics stats = cache.getStatistics();
        assertEquals(1, stats.currentSize);
        assertEquals(5, stats.maxSize);
        assertEquals(2, stats.hits);
        assertEquals(2, stats.misses);
        
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("size=1/5"));
        assertTrue(statsStr.contains("hits=2"));
        
        // Test cache clearing
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.getCacheHitRate());
    }

    @Test
    void testQueryExecutionPlanner() {
        QueryOptimizer.QueryExecutionPlanner planner = new QueryOptimizer.QueryExecutionPlanner();
        
        // Create test data stats
        SpatialIndexOptimizer.SpatialDistributionStats dataStats = 
            new SpatialIndexOptimizer.SpatialDistributionStats(100, 50.0f, 500.0f, 50.0f, 500.0f, 
                50.0f, 500.0f, (byte) 15, 2, 0.7f);
        
        Point3f queryCenter = new Point3f(200.0f, 200.0f, 200.0f);
        
        // Test small query (should use spatial index)
        QueryOptimizer.QueryPlan smallQueryPlan = planner.createQueryPlan("radius", queryCenter, 10.0f, dataStats);
        assertNotNull(smallQueryPlan);
        assertEquals(QueryOptimizer.QueryStrategy.SPATIAL_INDEX, smallQueryPlan.strategy);
        assertTrue(smallQueryPlan.enableCaching);
        assertFalse(smallQueryPlan.useParallelProcessing);
        
        // Test large query (should use full scan)
        QueryOptimizer.QueryPlan largeQueryPlan = planner.createQueryPlan("radius", queryCenter, 1000.0f, dataStats);
        assertNotNull(largeQueryPlan);
        assertEquals(QueryOptimizer.QueryStrategy.FULL_SCAN, largeQueryPlan.strategy);
        assertFalse(largeQueryPlan.enableCaching);
        
        // Test plan caching (same query should return cached plan)
        QueryOptimizer.QueryPlan cachedPlan = planner.createQueryPlan("radius", queryCenter, 10.0f, dataStats);
        assertSame(smallQueryPlan, cachedPlan);
        
        // Test metrics recording
        QueryOptimizer.QueryMetrics testMetrics = new QueryOptimizer.QueryMetrics("test", 5000000L, 15, 100, false);
        planner.recordExecution(testMetrics);
        
        List<QueryOptimizer.QueryMetrics> history = planner.getExecutionHistory();
        assertEquals(1, history.size());
        assertEquals(testMetrics, history.get(0));
        
        // Test planner statistics
        QueryOptimizer.QueryExecutionPlanner.QueryPlannerStatistics plannerStats = planner.getStatistics();
        assertTrue(plannerStats.cachedPlans >= 2); // At least the two plans we created
        assertEquals(1, plannerStats.executedQueries);
        assertEquals(5.0, plannerStats.averageExecutionTimeMs, 0.001);
        assertEquals(0.15, plannerStats.averageSelectivity, 0.001);
        
        String plannerStatsStr = plannerStats.toString();
        assertTrue(plannerStatsStr.contains("plans="));
        assertTrue(plannerStatsStr.contains("queries=1"));
    }

    @Test
    void testQueryPlan() {
        QueryOptimizer.QueryPlan plan = new QueryOptimizer.QueryPlan(
            QueryOptimizer.QueryStrategy.MORTON_ORDERED, 16, true, false, 25.5f);
        
        assertEquals(QueryOptimizer.QueryStrategy.MORTON_ORDERED, plan.strategy);
        assertEquals(16, plan.recommendedLevel);
        assertTrue(plan.useParallelProcessing);
        assertFalse(plan.enableCaching);
        assertEquals(25.5f, plan.estimatedCost, 0.001f);
        
        String planStr = plan.toString();
        assertTrue(planStr.contains("MORTON_ORDERED"));
        assertTrue(planStr.contains("level=16"));
        assertTrue(planStr.contains("parallel=true"));
        assertTrue(planStr.contains("cache=false"));
    }

    @Test
    void testOptimizedRadiusQuery() {
        Point3f queryCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float radius = 50.0f;
        
        // First query should miss cache
        QueryOptimizer.QueryResult<String> result1 = queryEngine.radiusQuery(queryCenter, radius);
        assertNotNull(result1);
        assertNotNull(result1.results);
        assertNotNull(result1.metrics);
        assertFalse(result1.fromCache);
        assertEquals("radius", result1.metrics.queryType);
        assertTrue(result1.metrics.executionTimeNanos > 0);
        
        // Second identical query should hit cache (if caching is enabled for this query size)
        QueryOptimizer.QueryResult<String> result2 = queryEngine.radiusQuery(queryCenter, radius);
        assertNotNull(result2);
        assertEquals(result1.results.size(), result2.results.size());
        
        // Query with different parameters
        QueryOptimizer.QueryResult<String> result3 = queryEngine.radiusQuery(queryCenter, radius * 2);
        assertNotNull(result3);
        assertFalse(result3.fromCache);
        // Larger radius should generally find more results
        assertTrue(result3.results.size() >= result1.results.size());
    }

    @Test
    void testOptimizedRangeQuery() {
        Point3f minBounds = new Point3f(150.0f, 150.0f, 150.0f);
        Point3f maxBounds = new Point3f(250.0f, 250.0f, 250.0f);
        
        QueryOptimizer.QueryResult<String> result = queryEngine.rangeQuery(minBounds, maxBounds);
        
        assertNotNull(result);
        assertNotNull(result.results);
        assertNotNull(result.metrics);
        assertEquals("range", result.metrics.queryType);
        assertTrue(result.metrics.executionTimeNanos > 0);
        
        // Results should be non-empty since we have data in that range
        assertTrue(result.results.size() > 0);
        
        // Verify all results are actually in range by checking if they have "Dense" prefix
        boolean hasDenseResults = result.results.stream().anyMatch(s -> s.startsWith("Dense"));
        assertTrue(hasDenseResults);
    }

    @Test
    void testOptimizedNearestNeighborQuery() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        int k = 5;
        
        QueryOptimizer.QueryResult<String> result = queryEngine.nearestNeighborQuery(queryPoint, k);
        
        assertNotNull(result);
        assertNotNull(result.results);
        assertNotNull(result.metrics);
        assertEquals("knn", result.metrics.queryType);
        assertTrue(result.metrics.executionTimeNanos > 0);
        
        // Should find exactly k results (or fewer if total data < k)
        assertTrue(result.results.size() <= k);
        assertTrue(result.results.size() <= octree.getMap().size());
        
        // Test with k=1
        QueryOptimizer.QueryResult<String> singleResult = queryEngine.nearestNeighborQuery(queryPoint, 1);
        assertEquals(1, singleResult.results.size());
    }

    @Test
    void testQueryPerformanceBenchmark() {
        // Generate test query points
        List<Point3f> queryPoints = QueryOptimizer.QueryPerformanceBenchmark.generateRandomQueryPoints(
            10, 100.0f, 400.0f);
        
        assertEquals(10, queryPoints.size());
        
        // Verify all points have positive coordinates
        for (Point3f point : queryPoints) {
            assertTrue(point.x >= 100.0f);
            assertTrue(point.y >= 100.0f);
            assertTrue(point.z >= 100.0f);
            assertTrue(point.x <= 400.0f);
            assertTrue(point.y <= 400.0f);
            assertTrue(point.z <= 400.0f);
        }
        
        // Benchmark radius queries
        QueryOptimizer.QueryPerformanceBenchmark.BenchmarkResult radiusResult = 
            QueryOptimizer.QueryPerformanceBenchmark.benchmarkRadiusQueries(queryEngine, queryPoints, 50.0f);
        
        assertNotNull(radiusResult);
        assertEquals("Radius Query", radiusResult.queryType);
        assertEquals(10, radiusResult.sampleSize);
        assertTrue(radiusResult.totalTimeNanos > 0);
        assertTrue(radiusResult.averageTimeMs > 0);
        assertTrue(radiusResult.throughput > 0);
        
        String resultStr = radiusResult.toString();
        assertTrue(resultStr.contains("Radius Query"));
        assertTrue(resultStr.contains("samples=10"));
        
        // Benchmark K-NN queries
        QueryOptimizer.QueryPerformanceBenchmark.BenchmarkResult knnResult = 
            QueryOptimizer.QueryPerformanceBenchmark.benchmarkKNNQueries(queryEngine, queryPoints, 3);
        
        assertNotNull(knnResult);
        assertEquals("K-NN Query", knnResult.queryType);
        assertEquals(10, knnResult.sampleSize);
        assertTrue(knnResult.totalTimeNanos > 0);
    }

    @Test
    void testCacheAndPlannerStatistics() {
        // Perform several queries to populate cache and planner history
        Point3f basePoint = new Point3f(200.0f, 200.0f, 200.0f);
        
        for (int i = 0; i < 5; i++) {
            Point3f queryPoint = new Point3f(basePoint.x + i * 10.0f, basePoint.y, basePoint.z);
            queryEngine.radiusQuery(queryPoint, 30.0f);
            queryEngine.nearestNeighborQuery(queryPoint, 3);
        }
        
        // Check cache statistics
        QueryOptimizer.SpatialQueryCache.CacheStatistics cacheStats = queryEngine.getCacheStatistics();
        assertNotNull(cacheStats);
        assertTrue(cacheStats.currentSize >= 0);
        assertTrue(cacheStats.maxSize > 0);
        
        // Check planner statistics
        QueryOptimizer.QueryExecutionPlanner.QueryPlannerStatistics plannerStats = queryEngine.getPlannerStatistics();
        assertNotNull(plannerStats);
        assertTrue(plannerStats.executedQueries >= 10); // At least 10 queries (5 radius + 5 knn)
        assertTrue(plannerStats.averageExecutionTimeMs >= 0);
        assertTrue(plannerStats.averageSelectivity >= 0);
    }

    @Test
    void testCacheClearAndTTL() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        
        // Perform a query
        QueryOptimizer.QueryResult<String> result1 = queryEngine.radiusQuery(queryPoint, 25.0f);
        
        // Clear cache
        queryEngine.clearCache();
        
        // Same query should miss cache now
        QueryOptimizer.QueryResult<String> result2 = queryEngine.radiusQuery(queryPoint, 25.0f);
        assertFalse(result2.fromCache);
        
        // Cache statistics should show cache was cleared
        QueryOptimizer.SpatialQueryCache.CacheStatistics statsAfterClear = queryEngine.getCacheStatistics();
        assertEquals(0.0, statsAfterClear.hitRate, 0.001);
    }

    @Test
    void testQueryResultImmutability() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        QueryOptimizer.QueryResult<String> result = queryEngine.radiusQuery(queryPoint, 50.0f);
        
        // Results list should be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> {
            result.results.add("new item");
        });
        
        assertThrows(UnsupportedOperationException.class, () -> {
            result.results.clear();
        });
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativePoint = new Point3f(-100.0f, 200.0f, 200.0f);
        
        // All query types should reject negative coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            queryEngine.radiusQuery(negativePoint, 50.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            queryEngine.nearestNeighborQuery(negativePoint, 5);
        });
        
        Point3f positiveBounds = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f negativeBounds = new Point3f(-50.0f, 150.0f, 150.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            queryEngine.rangeQuery(negativeBounds, positiveBounds);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            queryEngine.rangeQuery(positiveBounds, negativeBounds);
        });
    }

    @Test
    void testRandomQueryPointGeneration() {
        List<Point3f> points = QueryOptimizer.QueryPerformanceBenchmark.generateRandomQueryPoints(100, 50.0f, 300.0f);
        
        assertEquals(100, points.size());
        
        for (Point3f point : points) {
            assertTrue(point.x >= 50.0f && point.x <= 300.0f);
            assertTrue(point.y >= 50.0f && point.y <= 300.0f);
            assertTrue(point.z >= 50.0f && point.z <= 300.0f);
        }
        
        // Test edge case: single point
        List<Point3f> singlePoint = QueryOptimizer.QueryPerformanceBenchmark.generateRandomQueryPoints(1, 100.0f, 100.1f);
        assertEquals(1, singlePoint.size());
    }

    @Test
    void testQueryPlannerWithDifferentDataDistributions() {
        QueryOptimizer.QueryExecutionPlanner planner = new QueryOptimizer.QueryExecutionPlanner();
        Point3f queryCenter = new Point3f(200.0f, 200.0f, 200.0f);
        
        // Test with highly clustered data
        SpatialIndexOptimizer.SpatialDistributionStats clusteredStats = 
            new SpatialIndexOptimizer.SpatialDistributionStats(1000, 100.0f, 300.0f, 100.0f, 300.0f, 
                100.0f, 300.0f, (byte) 14, 5, 0.2f); // Low uniformity, many clusters
        
        QueryOptimizer.QueryPlan clusteredPlan = planner.createQueryPlan("radius", queryCenter, 50.0f, clusteredStats);
        assertEquals(QueryOptimizer.QueryStrategy.ADAPTIVE_HIERARCHICAL, clusteredPlan.strategy);
        
        // Test with uniform data
        SpatialIndexOptimizer.SpatialDistributionStats uniformStats = 
            new SpatialIndexOptimizer.SpatialDistributionStats(1000, 100.0f, 500.0f, 100.0f, 500.0f, 
                100.0f, 500.0f, (byte) 15, 1, 0.8f); // High uniformity, few clusters
        
        QueryOptimizer.QueryPlan uniformPlan = planner.createQueryPlan("radius", queryCenter, 50.0f, uniformStats);
        // Uniform data should use either MORTON_ORDERED or ADAPTIVE_HIERARCHICAL
        assertTrue(uniformPlan.strategy == QueryOptimizer.QueryStrategy.MORTON_ORDERED ||
                  uniformPlan.strategy == QueryOptimizer.QueryStrategy.ADAPTIVE_HIERARCHICAL);
        
        // Test with sparse data
        SpatialIndexOptimizer.SpatialDistributionStats sparseStats = 
            new SpatialIndexOptimizer.SpatialDistributionStats(50, 100.0f, 1000.0f, 100.0f, 1000.0f, 
                100.0f, 1000.0f, (byte) 12, 1, 0.9f); // Few points, large space
        
        QueryOptimizer.QueryPlan sparsePlan = planner.createQueryPlan("radius", queryCenter, 50.0f, sparseStats);
        // Sparse data can use any reasonable strategy, just verify it's a valid one
        assertNotNull(sparsePlan.strategy);
    }

    @Test
    void testEmptyOctreeQueries() {
        Octree<String> emptyOctree = new Octree<>(new TreeMap<>());
        QueryOptimizer.OptimizedSpatialQuery<String> emptyQueryEngine = 
            new QueryOptimizer.OptimizedSpatialQuery<>(emptyOctree, 10);
        
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        
        // All queries on empty octree should return empty results
        QueryOptimizer.QueryResult<String> radiusResult = emptyQueryEngine.radiusQuery(queryPoint, 100.0f);
        assertTrue(radiusResult.results.isEmpty());
        
        QueryOptimizer.QueryResult<String> knnResult = emptyQueryEngine.nearestNeighborQuery(queryPoint, 5);
        assertTrue(knnResult.results.isEmpty());
        
        Point3f minBounds = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f maxBounds = new Point3f(300.0f, 300.0f, 300.0f);
        QueryOptimizer.QueryResult<String> rangeResult = emptyQueryEngine.rangeQuery(minBounds, maxBounds);
        assertTrue(rangeResult.results.isEmpty());
    }

    @Test
    void testCacheTTLExpiration() throws InterruptedException {
        // This test would need to be adapted to work with shorter TTL for testing
        // Currently using production TTL values, so we'll just test the expiration mechanism
        
        QueryOptimizer.SpatialQueryCache<String> cache = new QueryOptimizer.SpatialQueryCache<>(10);
        List<String> results = List.of("test");
        QueryOptimizer.QueryMetrics metrics = new QueryOptimizer.QueryMetrics("test", 1000000L, 1, 5, false);
        
        cache.put("test_query", results, metrics);
        
        QueryOptimizer.SpatialQueryCache.CachedQueryResult<String> cached = cache.get("test_query");
        assertNotNull(cached);
        
        // Test expiration - wait a bit to ensure timestamp difference
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        assertTrue(cached.isExpired(0)); // 0ms TTL should always be expired
        assertFalse(cached.isExpired(Long.MAX_VALUE)); // Very long TTL should never expire
    }
}