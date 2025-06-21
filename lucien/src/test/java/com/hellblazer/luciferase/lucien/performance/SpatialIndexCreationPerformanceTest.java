/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.performance;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for spatial index creation operations.
 * Tests bulk loading, incremental insertion, and construction performance
 * with various data distributions and sizes.
 *
 * @author hal.hildebrand
 */
public abstract class SpatialIndexCreationPerformanceTest<ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> extends AbstractSpatialIndexPerformanceTest<ID, Content> {
    
    private static final byte DEFAULT_LEVEL = 10;
    
    // createTestContent is now abstract in parent class
    
    static Stream<Arguments> spatialDistributions() {
        return Stream.of(
            SpatialDistribution.UNIFORM_RANDOM,
            SpatialDistribution.CLUSTERED,
            SpatialDistribution.DIAGONAL,
            SpatialDistribution.SURFACE_ALIGNED
        ).flatMap(dist -> 
            java.util.Arrays.stream(SMOKE_TEST_SIZES).mapToObj(size -> Arguments.of(dist, size))
        );
    }
    
    static Stream<Arguments> testSizesProvider() {
        return java.util.Arrays.stream(TEST_SIZES).mapToObj(Arguments::of);
    }
    
    @ParameterizedTest(name = "Distribution={0}, Size={1}")
    @MethodSource("spatialDistributions")
    @DisplayName("Test creation performance with different distributions")
    void testCreationPerformance(SpatialDistribution distribution, int size) {
        // Generate test data
        List<TestEntity> entities = generateTestEntities(size, distribution);
        
        // Measure creation time
        PerformanceMetrics metrics = measureAverage(
            String.format("create_%s", distribution.name().toLowerCase()),
            size,
            () -> {}, // No setup needed
            () -> {
                SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                for (TestEntity entity : entities) {
                    index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
                }
            }
        );
        
        performanceResults.add(metrics);
        
        // Basic assertions
        assertTrue(metrics.getElapsedMillis() > 0, "Creation should take measurable time");
        assertTrue(metrics.getOperationsPerSecond() > 0, "Should have positive operations per second");
        
        System.out.printf("%s creation with %s distribution: %d entities in %.2fms (%.0f ops/sec)%n",
            getImplementationName(), distribution, size, metrics.getElapsedMillis(), 
            metrics.getOperationsPerSecond());
    }
    
    @Test
    @DisplayName("Test optimization effectiveness: bulk vs incremental insertion")
    void testOptimizationEffectiveness() {
        int[] testSizes = {1000, 10000, 50000};
        
        for (int size : testSizes) {
            List<TestEntity> entities = generateTestEntities(size, SpatialDistribution.UNIFORM_RANDOM);
            
            // Prepare data for bulk operations
            List<Point3f> positions = new ArrayList<>();
            List<Content> contents = new ArrayList<>();
            for (TestEntity entity : entities) {
                positions.add(entity.position);
                contents.add((Content) entity.content);
            }
            
            // Test 1: Incremental insertion (baseline)
            PerformanceMetrics incrementalMetrics = measure(
                "incremental_insertion_baseline",
                size,
                () -> {
                    SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                    for (TestEntity entity : entities) {
                        index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
                    }
                }
            );
            
            // Test 2: Basic bulk insertion
            PerformanceMetrics bulkMetrics = measure(
                "bulk_insertion_basic",
                size,
                () -> {
                    SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                    index.insertBatch(positions, contents, DEFAULT_LEVEL);
                }
            );
            
            // Test 3: Optimized bulk insertion with pre-allocation
            PerformanceMetrics optimizedBulkMetrics = measure(
                "bulk_insertion_optimized",
                size,
                () -> {
                    SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                    // Enable optimizations if supported by AbstractSpatialIndex
                    if (index instanceof com.hellblazer.luciferase.lucien.AbstractSpatialIndex) {
                        var abstractIndex = (com.hellblazer.luciferase.lucien.AbstractSpatialIndex<ID, Content, ?>) index;
                        // Pre-allocate nodes for better performance
                        abstractIndex.preAllocateAdaptive(positions.subList(0, Math.min(1000, size)), size, DEFAULT_LEVEL);
                    }
                    index.insertBatch(positions, contents, DEFAULT_LEVEL);
                }
            );
            
            // Test 4: Parallel bulk insertion for large datasets
            PerformanceMetrics parallelMetrics = null;
            if (size >= 10000) {
                parallelMetrics = measure(
                    "bulk_insertion_parallel",
                    size,
                    () -> {
                        SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                        try {
                            if (index instanceof com.hellblazer.luciferase.lucien.AbstractSpatialIndex) {
                                var abstractIndex = (com.hellblazer.luciferase.lucien.AbstractSpatialIndex<ID, Content, ?>) index;
                                abstractIndex.insertBatchParallel(positions, contents, DEFAULT_LEVEL);
                            } else {
                                // Fallback to regular bulk insertion
                                index.insertBatch(positions, contents, DEFAULT_LEVEL);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Parallel insertion interrupted", e);
                        }
                    }
                );
            }
            
            // Store all metrics
            performanceResults.add(incrementalMetrics);
            performanceResults.add(bulkMetrics);
            performanceResults.add(optimizedBulkMetrics);
            if (parallelMetrics != null) {
                performanceResults.add(parallelMetrics);
            }
            
            // Calculate and display optimization effectiveness
            double bulkSpeedup = incrementalMetrics.getOperationsPerSecond() / bulkMetrics.getOperationsPerSecond();
            double optimizedSpeedup = incrementalMetrics.getOperationsPerSecond() / optimizedBulkMetrics.getOperationsPerSecond();
            
            System.out.printf("%s Performance Results (%d entities):%n", getImplementationName(), size);
            System.out.printf("  Incremental: %.2f ops/sec (baseline)%n", incrementalMetrics.getOperationsPerSecond());
            System.out.printf("  Bulk:        %.2f ops/sec (%.2fx improvement)%n", 
                bulkMetrics.getOperationsPerSecond(), bulkSpeedup);
            System.out.printf("  Optimized:   %.2f ops/sec (%.2fx improvement)%n", 
                optimizedBulkMetrics.getOperationsPerSecond(), optimizedSpeedup);
            
            if (parallelMetrics != null) {
                double parallelSpeedup = incrementalMetrics.getOperationsPerSecond() / parallelMetrics.getOperationsPerSecond();
                System.out.printf("  Parallel:    %.2f ops/sec (%.2fx improvement)%n", 
                    parallelMetrics.getOperationsPerSecond(), parallelSpeedup);
                
                // Assert parallel provides best performance for large datasets
                assertTrue(parallelSpeedup > 2.0, 
                    String.format("Parallel insertion should provide 2x+ speedup. Actual: %.2fx", parallelSpeedup));
            }
            
            // Assert optimizations provide meaningful improvements
            assertTrue(bulkSpeedup > 1.5, 
                String.format("Bulk insertion should provide 1.5x+ speedup. Actual: %.2fx", bulkSpeedup));
            assertTrue(optimizedSpeedup > 2.0, 
                String.format("Optimized bulk insertion should provide 2x+ speedup. Actual: %.2fx", optimizedSpeedup));
        }
    }
    
    @Test
    @DisplayName("Test creation with pre-sorted vs random order")
    void testSortedVsRandomInsertion() {
        int size = 10000;
        List<TestEntity> entities = generateTestEntities(size, SpatialDistribution.UNIFORM_RANDOM);
        
        // Create spatially sorted version (by Morton code or similar)
        List<TestEntity> sortedEntities = new ArrayList<>(entities);
        sortedEntities.sort((a, b) -> {
            // Simple spatial sorting by diagonal distance
            float distA = a.position.x + a.position.y + a.position.z;
            float distB = b.position.x + b.position.y + b.position.z;
            return Float.compare(distA, distB);
        });
        
        // Random order
        List<TestEntity> randomEntities = new ArrayList<>(entities);
        Collections.shuffle(randomEntities, random);
        
        // Test sorted insertion
        PerformanceMetrics sortedMetrics = measure(
            "sorted_insertion",
            size,
            () -> {
                SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                for (TestEntity entity : sortedEntities) {
                    index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
                }
            }
        );
        
        // Test random insertion
        PerformanceMetrics randomMetrics = measure(
            "random_insertion",
            size,
            () -> {
                SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                for (TestEntity entity : randomEntities) {
                    index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
                }
            }
        );
        
        performanceResults.add(sortedMetrics);
        performanceResults.add(randomMetrics);
        
        double speedup = sortedMetrics.getSpeedup(randomMetrics);
        System.out.printf("Sorted vs Random insertion: %.2fx speedup%n", speedup);
    }
    
    @ParameterizedTest(name = "Size={0}")
    @MethodSource("testSizesProvider")
    @DisplayName("Test creation scalability")
    void testCreationScalability(int size) {
        List<TestEntity> entities = generateTestEntities(size, SpatialDistribution.UNIFORM_RANDOM);
        
        PerformanceMetrics metrics = measure(
            "creation_scalability",
            size,
            () -> {
                SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                for (TestEntity entity : entities) {
                    index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
                }
            }
        );
        
        performanceResults.add(metrics);
        
        // Check that performance scales reasonably (not worse than O(n log n))
        if (size >= 1000) {
            double expectedMaxTime = size * Math.log(size) / 1000.0; // Rough O(n log n) expectation
            assertTrue(metrics.getElapsedMillis() < expectedMaxTime * 10, 
                String.format("Creation time should scale reasonably. Size: %d, Time: %.2fms", 
                    size, metrics.getElapsedMillis()));
        }
    }
    
    @Test
    @DisplayName("Test memory usage during creation")
    void testCreationMemoryUsage() {
        int[] memorySizes = {1000, 10000, 100000};
        
        for (int size : memorySizes) {
            List<TestEntity> entities = generateTestEntities(size, SpatialDistribution.UNIFORM_RANDOM);
            
            PerformanceMetrics metrics = measure(
                "memory_usage",
                size,
                () -> {
                    SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                    for (TestEntity entity : entities) {
                        index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
                    }
                }
            );
            
            performanceResults.add(metrics);
            
            double memoryPerEntity = metrics.getMemoryPerEntity();
            System.out.printf("Memory usage for %d entities: %.2f MB total, %.2f bytes/entity%n",
                size, metrics.getMemoryUsedMB(), memoryPerEntity);
            
            // Sanity check - each entity shouldn't use excessive memory
            assertTrue(memoryPerEntity < 10000, 
                "Memory per entity should be reasonable (< 10KB)");
        }
    }
    
    @Test
    @DisplayName("Test worst-case creation performance")
    void testWorstCaseCreation() {
        int size = 1000;
        List<TestEntity> entities = generateTestEntities(size, SpatialDistribution.WORST_CASE);
        
        PerformanceMetrics worstCaseMetrics = measure(
            "worst_case_creation",
            size,
            () -> {
                SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                for (TestEntity entity : entities) {
                    index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
                }
            }
        );
        
        // Compare with uniform distribution
        List<TestEntity> uniformEntities = generateTestEntities(size, SpatialDistribution.UNIFORM_RANDOM);
        PerformanceMetrics uniformMetrics = measure(
            "uniform_creation",
            size,
            () -> {
                SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
                for (TestEntity entity : uniformEntities) {
                    index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
                }
            }
        );
        
        performanceResults.add(worstCaseMetrics);
        performanceResults.add(uniformMetrics);
        
        double slowdown = worstCaseMetrics.getElapsedNanos() / (double) uniformMetrics.getElapsedNanos();
        System.out.printf("Worst-case vs Uniform: %.2fx slower%n", slowdown);
        
        // Even in worst case, shouldn't be more than 10x slower
        assertTrue(slowdown < 10.0, "Worst case shouldn't be more than 10x slower than uniform");
    }
    
    /**
     * Marker interface for spatial indices that support bulk insertion
     */
    interface BulkInsertable<ID, Content> {
        void insertBatch(List<ID> ids, List<Point3f> positions, List<Content> contents);
    }
}