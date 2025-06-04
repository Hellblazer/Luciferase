package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.TetParallelProcessor.TetParallelRangeQuery;
import com.hellblazer.luciferase.lucien.TetParallelProcessor.TetParallelIntersectionProcessor;
import com.hellblazer.luciferase.lucien.TetParallelProcessor.TetParallelContainmentProcessor;
import com.hellblazer.luciferase.lucien.TetParallelProcessor.TetLoadBalancer;
import com.hellblazer.luciferase.lucien.TetParallelProcessor.TetParallelMetrics;
import com.hellblazer.luciferase.lucien.TetParallelProcessor.TetPointPair;
import com.hellblazer.luciferase.lucien.TetQueryOptimizer.QueryMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TetParallelProcessor (Phase 5C)
 * Tests tetrahedral-specific parallel processing including work-stealing,
 * load balancing, thread-safe caching, and parallel batch operations.
 */
public class TetParallelProcessorTest {

    private TetParallelProcessor processor;
    private TetParallelRangeQuery parallelRangeQuery;
    private TetParallelIntersectionProcessor parallelIntersectionProcessor;
    private TetParallelContainmentProcessor parallelContainmentProcessor;
    private TetLoadBalancer loadBalancer;
    private TetParallelMetrics metrics;

    @BeforeEach
    void setUp() {
        processor = new TetParallelProcessor(4); // Use 4 threads for testing
        parallelRangeQuery = processor.createParallelRangeQuery();
        parallelIntersectionProcessor = processor.createParallelIntersectionProcessor();
        parallelContainmentProcessor = processor.createParallelContainmentProcessor();
        loadBalancer = processor.createLoadBalancer();
        metrics = processor.getMetrics();
        metrics.reset();
    }

    @AfterEach
    void tearDown() {
        processor.shutdown();
    }

    @Test
    @DisplayName("Test parallel tetrahedral range query with work-stealing")
    void testParallelTetRangeQuery() throws Exception {
        // Test with a volume that should generate enough work for parallel processing
        var cube = new Spatial.Cube(200, 200, 200, 400);
        
        var future = parallelRangeQuery.queryTetRangeParallel(cube, QueryMode.INTERSECTING);
        var results = future.get(10, TimeUnit.SECONDS);
        
        // Verify results are valid
        assertNotNull(results, "Results should not be null");
        assertFalse(results.isEmpty(), "Should find some intersecting tetrahedra");
        
        // Verify all results are valid tetrahedral indices
        for (var index : results) {
            assertTrue(index >= 0, "SFC index should be non-negative: " + index);
            var tet = Tet.tetrahedron(index);
            assertNotNull(tet, "Should be able to reconstruct tetrahedron from index");
        }
        
        // Verify results are sorted and unique
        var sortedResults = new ArrayList<>(results);
        sortedResults.sort(Long::compareTo);
        assertEquals(sortedResults, results, "Results should be sorted");
        assertEquals(results.size(), new HashSet<>(results).size(), "Results should be unique");
        
        // Verify metrics were recorded
        assertTrue(metrics.getMetricsSummary().contains("Parallel Range Queries: 1"));
    }

    @Test
    @DisplayName("Test parallel range query with different query modes")
    void testParallelRangeQueryModes() throws Exception {
        var aabb = new Spatial.aabb(100, 100, 100, 300, 300, 300);
        
        // Test all query modes in parallel
        var containedFuture = parallelRangeQuery.queryTetRangeParallel(aabb, QueryMode.CONTAINED);
        var intersectingFuture = parallelRangeQuery.queryTetRangeParallel(aabb, QueryMode.INTERSECTING);
        var overlappingFuture = parallelRangeQuery.queryTetRangeParallel(aabb, QueryMode.OVERLAPPING);
        
        var containedResults = containedFuture.get(10, TimeUnit.SECONDS);
        var intersectingResults = intersectingFuture.get(10, TimeUnit.SECONDS);
        var overlappingResults = overlappingFuture.get(10, TimeUnit.SECONDS);
        
        // Verify results
        assertNotNull(containedResults);
        assertNotNull(intersectingResults);
        assertNotNull(overlappingResults);
        
        // Contained should be subset of intersecting
        assertTrue(intersectingResults.containsAll(containedResults), 
            "Intersecting results should contain all contained results");
        
        // Overlapping should behave similar to intersecting
        assertFalse(overlappingResults.isEmpty(), "Should find overlapping tetrahedra");
    }

    @Test
    @DisplayName("Test parallel intersection batch processing")
    void testParallelIntersectionBatch() throws Exception {
        // Create test tetrahedra indices
        var tetIndices = LongStream.range(0, 100)
            .boxed()
            .collect(Collectors.toList());
        
        var sphere = new Spatial.Sphere(500, 500, 500, 200);
        
        var future = parallelIntersectionProcessor.intersectBatchParallel(tetIndices, sphere);
        var results = future.get(10, TimeUnit.SECONDS);
        
        // Verify results
        assertNotNull(results, "Results should not be null");
        assertEquals(tetIndices.size(), results.size(), "Should have result for each tetrahedron");
        
        // Verify all indices are present
        for (var index : tetIndices) {
            assertTrue(results.containsKey(index), "Should have result for index: " + index);
            assertNotNull(results.get(index), "Result should not be null");
        }
        
        // Verify cache hit rate improvement on second call
        var future2 = parallelIntersectionProcessor.intersectBatchParallel(tetIndices, sphere);
        var results2 = future2.get(10, TimeUnit.SECONDS);
        
        assertEquals(results, results2, "Cached results should match");
        assertTrue(metrics.getCacheHitRate() > 0.0, "Should have cache hits");
        
        // Verify metrics
        assertTrue(metrics.getMetricsSummary().contains("Parallel Intersection Batches: 2"));
    }

    @Test
    @DisplayName("Test parallel containment batch processing")
    void testParallelContainmentBatch() throws Exception {
        // Create test data
        var tetIndices = List.of(0L, 1L, 7L, 8L, 63L); // Mix of different levels
        var testPoints = List.of(
            new Point3f(100, 100, 100),
            new Point3f(500, 500, 500),
            new Point3f(1000, 1000, 1000),
            new Point3f(2000, 2000, 2000)
        );
        
        var future = parallelContainmentProcessor.containsBatchParallel(tetIndices, testPoints);
        var results = future.get(10, TimeUnit.SECONDS);
        
        // Verify results
        assertNotNull(results, "Results should not be null");
        assertEquals(tetIndices.size() * testPoints.size(), results.size(), 
            "Should have result for each tet-point pair");
        
        // Verify all results are non-null booleans
        for (var entry : results.entrySet()) {
            assertNotNull(entry.getKey(), "Key should not be null");
            assertNotNull(entry.getValue(), "Value should not be null");
            assertTrue(entry.getValue() instanceof Boolean, "Value should be boolean");
        }
        
        // Verify metrics
        assertTrue(metrics.getMetricsSummary().contains("Parallel Containment Batches: 1"));
    }

    @Test
    @DisplayName("Test parallel spatial filtering")
    void testParallelSpatialFiltering() throws Exception {
        var tetIndices = LongStream.range(0, 50)
            .boxed()
            .collect(Collectors.toList());
        
        var testPoints = List.of(
            new Point3f(127.5f, 127.5f, 127.5f),      // Fractional cell offset to avoid vertex ambiguity
            new Point3f(10000.5f, 10000.5f, 10000.5f) // Fractional offset, likely not contained
        );
        
        var future = parallelContainmentProcessor.findContainingTetrahedraParallel(tetIndices, testPoints);
        var containingTets = future.get(10, TimeUnit.SECONDS);
        
        // Verify results
        assertNotNull(containingTets, "Results should not be null");
        assertTrue(containingTets.size() <= tetIndices.size(), 
            "Containing tetrahedra should be subset of input");
        
        // Verify all results are from input set
        for (var tetIndex : containingTets) {
            assertTrue(tetIndices.contains(tetIndex), 
                "Result should be from input set: " + tetIndex);
        }
    }

    @Test
    @DisplayName("Test tetrahedral load balancing")
    void testTetLoadBalancing() {
        // Create tetrahedra with different complexity characteristics
        var tetIndices = new ArrayList<Long>();
        
        // Add tetrahedra at different levels (different complexity)
        tetIndices.addAll(LongStream.range(0, 8).boxed().toList());     // Level 0-1
        tetIndices.addAll(LongStream.range(8, 64).boxed().toList());    // Level 2
        tetIndices.addAll(LongStream.range(64, 128).boxed().toList());  // Level 3
        
        var partitions = loadBalancer.balanceTetWorkload(tetIndices);
        
        // Verify partitioning
        assertEquals(processor.getParallelism(), partitions.size(), 
            "Should create one partition per thread");
        
        // Verify all indices are assigned
        var allAssigned = partitions.stream()
            .flatMap(List::stream)
            .collect(Collectors.toSet());
        assertEquals(new HashSet<>(tetIndices), allAssigned, 
            "All indices should be assigned exactly once");
        
        // Verify no empty partitions (unless more partitions than work)
        for (var partition : partitions) {
            if (tetIndices.size() >= processor.getParallelism()) {
                assertFalse(partition.isEmpty(), "Partitions should not be empty when there's enough work");
            }
        }
        
        // Verify metrics
        assertTrue(metrics.getMetricsSummary().contains("Load Balancing Operations: 1"));
    }

    @Test
    @DisplayName("Test parallel execution with automatic load balancing")
    void testExecuteBalanced() throws Exception {
        var tetIndices = LongStream.range(0, 100)
            .boxed()
            .collect(Collectors.toList());
        
        // Execute operation that computes tetrahedron level
        var future = processor.executeBalanced(tetIndices, index -> {
            try {
                var tet = Tet.tetrahedron(index);
                return tet.l();
            } catch (Exception e) {
                return null;
            }
        });
        
        var results = future.get(10, TimeUnit.SECONDS);
        
        // Verify results
        assertNotNull(results, "Results should not be null");
        assertEquals(tetIndices.size(), results.size(), "Should have result for each input");
        
        // Verify all results are valid levels
        for (var level : results) {
            assertNotNull(level, "Level should not be null");
            assertTrue(level >= 0 && level <= Constants.getMaxRefinementLevel(), 
                "Level should be valid: " + level);
        }
    }

    @Test
    @DisplayName("Test parallel processor metrics collection")
    void testParallelMetrics() throws Exception {
        // Perform various parallel operations
        var cube = new Spatial.Cube(100, 100, 100, 100);
        var tetIndices = List.of(0L, 1L, 7L, 8L);
        var points = List.of(new Point3f(50, 50, 50));
        
        // Execute operations
        parallelRangeQuery.queryTetRangeParallel(cube, QueryMode.INTERSECTING).get(5, TimeUnit.SECONDS);
        parallelIntersectionProcessor.intersectBatchParallel(tetIndices, cube).get(5, TimeUnit.SECONDS);
        parallelContainmentProcessor.containsBatchParallel(tetIndices, points).get(5, TimeUnit.SECONDS);
        loadBalancer.balanceTetWorkload(tetIndices);
        
        // Verify metrics
        var summary = metrics.getMetricsSummary();
        assertTrue(summary.contains("Parallel Range Queries: 1"), "Should record range queries");
        assertTrue(summary.contains("Parallel Intersection Batches: 1"), "Should record intersection batches");
        assertTrue(summary.contains("Parallel Containment Batches: 1"), "Should record containment batches");
        assertTrue(summary.contains("Load Balancing Operations: 1"), "Should record load balancing");
        
        // Test metrics reset
        metrics.reset();
        var resetSummary = metrics.getMetricsSummary();
        assertTrue(resetSummary.contains("Parallel Range Queries: 0"), "Metrics should reset");
    }

    @Test
    @DisplayName("Test parallel processor configuration")
    void testParallelProcessorConfiguration() {
        // Test default constructor
        var defaultProcessor = new TetParallelProcessor();
        assertTrue(defaultProcessor.getParallelism() > 0, "Should have positive parallelism");
        defaultProcessor.shutdown();
        
        // Test custom parallelism
        var customProcessor = new TetParallelProcessor(8);
        assertEquals(8, customProcessor.getParallelism(), "Should use custom parallelism");
        customProcessor.shutdown();
        
        // Test processor components
        assertNotNull(processor.createParallelRangeQuery(), "Should create range query");
        assertNotNull(processor.createParallelIntersectionProcessor(), "Should create intersection processor");
        assertNotNull(processor.createParallelContainmentProcessor(), "Should create containment processor");
        assertNotNull(processor.createLoadBalancer(), "Should create load balancer");
        assertNotNull(processor.getMetrics(), "Should provide metrics");
    }

    @Test
    @DisplayName("Test parallel cache management")
    void testParallelCacheManagement() throws Exception {
        var tetIndices = List.of(0L, 1L, 7L);
        var cube = new Spatial.Cube(0, 0, 0, 100);
        
        // Warm up caches
        parallelIntersectionProcessor.intersectBatchParallel(tetIndices, cube).get(5, TimeUnit.SECONDS);
        
        // Clear caches
        processor.clearCaches();
        
        // Operations should still work (rebuilding caches)
        var results = parallelIntersectionProcessor.intersectBatchParallel(tetIndices, cube)
            .get(5, TimeUnit.SECONDS);
        
        assertNotNull(results, "Should work after cache clear");
        assertEquals(tetIndices.size(), results.size(), "Should have all results");
    }

    @Test
    @DisplayName("Test parallel processor error handling")
    void testParallelErrorHandling() throws Exception {
        // Test with some invalid indices mixed with valid ones  
        var mixedIndices = new ArrayList<Long>();
        mixedIndices.addAll(List.of(0L, 1L, 7L)); // Valid indices
        mixedIndices.addAll(List.of(-1L, -100L)); // Invalid indices (negative numbers)
        
        var cube = new Spatial.Cube(0, 0, 0, 100);
        
        // Should handle errors gracefully
        var future = parallelIntersectionProcessor.intersectBatchParallel(mixedIndices, cube);
        var results = future.get(10, TimeUnit.SECONDS);
        
        // Should have results for all indices (false for invalid ones)
        assertEquals(mixedIndices.size(), results.size(), "Should handle all indices");
        
        // Valid indices should have proper results, invalid ones should be false
        assertTrue(results.containsKey(0L), "Should have result for valid index");
        assertTrue(results.containsKey(-1L), "Should have result for invalid index");
        assertEquals(false, results.get(-1L), "Invalid index should return false");
    }

    @Test
    @DisplayName("Test parallel processor performance characteristics")
    void testParallelPerformanceCharacteristics() throws Exception {
        // Test with progressively larger workloads
        var workloads = List.of(10, 50, 100);
        var cube = new Spatial.Cube(100, 100, 100, 200);
        
        for (var workloadSize : workloads) {
            var tetIndices = LongStream.range(0, workloadSize)
                .boxed()
                .collect(Collectors.toList());
            
            long startTime = System.nanoTime();
            
            var future = parallelIntersectionProcessor.intersectBatchParallel(tetIndices, cube);
            var results = future.get(10, TimeUnit.SECONDS);
            
            long duration = System.nanoTime() - startTime;
            
            // Verify performance is reasonable
            assertTrue(duration < 5_000_000_000L, "Should complete in reasonable time: " + duration + "ns");
            assertEquals(workloadSize, results.size(), "Should process all work items");
        }
    }

    @Test
    @DisplayName("Test concurrent parallel operations")
    void testConcurrentParallelOperations() throws Exception {
        var cube = new Spatial.Cube(200, 200, 200, 100);
        var tetIndices = LongStream.range(0, 50).boxed().collect(Collectors.toList());
        var points = List.of(new Point3f(250, 250, 250));
        
        // Execute multiple operations concurrently
        var rangeQuery = parallelRangeQuery.queryTetRangeParallel(cube, QueryMode.INTERSECTING);
        var intersectionBatch = parallelIntersectionProcessor.intersectBatchParallel(tetIndices, cube);
        var containmentBatch = parallelContainmentProcessor.containsBatchParallel(tetIndices, points);
        var balancedExecution = processor.executeBalanced(tetIndices, index -> index * 2);
        
        // Wait for all to complete
        var rangeResults = rangeQuery.get(10, TimeUnit.SECONDS);
        var intersectionResults = intersectionBatch.get(10, TimeUnit.SECONDS);
        var containmentResults = containmentBatch.get(10, TimeUnit.SECONDS);
        var balancedResults = balancedExecution.get(10, TimeUnit.SECONDS);
        
        // Verify all operations completed successfully
        assertNotNull(rangeResults, "Range query should complete");
        assertNotNull(intersectionResults, "Intersection batch should complete");
        assertNotNull(containmentResults, "Containment batch should complete");
        assertNotNull(balancedResults, "Balanced execution should complete");
        
        assertEquals(tetIndices.size(), intersectionResults.size(), "Intersection should process all indices");
        assertEquals(tetIndices.size() * points.size(), containmentResults.size(), "Containment should process all pairs");
        assertEquals(tetIndices.size(), balancedResults.size(), "Balanced execution should process all indices");
    }

}