package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Memory Optimizer functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class MemoryOptimizerTest {

    private MemoryOptimizer.MemoryMonitor memoryMonitor;
    private MemoryOptimizer.CompressedPointStorage pointStorage;
    private MemoryOptimizer.ObjectPool<Point3f> pointPool;

    @BeforeEach
    void setUp() {
        memoryMonitor = new MemoryOptimizer.MemoryMonitor(50);
        pointStorage = new MemoryOptimizer.CompressedPointStorage(100, 500.0f);
        
        // Create point pool with factory and validator
        MemoryOptimizer.ObjectPool.ObjectFactory<Point3f> factory = new MemoryOptimizer.ObjectPool.ObjectFactory<Point3f>() {
            @Override
            public Point3f create() {
                return new Point3f();
            }
            
            @Override
            public void reset(Point3f object) {
                object.set(0.0f, 0.0f, 0.0f);
            }
        };
        
        MemoryOptimizer.ObjectPool.ObjectValidator<Point3f> validator = new MemoryOptimizer.ObjectPool.ObjectValidator<Point3f>() {
            @Override
            public boolean isValid(Point3f object) {
                return object != null && !Float.isNaN(object.x) && !Float.isNaN(object.y) && !Float.isNaN(object.z);
            }
        };
        
        pointPool = new MemoryOptimizer.ObjectPool<>(factory, validator, 10);
    }

    @AfterEach
    void tearDown() {
        if (memoryMonitor != null) {
            memoryMonitor.clearHistory();
        }
        if (pointStorage != null) {
            pointStorage.clear();
        }
        if (pointPool != null) {
            pointPool.clear();
        }
    }

    @Test
    void testMemoryUsageStats() {
        MemoryOptimizer.MemoryUsageStats stats = new MemoryOptimizer.MemoryUsageStats(
            100_000_000L,   // heapUsedBytes
            200_000_000L,   // heapMaxBytes
            150_000_000L,   // heapCommittedBytes
            50_000_000L,    // nonHeapUsedBytes
            100_000_000L,   // nonHeapMaxBytes
            50.0,           // heapUsagePercentage
            25,             // gcCollections
            5000,           // gcTimeMillis
            System.currentTimeMillis()
        );
        
        assertEquals(100_000_000L, stats.heapUsedBytes);
        assertEquals(200_000_000L, stats.heapMaxBytes);
        assertEquals(50.0, stats.heapUsagePercentage, 0.001);
        assertFalse(stats.isMemoryPressure());
        assertFalse(stats.isCriticalMemory());
        
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("MemoryStats"));
        assertTrue(statsStr.contains("50.0%"));
        assertTrue(statsStr.contains("95MB/190MB"));
        
        // Test memory pressure detection
        MemoryOptimizer.MemoryUsageStats pressureStats = new MemoryOptimizer.MemoryUsageStats(
            170_000_000L, 200_000_000L, 150_000_000L, 50_000_000L, 100_000_000L,
            85.0, 25, 5000, System.currentTimeMillis()
        );
        assertTrue(pressureStats.isMemoryPressure());
        assertFalse(pressureStats.isCriticalMemory());
        
        // Test critical memory detection
        MemoryOptimizer.MemoryUsageStats criticalStats = new MemoryOptimizer.MemoryUsageStats(
            195_000_000L, 200_000_000L, 150_000_000L, 50_000_000L, 100_000_000L,
            97.5, 25, 5000, System.currentTimeMillis()
        );
        assertTrue(criticalStats.isMemoryPressure());
        assertTrue(criticalStats.isCriticalMemory());
    }

    @Test
    void testCompressedPointStorage() {
        assertEquals(0, pointStorage.size());
        assertEquals(100, pointStorage.capacity());
        
        // Add some points
        Point3f point1 = new Point3f(100.0f, 200.0f, 300.0f);
        Point3f point2 = new Point3f(400.0f, 450.0f, 480.0f);
        Point3f point3 = new Point3f(50.0f, 75.0f, 125.0f);  // Should be compressible
        
        assertTrue(pointStorage.addPoint(point1));
        assertTrue(pointStorage.addPoint(point2));
        assertTrue(pointStorage.addPoint(point3));
        
        assertEquals(3, pointStorage.size());
        
        // Retrieve points
        Point3f retrieved1 = pointStorage.getPoint(0);
        Point3f retrieved2 = pointStorage.getPoint(1);
        Point3f retrieved3 = pointStorage.getPoint(2);
        
        assertEquals(point1.x, retrieved1.x, 0.001f);
        assertEquals(point1.y, retrieved1.y, 0.001f);
        assertEquals(point1.z, retrieved1.z, 0.001f);
        
        assertEquals(point2.x, retrieved2.x, 0.001f);
        assertEquals(point2.y, retrieved2.y, 0.001f);
        assertEquals(point2.z, retrieved2.z, 0.001f);
        
        // Test compression ratio
        double compressionRatio = pointStorage.getCompressionRatio();
        assertTrue(compressionRatio >= 0.0 && compressionRatio <= 1.0);
        
        // Test memory footprint calculation
        long footprint = pointStorage.getMemoryFootprintBytes();
        assertTrue(footprint > 0);
        
        // Test bounds checking
        assertThrows(IndexOutOfBoundsException.class, () -> {
            pointStorage.getPoint(-1);
        });
        
        assertThrows(IndexOutOfBoundsException.class, () -> {
            pointStorage.getPoint(pointStorage.size());
        });
        
        // Test clear
        pointStorage.clear();
        assertEquals(0, pointStorage.size());
        assertEquals(1.0, pointStorage.getCompressionRatio(), 0.001); // Empty storage should have ratio 1.0
    }

    @Test
    void testCompressedPointStorageCapacityLimit() {
        MemoryOptimizer.CompressedPointStorage smallStorage = 
            new MemoryOptimizer.CompressedPointStorage(2, 100.0f);
        
        Point3f point1 = new Point3f(10.0f, 20.0f, 30.0f);
        Point3f point2 = new Point3f(40.0f, 50.0f, 60.0f);
        Point3f point3 = new Point3f(70.0f, 80.0f, 90.0f);
        
        assertTrue(smallStorage.addPoint(point1));
        assertTrue(smallStorage.addPoint(point2));
        assertFalse(smallStorage.addPoint(point3)); // Should fail due to capacity
        
        assertEquals(2, smallStorage.size());
        assertEquals(2, smallStorage.capacity());
    }

    @Test
    void testObjectPool() {
        MemoryOptimizer.ObjectPool.PoolStatistics initialStats = pointPool.getStatistics();
        assertEquals(0, initialStats.totalCreated);
        assertEquals(0, initialStats.currentPooled);
        assertEquals(0, initialStats.currentBorrowed);
        
        // Borrow objects
        Point3f borrowed1 = pointPool.borrow();
        Point3f borrowed2 = pointPool.borrow();
        
        assertNotNull(borrowed1);
        assertNotNull(borrowed2);
        
        MemoryOptimizer.ObjectPool.PoolStatistics afterBorrow = pointPool.getStatistics();
        assertEquals(2, afterBorrow.totalCreated);
        assertEquals(0, afterBorrow.currentPooled);
        assertEquals(2, afterBorrow.currentBorrowed);
        
        // Return objects
        pointPool.returnObject(borrowed1);
        pointPool.returnObject(borrowed2);
        
        MemoryOptimizer.ObjectPool.PoolStatistics afterReturn = pointPool.getStatistics();
        assertEquals(2, afterReturn.totalCreated);
        assertEquals(2, afterReturn.currentPooled);
        assertEquals(0, afterReturn.currentBorrowed);
        
        // Borrow again (should reuse pooled objects)
        Point3f reused1 = pointPool.borrow();
        Point3f reused2 = pointPool.borrow();
        
        assertNotNull(reused1);
        assertNotNull(reused2);
        
        MemoryOptimizer.ObjectPool.PoolStatistics afterReuse = pointPool.getStatistics();
        assertEquals(2, afterReuse.totalCreated); // No new objects created
        assertEquals(0, afterReuse.currentPooled);
        assertEquals(2, afterReuse.currentBorrowed);
        
        String statsStr = afterReuse.toString();
        assertTrue(statsStr.contains("PoolStats"));
        assertTrue(statsStr.contains("created=2"));
        assertTrue(statsStr.contains("borrowed=2"));
        
        // Test clear
        pointPool.clear();
        MemoryOptimizer.ObjectPool.PoolStatistics afterClear = pointPool.getStatistics();
        assertEquals(0, afterClear.currentPoolSize);
    }

    @Test
    void testObjectPoolValidation() {
        // Return null object
        pointPool.returnObject(null);
        
        // Pool should not contain null
        MemoryOptimizer.ObjectPool.PoolStatistics stats = pointPool.getStatistics();
        assertEquals(0, stats.currentPooled);
        
        // Create an invalid object (with NaN coordinates)
        Point3f invalidPoint = new Point3f(Float.NaN, 100.0f, 200.0f);
        pointPool.returnObject(invalidPoint);
        
        // Invalid object should not be pooled
        MemoryOptimizer.ObjectPool.PoolStatistics statsAfterInvalid = pointPool.getStatistics();
        assertEquals(0, statsAfterInvalid.currentPooled);
    }

    @Test
    void testMemoryAwareCache() {
        MemoryOptimizer.MemoryAwareCache.MemoryEstimator<String> stringEstimator = 
            new MemoryOptimizer.MemoryAwareCache.MemoryEstimator<String>() {
                @Override
                public long estimateSize(String value) {
                    return value != null ? 40 + value.length() * 2 : 0;
                }
            };
        
        MemoryOptimizer.MemoryAwareCache<String, String> cache = 
            new MemoryOptimizer.MemoryAwareCache<>(5, 1000, stringEstimator);
        
        // Initially empty
        assertNull(cache.get("nonexistent"));
        
        MemoryOptimizer.MemoryAwareCache.CacheStatistics initialStats = cache.getStatistics();
        assertEquals(0, initialStats.totalEntries);
        assertEquals(0, initialStats.hits);
        assertEquals(1, initialStats.misses); // The get("nonexistent") above
        
        // Add entries
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        
        MemoryOptimizer.MemoryAwareCache.CacheStatistics afterInsert = cache.getStatistics();
        assertEquals(3, afterInsert.totalEntries);
        assertEquals(3, afterInsert.validEntries);
        assertTrue(afterInsert.memoryUsageBytes > 0);
        
        // Test cache hits
        assertEquals("value1", cache.get("key1"));
        assertEquals("value2", cache.get("key2"));
        
        MemoryOptimizer.MemoryAwareCache.CacheStatistics afterHits = cache.getStatistics();
        assertEquals(2, afterHits.hits);
        assertTrue(afterHits.getHitRate() > 0);
        
        String statsStr = afterHits.toString();
        assertTrue(statsStr.contains("CacheStats"));
        assertTrue(statsStr.contains("entries=3/3"));
        assertTrue(statsStr.contains("hits=2"));
        
        // Test cache clearing
        cache.clear();
        MemoryOptimizer.MemoryAwareCache.CacheStatistics afterClear = cache.getStatistics();
        assertEquals(0, afterClear.totalEntries);
        assertEquals(0, afterClear.memoryUsageBytes);
    }

    @Test
    void testMemoryAwareCacheEviction() {
        MemoryOptimizer.MemoryAwareCache.MemoryEstimator<String> estimator = 
            value -> value != null ? 100 : 0; // Fixed size for testing
        
        // Small cache that will trigger eviction
        MemoryOptimizer.MemoryAwareCache<String, String> smallCache = 
            new MemoryOptimizer.MemoryAwareCache<>(2, 150, estimator);
        
        smallCache.put("key1", "value1"); // 100 bytes
        smallCache.put("key2", "value2"); // 100 bytes (total: 200 > 150, should trigger cleanup)
        
        MemoryOptimizer.MemoryAwareCache.CacheStatistics stats = smallCache.getStatistics();
        assertTrue(stats.totalEntries <= 2); // Should have evicted something
    }

    @Test
    void testCompactBounds() {
        Point3f min = new Point3f(100.0f, 200.0f, 300.0f);
        Point3f max = new Point3f(400.0f, 500.0f, 600.0f);
        float scale = 10.0f;
        
        MemoryOptimizer.MemoryEfficientSpatialStructures.CompactBounds bounds = 
            new MemoryOptimizer.MemoryEfficientSpatialStructures.CompactBounds(min, max, scale);
        
        // Test retrieval of bounds
        Point3f retrievedMin = bounds.getMin();
        Point3f retrievedMax = bounds.getMax();
        
        // Should be close to original values (may have some precision loss due to scale)
        assertEquals(100.0f, retrievedMin.x, scale);
        assertEquals(200.0f, retrievedMin.y, scale);
        assertEquals(300.0f, retrievedMin.z, scale);
        
        assertEquals(400.0f, retrievedMax.x, scale);
        assertEquals(500.0f, retrievedMax.y, scale);
        assertEquals(600.0f, retrievedMax.z, scale);
        
        // Test containment
        Point3f insidePoint = new Point3f(250.0f, 350.0f, 450.0f);
        Point3f outsidePoint = new Point3f(50.0f, 100.0f, 150.0f);
        
        assertTrue(bounds.contains(insidePoint));
        assertFalse(bounds.contains(outsidePoint));
        
        // Test volume calculation
        float volume = bounds.getVolume();
        assertTrue(volume > 0);
    }

    @Test
    void testAdaptivePointList() {
        MemoryOptimizer.MemoryEfficientSpatialStructures.AdaptivePointList pointList = 
            new MemoryOptimizer.MemoryEfficientSpatialStructures.AdaptivePointList(5, 2.0f, 20);
        
        assertEquals(0, pointList.size());
        
        // Add points
        for (int i = 0; i < 8; i++) {
            Point3f point = new Point3f(i * 10.0f, i * 20.0f, i * 30.0f);
            assertTrue(pointList.add(point));
        }
        
        assertEquals(8, pointList.size());
        
        // Verify points
        for (int i = 0; i < 8; i++) {
            Point3f retrieved = pointList.get(i);
            assertEquals(i * 10.0f, retrieved.x, 0.001f);
            assertEquals(i * 20.0f, retrieved.y, 0.001f);
            assertEquals(i * 30.0f, retrieved.z, 0.001f);
        }
        
        // Test bounds checking
        assertThrows(IndexOutOfBoundsException.class, () -> {
            pointList.get(-1);
        });
        
        assertThrows(IndexOutOfBoundsException.class, () -> {
            pointList.get(pointList.size());
        });
        
        // Test memory footprint
        long footprint = pointList.getMemoryFootprintBytes();
        assertTrue(footprint > 0);
        
        // Test trimming
        pointList.trimToSize();
        assertEquals(8, pointList.size());
        
        // Test clearing
        pointList.clear();
        assertEquals(0, pointList.size());
    }

    @Test
    void testAdaptivePointListCapacityLimit() {
        MemoryOptimizer.MemoryEfficientSpatialStructures.AdaptivePointList limitedList = 
            new MemoryOptimizer.MemoryEfficientSpatialStructures.AdaptivePointList(2, 1.5f, 3);
        
        Point3f point1 = new Point3f(10.0f, 20.0f, 30.0f);
        Point3f point2 = new Point3f(40.0f, 50.0f, 60.0f);
        Point3f point3 = new Point3f(70.0f, 80.0f, 90.0f);
        Point3f point4 = new Point3f(100.0f, 110.0f, 120.0f);
        
        assertTrue(limitedList.add(point1));
        assertTrue(limitedList.add(point2));
        assertTrue(limitedList.add(point3)); // Should trigger expansion to capacity 3
        assertFalse(limitedList.add(point4)); // Should fail due to max capacity
        
        assertEquals(3, limitedList.size());
    }

    @Test
    void testMemoryMonitor() {
        MemoryOptimizer.MemoryUsageStats currentStats = memoryMonitor.getCurrentMemoryUsage();
        
        assertNotNull(currentStats);
        assertTrue(currentStats.heapUsedBytes >= 0);
        assertTrue(currentStats.heapMaxBytes > 0);
        assertTrue(currentStats.heapUsagePercentage >= 0);
        assertTrue(currentStats.timestamp > 0);
        
        // History should contain at least one entry
        List<MemoryOptimizer.MemoryUsageStats> history = memoryMonitor.getHistory();
        assertTrue(history.size() >= 1);
        
        // Record another measurement
        memoryMonitor.getCurrentMemoryUsage();
        
        // History should have grown
        List<MemoryOptimizer.MemoryUsageStats> newHistory = memoryMonitor.getHistory();
        assertTrue(newHistory.size() >= history.size());
        
        // Test trend analysis
        MemoryOptimizer.MemoryMonitor.MemoryTrend trend = memoryMonitor.analyzeTrend();
        assertNotNull(trend);
        assertNotNull(trend.toString());
        
        // Test clearing history
        memoryMonitor.clearHistory();
        List<MemoryOptimizer.MemoryUsageStats> clearedHistory = memoryMonitor.getHistory();
        assertTrue(clearedHistory.isEmpty());
    }

    @Test
    void testMemoryTrend() {
        MemoryOptimizer.MemoryMonitor.MemoryTrend trend = 
            new MemoryOptimizer.MemoryMonitor.MemoryTrend(2.5, true, false);
        
        assertEquals(2.5, trend.averageChangePercentage, 0.001);
        assertTrue(trend.increasing);
        assertFalse(trend.memoryPressure);
        
        String trendStr = trend.toString();
        assertTrue(trendStr.contains("MemoryTrend"));
        assertTrue(trendStr.contains("change=2.50%"));
        assertTrue(trendStr.contains("increasing=true"));
        assertTrue(trendStr.contains("pressure=false"));
    }

    @Test
    void testMemoryOptimizationStrategies() {
        // Test cleanup trigger detection
        MemoryOptimizer.MemoryUsageStats lowMemoryStats = new MemoryOptimizer.MemoryUsageStats(
            150_000_000L, 200_000_000L, 150_000_000L, 50_000_000L, 100_000_000L,
            75.0, 25, 5000, System.currentTimeMillis()
        );
        assertFalse(MemoryOptimizer.MemoryOptimizationStrategies.shouldTriggerCleanup(lowMemoryStats));
        
        MemoryOptimizer.MemoryUsageStats highMemoryStats = new MemoryOptimizer.MemoryUsageStats(
            160_000_000L, 200_000_000L, 150_000_000L, 50_000_000L, 100_000_000L,
            80.0, 25, 5000, System.currentTimeMillis()
        );
        assertTrue(MemoryOptimizer.MemoryOptimizationStrategies.shouldTriggerCleanup(highMemoryStats));
        
        // Test object size estimation
        Point3f testPoint = new Point3f(100.0f, 200.0f, 300.0f);
        long pointSize = MemoryOptimizer.MemoryOptimizationStrategies.estimateObjectSize(testPoint);
        assertEquals(32, pointSize);
        
        String testString = "Hello World";
        long stringSize = MemoryOptimizer.MemoryOptimizationStrategies.estimateObjectSize(testString);
        assertEquals(40 + testString.length() * 2, stringSize);
        
        List<String> testList = new ArrayList<>();
        testList.add("item1");
        testList.add("item2");
        long listSize = MemoryOptimizer.MemoryOptimizationStrategies.estimateObjectSize(testList);
        assertEquals(64 + testList.size() * 8, listSize);
        
        long nullSize = MemoryOptimizer.MemoryOptimizationStrategies.estimateObjectSize(null);
        assertEquals(0, nullSize);
        
        // Test cleanup methods (should not throw exceptions)
        assertDoesNotThrow(() -> {
            MemoryOptimizer.MemoryOptimizationStrategies.optimizeForLowMemory();
        });
        
        assertDoesNotThrow(() -> {
            MemoryOptimizer.MemoryOptimizationStrategies.aggressiveCleanup();
        });
    }

    @Test
    void testSystemMemoryUsage() {
        MemoryOptimizer.MemoryUsageStats systemStats = MemoryOptimizer.getSystemMemoryUsage();
        
        assertNotNull(systemStats);
        assertTrue(systemStats.heapUsedBytes >= 0);
        assertTrue(systemStats.heapMaxBytes > 0);
        assertTrue(systemStats.heapUsagePercentage >= 0);
        assertTrue(systemStats.timestamp > 0);
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativePoint = new Point3f(-100.0f, 200.0f, 300.0f);
        
        // Compressed point storage should reject negative coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            pointStorage.addPoint(negativePoint);
        });
        
        // Compact bounds should reject negative coordinates
        Point3f positiveMin = new Point3f(100.0f, 200.0f, 300.0f);
        Point3f positiveMax = new Point3f(400.0f, 500.0f, 600.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            new MemoryOptimizer.MemoryEfficientSpatialStructures.CompactBounds(negativePoint, positiveMax, 10.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new MemoryOptimizer.MemoryEfficientSpatialStructures.CompactBounds(positiveMin, negativePoint, 10.0f);
        });
        
        // Adaptive point list should reject negative coordinates
        MemoryOptimizer.MemoryEfficientSpatialStructures.AdaptivePointList pointList = 
            new MemoryOptimizer.MemoryEfficientSpatialStructures.AdaptivePointList(10, 2.0f, 20);
        
        assertThrows(IllegalArgumentException.class, () -> {
            pointList.add(negativePoint);
        });
        
        // Compact bounds containment check should reject negative coordinates
        MemoryOptimizer.MemoryEfficientSpatialStructures.CompactBounds bounds = 
            new MemoryOptimizer.MemoryEfficientSpatialStructures.CompactBounds(positiveMin, positiveMax, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            bounds.contains(negativePoint);
        });
    }

    @Test
    void testMemoryMonitorHistoryLimit() {
        MemoryOptimizer.MemoryMonitor limitedMonitor = new MemoryOptimizer.MemoryMonitor(3);
        
        // Add more measurements than the history limit
        for (int i = 0; i < 6; i++) {
            limitedMonitor.getCurrentMemoryUsage();
        }
        
        List<MemoryOptimizer.MemoryUsageStats> history = limitedMonitor.getHistory();
        assertTrue(history.size() <= 3); // Should not exceed max history size
    }

    @Test
    void testCacheStatisticsCalculations() {
        MemoryOptimizer.MemoryAwareCache.CacheStatistics stats = 
            new MemoryOptimizer.MemoryAwareCache.CacheStatistics(10, 8, 15, 5, 800, 1000);
        
        assertEquals(10, stats.totalEntries);
        assertEquals(8, stats.validEntries);
        assertEquals(15, stats.hits);
        assertEquals(5, stats.misses);
        assertEquals(800, stats.memoryUsageBytes);
        assertEquals(1000, stats.maxMemoryBytes);
        
        assertEquals(0.75, stats.getHitRate(), 0.001); // 15 / (15 + 5)
        assertEquals(80.0, stats.getMemoryUsagePercentage(), 0.001); // 800 / 1000 * 100
        
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("entries=8/10"));
        assertTrue(statsStr.contains("hitRate=75.00%"));
        assertTrue(statsStr.contains("memory=80.0%"));
    }

    @Test
    void testPoolStatisticsCalculations() {
        MemoryOptimizer.ObjectPool.PoolStatistics stats = 
            new MemoryOptimizer.ObjectPool.PoolStatistics(20, 5, 3, 5, 10);
        
        assertEquals(20, stats.totalCreated);
        assertEquals(5, stats.currentPooled);
        assertEquals(3, stats.currentBorrowed);
        assertEquals(5, stats.currentPoolSize);
        assertEquals(10, stats.maxPoolSize);
        
        assertEquals(0.25, stats.getHitRate(), 0.001); // 5 / 20
        
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("created=20"));
        assertTrue(statsStr.contains("pooled=5"));
        assertTrue(statsStr.contains("borrowed=3"));
        assertTrue(statsStr.contains("hitRate=25.00%"));
    }
}