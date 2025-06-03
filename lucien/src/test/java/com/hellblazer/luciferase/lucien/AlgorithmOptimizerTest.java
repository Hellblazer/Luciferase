package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Algorithm Optimizer functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class AlgorithmOptimizerTest {

    private Octree<String> octree;
    private SpatialIndexOptimizer.SpatialDistributionStats dataStats;
    private AlgorithmOptimizer.AdvancedSpatialSearch.AdaptiveKNearestNeighbor<String> adaptiveKNN;
    private AlgorithmOptimizer.AdvancedSpatialSearch.AdaptiveRangeQuery<String> adaptiveRange;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new TreeMap<>());
        
        // Insert test data with known spatial patterns
        insertTestData();
        
        // Generate spatial distribution stats
        List<Point3f> points = extractPointsFromOctree();
        dataStats = SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(points);
        
        // Create adaptive search engines
        adaptiveKNN = new AlgorithmOptimizer.AdvancedSpatialSearch.AdaptiveKNearestNeighbor<>(octree, dataStats);
        adaptiveRange = new AlgorithmOptimizer.AdvancedSpatialSearch.AdaptiveRangeQuery<>(octree);
    }

    @AfterEach
    void tearDown() {
        if (octree != null) {
            octree.getMap().clear();
        }
    }

    private void insertTestData() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // Dense cluster around (200, 200, 200)
        for (int i = 0; i < 30; i++) {
            float x = 180.0f + random.nextFloat() * 40.0f;
            float y = 180.0f + random.nextFloat() * 40.0f;
            float z = 180.0f + random.nextFloat() * 40.0f;
            octree.insert(new Point3f(x, y, z), testLevel, "Dense_" + i);
        }
        
        // Medium density cluster around (400, 400, 400)
        for (int i = 0; i < 20; i++) {
            float x = 380.0f + random.nextFloat() * 40.0f;
            float y = 380.0f + random.nextFloat() * 40.0f;
            float z = 380.0f + random.nextFloat() * 40.0f;
            octree.insert(new Point3f(x, y, z), testLevel, "Medium_" + i);
        }
        
        // Sparse points distributed widely
        for (int i = 0; i < 25; i++) {
            float x = 50.0f + random.nextFloat() * 500.0f;
            float y = 50.0f + random.nextFloat() * 500.0f;
            float z = 50.0f + random.nextFloat() * 500.0f;
            octree.insert(new Point3f(x, y, z), testLevel, "Sparse_" + i);
        }
    }

    private List<Point3f> extractPointsFromOctree() {
        List<Point3f> points = new ArrayList<>();
        for (Long key : octree.getMap().keySet()) {
            Spatial.Cube cube = Octree.toCube(key);
            points.add(new Point3f(
                cube.originX() + cube.extent() / 2.0f,
                cube.originY() + cube.extent() / 2.0f,
                cube.originZ() + cube.extent() / 2.0f
            ));
        }
        return points;
    }

    @Test
    void testAdaptiveKNearestNeighbor() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        int k = 5;
        
        AlgorithmOptimizer.AdvancedSpatialSearch.KNNResult<String> result = 
            adaptiveKNN.findKNearest(queryPoint, k);
        
        assertNotNull(result);
        assertNotNull(result.results);
        assertTrue(result.results.size() <= k);
        assertTrue(result.results.size() <= octree.getMap().size());
        assertTrue(result.executionTimeNanos > 0);
        assertNotNull(result.strategyUsed);
        assertTrue(result.getThroughputItemsPerSecond() > 0);
        
        // Results should be sorted by distance
        for (int i = 1; i < result.results.size(); i++) {
            assertTrue(result.results.get(i-1).distance <= result.results.get(i).distance);
        }
        
        String resultStr = result.toString();
        assertTrue(resultStr.contains("KNNResult"));
        assertTrue(resultStr.contains("strategy="));
        assertTrue(resultStr.contains("items=" + result.results.size()));
        
        // Test with different k values
        AlgorithmOptimizer.AdvancedSpatialSearch.KNNResult<String> smallK = 
            adaptiveKNN.findKNearest(queryPoint, 1);
        assertEquals(1, smallK.results.size());
        
        AlgorithmOptimizer.AdvancedSpatialSearch.KNNResult<String> largeK = 
            adaptiveKNN.findKNearest(queryPoint, 20);
        assertTrue(largeK.results.size() <= 20);
        assertTrue(largeK.results.size() <= octree.getMap().size());
    }

    @Test
    void testAdaptiveKNNStrategies() {
        Point3f queryPoint = new Point3f(300.0f, 300.0f, 300.0f);
        
        // Execute multiple queries to build performance history
        for (int i = 0; i < 10; i++) {
            Point3f testPoint = new Point3f(
                queryPoint.x + (i - 5) * 10.0f,
                queryPoint.y + (i - 5) * 10.0f,
                queryPoint.z + (i - 5) * 10.0f
            );
            adaptiveKNN.findKNearest(testPoint, 3);
        }
        
        // Check that performance metrics are being tracked
        Map<AlgorithmOptimizer.AdvancedSpatialSearch.SearchStrategy, AlgorithmOptimizer.PerformanceMetrics> metrics = 
            adaptiveKNN.getPerformanceMetrics();
        
        assertNotNull(metrics);
        assertFalse(metrics.isEmpty());
        
        // At least one strategy should have been used
        assertTrue(metrics.values().stream().anyMatch(m -> m.getTotalExecutions() > 0));
        
        for (AlgorithmOptimizer.PerformanceMetrics metric : metrics.values()) {
            if (metric.getTotalExecutions() > 0) {
                assertTrue(metric.getAverageTimeNanos() > 0);
                assertTrue(metric.getAverageThroughput() >= 0);
                
                String metricStr = metric.toString();
                assertTrue(metricStr.contains("PerformanceMetrics"));
                assertTrue(metricStr.contains("executions="));
            }
        }
    }

    @Test
    void testAdaptiveRangeQuery() {
        Point3f minBounds = new Point3f(150.0f, 150.0f, 150.0f);
        Point3f maxBounds = new Point3f(250.0f, 250.0f, 250.0f);
        
        AlgorithmOptimizer.AdvancedSpatialSearch.RangeQueryResult<String> result = 
            adaptiveRange.rangeQuery(minBounds, maxBounds);
        
        assertNotNull(result);
        assertNotNull(result.results);
        assertNotNull(result.bounds);
        assertTrue(result.executionTimeNanos > 0);
        assertTrue(result.nodesVisited > 0);
        assertTrue(result.getSelectivity() >= 0.0 && result.getSelectivity() <= 1.0);
        
        // Results should be non-empty since we have data in that range
        assertTrue(result.results.size() > 0);
        
        String resultStr = result.toString();
        assertTrue(resultStr.contains("RangeResult"));
        assertTrue(resultStr.contains("items=" + result.results.size()));
        assertTrue(resultStr.contains("selectivity="));
        
        // Test with larger range
        Point3f largeBounds = new Point3f(500.0f, 500.0f, 500.0f);
        AlgorithmOptimizer.AdvancedSpatialSearch.RangeQueryResult<String> largeResult = 
            adaptiveRange.rangeQuery(minBounds, largeBounds);
        
        assertTrue(largeResult.results.size() >= result.results.size());
        assertTrue(largeResult.getSelectivity() >= result.getSelectivity());
    }

    @Test
    void testBoundsOptimizer() {
        AlgorithmOptimizer.BoundsOptimizer optimizer = new AlgorithmOptimizer.BoundsOptimizer();
        
        Point3f minBounds = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f maxBounds = new Point3f(300.0f, 300.0f, 300.0f);
        
        AlgorithmOptimizer.OptimizedBounds optimized = optimizer.optimizeBounds(minBounds, maxBounds, octree);
        
        assertNotNull(optimized);
        assertTrue(optimized.getSelectivity() >= 0.0f && optimized.getSelectivity() <= 1.0f);
        assertTrue(optimized.getExpectedResults() >= 0);
        assertTrue(optimized.getExpectedResults() <= octree.getMap().size());
        
        // Test intersection logic
        for (Long key : octree.getMap().keySet()) {
            Spatial.Cube cube = Octree.toCube(key);
            boolean shouldIntersect = 
                cube.originX() <= maxBounds.x && cube.originX() + cube.extent() >= minBounds.x &&
                cube.originY() <= maxBounds.y && cube.originY() + cube.extent() >= minBounds.y &&
                cube.originZ() <= maxBounds.z && cube.originZ() + cube.extent() >= minBounds.z;
            
            assertEquals(shouldIntersect, optimized.intersects(cube));
        }
        
        String optimizedStr = optimized.toString();
        assertTrue(optimizedStr.contains("OptimizedBounds"));
        assertTrue(optimizedStr.contains("selectivity="));
        assertTrue(optimizedStr.contains("expected="));
        
        // Test with small bounds (should be highly selective)
        Point3f smallMax = new Point3f(110.0f, 110.0f, 110.0f);
        AlgorithmOptimizer.OptimizedBounds smallBounds = optimizer.optimizeBounds(minBounds, smallMax, octree);
        assertTrue(smallBounds.getSelectivity() < optimized.getSelectivity());
    }

    @Test
    void testPerformanceMetrics() {
        AlgorithmOptimizer.PerformanceMetrics metrics = new AlgorithmOptimizer.PerformanceMetrics();
        
        // Initially empty
        assertEquals(0, metrics.getTotalExecutions());
        assertEquals(0.0, metrics.getAverageTimeNanos());
        assertEquals(0.0, metrics.getAverageThroughput());
        assertEquals(0, metrics.getMinTimeNanos());
        assertEquals(0, metrics.getMaxTimeNanos());
        
        // Record some executions
        metrics.recordExecution(1_000_000L, 5); // 1ms, 5 results
        metrics.recordExecution(2_000_000L, 8); // 2ms, 8 results
        metrics.recordExecution(500_000L, 3);   // 0.5ms, 3 results
        
        assertEquals(3, metrics.getTotalExecutions());
        assertEquals(1_166_666.67, metrics.getAverageTimeNanos(), 0.01);
        assertTrue(metrics.getAverageThroughput() > 0);
        assertEquals(500_000L, metrics.getMinTimeNanos());
        assertEquals(2_000_000L, metrics.getMaxTimeNanos());
        
        String metricsStr = metrics.toString();
        assertTrue(metricsStr.contains("PerformanceMetrics"));
        assertTrue(metricsStr.contains("executions=3"));
        assertTrue(metricsStr.contains("throughput="));
    }

    @Test
    void testIntelligentCacheLRU() {
        Function<String, Long> sizeEstimator = s -> (long) s.length();
        
        AlgorithmOptimizer.IntelligentCache<String, String> cache = 
            new AlgorithmOptimizer.IntelligentCache<>(3, 1000, 
                AlgorithmOptimizer.IntelligentCache.CacheEvictionStrategy.LRU, sizeEstimator);
        
        // Initially empty
        assertNull(cache.get("key1"));
        
        AlgorithmOptimizer.IntelligentCache.CacheStatistics initialStats = cache.getStatistics();
        assertEquals(0, initialStats.currentSize);
        assertEquals(0, initialStats.hits);
        assertEquals(1, initialStats.misses); // The get("key1") above
        
        // Add entries
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        
        AlgorithmOptimizer.IntelligentCache.CacheStatistics afterInsert = cache.getStatistics();
        assertEquals(3, afterInsert.currentSize);
        assertEquals(3, afterInsert.maxSize);
        assertTrue(afterInsert.memoryUsageBytes > 0);
        
        // Test cache hits
        assertEquals("value1", cache.get("key1"));
        assertEquals("value2", cache.get("key2"));
        
        AlgorithmOptimizer.IntelligentCache.CacheStatistics afterHits = cache.getStatistics();
        assertEquals(2, afterHits.hits);
        assertTrue(afterHits.getHitRate() > 0);
        
        // Add fourth entry (should trigger LRU eviction)
        cache.put("key4", "value4");
        
        AlgorithmOptimizer.IntelligentCache.CacheStatistics afterEviction = cache.getStatistics();
        assertEquals(3, afterEviction.currentSize); // Still max size
        assertTrue(afterEviction.evictions > 0);
        
        // key3 should be evicted (least recently used)
        assertNull(cache.get("key3"));
        assertNotNull(cache.get("key1")); // Should still be there
        
        String statsStr = afterEviction.toString();
        assertTrue(statsStr.contains("CacheStats"));
        assertTrue(statsStr.contains("hitRate="));
        assertTrue(statsStr.contains("evictions="));
        
        // Test clear
        cache.clear();
        AlgorithmOptimizer.IntelligentCache.CacheStatistics afterClear = cache.getStatistics();
        assertEquals(0, afterClear.currentSize);
        assertEquals(0, afterClear.memoryUsageBytes);
    }

    @Test
    void testIntelligentCacheLFU() {
        Function<String, Long> sizeEstimator = s -> (long) s.length();
        
        AlgorithmOptimizer.IntelligentCache<String, String> cache = 
            new AlgorithmOptimizer.IntelligentCache<>(2, 1000, 
                AlgorithmOptimizer.IntelligentCache.CacheEvictionStrategy.LFU, sizeEstimator);
        
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        
        // Access key1 multiple times
        cache.get("key1");
        cache.get("key1");
        cache.get("key1");
        
        // Access key2 once
        cache.get("key2");
        
        // Add third entry (should trigger LFU eviction)
        cache.put("key3", "value3");
        
        // key2 should be evicted (least frequently used)
        assertNull(cache.get("key2"));
        assertNotNull(cache.get("key1")); // Should still be there
        assertNotNull(cache.get("key3")); // Should be there
    }

    @Test
    void testIntelligentCacheSizeBased() {
        Function<String, Long> sizeEstimator = s -> (long) s.length();
        
        AlgorithmOptimizer.IntelligentCache<String, String> cache = 
            new AlgorithmOptimizer.IntelligentCache<>(3, 1000, 
                AlgorithmOptimizer.IntelligentCache.CacheEvictionStrategy.SIZE_BASED, sizeEstimator);
        
        cache.put("small", "a");                    // Size: 1
        cache.put("medium", "abcdef");              // Size: 6
        cache.put("large", "abcdefghijklmnopqrst"); // Size: 20
        
        // Add another entry (should trigger size-based eviction)
        cache.put("new", "xyz");
        
        // Large entry should be evicted first
        assertNull(cache.get("large"));
        assertNotNull(cache.get("small"));
        assertNotNull(cache.get("medium"));
        assertNotNull(cache.get("new"));
    }

    @Test
    void testIntelligentCacheAdaptive() {
        Function<String, Long> sizeEstimator = s -> (long) s.length();
        
        AlgorithmOptimizer.IntelligentCache<String, String> cache = 
            new AlgorithmOptimizer.IntelligentCache<>(2, 1000, 
                AlgorithmOptimizer.IntelligentCache.CacheEvictionStrategy.ADAPTIVE, sizeEstimator);
        
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        
        // Access key1 to increase its score
        cache.get("key1");
        
        // Wait a bit to age key2
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Add third entry (adaptive strategy should consider frequency, recency, and size)
        cache.put("key3", "value3");
        
        // Verify that adaptive eviction occurred
        AlgorithmOptimizer.IntelligentCache.CacheStatistics stats = cache.getStatistics();
        assertEquals(2, stats.currentSize);
        assertTrue(stats.evictions > 0);
    }

    @Test
    void testIntelligentCacheMemoryLimit() {
        Function<String, Long> sizeEstimator = s -> (long) s.length();
        
        // Small memory limit to trigger memory-based eviction
        AlgorithmOptimizer.IntelligentCache<String, String> cache = 
            new AlgorithmOptimizer.IntelligentCache<>(10, 20, 
                AlgorithmOptimizer.IntelligentCache.CacheEvictionStrategy.LRU, sizeEstimator);
        
        cache.put("key1", "abcdef");     // Size: 6
        cache.put("key2", "ghijkl");     // Size: 6, total: 12
        cache.put("key3", "mnopqrstuv"); // Size: 10, total: 22 > 20, should trigger eviction
        
        AlgorithmOptimizer.IntelligentCache.CacheStatistics stats = cache.getStatistics();
        assertTrue(stats.memoryUsageBytes <= 20);
        assertTrue(stats.getMemoryUsagePercentage() <= 100.0);
    }

    @Test
    void testDistanceEntry() {
        AlgorithmOptimizer.AdvancedSpatialSearch.DistanceEntry<String> entry1 = 
            new AlgorithmOptimizer.AdvancedSpatialSearch.DistanceEntry<>(5.0f, "content1");
        AlgorithmOptimizer.AdvancedSpatialSearch.DistanceEntry<String> entry2 = 
            new AlgorithmOptimizer.AdvancedSpatialSearch.DistanceEntry<>(3.0f, "content2");
        
        assertEquals(5.0f, entry1.distance, 0.001f);
        assertEquals("content1", entry1.content);
        assertEquals(3.0f, entry2.distance, 0.001f);
        assertEquals("content2", entry2.content);
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativePoint = new Point3f(-100.0f, 200.0f, 200.0f);
        
        // Adaptive KNN should reject negative coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            adaptiveKNN.findKNearest(negativePoint, 5);
        });
        
        // Adaptive range query should reject negative coordinates
        Point3f positiveBounds = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f negativeBounds = new Point3f(-50.0f, 150.0f, 150.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            adaptiveRange.rangeQuery(negativeBounds, positiveBounds);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            adaptiveRange.rangeQuery(positiveBounds, negativeBounds);
        });
    }

    @Test
    void testEmptyOctreeQueries() {
        Octree<String> emptyOctree = new Octree<>(new TreeMap<>());
        List<Point3f> emptyPoints = new ArrayList<>();
        SpatialIndexOptimizer.SpatialDistributionStats emptyStats = 
            SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(emptyPoints);
        
        AlgorithmOptimizer.AdvancedSpatialSearch.AdaptiveKNearestNeighbor<String> emptyKNN = 
            new AlgorithmOptimizer.AdvancedSpatialSearch.AdaptiveKNearestNeighbor<>(emptyOctree, emptyStats);
        
        AlgorithmOptimizer.AdvancedSpatialSearch.AdaptiveRangeQuery<String> emptyRange = 
            new AlgorithmOptimizer.AdvancedSpatialSearch.AdaptiveRangeQuery<>(emptyOctree);
        
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        
        // KNN on empty octree should return empty results
        AlgorithmOptimizer.AdvancedSpatialSearch.KNNResult<String> knnResult = 
            emptyKNN.findKNearest(queryPoint, 5);
        assertTrue(knnResult.results.isEmpty());
        
        // Range query on empty octree should return empty results
        Point3f minBounds = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f maxBounds = new Point3f(300.0f, 300.0f, 300.0f);
        AlgorithmOptimizer.AdvancedSpatialSearch.RangeQueryResult<String> rangeResult = 
            emptyRange.rangeQuery(minBounds, maxBounds);
        assertTrue(rangeResult.results.isEmpty());
    }

    @Test
    void testCacheStatisticsCalculations() {
        AlgorithmOptimizer.IntelligentCache.CacheStatistics stats = 
            new AlgorithmOptimizer.IntelligentCache.CacheStatistics(8, 10, 15, 5, 3, 800, 1000);
        
        assertEquals(8, stats.currentSize);
        assertEquals(10, stats.maxSize);
        assertEquals(15, stats.hits);
        assertEquals(5, stats.misses);
        assertEquals(3, stats.evictions);
        assertEquals(800, stats.memoryUsageBytes);
        assertEquals(1000, stats.maxMemoryBytes);
        
        assertEquals(0.75, stats.getHitRate(), 0.001); // 15 / (15 + 5)
        assertEquals(80.0, stats.getMemoryUsagePercentage(), 0.001); // 800 / 1000 * 100
        
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("size=8/10"));
        assertTrue(statsStr.contains("hitRate=75.00%"));
        assertTrue(statsStr.contains("memory=80.0%"));
        assertTrue(statsStr.contains("evictions=3"));
    }

    @Test
    void testSearchStrategyAdaptation() {
        // Test that strategy selection adapts based on data characteristics
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        
        // Perform many queries to build up strategy performance history
        for (int i = 0; i < 20; i++) {
            adaptiveKNN.findKNearest(queryPoint, 3);
        }
        
        Map<AlgorithmOptimizer.AdvancedSpatialSearch.SearchStrategy, AlgorithmOptimizer.PerformanceMetrics> metrics = 
            adaptiveKNN.getPerformanceMetrics();
        
        // Should have tried multiple strategies
        assertTrue(metrics.size() > 0);
        
        // Verify that at least one strategy has good performance metrics
        boolean hasValidMetrics = metrics.values().stream()
            .anyMatch(m -> m.getTotalExecutions() > 0 && m.getAverageTimeNanos() > 0);
        assertTrue(hasValidMetrics);
    }

    @Test
    void testAlgorithmConsistency() {
        Point3f queryPoint = new Point3f(300.0f, 300.0f, 300.0f);
        int k = 5;
        
        // Run the same query multiple times
        AlgorithmOptimizer.AdvancedSpatialSearch.KNNResult<String> result1 = 
            adaptiveKNN.findKNearest(queryPoint, k);
        AlgorithmOptimizer.AdvancedSpatialSearch.KNNResult<String> result2 = 
            adaptiveKNN.findKNearest(queryPoint, k);
        
        // Results should be consistent (same size, similar distances)
        assertEquals(result1.results.size(), result2.results.size());
        
        if (!result1.results.isEmpty() && !result2.results.isEmpty()) {
            // First result should be the same (closest point)
            assertEquals(result1.results.get(0).distance, result2.results.get(0).distance, 0.001f);
        }
    }

    @Test
    void testCacheNullHandling() {
        Function<String, Long> sizeEstimator = s -> (long) s.length();
        
        AlgorithmOptimizer.IntelligentCache<String, String> cache = 
            new AlgorithmOptimizer.IntelligentCache<>(5, 1000, 
                AlgorithmOptimizer.IntelligentCache.CacheEvictionStrategy.LRU, sizeEstimator);
        
        // Putting null should be ignored
        cache.put("key1", null);
        assertNull(cache.get("key1"));
        
        cache.put("key2", "value2");
        assertNotNull(cache.get("key2"));
        
        AlgorithmOptimizer.IntelligentCache.CacheStatistics stats = cache.getStatistics();
        assertEquals(1, stats.currentSize); // Only non-null entry
    }
}