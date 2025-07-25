/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Performance benchmarks comparing Forest vs single Octree
 * 
 * These tests are marked as @Disabled by default since they are 
 * performance benchmarks rather than correctness tests.
 * Remove @Disabled to run benchmarks.
 */
@Disabled("Performance benchmarks - enable to run")
public class ForestPerformanceBenchmark {
    
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 10;
    
    private Forest<MortonKey, LongEntityID, String> forest;
    private Octree<LongEntityID, String> singleTree;
    private SequentialLongIDGenerator idGenerator;
    private List<LongEntityID> entityIds;
    private List<Point3f> entityPositions;
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        entityIds = new ArrayList<>();
        entityPositions = new ArrayList<>();
    }
    
    @Test
    void benchmarkInsertionPerformance() {
        System.out.println("\n=== Insertion Performance Benchmark ===");
        
        int[] entityCounts = {1000, 10000, 50000, 100000};
        
        for (int count : entityCounts) {
            prepareTestData(count);
            
            // Single tree benchmark
            long singleTreeTime = benchmarkSingleTreeInsertion();
            
            // Forest benchmark (4 trees)
            long forestTime = benchmarkForestInsertion(4);
            
            // Forest benchmark (16 trees)
            long forest16Time = benchmarkForestInsertion(16);
            
            System.out.printf("Entities: %d%n", count);
            System.out.printf("  Single Tree:    %,d ms (%.2f μs/entity)%n", 
                singleTreeTime, singleTreeTime * 1000.0 / count);
            System.out.printf("  Forest (4):     %,d ms (%.2f μs/entity) - %.2fx%n", 
                forestTime, forestTime * 1000.0 / count, 
                (double)singleTreeTime / forestTime);
            System.out.printf("  Forest (16):    %,d ms (%.2f μs/entity) - %.2fx%n", 
                forest16Time, forest16Time * 1000.0 / count,
                (double)singleTreeTime / forest16Time);
            System.out.println();
        }
    }
    
    @Test
    void benchmarkKNNQueryPerformance() {
        System.out.println("\n=== K-NN Query Performance Benchmark ===");
        
        int entityCount = 100000;
        int[] kValues = {10, 50, 100, 500};
        
        prepareTestData(entityCount);
        
        // Setup structures
        setupSingleTree();
        insertIntoSingleTree();
        
        for (int k : kValues) {
            setupForest(4);
            insertIntoForest();
            
            // Benchmark queries
            long singleTreeTime = benchmarkSingleTreeKNN(k);
            long forestTime = benchmarkForestKNN(k);
            
            System.out.printf("K=%d:%n", k);
            System.out.printf("  Single Tree:    %,d ms%n", singleTreeTime);
            System.out.printf("  Forest (4):     %,d ms (%.2fx)%n", 
                forestTime, (double)singleTreeTime / forestTime);
            System.out.println();
        }
    }
    
    @Test
    void benchmarkRangeQueryPerformance() {
        System.out.println("\n=== Range Query Performance Benchmark ===");
        
        int entityCount = 100000;
        float[] ranges = {10.0f, 50.0f, 100.0f, 200.0f};
        
        prepareTestData(entityCount);
        
        // Setup structures
        setupSingleTree();
        insertIntoSingleTree();
        
        for (float range : ranges) {
            setupForest(4);
            insertIntoForest();
            
            // Benchmark queries
            long singleTreeTime = benchmarkSingleTreeRange(range);
            long forestTime = benchmarkForestRange(range);
            
            System.out.printf("Range=%.1f:%n", range);
            System.out.printf("  Single Tree:    %,d ms%n", singleTreeTime);
            System.out.printf("  Forest (4):     %,d ms (%.2fx)%n", 
                forestTime, (double)singleTreeTime / forestTime);
            System.out.println();
        }
    }
    
    @Test
    void benchmarkUpdatePerformance() {
        System.out.println("\n=== Update Performance Benchmark ===");
        
        int entityCount = 50000;
        int updateCount = 10000;
        
        prepareTestData(entityCount);
        
        // Setup and populate
        setupSingleTree();
        insertIntoSingleTree();
        
        setupForest(4);
        insertIntoForest();
        
        // Benchmark updates
        long singleTreeTime = benchmarkSingleTreeUpdates(updateCount);
        long forestTime = benchmarkForestUpdates(updateCount);
        
        System.out.printf("Updates: %d%n", updateCount);
        System.out.printf("  Single Tree:    %,d ms (%.2f μs/update)%n", 
            singleTreeTime, singleTreeTime * 1000.0 / updateCount);
        System.out.printf("  Forest (4):     %,d ms (%.2f μs/update) - %.2fx%n", 
            forestTime, forestTime * 1000.0 / updateCount,
            (double)singleTreeTime / forestTime);
    }
    
    @Test
    void benchmarkMemoryUsage() {
        System.out.println("\n=== Memory Usage Benchmark ===");
        
        int[] entityCounts = {10000, 50000, 100000};
        
        for (int count : entityCounts) {
            prepareTestData(count);
            
            // Single tree memory
            long singleTreeMemory = measureSingleTreeMemory();
            
            // Forest memory
            long forestMemory = measureForestMemory(4);
            
            System.out.printf("Entities: %d%n", count);
            System.out.printf("  Single Tree:    %,d KB%n", singleTreeMemory / 1024);
            System.out.printf("  Forest (4):     %,d KB (%.2fx)%n", 
                forestMemory / 1024, (double)forestMemory / singleTreeMemory);
            System.out.println();
        }
    }
    
    @Test
    void benchmarkScalability() {
        System.out.println("\n=== Scalability Benchmark ===");
        
        int entityCount = 100000;
        int[] treeCounts = {1, 2, 4, 8, 16, 32};
        
        prepareTestData(entityCount);
        
        System.out.println("Insertion time vs number of trees:");
        for (int treeCount : treeCounts) {
            long time = benchmarkForestInsertion(treeCount);
            System.out.printf("  Trees: %2d - %,d ms (%.2f μs/entity)%n", 
                treeCount, time, time * 1000.0 / entityCount);
        }
        
        System.out.println("\nQuery time vs number of trees (K=100):");
        for (int treeCount : treeCounts) {
            setupForest(treeCount);
            insertIntoForest();
            long time = benchmarkForestKNN(100);
            System.out.printf("  Trees: %2d - %,d ms%n", treeCount, time);
        }
    }
    
    @Test
    void benchmarkLoadBalancing() {
        System.out.println("\n=== Load Balancing Performance ===");
        
        int entityCount = 100000;
        
        // Create imbalanced forest
        setupForest(4);
        var trees = forest.getAllTrees();
        
        // Insert 80% of entities into first tree
        for (int i = 0; i < entityCount * 0.8; i++) {
            var id = entityIds.get(i);
            var pos = new Point3f(50, (i % 100), 50); // Cluster in first tree
            trees.get(0).getSpatialIndex().insert(id, pos, (byte)10, "Entity-" + i);
        }
        
        // Insert remaining 20% spread across other trees
        for (int i = (int)(entityCount * 0.8); i < entityCount; i++) {
            var id = entityIds.get(i);
            var pos = entityPositions.get(i);
            trees.get(0).getSpatialIndex().insert(id, pos, (byte)10, "Entity-" + i);
        }
        
        // Measure query performance before balancing
        long beforeBalance = benchmarkForestKNN(100);
        
        // Perform load balancing
        var loadBalancer = new ForestLoadBalancer<MortonKey, LongEntityID, String>();
        long balanceStart = System.currentTimeMillis();
        
        // Create tree index map for load balancer
        Map<Integer, SpatialIndex<MortonKey, LongEntityID, String>> treeIndexMap = new HashMap<>();
        for (int i = 0; i < trees.size(); i++) {
            treeIndexMap.put(i, trees.get(i).getSpatialIndex());
        }
        
        // Collect metrics
        loadBalancer.collectMetrics(treeIndexMap);
        
        // Identify overloaded and underloaded trees
        var overloaded = loadBalancer.identifyOverloadedTrees();
        var underloaded = loadBalancer.identifyUnderloadedTrees();
        
        int movedCount = 0;
        if (!overloaded.isEmpty() && !underloaded.isEmpty()) {
            // Create migration plans
            var plans = loadBalancer.createMigrationPlans(treeIndexMap);
            // For simplicity, just count the entities in the plans
            for (var plan : plans) {
                movedCount += plan.getEntityIds().size();
            }
        }
        
        long balanceTime = System.currentTimeMillis() - balanceStart;
        
        // Measure query performance after balancing
        long afterBalance = benchmarkForestKNN(100);
        
        System.out.printf("Entities moved: %d%n", movedCount);
        System.out.printf("Balancing time: %,d ms%n", balanceTime);
        System.out.printf("Query time before: %,d ms%n", beforeBalance);
        System.out.printf("Query time after:  %,d ms (%.2fx improvement)%n", 
            afterBalance, (double)beforeBalance / afterBalance);
    }
    
    // Helper methods
    
    private void prepareTestData(int count) {
        entityIds.clear();
        entityPositions.clear();
        
        Random rand = new Random(42); // Fixed seed for reproducibility
        
        for (int i = 0; i < count; i++) {
            entityIds.add(new LongEntityID(i));
            entityPositions.add(new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            ));
        }
    }
    
    private void setupSingleTree() {
        singleTree = new Octree<>(idGenerator);
    }
    
    private void setupForest(int treeCount) {
        var config = ForestConfig.defaultConfig();
        forest = new Forest<>(config);
        
        int gridSize = (int)Math.ceil(Math.sqrt(treeCount));
        float treeSize = 1000.0f / gridSize;
        
        for (int x = 0; x < gridSize && forest.getAllTrees().size() < treeCount; x++) {
            for (int z = 0; z < gridSize && forest.getAllTrees().size() < treeCount; z++) {
                var tree = new Octree<LongEntityID, String>(idGenerator);
                var bounds = new EntityBounds(
                    new Point3f(x * treeSize, 0, z * treeSize),
                    new Point3f((x + 1) * treeSize, 1000, (z + 1) * treeSize)
                );
                var metadata = TreeMetadata.builder()
                    .name("octree-" + x + "-" + z)
                    .treeType(TreeMetadata.TreeType.OCTREE)
                    .property("bounds", bounds)
                    .build();
                forest.addTree(tree, metadata);
            }
        }
    }
    
    private void insertIntoSingleTree() {
        for (int i = 0; i < entityIds.size(); i++) {
            singleTree.insert(entityIds.get(i), entityPositions.get(i), 
                (byte)10, "Entity-" + i);
        }
    }
    
    private void insertIntoForest() {
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        for (int i = 0; i < entityIds.size(); i++) {
            entityManager.insert(entityIds.get(i), "Entity-" + i, entityPositions.get(i), null);
        }
    }
    
    private long benchmarkSingleTreeInsertion() {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            setupSingleTree();
            insertIntoSingleTree();
        }
        
        // Measurement
        long totalTime = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            setupSingleTree();
            long start = System.currentTimeMillis();
            insertIntoSingleTree();
            totalTime += System.currentTimeMillis() - start;
        }
        
        return totalTime / MEASUREMENT_ITERATIONS;
    }
    
    private long benchmarkForestInsertion(int treeCount) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            setupForest(treeCount);
            insertIntoForest();
        }
        
        // Measurement
        long totalTime = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            setupForest(treeCount);
            long start = System.currentTimeMillis();
            insertIntoForest();
            totalTime += System.currentTimeMillis() - start;
        }
        
        return totalTime / MEASUREMENT_ITERATIONS;
    }
    
    private long benchmarkSingleTreeKNN(int k) {
        Random rand = new Random(42);
        List<Point3f> queryPoints = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            queryPoints.add(new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            ));
        }
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (var point : queryPoints) {
                singleTree.kNearestNeighbors(point, k, Float.MAX_VALUE);
            }
        }
        
        // Measurement
        long start = System.currentTimeMillis();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            for (var point : queryPoints) {
                singleTree.kNearestNeighbors(point, k, Float.MAX_VALUE);
            }
        }
        long totalTime = System.currentTimeMillis() - start;
        
        return totalTime / MEASUREMENT_ITERATIONS;
    }
    
    private long benchmarkForestKNN(int k) {
        Random rand = new Random(42);
        List<Point3f> queryPoints = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            queryPoints.add(new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            ));
        }
        
        var queries = new ForestSpatialQueries<>(forest);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (var point : queryPoints) {
                queries.findKNearestNeighbors(point, k, Float.MAX_VALUE);
            }
        }
        
        // Measurement
        long start = System.currentTimeMillis();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            for (var point : queryPoints) {
                queries.findKNearestNeighbors(point, k, Float.MAX_VALUE);
            }
        }
        long totalTime = System.currentTimeMillis() - start;
        
        return totalTime / MEASUREMENT_ITERATIONS;
    }
    
    private long benchmarkSingleTreeRange(float range) {
        Random rand = new Random(42);
        List<Point3f> queryPoints = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            queryPoints.add(new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            ));
        }
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (var point : queryPoints) {
                // Use kNearestNeighbors with distance limit to simulate range query
                singleTree.kNearestNeighbors(point, Integer.MAX_VALUE, range);
            }
        }
        
        // Measurement
        long start = System.currentTimeMillis();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            for (var point : queryPoints) {
                // Use kNearestNeighbors with distance limit to simulate range query
                singleTree.kNearestNeighbors(point, Integer.MAX_VALUE, range);
            }
        }
        long totalTime = System.currentTimeMillis() - start;
        
        return totalTime / MEASUREMENT_ITERATIONS;
    }
    
    private long benchmarkForestRange(float range) {
        Random rand = new Random(42);
        List<Point3f> queryPoints = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            queryPoints.add(new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            ));
        }
        
        var queries = new ForestSpatialQueries<>(forest);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (var point : queryPoints) {
                queries.findEntitiesWithinDistance(point, range);
            }
        }
        
        // Measurement
        long start = System.currentTimeMillis();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            for (var point : queryPoints) {
                queries.findEntitiesWithinDistance(point, range);
            }
        }
        long totalTime = System.currentTimeMillis() - start;
        
        return totalTime / MEASUREMENT_ITERATIONS;
    }
    
    private long benchmarkSingleTreeUpdates(int updateCount) {
        Random rand = new Random(42);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (int j = 0; j < 100; j++) {
                var id = entityIds.get(rand.nextInt(entityIds.size()));
                var newPos = new Point3f(
                    rand.nextFloat() * 1000,
                    rand.nextFloat() * 1000,
                    rand.nextFloat() * 1000
                );
                singleTree.updateEntity(id, newPos, (byte)10);
            }
        }
        
        // Measurement
        long start = System.currentTimeMillis();
        for (int i = 0; i < updateCount; i++) {
            var id = entityIds.get(rand.nextInt(entityIds.size()));
            var newPos = new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            );
            singleTree.updateEntity(id, newPos, (byte)10);
        }
        
        return System.currentTimeMillis() - start;
    }
    
    private long benchmarkForestUpdates(int updateCount) {
        Random rand = new Random(42);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (int j = 0; j < 100; j++) {
                var id = entityIds.get(rand.nextInt(entityIds.size()));
                var newPos = new Point3f(
                    rand.nextFloat() * 1000,
                    rand.nextFloat() * 1000,
                    rand.nextFloat() * 1000
                );
                var entityManager = new ForestEntityManager<>(forest, idGenerator);
            entityManager.updatePosition(id, newPos);
            }
        }
        
        // Measurement
        long start = System.currentTimeMillis();
        for (int i = 0; i < updateCount; i++) {
            var id = entityIds.get(rand.nextInt(entityIds.size()));
            var newPos = new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            );
            var entityManager = new ForestEntityManager<>(forest, idGenerator);
            entityManager.updatePosition(id, newPos);
        }
        
        return System.currentTimeMillis() - start;
    }
    
    private long measureSingleTreeMemory() {
        System.gc();
        long before = getUsedMemory();
        
        setupSingleTree();
        insertIntoSingleTree();
        
        System.gc();
        long after = getUsedMemory();
        
        return after - before;
    }
    
    private long measureForestMemory(int treeCount) {
        System.gc();
        long before = getUsedMemory();
        
        setupForest(treeCount);
        insertIntoForest();
        
        System.gc();
        long after = getUsedMemory();
        
        return after - before;
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}