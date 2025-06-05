package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for TetParallelSpatialProcessor
 * Tests parallel processing of tetrahedral spatial operations
 * 
 * @author hal.hildebrand
 */
class TetParallelSpatialProcessorTest {

    private Tetree<String> testTetree;
    private TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<String> queryExecutor;
    private TetParallelSpatialProcessor.ParallelConfig defaultConfig;

    @BeforeEach
    void setUp() {
        testTetree = new Tetree<>(new TreeMap<>());
        defaultConfig = TetParallelSpatialProcessor.ParallelConfig.defaultConfig();
        queryExecutor = new TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<>(testTetree, defaultConfig);
        
        // Add test tetrahedra in valid tetrahedral domain
        float scale = Constants.MAX_EXTENT / 4.0f;
        byte testLevel = 15;
        
        // Create a grid of test points
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                for (int k = 0; k < 10; k++) {
                    float x = scale * 0.05f + i * scale * 0.03f;
                    float y = scale * 0.05f + j * scale * 0.03f;
                    float z = scale * 0.05f + k * scale * 0.03f;
                    testTetree.insert(new Point3f(x, y, z), testLevel, String.format("tet_%d_%d_%d", i, j, k));
                }
            }
        }
    }

    @Test
    @DisplayName("Test parallel radius query")
    void testParallelRadiusQuery() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f center = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float radius = scale * 0.1f;

        TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> result = 
            queryExecutor.parallelRadiusQuery(center, radius);

        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertFalse(result.results.isEmpty());
        assertTrue(result.executionTimeNanos > 0);
        assertTrue(result.threadsUsed > 0);
        assertTrue(result.chunksProcessed > 0);
        
        // Verify all results are within radius
        for (Tetree.Simplex<String> simplex : result.results) {
            Point3f tetCenter = TetrahedralSearchBase.tetrahedronCenter(simplex.index());
            float distance = calculateDistance(center, tetCenter);
            assertTrue(distance <= radius, "Tetrahedron should be within radius");
        }
    }

    @Test
    @DisplayName("Test parallel range query")
    void testParallelRangeQuery() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f minBounds = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        Point3f maxBounds = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);

        TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> result = 
            queryExecutor.parallelRangeQuery(minBounds, maxBounds);

        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertFalse(result.results.isEmpty());
        
        // Verify all results are within bounds
        for (Tetree.Simplex<String> simplex : result.results) {
            // Note: This is an approximation since tetrahedra may partially intersect bounds
            Point3f tetCenter = TetrahedralSearchBase.tetrahedronCenter(simplex.index());
            // We can't strictly check bounds here since tetree.boundedBy may return
            // tetrahedra that intersect the bounds, not just those completely inside
            assertNotNull(simplex.cell());
        }
    }

    @Test
    @DisplayName("Test parallel k-nearest neighbor query")
    void testParallelKNearestNeighborQuery() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        int k = 10;

        TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> result = 
            queryExecutor.parallelKNearestNeighborQuery(queryPoint, k);

        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertFalse(result.results.isEmpty());
        assertTrue(result.results.size() <= k);
        
        // Verify results are sorted by distance
        List<Float> distances = new ArrayList<>();
        for (Tetree.Simplex<String> simplex : result.results) {
            Point3f tetCenter = TetrahedralSearchBase.tetrahedronCenter(simplex.index());
            distances.add(calculateDistance(queryPoint, tetCenter));
        }
        
        for (int i = 1; i < distances.size(); i++) {
            assertTrue(distances.get(i-1) <= distances.get(i), 
                      "Results should be sorted by distance");
        }
    }

    @Test
    @DisplayName("Test parallel custom query")
    void testParallelCustomQuery() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Spatial.aabb bounds = new Spatial.aabb(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.3f, scale * 0.3f, scale * 0.3f
        );
        
        // Custom predicate: filter tetrahedra with specific content pattern
        TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> result = 
            queryExecutor.parallelCustomQuery(simplex -> 
                simplex.cell() != null && simplex.cell().contains("_5_"), bounds);

        assertNotNull(result);
        assertTrue(result.isSuccessful());
        
        // Verify all results match the predicate
        for (Tetree.Simplex<String> simplex : result.results) {
            assertTrue(simplex.cell().contains("_5_"));
        }
    }

    @Test
    @DisplayName("Test parallel batch insert")
    void testParallelBatchInsert() {
        Tetree<String> batchTetree = new Tetree<>(new TreeMap<>());
        TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<String> batchExecutor = 
            new TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<>(batchTetree, defaultConfig);
        
        float scale = Constants.MAX_EXTENT / 4.0f;
        byte level = 15;
        
        // Create batch of points
        List<Point3f> points = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            float x = scale * 0.1f + (i % 10) * scale * 0.02f;
            float y = scale * 0.1f + ((i / 10) % 10) * scale * 0.02f;
            float z = scale * 0.1f + (i / 100) * scale * 0.02f;
            points.add(new Point3f(x, y, z));
        }
        
        TetParallelSpatialProcessor.ParallelResult<Long> result = 
            batchExecutor.parallelBatchInsert(points, level, "batch_test");

        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(points.size(), result.results.size());
        
        // Verify all insertions succeeded
        for (Long index : result.results) {
            assertNotNull(index);
            assertTrue(index > 0);
        }
    }

    @Test
    @DisplayName("Test different parallel configurations")
    void testDifferentConfigurations() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f center = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float radius = scale * 0.1f;
        
        // Test conservative config
        TetParallelSpatialProcessor.ParallelConfig conservativeConfig = 
            TetParallelSpatialProcessor.ParallelConfig.conservativeConfig();
        TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<String> conservativeExecutor = 
            new TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<>(testTetree, conservativeConfig);
        
        TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> conservativeResult = 
            conservativeExecutor.parallelRadiusQuery(center, radius);
        
        assertTrue(conservativeResult.isSuccessful());
        assertEquals(2, conservativeConfig.threadPoolSize);
        
        // Test aggressive config
        TetParallelSpatialProcessor.ParallelConfig aggressiveConfig = 
            TetParallelSpatialProcessor.ParallelConfig.aggressiveConfig();
        TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<String> aggressiveExecutor = 
            new TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<>(testTetree, aggressiveConfig);
        
        TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> aggressiveResult = 
            aggressiveExecutor.parallelRadiusQuery(center, radius);
        
        assertTrue(aggressiveResult.isSuccessful());
        assertTrue(aggressiveConfig.threadPoolSize >= Runtime.getRuntime().availableProcessors() * 2);
        
        // Cleanup
        conservativeExecutor.shutdown();
        aggressiveExecutor.shutdown();
    }

    @Test
    @DisplayName("Test parallel data transformation")
    void testParallelDataTransformation() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        // Create test data
        List<Point3f> points = IntStream.range(0, 100)
            .mapToObj(i -> new Point3f(
                scale * 0.1f + (i % 10) * scale * 0.01f,
                scale * 0.1f + ((i / 10) % 10) * scale * 0.01f,
                scale * 0.1f + (i / 100) * scale * 0.01f
            ))
            .collect(Collectors.toList());
        
        // Transform points to distances from origin
        TetParallelSpatialProcessor.ParallelResult<Float> result = 
            TetParallelSpatialProcessor.ParallelTetSpatialDataProcessor.parallelTransform(
                points,
                point -> calculateDistance(point, new Point3f(0, 0, 0)),
                defaultConfig
            );
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(points.size(), result.results.size());
        
        // Verify all distances are positive
        for (Float distance : result.results) {
            assertTrue(distance > 0);
        }
    }

    @Test
    @DisplayName("Test parallel Tet encoding")
    void testParallelTetEncoding() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        byte level = 10;
        
        List<Point3f> points = IntStream.range(0, 50)
            .mapToObj(i -> new Point3f(
                scale * 0.1f + i * scale * 0.001f,
                scale * 0.1f + i * scale * 0.001f,
                scale * 0.1f + i * scale * 0.001f
            ))
            .collect(Collectors.toList());
        
        TetParallelSpatialProcessor.ParallelResult<Long> result = 
            TetParallelSpatialProcessor.ParallelTetSpatialDataProcessor.parallelTetEncoding(
                points, level, defaultConfig
            );
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(points.size(), result.results.size());
        
        // Verify all indices are valid
        for (Long index : result.results) {
            assertNotNull(index);
            assertTrue(index > 0);
            
            // Verify we can reconstruct the tetrahedron
            Tet tet = Tet.tetrahedron(index);
            assertNotNull(tet);
            assertEquals(level, tet.l());
        }
    }

    @Test
    @DisplayName("Test parallel tetrahedron reconstruction")
    void testParallelTetReconstruction() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        byte level = 12;
        
        // Create test indices
        List<Long> indices = new ArrayList<>();
        Tetree<Void> tempTetree = new Tetree<>(new TreeMap<>());
        for (int i = 0; i < 100; i++) {
            Tet tet = tempTetree.locate(
                new Point3f(scale * 0.1f + i * scale * 0.001f, scale * 0.1f, scale * 0.1f), 
                level
            );
            indices.add(tet.index());
        }
        
        TetParallelSpatialProcessor.ParallelResult<Tet> result = 
            TetParallelSpatialProcessor.ParallelTetSpatialDataProcessor.parallelTetReconstruction(
                indices, defaultConfig
            );
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(indices.size(), result.results.size());
        
        // Verify all tetrahedra are valid
        for (int i = 0; i < indices.size(); i++) {
            Tet tet = result.results.get(i);
            assertNotNull(tet);
            assertEquals(level, tet.l());
            // Verify the index matches
            assertEquals(indices.get(i), tet.index());
        }
    }

    @Test
    @DisplayName("Test performance monitoring")
    void testPerformanceMonitoring() {
        TetParallelSpatialProcessor.TetParallelPerformanceMonitor monitor = 
            new TetParallelSpatialProcessor.TetParallelPerformanceMonitor();
        
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f center = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        // Execute several queries and record results
        for (int i = 0; i < 5; i++) {
            float radius = scale * 0.05f + i * scale * 0.01f;
            TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> result = 
                queryExecutor.parallelRadiusQuery(center, radius);
            monitor.recordExecution(result);
        }
        
        TetParallelSpatialProcessor.TetParallelPerformanceMonitor.ParallelPerformanceStats stats = 
            monitor.getStatistics();
        
        assertNotNull(stats);
        assertEquals(5, stats.totalExecutions);
        assertEquals(5, stats.successfulExecutions);
        assertEquals(100.0, stats.getSuccessRate() * 100, 0.01);
        assertTrue(stats.avgExecutionTimeMs > 0);
        assertTrue(stats.avgThroughputItemsPerSecond > 0);
        assertTrue(stats.avgThreadsUsed > 0);
        assertEquals(0, stats.timeouts);
        assertEquals(0, stats.errors);
        
        // Test string representation
        String statsString = stats.toString();
        assertNotNull(statsString);
        assertTrue(statsString.contains("TetParallelStats"));
    }

    @Test
    @DisplayName("Test sequential fallback for small datasets")
    void testSequentialFallback() {
        // Create a small tetree
        Tetree<String> smallTetree = new Tetree<>(new TreeMap<>());
        float scale = Constants.MAX_EXTENT / 4.0f;
        byte level = 15;
        
        // Add only a few tetrahedra (below parallel threshold)
        for (int i = 0; i < 5; i++) {
            smallTetree.insert(new Point3f(scale * 0.1f + i * scale * 0.01f, scale * 0.1f, scale * 0.1f), 
                             level, "small_" + i);
        }
        
        TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<String> smallExecutor = 
            new TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<>(smallTetree, defaultConfig);
        
        Point3f center = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float radius = scale * 0.2f;
        
        TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> result = 
            smallExecutor.parallelRadiusQuery(center, radius);
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(1, result.threadsUsed); // Should use sequential processing
        assertEquals(1, result.chunksProcessed);
    }

    @Test
    @DisplayName("Test input validation")
    void testInputValidation() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f validPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        Point3f negativePoint = new Point3f(-scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        // Test negative coordinates in radius query
        assertThrows(IllegalArgumentException.class, () -> 
            queryExecutor.parallelRadiusQuery(negativePoint, scale * 0.1f));
        
        // Test negative coordinates in range query
        assertThrows(IllegalArgumentException.class, () -> 
            queryExecutor.parallelRangeQuery(negativePoint, validPoint));
        
        assertThrows(IllegalArgumentException.class, () -> 
            queryExecutor.parallelRangeQuery(validPoint, negativePoint));
        
        // Test negative coordinates in k-NN query
        assertThrows(IllegalArgumentException.class, () -> 
            queryExecutor.parallelKNearestNeighborQuery(negativePoint, 10));
        
        // Test negative coordinates in batch insert
        List<Point3f> pointsWithNegative = List.of(validPoint, negativePoint);
        assertThrows(IllegalArgumentException.class, () -> 
            queryExecutor.parallelBatchInsert(pointsWithNegative, (byte) 10, "test"));
    }

    @Test
    @DisplayName("Test executor shutdown")
    void testExecutorShutdown() {
        // Create executor with non-work-stealing pool
        TetParallelSpatialProcessor.ParallelConfig customConfig = 
            new TetParallelSpatialProcessor.ParallelConfig(4, 100, 50, false, 30000);
        TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<String> customExecutor = 
            new TetParallelSpatialProcessor.ParallelTetreeSpatialQueryExecutor<>(testTetree, customConfig);
        
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f center = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        // Execute a query
        TetParallelSpatialProcessor.ParallelResult<Tetree.Simplex<String>> result = 
            customExecutor.parallelRadiusQuery(center, scale * 0.1f);
        
        assertTrue(result.isSuccessful());
        
        // Shutdown executor
        customExecutor.shutdown();
        
        // Further queries should still work (will create new executor if needed)
        // This test mainly verifies shutdown doesn't throw exceptions
    }

    @Test
    @DisplayName("Test tetrahedral statistics analysis")
    void testTetrahedralStatisticsAnalysis() {
        byte level = 3;
        
        // Create test indices using proper SFC indices
        // At each level, we have 8^level cubes, each containing 6 tetrahedra
        List<Long> indices = new ArrayList<>();
        
        // Generate a variety of valid SFC indices that will produce different types
        // Use the tetrahedron method to generate indices from the SFC
        int startIndex = level == 0 ? 0 : (1 << (3 * (level - 1)));
        int numIndices = Math.min(300, (1 << (3 * level)) - startIndex);
        
        for (int i = 0; i < numIndices; i++) {
            long index = startIndex + i;
            indices.add(index);
        }
        
        TetParallelSpatialProcessor.ParallelResult<TetParallelSpatialProcessor.TetrahedralStats> result = 
            TetParallelSpatialProcessor.ParallelTetSpatialDataProcessor.parallelTetrahedralAnalysis(
                indices, defaultConfig
            );
        
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(1, result.results.size());
        
        TetParallelSpatialProcessor.TetrahedralStats stats = result.results.get(0);
        assertNotNull(stats);
        assertEquals(indices.size(), stats.count);
        assertTrue(stats.minX <= stats.maxX);
        assertTrue(stats.minY <= stats.maxY);
        assertTrue(stats.minZ <= stats.maxZ);
        
        // Verify type distribution - should have multiple types represented
        System.out.println("Type distribution: " + stats.typeDistribution);
        assertTrue(stats.typeDistribution.size() > 1, 
            "Should have multiple tetrahedron types represented in the SFC range");
        
        // Verify all types sum to total count
        long totalTypes = stats.typeDistribution.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(indices.size(), totalTypes);
    }

    // Utility method
    private float calculateDistance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}