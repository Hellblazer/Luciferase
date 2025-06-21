/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.performance;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests focusing on memory usage characteristics of spatial indices.
 *
 * @author hal.hildebrand
 */
public abstract class SpatialIndexMemoryPerformanceTest<ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> extends AbstractSpatialIndexPerformanceTest<ID, Content> {
    
    private static final byte DEFAULT_LEVEL = 10;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    
    // createTestContent is now abstract in parent class
    
    @Test
    @DisplayName("Test memory usage scaling with entity count")
    void testMemoryUsageScaling() {
        int[] testSizes = {1000, 5000, 10000, 50000, 100000};
        List<MemoryScalingResult> results = new ArrayList<>();
        
        for (int size : testSizes) {
            // Force GC and measure baseline
            forceGarbageCollection();
            long baselineMemory = getHeapUsage();
            
            // Create index and populate
            SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
            List<TestEntity> entities = generateTestEntities(size, SpatialDistribution.UNIFORM_RANDOM);
            
            for (TestEntity entity : entities) {
                index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
            }
            
            // Force GC and measure after population
            forceGarbageCollection();
            long usedMemory = getHeapUsage() - baselineMemory;
            
            double memoryPerEntity = (double) usedMemory / size;
            results.add(new MemoryScalingResult(size, usedMemory, memoryPerEntity));
            
            PerformanceMetrics metrics = new PerformanceMetrics(
                "memory_scaling",
                size,
                0, // Not timing-based
                usedMemory,
                null
            );
            performanceResults.add(metrics);
            
            System.out.printf("Memory usage for %d entities: %.2f MB (%.2f bytes/entity)%n",
                size, usedMemory / (1024.0 * 1024.0), memoryPerEntity);
        }
        
        // Check that memory scales linearly (within reasonable bounds)
        if (results.size() >= 2) {
            MemoryScalingResult first = results.get(0);
            MemoryScalingResult last = results.get(results.size() - 1);
            
            double expectedRatio = (double) last.size / first.size;
            double actualRatio = (double) last.totalMemory / first.totalMemory;
            
            // Memory should scale roughly linearly (allow 50% deviation)
            assertTrue(actualRatio < expectedRatio * 1.5,
                String.format("Memory usage should scale roughly linearly. Expected ratio: %.2f, Actual: %.2f",
                    expectedRatio, actualRatio));
        }
    }
    
    @Test
    @DisplayName("Test memory fragmentation after many operations")
    void testMemoryFragmentation() {
        int baseSize = 10000;
        int operations = 5000;
        
        // Create initial index
        SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        List<TestEntity> entities = generateTestEntities(baseSize, SpatialDistribution.UNIFORM_RANDOM);
        
        for (TestEntity entity : entities) {
            index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
        }
        
        // Measure initial memory
        forceGarbageCollection();
        long initialMemory = getHeapUsage();
        
        // Perform many insert/remove operations
        List<TestEntity> additionalEntities = generateTestEntities(operations, SpatialDistribution.UNIFORM_RANDOM);
        
        for (int i = 0; i < operations; i++) {
            // Insert new entity
            TestEntity newEntity = additionalEntities.get(i);
            index.insert((ID) newEntity.id, newEntity.position, DEFAULT_LEVEL, (Content) newEntity.content);
            
            // Remove old entity (cycling through original entities)
            TestEntity toRemove = entities.get(i % entities.size());
            index.removeEntity((ID) toRemove.id);
            
            // Re-insert removed entity
            index.insert((ID) toRemove.id, toRemove.position, DEFAULT_LEVEL, (Content) toRemove.content);
        }
        
        // Measure memory after operations
        forceGarbageCollection();
        long finalMemory = getHeapUsage();
        
        double fragmentationRatio = (double) finalMemory / initialMemory;
        
        PerformanceMetrics metrics = new PerformanceMetrics(
            "memory_fragmentation",
            operations,
            0,
            finalMemory - initialMemory,
            null
        );
        performanceResults.add(metrics);
        
        System.out.printf("Memory fragmentation after %d operations: %.2fx increase (%.2f MB -> %.2f MB)%n",
            operations, fragmentationRatio, 
            initialMemory / (1024.0 * 1024.0), 
            finalMemory / (1024.0 * 1024.0));
        
        // Memory shouldn't grow more than 2x due to fragmentation
        assertTrue(fragmentationRatio < 2.0,
            "Memory fragmentation should be limited (less than 2x growth)");
    }
    
    @Test
    @DisplayName("Test memory usage with different spatial distributions")
    void testMemoryWithDifferentDistributions() {
        int size = 50000;
        
        for (SpatialDistribution distribution : SpatialDistribution.values()) {
            forceGarbageCollection();
            long baselineMemory = getHeapUsage();
            
            // Create index and populate
            SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
            List<TestEntity> entities = generateTestEntities(size, distribution);
            
            for (TestEntity entity : entities) {
                index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
            }
            
            forceGarbageCollection();
            long usedMemory = getHeapUsage() - baselineMemory;
            
            PerformanceMetrics metrics = new PerformanceMetrics(
                "memory_distribution_" + distribution.name().toLowerCase(),
                size,
                0,
                usedMemory,
                null
            );
            performanceResults.add(metrics);
            
            System.out.printf("Memory usage with %s distribution: %.2f MB (%.2f bytes/entity)%n",
                distribution, usedMemory / (1024.0 * 1024.0), (double) usedMemory / size);
        }
    }
    
    @Test
    @DisplayName("Test memory overhead of empty nodes")
    void testEmptyNodeOverhead() {
        // Create sparse index with entities only at corners
        SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        
        forceGarbageCollection();
        long baselineMemory = getHeapUsage();
        
        // Insert entities only at the 8 corners of the space
        int entityCount = 0;
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    ID id = (ID) new com.hellblazer.luciferase.lucien.entity.LongEntityID(entityCount);
                    index.insert(
                        id,
                        new javax.vecmath.Point3f(
                            x == 0 ? DEFAULT_BOUNDS.minX() : DEFAULT_BOUNDS.maxX(),
                            y == 0 ? DEFAULT_BOUNDS.minY() : DEFAULT_BOUNDS.maxY(),
                            z == 0 ? DEFAULT_BOUNDS.minZ() : DEFAULT_BOUNDS.maxZ()
                        ),
                        DEFAULT_LEVEL,
                        createTestContent(entityCount)
                    );
                    entityCount++;
                }
            }
        }
        
        forceGarbageCollection();
        long usedMemory = getHeapUsage() - baselineMemory;
        
        PerformanceMetrics metrics = new PerformanceMetrics(
            "empty_node_overhead",
            entityCount,
            0,
            usedMemory,
            null
        );
        performanceResults.add(metrics);
        
        System.out.printf("Memory overhead for sparse tree (8 corner entities): %.2f MB total, %.2f KB/entity%n",
            usedMemory / (1024.0 * 1024.0), usedMemory / (1024.0 * entityCount));
    }
    
    @Test
    @DisplayName("Test memory efficiency of dense vs sparse regions")
    void testDenseVsSparseMemoryEfficiency() {
        int totalEntities = 10000;
        
        // Test 1: Dense packing in small region
        forceGarbageCollection();
        long denseBaseline = getHeapUsage();
        
        SpatialIndex<ID, Content> denseIndex = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        List<TestEntity> denseEntities = generateTestEntities(totalEntities, SpatialDistribution.WORST_CASE);
        
        for (TestEntity entity : denseEntities) {
            denseIndex.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
        }
        
        forceGarbageCollection();
        long denseMemory = getHeapUsage() - denseBaseline;
        
        // Test 2: Sparse distribution
        forceGarbageCollection();
        long sparseBaseline = getHeapUsage();
        
        SpatialIndex<ID, Content> sparseIndex = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        List<TestEntity> sparseEntities = generateTestEntities(totalEntities, SpatialDistribution.UNIFORM_RANDOM);
        
        for (TestEntity entity : sparseEntities) {
            sparseIndex.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
        }
        
        forceGarbageCollection();
        long sparseMemory = getHeapUsage() - sparseBaseline;
        
        double efficiencyRatio = (double) denseMemory / sparseMemory;
        
        System.out.printf("Memory efficiency - Dense: %.2f MB, Sparse: %.2f MB, Ratio: %.2fx%n",
            denseMemory / (1024.0 * 1024.0),
            sparseMemory / (1024.0 * 1024.0),
            efficiencyRatio);
        
        PerformanceMetrics denseMet = new PerformanceMetrics("memory_dense", totalEntities, 0, denseMemory, null);
        PerformanceMetrics sparseMet = new PerformanceMetrics("memory_sparse", totalEntities, 0, sparseMemory, null);
        performanceResults.add(denseMet);
        performanceResults.add(sparseMet);
    }
    
    // Helper methods
    
    private void forceGarbageCollection() {
        System.gc();
        // System.runFinalization(); // Deprecated in newer Java versions
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private long getHeapUsage() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return heapUsage.getUsed();
    }
    
    private static class MemoryScalingResult {
        final int size;
        final long totalMemory;
        final double memoryPerEntity;
        
        MemoryScalingResult(int size, long totalMemory, double memoryPerEntity) {
            this.size = size;
            this.totalMemory = totalMemory;
            this.memoryPerEntity = memoryPerEntity;
        }
    }
}