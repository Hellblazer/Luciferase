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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Parallel Spatial Processor functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class ParallelSpatialProcessorTest {

    private Octree<String> octree;
    private ParallelSpatialProcessor.ParallelSpatialQueryExecutor<String> parallelExecutor;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        octree = new Octree<>();
        
        // Insert sufficient test data to trigger parallel processing
        insertTestData();
        
        // Create parallel executor with default config
        ParallelSpatialProcessor.ParallelConfig config = 
            ParallelSpatialProcessor.ParallelConfig.defaultConfig();
        parallelExecutor = new ParallelSpatialProcessor.ParallelSpatialQueryExecutor<>(octree, config);
    }
    
    @AfterEach
    void tearDown() {
        if (parallelExecutor != null) {
            parallelExecutor.shutdown();
        }
    }
    
    private void insertTestData() {
        // Insert enough data to trigger parallel processing (>= 100 items)
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // Dense cluster around (200, 200, 200)
        for (int i = 0; i < 50; i++) {
            float x = 180.0f + random.nextFloat() * 40.0f;
            float y = 180.0f + random.nextFloat() * 40.0f;
            float z = 180.0f + random.nextFloat() * 40.0f;
            octree.insert(new Point3f(x, y, z), testLevel, "Dense_" + i);
        }
        
        // Sparse points distributed widely
        for (int i = 0; i < 30; i++) {
            float x = 50.0f + random.nextFloat() * 400.0f;
            float y = 50.0f + random.nextFloat() * 400.0f;
            float z = 50.0f + random.nextFloat() * 400.0f;
            octree.insert(new Point3f(x, y, z), testLevel, "Sparse_" + i);
        }
        
        // Medium density cluster around (350, 350, 350)
        for (int i = 0; i < 40; i++) {
            float x = 320.0f + random.nextFloat() * 60.0f;
            float y = 320.0f + random.nextFloat() * 60.0f;
            float z = 320.0f + random.nextFloat() * 60.0f;
            octree.insert(new Point3f(x, y, z), testLevel, "Medium_" + i);
        }
    }

    @Test
    void testParallelConfig() {
        // Test default config
        ParallelSpatialProcessor.ParallelConfig defaultConfig = 
            ParallelSpatialProcessor.ParallelConfig.defaultConfig();
        
        assertTrue(defaultConfig.threadPoolSize >= 2);
        assertEquals(100, defaultConfig.minDataSizeForParallel);
        assertEquals(50, defaultConfig.workChunkSize);
        assertTrue(defaultConfig.enableWorkStealing);
        assertEquals(30000, defaultConfig.timeoutMillis);
        
        String configStr = defaultConfig.toString();
        assertTrue(configStr.contains("ParallelConfig"));
        assertTrue(configStr.contains("threads="));
        
        // Test conservative config
        ParallelSpatialProcessor.ParallelConfig conservativeConfig = 
            ParallelSpatialProcessor.ParallelConfig.conservativeConfig();
        
        assertEquals(2, conservativeConfig.threadPoolSize);
        assertEquals(500, conservativeConfig.minDataSizeForParallel);
        assertEquals(100, conservativeConfig.workChunkSize);
        assertFalse(conservativeConfig.enableWorkStealing);
        assertEquals(60000, conservativeConfig.timeoutMillis);
        
        // Test aggressive config
        ParallelSpatialProcessor.ParallelConfig aggressiveConfig = 
            ParallelSpatialProcessor.ParallelConfig.aggressiveConfig();
        
        assertTrue(aggressiveConfig.threadPoolSize >= 2);
        assertEquals(50, aggressiveConfig.minDataSizeForParallel);
        assertEquals(25, aggressiveConfig.workChunkSize);
        assertTrue(aggressiveConfig.enableWorkStealing);
        assertEquals(15000, aggressiveConfig.timeoutMillis);
    }

    @Test
    void testParallelResult() {
        List<String> results = List.of("result1", "result2", "result3");
        ParallelSpatialProcessor.ParallelResult<String> result = 
            new ParallelSpatialProcessor.ParallelResult<>(results, 5_000_000L, 4, 2, false, null);
        
        assertEquals(3, result.results.size());
        assertEquals(5_000_000L, result.executionTimeNanos);
        assertEquals(4, result.threadsUsed);
        assertEquals(2, result.chunksProcessed);
        assertFalse(result.timedOut);
        assertNull(result.error);
        assertTrue(result.isSuccessful());
        assertTrue(result.getThroughputItemsPerSecond() > 0);
        
        // Test immutability
        assertThrows(UnsupportedOperationException.class, () -> {
            result.results.add("new item");
        });
        
        String resultStr = result.toString();
        assertTrue(resultStr.contains("ParallelResult"));
        assertTrue(resultStr.contains("items=3"));
        assertTrue(resultStr.contains("success=true"));
        
        // Test failed result
        Exception error = new RuntimeException("test error");
        ParallelSpatialProcessor.ParallelResult<String> failedResult = 
            new ParallelSpatialProcessor.ParallelResult<>(List.of(), 1_000_000L, 0, 0, false, error);
        
        assertFalse(failedResult.isSuccessful());
        assertEquals(error, failedResult.error);
        
        // Test timed out result
        ParallelSpatialProcessor.ParallelResult<String> timedOutResult = 
            new ParallelSpatialProcessor.ParallelResult<>(List.of(), 10_000_000L, 2, 0, true, null);
        
        assertFalse(timedOutResult.isSuccessful());
        assertTrue(timedOutResult.timedOut);
    }

    @Test
    void testParallelRadiusQuery() {
        Point3f queryCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float radius = 100.0f;
        
        ParallelSpatialProcessor.ParallelResult<String> result = 
            parallelExecutor.parallelRadiusQuery(queryCenter, radius);
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertNotNull(result.results);
        assertTrue(result.executionTimeNanos > 0);
        assertTrue(result.threadsUsed > 0);
        assertTrue(result.chunksProcessed > 0);
        assertFalse(result.timedOut);
        assertNull(result.error);
        
        // Results should contain items within radius
        assertTrue(result.results.size() > 0);
        
        // Test with very small radius
        ParallelSpatialProcessor.ParallelResult<String> smallResult = 
            parallelExecutor.parallelRadiusQuery(queryCenter, 1.0f);
        
        assertTrue(smallResult.isSuccessful());
        // Small radius should generally find fewer results
        assertTrue(smallResult.results.size() <= result.results.size());
    }

    @Test
    void testParallelRangeQuery() {
        Point3f minBounds = new Point3f(150.0f, 150.0f, 150.0f);
        Point3f maxBounds = new Point3f(250.0f, 250.0f, 250.0f);
        
        ParallelSpatialProcessor.ParallelResult<String> result = 
            parallelExecutor.parallelRangeQuery(minBounds, maxBounds);
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertNotNull(result.results);
        assertTrue(result.executionTimeNanos > 0);
        assertTrue(result.threadsUsed > 0);
        assertTrue(result.chunksProcessed > 0);
        
        // Results should be non-empty since we have data in that range
        assertTrue(result.results.size() > 0);
        
        // Test with larger range
        Point3f largeBounds = new Point3f(400.0f, 400.0f, 400.0f);
        ParallelSpatialProcessor.ParallelResult<String> largeResult = 
            parallelExecutor.parallelRangeQuery(minBounds, largeBounds);
        
        assertTrue(largeResult.isSuccessful());
        // Larger range should generally find more results
        assertTrue(largeResult.results.size() >= result.results.size());
    }

    @Test
    void testParallelKNearestNeighborQuery() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        int k = 10;
        
        ParallelSpatialProcessor.ParallelResult<String> result = 
            parallelExecutor.parallelKNearestNeighborQuery(queryPoint, k);
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertNotNull(result.results);
        assertTrue(result.executionTimeNanos > 0);
        
        // Should find exactly k results (or fewer if total data < k)
        assertTrue(result.results.size() <= k);
        assertTrue(result.results.size() <= octree.getMap().size());
        
        // Test with k=1
        ParallelSpatialProcessor.ParallelResult<String> singleResult = 
            parallelExecutor.parallelKNearestNeighborQuery(queryPoint, 1);
        
        assertTrue(singleResult.isSuccessful());
        assertEquals(1, singleResult.results.size());
        
        // Test with very large k
        ParallelSpatialProcessor.ParallelResult<String> largeKResult = 
            parallelExecutor.parallelKNearestNeighborQuery(queryPoint, 1000);
        
        assertTrue(largeKResult.isSuccessful());
        // Should not exceed total number of items in octree
        assertTrue(largeKResult.results.size() <= octree.getMap().size());
    }

    @Test
    void testParallelCustomQuery() {
        // Custom predicate: find all items with "Dense" in their content
        ParallelSpatialProcessor.ParallelResult<String> result = 
            parallelExecutor.parallelCustomQuery(entry -> entry.getValue().contains("Dense"));
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertNotNull(result.results);
        assertTrue(result.executionTimeNanos > 0);
        
        // All results should contain "Dense"
        for (String item : result.results) {
            assertTrue(item.contains("Dense"));
        }
        
        // Test with different predicate: find all items with specific coordinate range
        ParallelSpatialProcessor.ParallelResult<String> coordResult = 
            parallelExecutor.parallelCustomQuery(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                return cube.originX() >= 300.0f && cube.originX() <= 400.0f;
            });
        
        assertTrue(coordResult.isSuccessful());
        assertNotNull(coordResult.results);
    }

    @Test
    void testParallelDataTransform() {
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            numbers.add(i);
        }
        
        ParallelSpatialProcessor.ParallelConfig config = 
            ParallelSpatialProcessor.ParallelConfig.defaultConfig();
        
        // Transform numbers to their squares
        ParallelSpatialProcessor.ParallelResult<Integer> result = 
            ParallelSpatialProcessor.ParallelSpatialDataProcessor.parallelTransform(
                numbers, x -> x * x, config);
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(200, result.results.size());
        assertTrue(result.threadsUsed > 1); // Should use multiple threads for 200 items
        
        // Verify transformation correctness
        for (int i = 0; i < numbers.size(); i++) {
            int expected = numbers.get(i) * numbers.get(i);
            assertEquals(expected, result.results.get(i));
        }
        
        // Test with small dataset (should fall back to sequential)
        List<Integer> smallNumbers = List.of(1, 2, 3, 4, 5);
        ParallelSpatialProcessor.ParallelResult<Integer> smallResult = 
            ParallelSpatialProcessor.ParallelSpatialDataProcessor.parallelTransform(
                smallNumbers, x -> x * 2, config);
        
        assertTrue(smallResult.isSuccessful());
        assertEquals(1, smallResult.threadsUsed); // Should use only 1 thread for small data
        assertEquals(5, smallResult.results.size());
    }

    @Test
    void testParallelMortonEncoding() {
        List<Point3f> points = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // Generate enough points to trigger parallel processing
        for (int i = 0; i < 150; i++) {
            float x = 100.0f + random.nextFloat() * 300.0f;
            float y = 100.0f + random.nextFloat() * 300.0f;
            float z = 100.0f + random.nextFloat() * 300.0f;
            points.add(new Point3f(x, y, z));
        }
        
        ParallelSpatialProcessor.ParallelConfig config = 
            ParallelSpatialProcessor.ParallelConfig.defaultConfig();
        
        ParallelSpatialProcessor.ParallelResult<Long> result = 
            ParallelSpatialProcessor.ParallelSpatialDataProcessor.parallelMortonEncoding(points, config);
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(150, result.results.size());
        assertTrue(result.threadsUsed > 1);
        
        // Verify Morton codes are non-zero (for positive coordinates)
        for (Long mortonCode : result.results) {
            assertTrue(mortonCode >= 0);
        }
    }

    @Test
    void testParallelSpatialAnalysis() {
        List<Point3f> points = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // Generate points for analysis
        for (int i = 0; i < 80; i++) {
            float x = 100.0f + random.nextFloat() * 200.0f;
            float y = 100.0f + random.nextFloat() * 200.0f;
            float z = 100.0f + random.nextFloat() * 200.0f;
            points.add(new Point3f(x, y, z));
        }
        
        ParallelSpatialProcessor.ParallelConfig config = 
            ParallelSpatialProcessor.ParallelConfig.defaultConfig();
        
        ParallelSpatialProcessor.ParallelResult<SpatialIndexOptimizer.SpatialDistributionStats> result = 
            ParallelSpatialProcessor.ParallelSpatialDataProcessor.parallelSpatialAnalysis(points, config);
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(1, result.results.size());
        
        SpatialIndexOptimizer.SpatialDistributionStats stats = result.results.get(0);
        assertEquals(80, stats.totalPoints);
        assertTrue(stats.minX >= 100.0f);
        assertTrue(stats.maxX <= 300.0f);
        assertTrue(stats.density > 0);
        assertTrue(stats.recommendedLevel >= 10);
    }

    @Test
    void testParallelPerformanceMonitor() {
        ParallelSpatialProcessor.ParallelPerformanceMonitor monitor = 
            new ParallelSpatialProcessor.ParallelPerformanceMonitor();
        
        // Initially no statistics
        ParallelSpatialProcessor.ParallelPerformanceMonitor.ParallelPerformanceStats initialStats = 
            monitor.getStatistics();
        assertEquals(0, initialStats.totalExecutions);
        
        // Record some successful executions
        List<String> results = List.of("result1", "result2");
        ParallelSpatialProcessor.ParallelResult<String> successResult = 
            new ParallelSpatialProcessor.ParallelResult<>(results, 2_000_000L, 2, 1, false, null);
        monitor.recordExecution(successResult);
        
        ParallelSpatialProcessor.ParallelResult<String> anotherResult = 
            new ParallelSpatialProcessor.ParallelResult<>(results, 3_000_000L, 3, 2, false, null);
        monitor.recordExecution(anotherResult);
        
        // Record a failed execution
        ParallelSpatialProcessor.ParallelResult<String> failedResult = 
            new ParallelSpatialProcessor.ParallelResult<>(List.of(), 1_000_000L, 0, 0, false, 
                new RuntimeException("test error"));
        monitor.recordExecution(failedResult);
        
        // Record a timed out execution
        ParallelSpatialProcessor.ParallelResult<String> timedOutResult = 
            new ParallelSpatialProcessor.ParallelResult<>(List.of(), 5_000_000L, 2, 0, true, null);
        monitor.recordExecution(timedOutResult);
        
        ParallelSpatialProcessor.ParallelPerformanceMonitor.ParallelPerformanceStats stats = 
            monitor.getStatistics();
        
        assertEquals(4, stats.totalExecutions);
        assertEquals(2, stats.successfulExecutions);
        assertEquals(0.5, stats.getSuccessRate(), 0.001);
        assertTrue(stats.avgExecutionTimeMs > 0);
        assertTrue(stats.avgThroughputItemsPerSecond > 0);
        assertTrue(stats.avgThreadsUsed > 0);
        assertEquals(1, stats.timeouts);
        assertEquals(1, stats.errors);
        
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("ParallelStats"));
        assertTrue(statsStr.contains("executions=4"));
        assertTrue(statsStr.contains("success=50.0%"));
        
        // Test clearing history
        monitor.clearHistory();
        ParallelSpatialProcessor.ParallelPerformanceMonitor.ParallelPerformanceStats clearedStats = 
            monitor.getStatistics();
        assertEquals(0, clearedStats.totalExecutions);
    }

    @Test
    void testSequentialFallback() {
        // Create executor with high minimum data size to force sequential processing
        ParallelSpatialProcessor.ParallelConfig config = 
            new ParallelSpatialProcessor.ParallelConfig(4, 10000, 50, true, 30000);
        
        ParallelSpatialProcessor.ParallelSpatialQueryExecutor<String> fallbackExecutor = 
            new ParallelSpatialProcessor.ParallelSpatialQueryExecutor<>(octree, config);
        
        try {
            Point3f queryCenter = new Point3f(200.0f, 200.0f, 200.0f);
            
            // Should fall back to sequential processing
            ParallelSpatialProcessor.ParallelResult<String> result = 
                fallbackExecutor.parallelRadiusQuery(queryCenter, 50.0f);
            
            assertTrue(result.isSuccessful());
            assertEquals(1, result.threadsUsed); // Sequential execution
            assertEquals(1, result.chunksProcessed);
            assertNotNull(result.results);
        } finally {
            fallbackExecutor.shutdown();
        }
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativePoint = new Point3f(-100.0f, 200.0f, 200.0f);
        
        // All parallel query types should reject negative coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            parallelExecutor.parallelRadiusQuery(negativePoint, 50.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            parallelExecutor.parallelKNearestNeighborQuery(negativePoint, 5);
        });
        
        Point3f positiveBounds = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f negativeBounds = new Point3f(-50.0f, 150.0f, 150.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            parallelExecutor.parallelRangeQuery(negativeBounds, positiveBounds);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            parallelExecutor.parallelRangeQuery(positiveBounds, negativeBounds);
        });
        
        // Test parallel Morton encoding with negative coordinates
        List<Point3f> pointsWithNegative = List.of(
            new Point3f(100.0f, 100.0f, 100.0f),
            negativePoint
        );
        
        ParallelSpatialProcessor.ParallelConfig config = 
            ParallelSpatialProcessor.ParallelConfig.defaultConfig();
        
        assertThrows(IllegalArgumentException.class, () -> {
            ParallelSpatialProcessor.ParallelSpatialDataProcessor.parallelMortonEncoding(
                pointsWithNegative, config);
        });
    }

    @Test
    void testEmptyOctreeParallelQueries() {
        Octree<String> emptyOctree = new Octree<>();
        ParallelSpatialProcessor.ParallelConfig config = 
            ParallelSpatialProcessor.ParallelConfig.defaultConfig();
        
        ParallelSpatialProcessor.ParallelSpatialQueryExecutor<String> emptyExecutor = 
            new ParallelSpatialProcessor.ParallelSpatialQueryExecutor<>(emptyOctree, config);
        
        try {
            Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
            
            // All queries on empty octree should return empty results
            ParallelSpatialProcessor.ParallelResult<String> radiusResult = 
                emptyExecutor.parallelRadiusQuery(queryPoint, 100.0f);
            assertTrue(radiusResult.isSuccessful());
            assertTrue(radiusResult.results.isEmpty());
            
            ParallelSpatialProcessor.ParallelResult<String> knnResult = 
                emptyExecutor.parallelKNearestNeighborQuery(queryPoint, 5);
            assertTrue(knnResult.isSuccessful());
            assertTrue(knnResult.results.isEmpty());
            
            Point3f minBounds = new Point3f(100.0f, 100.0f, 100.0f);
            Point3f maxBounds = new Point3f(300.0f, 300.0f, 300.0f);
            ParallelSpatialProcessor.ParallelResult<String> rangeResult = 
                emptyExecutor.parallelRangeQuery(minBounds, maxBounds);
            assertTrue(rangeResult.isSuccessful());
            assertTrue(rangeResult.results.isEmpty());
            
        } finally {
            emptyExecutor.shutdown();
        }
    }

    @Test
    void testParallelConfigValidation() {
        // Test minimum values are enforced
        ParallelSpatialProcessor.ParallelConfig config = 
            new ParallelSpatialProcessor.ParallelConfig(0, 0, 0, true, 500);
        
        assertEquals(1, config.threadPoolSize); // Should be at least 1
        assertEquals(1, config.minDataSizeForParallel); // Should be at least 1
        assertEquals(1, config.workChunkSize); // Should be at least 1
        assertEquals(1000, config.timeoutMillis); // Should be at least 1000
    }

    @Test
    void testShutdownBehavior() {
        // Test that shutdown works properly
        assertDoesNotThrow(() -> {
            parallelExecutor.shutdown();
        });
        
        // Create another executor to test shutdown with non-work-stealing executor
        ParallelSpatialProcessor.ParallelConfig nonWorkStealingConfig = 
            new ParallelSpatialProcessor.ParallelConfig(2, 100, 50, false, 30000);
        
        ParallelSpatialProcessor.ParallelSpatialQueryExecutor<String> nonWorkStealingExecutor = 
            new ParallelSpatialProcessor.ParallelSpatialQueryExecutor<>(octree, nonWorkStealingConfig);
        
        assertDoesNotThrow(() -> {
            nonWorkStealingExecutor.shutdown();
        });
    }

    @Test
    void testWorkChunkSizeImpact() {
        // Test with different chunk sizes
        ParallelSpatialProcessor.ParallelConfig smallChunkConfig = 
            new ParallelSpatialProcessor.ParallelConfig(4, 50, 10, true, 30000);
        
        ParallelSpatialProcessor.ParallelConfig largeChunkConfig = 
            new ParallelSpatialProcessor.ParallelConfig(4, 50, 100, true, 30000);
        
        ParallelSpatialProcessor.ParallelSpatialQueryExecutor<String> smallChunkExecutor = 
            new ParallelSpatialProcessor.ParallelSpatialQueryExecutor<>(octree, smallChunkConfig);
        
        ParallelSpatialProcessor.ParallelSpatialQueryExecutor<String> largeChunkExecutor = 
            new ParallelSpatialProcessor.ParallelSpatialQueryExecutor<>(octree, largeChunkConfig);
        
        try {
            Point3f queryCenter = new Point3f(200.0f, 200.0f, 200.0f);
            
            ParallelSpatialProcessor.ParallelResult<String> smallChunkResult = 
                smallChunkExecutor.parallelRadiusQuery(queryCenter, 100.0f);
            
            ParallelSpatialProcessor.ParallelResult<String> largeChunkResult = 
                largeChunkExecutor.parallelRadiusQuery(queryCenter, 100.0f);
            
            // Both should succeed
            assertTrue(smallChunkResult.isSuccessful());
            assertTrue(largeChunkResult.isSuccessful());
            
            // Small chunks should generally create more chunks
            assertTrue(smallChunkResult.chunksProcessed >= largeChunkResult.chunksProcessed);
            
            // Both should find the same results (order might differ)
            assertEquals(smallChunkResult.results.size(), largeChunkResult.results.size());
            
        } finally {
            smallChunkExecutor.shutdown();
            largeChunkExecutor.shutdown();
        }
    }
}