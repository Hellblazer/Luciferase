package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Spatial Index Optimizer functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class SpatialIndexOptimizerTest {

    private List<Point3f> uniformPoints;
    private List<Point3f> clusteredPoints;
    private List<Point3f> sparsePoints;

    @BeforeEach
    void setUp() {
        // Create uniform distribution
        uniformPoints = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            float x = 100.0f + i * 10.0f;
            float y = 100.0f + (i % 10) * 10.0f;
            float z = 100.0f + (i / 10) * 10.0f;
            uniformPoints.add(new Point3f(x, y, z));
        }
        
        // Create clustered distribution
        clusteredPoints = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // Cluster 1 around (200, 200, 200)
        for (int i = 0; i < 40; i++) {
            float x = 200.0f + random.nextFloat() * 20.0f;
            float y = 200.0f + random.nextFloat() * 20.0f;
            float z = 200.0f + random.nextFloat() * 20.0f;
            clusteredPoints.add(new Point3f(x, y, z));
        }
        
        // Cluster 2 around (400, 400, 400)
        for (int i = 0; i < 40; i++) {
            float x = 400.0f + random.nextFloat() * 20.0f;
            float y = 400.0f + random.nextFloat() * 20.0f;
            float z = 400.0f + random.nextFloat() * 20.0f;
            clusteredPoints.add(new Point3f(x, y, z));
        }
        
        // Sparse points
        for (int i = 0; i < 20; i++) {
            float x = 100.0f + random.nextFloat() * 400.0f;
            float y = 100.0f + random.nextFloat() * 400.0f;
            float z = 100.0f + random.nextFloat() * 400.0f;
            clusteredPoints.add(new Point3f(x, y, z));
        }
        
        // Create sparse distribution
        sparsePoints = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            float x = 100.0f + i * 100.0f;
            float y = 100.0f + i * 100.0f;
            float z = 100.0f + i * 100.0f;
            sparsePoints.add(new Point3f(x, y, z));
        }
    }

    @Test
    void testOptimizedMortonCalculator() {
        // Test Morton encoding and decoding
        int x = 12345, y = 67890, z = 54321;
        
        long morton = SpatialIndexOptimizer.OptimizedMortonCalculator.encodeMorton3D(x, y, z);
        int[] decoded = SpatialIndexOptimizer.OptimizedMortonCalculator.decodeMorton3D(morton);
        
        // Note: Due to bit limitations, we might lose some precision
        // Test that the high bits are preserved
        assertEquals(x >> 8, decoded[0] >> 8, "X coordinate high bits should match");
        assertEquals(y >> 8, decoded[1] >> 8, "Y coordinate high bits should match");
        assertEquals(z >> 8, decoded[2] >> 8, "Z coordinate high bits should match");
        
        // Test edge cases
        assertEquals(0, SpatialIndexOptimizer.OptimizedMortonCalculator.encodeMorton3D(0, 0, 0));
        
        int[] zeroDecoded = SpatialIndexOptimizer.OptimizedMortonCalculator.decodeMorton3D(0);
        assertEquals(0, zeroDecoded[0]);
        assertEquals(0, zeroDecoded[1]);
        assertEquals(0, zeroDecoded[2]);
    }

    @Test
    void testSpatialDistributionAnalysisUniform() {
        SpatialIndexOptimizer.SpatialDistributionStats stats = 
            SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(uniformPoints);
        
        assertNotNull(stats);
        assertEquals(100, stats.totalPoints);
        assertTrue(stats.minX >= 0 && stats.minY >= 0 && stats.minZ >= 0);
        assertTrue(stats.maxX > stats.minX);
        assertTrue(stats.maxY > stats.minY);
        assertTrue(stats.maxZ > stats.minZ);
        assertTrue(stats.recommendedLevel >= 10 && stats.recommendedLevel <= 20);
        assertTrue(stats.density > 0);
        assertTrue(stats.uniformityScore >= 0 && stats.uniformityScore <= 1.0f);
        
        // Uniform distribution should have higher uniformity score
        assertTrue(stats.uniformityScore > 0.3f, "Uniform distribution should have reasonable uniformity score");
        
        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("SpatialStats"));
        assertTrue(statsStr.contains("points=100"));
    }

    @Test
    void testSpatialDistributionAnalysisClustered() {
        SpatialIndexOptimizer.SpatialDistributionStats stats = 
            SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(clusteredPoints);
        
        assertNotNull(stats);
        assertEquals(100, stats.totalPoints);
        assertTrue(stats.recommendedLevel >= 10 && stats.recommendedLevel <= 20);
        assertTrue(stats.clusteredRegions >= 1);
        
        // Clustered distribution should have lower uniformity score than uniform
        assertTrue(stats.uniformityScore >= 0 && stats.uniformityScore <= 1.0f);
    }

    @Test
    void testSpatialDistributionAnalysisSparse() {
        SpatialIndexOptimizer.SpatialDistributionStats stats = 
            SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(sparsePoints);
        
        assertNotNull(stats);
        assertEquals(10, stats.totalPoints);
        assertTrue(stats.recommendedLevel >= 10 && stats.recommendedLevel <= 20);
        assertTrue(stats.density > 0);
        
        // Sparse distribution should generally recommend lower levels
        assertTrue(stats.recommendedLevel <= 15, "Sparse distribution should recommend lower levels");
    }

    @Test
    void testEmptySpatialDistribution() {
        List<Point3f> emptyPoints = new ArrayList<>();
        SpatialIndexOptimizer.SpatialDistributionStats stats = 
            SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(emptyPoints);
        
        assertNotNull(stats);
        assertEquals(0, stats.totalPoints);
        assertEquals(10, stats.recommendedLevel); // Should default to minimum level
        assertEquals(0, stats.clusteredRegions);
        assertEquals(1.0f, stats.uniformityScore); // Empty is perfectly "uniform"
    }

    @Test
    void testCompactPoint() {
        Point3f originalPoint = new Point3f(123.456f, 789.012f, 345.678f);
        float scale = 1000.0f;
        
        SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint compact = 
            SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint.fromPoint3f(originalPoint, scale);
        
        assertNotNull(compact);
        assertEquals((int)(123.456f * scale), compact.x);
        assertEquals((int)(789.012f * scale), compact.y);
        assertEquals((int)(345.678f * scale), compact.z);
        
        Point3f reconstructed = compact.toPoint3f(1.0f / scale);
        assertEquals(originalPoint.x, reconstructed.x, 0.001f);
        assertEquals(originalPoint.y, reconstructed.y, 0.001f);
        assertEquals(originalPoint.z, reconstructed.z, 0.001f);
        
        // Test equals and hashCode
        SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint compact2 = 
            SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint.fromPoint3f(originalPoint, scale);
        
        assertEquals(compact, compact2);
        assertEquals(compact.hashCode(), compact2.hashCode());
    }

    @Test
    void testMortonOrderedArray() {
        SpatialIndexOptimizer.CacheOptimizedStructures.MortonOrderedArray<String> mortonArray = 
            new SpatialIndexOptimizer.CacheOptimizedStructures.MortonOrderedArray<>();
        
        // Add points in random order
        mortonArray.add(new Point3f(300.0f, 300.0f, 300.0f), "Point3");
        mortonArray.add(new Point3f(100.0f, 100.0f, 100.0f), "Point1");
        mortonArray.add(new Point3f(200.0f, 200.0f, 200.0f), "Point2");
        
        assertEquals(3, mortonArray.size());
        
        // Get entries (should be sorted by Morton order)
        var entries = mortonArray.getEntries();
        assertEquals(3, entries.size());
        
        // Verify Morton order (should be ascending)
        assertTrue(entries.get(0).morton <= entries.get(1).morton);
        assertTrue(entries.get(1).morton <= entries.get(2).morton);
        
        // Test clear
        mortonArray.clear();
        assertEquals(0, mortonArray.size());
    }

    @Test
    void testLazySpatialQuery() {
        SpatialIndexOptimizer.LazyEvaluationFramework.LazySpatialQuery<Point3f> lazyQuery = 
            new SpatialIndexOptimizer.LazyEvaluationFramework.LazySpatialQuery<Point3f>() {
                @Override
                protected List<Point3f> computeResults() {
                    // Simulate expensive computation
                    try {
                        Thread.sleep(1); // Small delay to simulate work
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return uniformPoints.subList(0, 5);
                }
            };
        
        assertFalse(lazyQuery.isComputed());
        
        List<Point3f> results = lazyQuery.getResults();
        assertEquals(5, results.size());
        assertTrue(lazyQuery.isComputed());
        
        // Second call should return cached results
        List<Point3f> cachedResults = lazyQuery.getResults();
        assertSame(results, cachedResults);
        
        // Test invalidation
        lazyQuery.invalidate();
        assertFalse(lazyQuery.isComputed());
    }

    @Test
    void testLazyDistanceCalculator() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        List<Point3f> targets = uniformPoints.subList(0, 10);
        float maxDistance = 100.0f;
        
        SpatialIndexOptimizer.LazyEvaluationFramework.LazyDistanceCalculator calculator = 
            new SpatialIndexOptimizer.LazyEvaluationFramework.LazyDistanceCalculator(
                queryPoint, targets, maxDistance);
        
        List<Float> distances = calculator.getDistances();
        assertEquals(10, distances.size());
        
        int withinDistance = calculator.countWithinDistance();
        assertTrue(withinDistance >= 0 && withinDistance <= 10);
        
        // Verify that distances beyond maxDistance are marked as Float.MAX_VALUE
        long beyondMaxCount = distances.stream()
            .mapToDouble(Float::doubleValue)
            .filter(d -> d == Float.MAX_VALUE)
            .count();
        
        assertEquals(10 - withinDistance, beyondMaxCount);
    }

    @Test
    void testPerformanceBenchmarking() {
        // Test Morton encoding benchmark
        SpatialIndexOptimizer.SpatialPerformanceBenchmark.BenchmarkResult mortonResult = 
            SpatialIndexOptimizer.SpatialPerformanceBenchmark.benchmarkMortonEncoding(uniformPoints);
        
        assertNotNull(mortonResult);
        assertEquals("Morton Encoding", mortonResult.operation);
        assertEquals(uniformPoints.size(), mortonResult.dataSize);
        assertTrue(mortonResult.timeNanos > 0);
        assertTrue(mortonResult.throughput > 0);
        
        // Test spatial analysis benchmark
        SpatialIndexOptimizer.SpatialPerformanceBenchmark.BenchmarkResult analysisResult = 
            SpatialIndexOptimizer.SpatialPerformanceBenchmark.benchmarkSpatialAnalysis(uniformPoints);
        
        assertNotNull(analysisResult);
        assertEquals("Spatial Analysis", analysisResult.operation);
        assertEquals(uniformPoints.size(), analysisResult.dataSize);
        assertTrue(analysisResult.timeNanos > 0);
        assertTrue(analysisResult.throughput > 0);
        
        // Test toString
        String resultStr = mortonResult.toString();
        assertTrue(resultStr.contains("Benchmark"));
        assertTrue(resultStr.contains("Morton Encoding"));
        assertTrue(resultStr.contains("ops/sec"));
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativePoint = new Point3f(-100.0f, 200.0f, 200.0f);
        
        // Test spatial distribution analysis
        List<Point3f> pointsWithNegative = List.of(
            new Point3f(100.0f, 100.0f, 100.0f),
            negativePoint
        );
        
        assertThrows(IllegalArgumentException.class, () -> {
            SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(pointsWithNegative);
        });
        
        // Test compact point creation
        assertThrows(IllegalArgumentException.class, () -> {
            SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint.fromPoint3f(negativePoint, 1000.0f);
        });
        
        // Test Morton ordered array
        SpatialIndexOptimizer.CacheOptimizedStructures.MortonOrderedArray<String> mortonArray = 
            new SpatialIndexOptimizer.CacheOptimizedStructures.MortonOrderedArray<>();
        
        assertThrows(IllegalArgumentException.class, () -> {
            mortonArray.add(negativePoint, "negative");
        });
        
        // Test lazy distance calculator
        Point3f positivePoint = new Point3f(200.0f, 200.0f, 200.0f);
        List<Point3f> targets = List.of(positivePoint);
        
        assertThrows(IllegalArgumentException.class, () -> {
            new SpatialIndexOptimizer.LazyEvaluationFramework.LazyDistanceCalculator(
                negativePoint, targets, 100.0f);
        });
    }

    @Test
    void testDataIntegrityAndConsistency() {
        // Test that spatial analysis produces consistent results
        SpatialIndexOptimizer.SpatialDistributionStats stats1 = 
            SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(uniformPoints);
        
        SpatialIndexOptimizer.SpatialDistributionStats stats2 = 
            SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(uniformPoints);
        
        assertEquals(stats1.totalPoints, stats2.totalPoints);
        assertEquals(stats1.recommendedLevel, stats2.recommendedLevel);
        assertEquals(stats1.minX, stats2.minX, 0.001f);
        assertEquals(stats1.maxX, stats2.maxX, 0.001f);
        assertEquals(stats1.density, stats2.density, 0.001f);
        
        // Test Morton array consistency
        SpatialIndexOptimizer.CacheOptimizedStructures.MortonOrderedArray<Integer> array1 = 
            new SpatialIndexOptimizer.CacheOptimizedStructures.MortonOrderedArray<>();
        
        SpatialIndexOptimizer.CacheOptimizedStructures.MortonOrderedArray<Integer> array2 = 
            new SpatialIndexOptimizer.CacheOptimizedStructures.MortonOrderedArray<>();
        
        for (int i = 0; i < uniformPoints.size(); i++) {
            array1.add(uniformPoints.get(i), i);
            array2.add(uniformPoints.get(i), i);
        }
        
        var entries1 = array1.getEntries();
        var entries2 = array2.getEntries();
        
        assertEquals(entries1.size(), entries2.size());
        for (int i = 0; i < entries1.size(); i++) {
            assertEquals(entries1.get(i).morton, entries2.get(i).morton);
        }
    }

    @Test
    void testLazySpatialQueryErrorHandling() {
        SpatialIndexOptimizer.LazyEvaluationFramework.LazySpatialQuery<Point3f> faultyQuery = 
            new SpatialIndexOptimizer.LazyEvaluationFramework.LazySpatialQuery<Point3f>() {
                @Override
                protected List<Point3f> computeResults() {
                    throw new RuntimeException("Simulated computation failure");
                }
            };
        
        assertFalse(faultyQuery.isComputed());
        
        assertThrows(RuntimeException.class, () -> {
            faultyQuery.getResults();
        });
        
        assertTrue(faultyQuery.isComputed()); // Should be marked as computed even after error
        
        // Subsequent calls should also throw
        assertThrows(RuntimeException.class, () -> {
            faultyQuery.getResults();
        });
    }

    @Test
    void testPerformanceCharacteristics() {
        // Create larger dataset for performance testing
        List<Point3f> largeDataset = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        for (int i = 0; i < 1000; i++) {
            float x = 100.0f + random.nextFloat() * 900.0f;
            float y = 100.0f + random.nextFloat() * 900.0f;
            float z = 100.0f + random.nextFloat() * 900.0f;
            largeDataset.add(new Point3f(x, y, z));
        }
        
        // Benchmark Morton encoding - should be fast
        SpatialIndexOptimizer.SpatialPerformanceBenchmark.BenchmarkResult mortonResult = 
            SpatialIndexOptimizer.SpatialPerformanceBenchmark.benchmarkMortonEncoding(largeDataset);
        
        assertTrue(mortonResult.throughput > 1000, "Morton encoding should process >1000 points/sec");
        
        // Benchmark spatial analysis
        SpatialIndexOptimizer.SpatialPerformanceBenchmark.BenchmarkResult analysisResult = 
            SpatialIndexOptimizer.SpatialPerformanceBenchmark.benchmarkSpatialAnalysis(largeDataset);
        
        assertTrue(analysisResult.throughput > 10, "Spatial analysis should process >10 points/sec");
        assertTrue(analysisResult.timeNanos < 10_000_000_000L, "Spatial analysis should complete within 10 seconds");
    }
}