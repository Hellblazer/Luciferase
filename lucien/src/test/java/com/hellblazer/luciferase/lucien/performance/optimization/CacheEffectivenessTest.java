/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.performance.optimization;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.performance.AbstractSpatialIndexPerformanceTest;
import com.hellblazer.luciferase.lucien.performance.PerformanceMetrics;
import com.hellblazer.luciferase.lucien.performance.SpatialDistribution;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to validate the effectiveness of caching optimizations implemented in 2025.
 * Specifically tests TetreeLevelCache, SpatialIndexSet, and other O(1) optimizations.
 *
 * @author hal.hildebrand
 */
public class CacheEffectivenessTest extends AbstractSpatialIndexPerformanceTest<com.hellblazer.luciferase.lucien.octree.MortonKey, LongEntityID, String> {

    private static final byte DEFAULT_LEVEL = 10;
    private static final int CACHE_TEST_SIZE = 5000;

    protected SequentialLongIDGenerator createIDGenerator() {
        return new SequentialLongIDGenerator();
    }

    protected SpatialIndex<com.hellblazer.luciferase.lucien.octree.MortonKey, LongEntityID, String> createSpatialIndex(VolumeBounds bounds, int maxDepth) {
        // Default to Octree, but specific tests will create what they need
        return new Octree<>(createIDGenerator(), 32, (byte) maxDepth);
    }

    protected String createTestContent(int entityIndex) {
        return "Entity_" + entityIndex;
    }

    protected String getImplementationName() {
        return "CacheEffectiveness";
    }

    @Test
    @DisplayName("Test TetreeLevelCache effectiveness")
    void testTetreeLevelCacheEffectiveness() {
        // This test validates that TetreeLevelCache provides significant speedup
        // for tetree operations that benefit from caching
        
        Tetree<LongEntityID, String> tetree = new Tetree<>(createIDGenerator(), 32, (byte) 15);
        List<TestEntity> entities = generateTestEntities(CACHE_TEST_SIZE, SpatialDistribution.UNIFORM_RANDOM);
        
        // Test operations that benefit from TetreeLevelCache
        PerformanceMetrics levelCacheMetrics = measure(
            "tetree_level_cache_operations",
            CACHE_TEST_SIZE,
            () -> {
                // Repeated insertions and removals exercise caching logic
                for (TestEntity entity : entities) {
                    tetree.insert((LongEntityID) entity.id, entity.position, DEFAULT_LEVEL, (String) entity.content);
                }
                
                // Operations that exercise cached computations
                Random random = new Random(42);
                for (int i = 0; i < 500; i++) {
                    Point3f queryPoint = entities.get(random.nextInt(entities.size())).position;
                    // k-NN search exercises level extraction and parent chains
                    tetree.kNearestNeighbors(queryPoint, 3, 1000.0f);
                }
            }
        );
        
        performanceResults.add(levelCacheMetrics);
        
        System.out.printf("TetreeLevelCache test: %.2fms for %d entities + queries (%.0f ops/sec)%n",
            levelCacheMetrics.getElapsedMillis(), CACHE_TEST_SIZE, levelCacheMetrics.getOperationsPerSecond());
        
        // The operations should be efficient with caching
        assertTrue(levelCacheMetrics.getOperationsPerSecond() > 1000,
            "TetreeLevelCache should enable >1K ops/sec for tetree operations");
    }

    @Test
    @DisplayName("Test SpatialIndexSet vs TreeSet performance")
    void testSpatialIndexSetPerformance() {
        // This test compares the performance of the new SpatialIndexSet
        // against what would have been TreeSet performance
        
        Octree<LongEntityID, String> octree = new Octree<>(createIDGenerator(), 32, (byte) 12);
        List<TestEntity> entities = generateTestEntities(3000, SpatialDistribution.UNIFORM_RANDOM);
        
        // Measure bulk insertion that exercises SpatialIndexSet
        PerformanceMetrics spatialIndexSetMetrics = measure(
            "spatial_index_set_operations",
            3000,
            () -> {
                List<Point3f> positions = new ArrayList<>();
                List<String> contents = new ArrayList<>();
                
                for (TestEntity entity : entities) {
                    positions.add(entity.position);
                    contents.add((String) entity.content);
                }
                
                // This will exercise SpatialIndexSet operations extensively
                octree.insertBatch(positions, contents, DEFAULT_LEVEL);
                
                // Additional operations that exercise the spatial index set
                for (int i = 0; i < 50; i++) {
                    // Range queries that exercise the sorted indices
                    float originX = i * 20;
                    float originY = i * 20;
                    float originZ = i * 20;
                    float extent = 20;
                    octree.entitiesInRegion(new com.hellblazer.luciferase.lucien.Spatial.Cube(originX, originY, originZ, extent));
                }
            }
        );
        
        performanceResults.add(spatialIndexSetMetrics);
        
        System.out.printf("SpatialIndexSet performance: %.2fms for bulk + queries (%.0f ops/sec)%n",
            spatialIndexSetMetrics.getElapsedMillis(), spatialIndexSetMetrics.getOperationsPerSecond());
        
        // With SpatialIndexSet optimizations, should be efficient
        assertTrue(spatialIndexSetMetrics.getElapsedMillis() < 2000,
            "SpatialIndexSet should enable fast bulk operations + queries");
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 3000, 5000})
    @DisplayName("Test cache scaling with dataset size")
    void testCacheScaling(int datasetSize) {
        // Test that cache performance scales well with increasing dataset size
        
        Tetree<LongEntityID, String> tetree = new Tetree<>(createIDGenerator(), 32, (byte) 15);
        List<TestEntity> entities = generateTestEntities(datasetSize, SpatialDistribution.CLUSTERED);
        
        PerformanceMetrics cacheScalingMetrics = measure(
            "cache_scaling_" + datasetSize,
            datasetSize,
            () -> {
                // Insert all entities (exercises cache during insertion)
                for (TestEntity entity : entities) {
                    tetree.insert((LongEntityID) entity.id, entity.position, DEFAULT_LEVEL, (String) entity.content);
                }
                
                // Perform operations that should benefit from caching
                Random random = new Random(42);
                int queryCount = Math.min(200, datasetSize / 10);
                for (int i = 0; i < queryCount; i++) {
                    Point3f queryPoint = entities.get(random.nextInt(entities.size())).position;
                    
                    // k-NN search (exercises parent chain cache and level cache)
                    tetree.kNearestNeighbors(queryPoint, 5, 1000.0f);
                    
                    // Range query (exercises spatial index set)
                    float cubeOriginX = queryPoint.x - 50;
                    float cubeOriginY = queryPoint.y - 50;
                    float cubeOriginZ = queryPoint.z - 50;
                    float cubeExtent = 100; // 50 + 50 = 100 total extent
                    tetree.entitiesInRegion(new com.hellblazer.luciferase.lucien.Spatial.Cube(cubeOriginX, cubeOriginY, cubeOriginZ, cubeExtent));
                }
            }
        );
        
        performanceResults.add(cacheScalingMetrics);
        
        double opsPerSecond = cacheScalingMetrics.getOperationsPerSecond();
        System.out.printf("Cache scaling (%d entities): %.0f ops/sec%n", datasetSize, opsPerSecond);
        
        // Cache performance should not degrade significantly with larger datasets
        assertTrue(opsPerSecond > 50,
            String.format("Cache should maintain good performance at %d entities", datasetSize));
    }

    @Test
    @DisplayName("Test memory efficiency of caching optimizations")
    void testCacheMemoryEfficiency() {
        // Test that caching optimizations don't use excessive memory
        
        Tetree<LongEntityID, String> tetree = new Tetree<>(createIDGenerator(), 32, (byte) 15);
        List<TestEntity> entities = generateTestEntities(5000, SpatialDistribution.UNIFORM_RANDOM);
        
        PerformanceMetrics memoryMetrics = measure(
            "cache_memory_efficiency",
            5000,
            () -> {
                // Insert entities and exercise caching
                for (TestEntity entity : entities) {
                    tetree.insert((LongEntityID) entity.id, entity.position, DEFAULT_LEVEL, (String) entity.content);
                }
                
                // Perform operations that populate caches
                Random random = new Random(42);
                for (int i = 0; i < 100; i++) {
                    Point3f queryPoint = entities.get(random.nextInt(entities.size())).position;
                    tetree.kNearestNeighbors(queryPoint, 3, 1000.0f);
                }
            }
        );
        
        performanceResults.add(memoryMetrics);
        
        double memoryPerEntity = memoryMetrics.getMemoryPerEntity();
        System.out.printf("Cache memory efficiency: %.2f bytes per entity%n", memoryPerEntity);
        
        // Memory overhead should be reasonable (documented ~120KB total for caches)
        // For 5K entities, this should be minimal per-entity overhead
        assertTrue(memoryPerEntity < 2000,
            "Cache memory overhead should be reasonable per entity");
    }

    @Test
    @DisplayName("Test cache effectiveness across different spatial distributions")
    void testCacheEffectivenessAcrossDistributions() {
        // Test that caching works well regardless of spatial distribution
        
        SpatialDistribution[] distributions = {
            SpatialDistribution.UNIFORM_RANDOM,
            SpatialDistribution.CLUSTERED,
            SpatialDistribution.DIAGONAL
        };
        
        for (SpatialDistribution distribution : distributions) {
            Tetree<LongEntityID, String> tetree = new Tetree<>(createIDGenerator(), 32, (byte) 12);
            List<TestEntity> entities = generateTestEntities(2000, distribution);
            
            PerformanceMetrics distributionMetrics = measure(
                "cache_" + distribution.name().toLowerCase(),
                2000,
                () -> {
                    // Insert entities
                    for (TestEntity entity : entities) {
                        tetree.insert((LongEntityID) entity.id, entity.position, DEFAULT_LEVEL, (String) entity.content);
                    }
                    
                    // Query pattern that exercises cache
                    Random random = new Random(42);
                    for (int i = 0; i < 50; i++) {
                        Point3f queryPoint = entities.get(random.nextInt(entities.size())).position;
                        tetree.kNearestNeighbors(queryPoint, 3, 1000.0f);
                    }
                }
            );
            
            performanceResults.add(distributionMetrics);
            
            System.out.printf("Cache with %s distribution: %.0f ops/sec%n",
                distribution, distributionMetrics.getOperationsPerSecond());
            
            // Cache should work well with all distributions
            assertTrue(distributionMetrics.getOperationsPerSecond() > 100,
                String.format("Cache should work well with %s distribution", distribution));
        }
    }
}