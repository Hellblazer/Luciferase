/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.BulkOperationConfig;
import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test bulk operation optimizations to track progress towards C++ performance parity
 *
 * @author hal.hildebrand
 */
public class OctreeBulkOptimizationTest {

    @Test
    void testBulkInsertWithOptimizations() {
        byte level = 15;
        int[] entityCounts = {1000, 5000, 10000, 50000};
        
        System.out.println("\nBulk Insert Performance Comparison:");
        System.out.println("===========================================");
        System.out.printf("%-10s | %-15s | %-15s | %-15s | %-10s%n", 
                         "Entities", "Sequential (ms)", "Default (ms)", "Optimized (ms)", "Speedup");
        System.out.println("-----------|-----------------|-----------------|-----------------|------------");
        
        for (int entityCount : entityCounts) {
            // Create test data
            Random random = new Random(42);
            List<EntityData<LongEntityID, String>> testData = new ArrayList<>();
            for (int i = 0; i < entityCount; i++) {
                LongEntityID id = new LongEntityID(i);
                Point3f pos = new Point3f(
                    random.nextFloat() * 10000, 
                    random.nextFloat() * 10000,
                    random.nextFloat() * 10000
                );
                testData.add(new EntityData<>(id, pos, level, "Entity" + i));
            }
            
            // Test 1: Sequential insertion
            Octree<LongEntityID, String> octreeSequential = new Octree<>(new SequentialLongIDGenerator());
            long sequentialStart = System.nanoTime();
            for (EntityData<LongEntityID, String> data : testData) {
                octreeSequential.insert(data.id(), data.position(), data.level(), data.content());
            }
            long sequentialTime = System.nanoTime() - sequentialStart;
            
            // Test 2: Default bulk insertion
            Octree<LongEntityID, String> octreeDefault = new Octree<>(new SequentialLongIDGenerator());
            long defaultStart = System.nanoTime();
            octreeDefault.insertAll(testData);
            long defaultTime = System.nanoTime() - defaultStart;
            
            // Test 3: Optimized bulk insertion
            Octree<LongEntityID, String> octreeOptimized = new Octree<>(new SequentialLongIDGenerator());
            octreeOptimized.configureBulkOperations(BulkOperationConfig.highPerformance());
            long optimizedStart = System.nanoTime();
            octreeOptimized.insertAll(testData);
            long optimizedTime = System.nanoTime() - optimizedStart;
            
            // Verify all have same entity count
            assertEquals(entityCount, octreeSequential.entityCount());
            assertEquals(entityCount, octreeDefault.entityCount());
            assertEquals(entityCount, octreeOptimized.entityCount());
            
            // Calculate metrics
            double sequentialMs = sequentialTime / 1_000_000.0;
            double defaultMs = defaultTime / 1_000_000.0;
            double optimizedMs = optimizedTime / 1_000_000.0;
            double speedup = sequentialMs / optimizedMs;
            
            System.out.printf("%-10d | %15.2f | %15.2f | %15.2f | %10.2fx%n",
                             entityCount, sequentialMs, defaultMs, optimizedMs, speedup);
        }
        
        System.out.println("\nConfiguration Details:");
        System.out.println("- Default: deferSubdivision=true, preSortByMorton=true");
        System.out.println("- Optimized: deferSubdivision=true, preSortByMorton=true, batchSize=5000");
        System.out.println("- Note: Parallel processing not yet implemented");
    }
    
    @Test
    void testDifferentSpatialDistributions() {
        byte level = 15;
        int entityCount = 10000;
        
        System.out.println("\nSpatial Distribution Performance Impact:");
        System.out.println("========================================");
        System.out.printf("%-20s | %-15s | %-15s | %-10s%n", 
                         "Distribution", "Sequential (ms)", "Bulk (ms)", "Speedup");
        System.out.println("---------------------|-----------------|-----------------|------------");
        
        // Test different spatial distributions
        testDistribution("Uniform Random", entityCount, level, this::generateUniformRandom);
        testDistribution("Clustered", entityCount, level, this::generateClustered);
        testDistribution("Grid Aligned", entityCount, level, this::generateGridAligned);
        testDistribution("Diagonal Line", entityCount, level, this::generateDiagonalLine);
    }
    
    private void testDistribution(String name, int count, byte level, DistributionGenerator generator) {
        List<EntityData<LongEntityID, String>> testData = generator.generate(count, level);
        
        // Sequential
        Octree<LongEntityID, String> octreeSeq = new Octree<>(new SequentialLongIDGenerator());
        long seqStart = System.nanoTime();
        for (EntityData<LongEntityID, String> data : testData) {
            octreeSeq.insert(data.id(), data.position(), data.level(), data.content());
        }
        long seqTime = System.nanoTime() - seqStart;
        
        // Bulk with optimization
        Octree<LongEntityID, String> octreeBulk = new Octree<>(new SequentialLongIDGenerator());
        octreeBulk.configureBulkOperations(BulkOperationConfig.highPerformance());
        long bulkStart = System.nanoTime();
        octreeBulk.insertAll(testData);
        long bulkTime = System.nanoTime() - bulkStart;
        
        double seqMs = seqTime / 1_000_000.0;
        double bulkMs = bulkTime / 1_000_000.0;
        double speedup = seqMs / bulkMs;
        
        System.out.printf("%-20s | %15.2f | %15.2f | %10.2fx%n",
                         name, seqMs, bulkMs, speedup);
    }
    
    @FunctionalInterface
    interface DistributionGenerator {
        List<EntityData<LongEntityID, String>> generate(int count, byte level);
    }
    
    private List<EntityData<LongEntityID, String>> generateUniformRandom(int count, byte level) {
        Random random = new Random(42);
        List<EntityData<LongEntityID, String>> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Point3f pos = new Point3f(
                random.nextFloat() * 10000,
                random.nextFloat() * 10000,
                random.nextFloat() * 10000
            );
            data.add(new EntityData<>(new LongEntityID(i), pos, level, "Entity" + i));
        }
        return data;
    }
    
    private List<EntityData<LongEntityID, String>> generateClustered(int count, byte level) {
        Random random = new Random(42);
        List<EntityData<LongEntityID, String>> data = new ArrayList<>();
        int clusterCount = 10;
        int entitiesPerCluster = count / clusterCount;
        
        for (int c = 0; c < clusterCount; c++) {
            // Random cluster center
            Point3f center = new Point3f(
                random.nextFloat() * 10000,
                random.nextFloat() * 10000,
                random.nextFloat() * 10000
            );
            
            // Generate points around cluster
            for (int i = 0; i < entitiesPerCluster; i++) {
                Point3f pos = new Point3f(
                    center.x + (random.nextFloat() - 0.5f) * 100,
                    center.y + (random.nextFloat() - 0.5f) * 100,
                    center.z + (random.nextFloat() - 0.5f) * 100
                );
                int id = c * entitiesPerCluster + i;
                data.add(new EntityData<>(new LongEntityID(id), pos, level, "Entity" + id));
            }
        }
        return data;
    }
    
    private List<EntityData<LongEntityID, String>> generateGridAligned(int count, byte level) {
        List<EntityData<LongEntityID, String>> data = new ArrayList<>();
        int gridSize = (int) Math.ceil(Math.cbrt(count));
        float spacing = 10000.0f / gridSize;
        
        int id = 0;
        for (int x = 0; x < gridSize && id < count; x++) {
            for (int y = 0; y < gridSize && id < count; y++) {
                for (int z = 0; z < gridSize && id < count; z++) {
                    Point3f pos = new Point3f(x * spacing, y * spacing, z * spacing);
                    data.add(new EntityData<>(new LongEntityID(id), pos, level, "Entity" + id));
                    id++;
                }
            }
        }
        return data;
    }
    
    private List<EntityData<LongEntityID, String>> generateDiagonalLine(int count, byte level) {
        List<EntityData<LongEntityID, String>> data = new ArrayList<>();
        float step = 10000.0f / count;
        
        for (int i = 0; i < count; i++) {
            float coord = i * step;
            Point3f pos = new Point3f(coord, coord, coord);
            data.add(new EntityData<>(new LongEntityID(i), pos, level, "Entity" + i));
        }
        return data;
    }
    
    @Test
    void testMemoryUsage() {
        byte level = 15;
        int entityCount = 50000;
        
        System.out.println("\nMemory Usage Analysis:");
        System.out.println("======================");
        
        // Force GC before measurement
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        long beforeMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Create and populate octree
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        octree.configureBulkOperations(BulkOperationConfig.highPerformance());
        
        Random random = new Random(42);
        List<EntityData<LongEntityID, String>> testData = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            Point3f pos = new Point3f(
                random.nextFloat() * 10000,
                random.nextFloat() * 10000,
                random.nextFloat() * 10000
            );
            testData.add(new EntityData<>(new LongEntityID(i), pos, level, "E" + i));
        }
        
        octree.insertAll(testData);
        
        // Force GC and measure after
        System.gc();
        long afterMemory = runtime.totalMemory() - runtime.freeMemory();
        long usedMemory = afterMemory - beforeMemory;
        
        System.out.printf("Entities: %d%n", entityCount);
        System.out.printf("Memory used: %.2f MB%n", usedMemory / (1024.0 * 1024.0));
        System.out.printf("Memory per entity: %.2f KB%n", usedMemory / (1024.0 * entityCount));
        System.out.printf("Node count: %d%n", octree.nodeCount());
        System.out.printf("Average entities per node: %.2f%n", 
                         (double) entityCount / octree.nodeCount());
    }
}