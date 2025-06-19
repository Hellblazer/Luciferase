/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree.performance;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.util.CIEnvironmentCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Long-running performance test for Tetree profiling.
 * This test exercises various aspects of the Tetree to help identify performance bottlenecks:
 * - Insertion patterns (uniform, clustered, hierarchical)
 * - Query operations (lookup, k-NN, range queries)
 * - Update operations (movement simulation)
 * - Tree balancing and subdivision
 * - Memory usage patterns
 * 
 * Run with profiler attached to identify hotspots and optimization opportunities.
 * 
 * @author hal.hildebrand
 */
public class TetreeProfilingTest {
    
    private static final int WARM_UP_ENTITIES = 10_000;
    private static final int TOTAL_ENTITIES = 100_000;
    private static final int QUERY_ITERATIONS = 50_000;
    private static final int UPDATE_ITERATIONS = 25_000;
    private static final int KNN_SEARCHES = 10_000;
    private static final int RANGE_QUERIES = 10_000;
    
    private Tetree<LongEntityID, TestEntity> tetree;
    private SequentialLongIDGenerator idGenerator;
    private List<LongEntityID> insertedIds;
    private Random random;
    
    @BeforeEach
    void setup() {
        // Skip this test in CI environments
        assumeFalse(CIEnvironmentCheck.isRunningInCI(), 
            "Profiling test is disabled in CI environments");
        
        idGenerator = new SequentialLongIDGenerator();
        EntitySpanningPolicy spanningPolicy = new EntitySpanningPolicy(EntitySpanningPolicy.SpanningStrategy.SPAN_TO_OVERLAPPING, true, 0.1f);
        tetree = new Tetree<>(idGenerator, 50, (byte) 12, spanningPolicy);
        insertedIds = new ArrayList<>();
        random = new Random(42); // Fixed seed for reproducibility
        
        // Enable performance monitoring
        // Performance monitoring is automatic
    }
    
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void comprehensiveProfilingTest() {
        System.out.println("=== Tetree Profiling Test Starting ===");
        System.out.println("Total entities: " + TOTAL_ENTITIES);
        System.out.println("Query iterations: " + QUERY_ITERATIONS);
        System.out.println("Update iterations: " + UPDATE_ITERATIONS);
        System.out.println();
        
        // Phase 1: Warm-up
        warmUp();
        
        // Phase 2: Bulk insertion with different patterns
        profileBulkInsertion();
        
        // Phase 3: Query operations
        profileQueryOperations();
        
        // Phase 4: Update operations (movement)
        profileUpdateOperations();
        
        // Phase 5: Tree traversal and structure
        profileTreeTraversal();
        
        // Phase 6: Memory pressure test
        profileMemoryPressure();
        
        // Phase 7: Concurrent operations
        profileConcurrentOperations();
        
        // Print final statistics
        printFinalStatistics();
    }
    
    private void warmUp() {
        System.out.println("=== Warm-up Phase ===");
        long start = System.nanoTime();
        
        for (int i = 0; i < WARM_UP_ENTITIES; i++) {
            Point3f pos = generateRandomPosition(500);
            TestEntity entity = new TestEntity("warmup_" + i, pos, 1.0f);
            tetree.insert(pos, (byte) 8, entity);
        }
        
        // Clear for actual test
        tetree = new Tetree<>(idGenerator, 50, (byte) 12, new EntitySpanningPolicy(EntitySpanningPolicy.SpanningStrategy.SPAN_TO_OVERLAPPING, true, 0.1f));
        insertedIds.clear();
        
        long elapsed = System.nanoTime() - start;
        System.out.printf("Warm-up completed in %.2f ms%n%n", elapsed / 1_000_000.0);
    }
    
    private void profileBulkInsertion() {
        System.out.println("=== Bulk Insertion Phase ===");
        
        // Test 1: Uniform distribution
        profileInsertionPattern("Uniform Distribution", this::generateRandomPosition);
        
        // Test 2: Clustered distribution
        profileInsertionPattern("Clustered Distribution", this::generateClusteredPosition);
        
        // Test 3: Hierarchical distribution (different sizes)
        profileInsertionPattern("Hierarchical Distribution", this::generateHierarchicalPosition);
        
        System.out.println();
    }
    
    private void profileInsertionPattern(String patternName, java.util.function.Function<Integer, Point3f> positionGenerator) {
        System.out.println("Pattern: " + patternName);
        long start = System.nanoTime();
        int batchSize = TOTAL_ENTITIES / 3;
        
        for (int i = 0; i < batchSize; i++) {
            Point3f pos = positionGenerator.apply(1000);
            float size = 0.5f + random.nextFloat() * 4.5f; // 0.5 to 5.0
            TestEntity entity = new TestEntity(patternName + "_" + i, pos, size);
            LongEntityID id = tetree.insert(pos, (byte) 8, entity);
            insertedIds.add(id);
            
            if (i % 10000 == 0 && i > 0) {
                long elapsed = System.nanoTime() - start;
                double rate = i / (elapsed / 1_000_000_000.0);
                System.out.printf("  Inserted %d entities (%.0f/sec)%n", i, rate);
            }
        }
        
        long elapsed = System.nanoTime() - start;
        double rate = batchSize / (elapsed / 1_000_000_000.0);
        System.out.printf("  Total: %d entities in %.2f ms (%.0f/sec)%n", 
                         batchSize, elapsed / 1_000_000.0, rate);
    }
    
    private void profileQueryOperations() {
        System.out.println("=== Query Operations Phase ===");
        
        // Test 1: Point lookups
        profilePointLookups();
        
        // Test 2: k-NN searches
        profileKNNSearches();
        
        // Test 3: Range queries
        profileRangeQueries();
        
        // Test 4: Frustum culling
        profileFrustumCulling();
        
        System.out.println();
    }
    
    private void profilePointLookups() {
        System.out.println("Point Lookups:");
        long start = System.nanoTime();
        int hits = 0;
        
        for (int i = 0; i < QUERY_ITERATIONS; i++) {
            Point3f queryPoint = generateRandomPosition(1000);
            var results = tetree.lookup(queryPoint, (byte) 8);
            if (!results.isEmpty()) {
                hits++;
            }
            
            if (i % 10000 == 0 && i > 0) {
                long elapsed = System.nanoTime() - start;
                double rate = i / (elapsed / 1_000_000_000.0);
                System.out.printf("  Completed %d lookups (%.0f/sec), hits: %d%n", i, rate, hits);
            }
        }
        
        long elapsed = System.nanoTime() - start;
        double rate = QUERY_ITERATIONS / (elapsed / 1_000_000_000.0);
        System.out.printf("  Total: %d lookups in %.2f ms (%.0f/sec), hit rate: %.1f%%%n", 
                         QUERY_ITERATIONS, elapsed / 1_000_000.0, rate, (hits * 100.0) / QUERY_ITERATIONS);
    }
    
    private void profileKNNSearches() {
        System.out.println("k-NN Searches:");
        long start = System.nanoTime();
        long totalFound = 0;
        
        for (int i = 0; i < KNN_SEARCHES; i++) {
            Point3f queryPoint = generateRandomPosition(1000);
            int k = 5 + random.nextInt(15); // 5 to 20 neighbors
            var results = tetree.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
            totalFound += results.size();
            
            if (i % 2000 == 0 && i > 0) {
                long elapsed = System.nanoTime() - start;
                double rate = i / (elapsed / 1_000_000_000.0);
                System.out.printf("  Completed %d k-NN searches (%.0f/sec)%n", i, rate);
            }
        }
        
        long elapsed = System.nanoTime() - start;
        double rate = KNN_SEARCHES / (elapsed / 1_000_000_000.0);
        double avgFound = (double) totalFound / KNN_SEARCHES;
        System.out.printf("  Total: %d k-NN searches in %.2f ms (%.0f/sec), avg found: %.1f%n", 
                         KNN_SEARCHES, elapsed / 1_000_000.0, rate, avgFound);
    }
    
    private void profileRangeQueries() {
        System.out.println("Range Queries:");
        long start = System.nanoTime();
        long totalFound = 0;
        
        for (int i = 0; i < RANGE_QUERIES; i++) {
            Point3f center = generateRandomPosition(900);
            float radius = 20 + random.nextFloat() * 80; // 20 to 100
            // Use cube approximation for range query
            
            var results = tetree.entitiesInRegion(new Spatial.Cube(
                center.x - radius, center.y - radius, center.z - radius, radius * 2));
            totalFound += results.size();
            
            if (i % 2000 == 0 && i > 0) {
                long elapsed = System.nanoTime() - start;
                double rate = i / (elapsed / 1_000_000_000.0);
                System.out.printf("  Completed %d range queries (%.0f/sec)%n", i, rate);
            }
        }
        
        long elapsed = System.nanoTime() - start;
        double rate = RANGE_QUERIES / (elapsed / 1_000_000_000.0);
        double avgFound = (double) totalFound / RANGE_QUERIES;
        System.out.printf("  Total: %d range queries in %.2f ms (%.0f/sec), avg found: %.1f%n", 
                         RANGE_QUERIES, elapsed / 1_000_000.0, rate, avgFound);
    }
    
    private void profileFrustumCulling() {
        System.out.println("Frustum Culling:");
        long start = System.nanoTime();
        long totalVisible = 0;
        
        for (int i = 0; i < 1000; i++) {
            // Generate random camera position and look-at
            Point3f cameraPos = new Point3f(
                random.nextFloat() * 1000,
                random.nextFloat() * 1000,
                random.nextFloat() * 1000
            );
            Point3f lookAt = new Point3f(
                random.nextFloat() * 1000,
                random.nextFloat() * 1000,
                random.nextFloat() * 1000
            );
            
            // Create frustum
            Frustum3D frustum = Frustum3D.createPerspective(
                cameraPos, lookAt,
                new Vector3f(0, 1, 0), // up vector
                (float) Math.toRadians(60.0), // field of view in radians
                1.77f, // aspect ratio
                1.0f,  // near plane
                500.0f // far plane
            );
            
            var visible = tetree.frustumCullVisible(frustum, cameraPos);
            totalVisible += visible.size();
        }
        
        long elapsed = System.nanoTime() - start;
        double avgVisible = (double) totalVisible / 1000;
        System.out.printf("  Total: 1000 frustum queries in %.2f ms, avg visible: %.1f%n", 
                         elapsed / 1_000_000.0, avgVisible);
    }
    
    private void profileUpdateOperations() {
        System.out.println("=== Update Operations Phase ===");
        System.out.println("Movement Simulation:");
        long start = System.nanoTime();
        
        // Select a subset of entities to move
        List<LongEntityID> movingEntities = new ArrayList<>();
        for (int i = 0; i < Math.min(UPDATE_ITERATIONS, insertedIds.size()); i++) {
            movingEntities.add(insertedIds.get(random.nextInt(insertedIds.size())));
        }
        
        // Simulate movement
        for (int iteration = 0; iteration < 10; iteration++) {
            for (LongEntityID id : movingEntities) {
                // Get current entity
                TestEntity entity = tetree.getEntity(id);
                if (entity != null) {
                    // Move entity by small amount
                    Point3f oldPos = entity.getPosition();
                    Point3f newPos = new Point3f(
                        oldPos.x + (random.nextFloat() - 0.5f) * 10,
                        oldPos.y + (random.nextFloat() - 0.5f) * 10,
                        oldPos.z + (random.nextFloat() - 0.5f) * 10
                    );
                    
                    // Ensure positive coordinates
                    newPos.x = Math.max(0, Math.min(1000, newPos.x));
                    newPos.y = Math.max(0, Math.min(1000, newPos.y));
                    newPos.z = Math.max(0, Math.min(1000, newPos.z));
                    
                    entity.setPosition(newPos);
                    tetree.updateEntity(id, newPos, (byte) 8);
                }
            }
            
            long elapsed = System.nanoTime() - start;
            double rate = (iteration + 1) * movingEntities.size() / (elapsed / 1_000_000_000.0);
            System.out.printf("  Iteration %d: %d moves (%.0f moves/sec)%n", 
                             iteration + 1, movingEntities.size(), rate);
        }
        
        long elapsed = System.nanoTime() - start;
        int totalMoves = 10 * movingEntities.size();
        double rate = totalMoves / (elapsed / 1_000_000_000.0);
        System.out.printf("  Total: %d moves in %.2f ms (%.0f/sec)%n%n", 
                         totalMoves, elapsed / 1_000_000.0, rate);
    }
    
    private void profileTreeTraversal() {
        System.out.println("=== Tree Traversal Phase ===");
        
        // Test different traversal patterns
        profileTraversalOrder("Depth-First", () -> {
            int count = 0;
            var iterator = tetree.iterator(com.hellblazer.luciferase.lucien.tetree.TetreeIterator.TraversalOrder.DEPTH_FIRST_PRE);
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
            return count;
        });
        
        profileTraversalOrder("Breadth-First", () -> {
            int count = 0;
            var iterator = tetree.iterator(com.hellblazer.luciferase.lucien.tetree.TetreeIterator.TraversalOrder.BREADTH_FIRST);
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
            return count;
        });
        
        profileTraversalOrder("SFC Order", () -> {
            int count = 0;
            var iterator = tetree.iterator(com.hellblazer.luciferase.lucien.tetree.TetreeIterator.TraversalOrder.SFC_ORDER);
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
            return count;
        });
        
        System.out.println();
    }
    
    private void profileTraversalOrder(String orderName, java.util.function.Supplier<Integer> traversal) {
        System.out.println("Traversal: " + orderName);
        long start = System.nanoTime();
        
        int nodeCount = traversal.get();
        
        long elapsed = System.nanoTime() - start;
        double rate = nodeCount / (elapsed / 1_000_000_000.0);
        System.out.printf("  Visited %d nodes in %.2f ms (%.0f nodes/sec)%n", 
                         nodeCount, elapsed / 1_000_000.0, rate);
    }
    
    private void profileMemoryPressure() {
        System.out.println("=== Memory Pressure Test ===");
        
        // Force garbage collection before test
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        long usedBefore = runtime.totalMemory() - runtime.freeMemory();
        
        // Create many small entities to stress memory
        System.out.println("Creating many small entities...");
        long start = System.nanoTime();
        
        for (int i = 0; i < 50000; i++) {
            Point3f pos = generateRandomPosition(1000);
            TestEntity entity = new TestEntity("mem_" + i, pos, 0.1f);
            tetree.insert(pos, (byte) 8, entity);
            
            if (i % 10000 == 0 && i > 0) {
                System.gc();
                long usedNow = runtime.totalMemory() - runtime.freeMemory();
                long usedDelta = (usedNow - usedBefore) / 1_048_576; // MB
                System.out.printf("  Entities: %d, Memory growth: %d MB%n", i, usedDelta);
            }
        }
        
        long elapsed = System.nanoTime() - start;
        System.gc();
        long usedAfter = runtime.totalMemory() - runtime.freeMemory();
        long totalUsed = (usedAfter - usedBefore) / 1_048_576; // MB
        
        System.out.printf("  Total memory growth: %d MB in %.2f ms%n%n", 
                         totalUsed, elapsed / 1_000_000.0);
    }
    
    private void profileConcurrentOperations() {
        System.out.println("=== Concurrent Operations Test ===");
        
        int numThreads = 4;
        AtomicLong totalOps = new AtomicLong(0);
        long start = System.nanoTime();
        
        Thread[] threads = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                ThreadLocalRandom tlr = ThreadLocalRandom.current();
                
                for (int i = 0; i < 5000; i++) {
                    // Mix of operations
                    int op = tlr.nextInt(4);
                    
                    switch (op) {
                        case 0: // Insert
                            Point3f pos = new Point3f(
                                tlr.nextFloat() * 1000,
                                tlr.nextFloat() * 1000,
                                tlr.nextFloat() * 1000
                            );
                            TestEntity entity = new TestEntity("thread" + threadId + "_" + i, pos, 1.0f);
                            tetree.insert(pos, (byte) 8, entity);
                            break;
                            
                        case 1: // Point lookup
                            Point3f query = new Point3f(
                                tlr.nextFloat() * 1000,
                                tlr.nextFloat() * 1000,
                                tlr.nextFloat() * 1000
                            );
                            tetree.lookup(query, (byte) 8);
                            break;
                            
                        case 2: // k-NN search
                            Point3f knnQuery = new Point3f(
                                tlr.nextFloat() * 1000,
                                tlr.nextFloat() * 1000,
                                tlr.nextFloat() * 1000
                            );
                            tetree.kNearestNeighbors(knnQuery, 10, Float.MAX_VALUE);
                            break;
                            
                        case 3: // Range query
                            Point3f center = new Point3f(
                                tlr.nextFloat() * 900,
                                tlr.nextFloat() * 900,
                                tlr.nextFloat() * 900
                            );
                            // Use cube approximation
                            tetree.entitiesInRegion(new Spatial.Cube(
                center.x - 50, center.y - 50, center.z - 50, 100));
                            break;
                    }
                    
                    totalOps.incrementAndGet();
                }
            });
            threads[t].start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        long elapsed = System.nanoTime() - start;
        double rate = totalOps.get() / (elapsed / 1_000_000_000.0);
        System.out.printf("  Completed %d operations across %d threads in %.2f ms (%.0f ops/sec)%n%n", 
                         totalOps.get(), numThreads, elapsed / 1_000_000.0, rate);
    }
    
    private void printFinalStatistics() {
        System.out.println("=== Final Statistics ===");
        
        // Tree structure stats
        var stats = tetree.getTreeStatistics();
        System.out.println("Tree Structure:");
        System.out.printf("  Total nodes: %d%n", tetree.nodeCount());
        System.out.printf("  Balance factor: %.2f%n", stats.getBalanceFactor());
        
        // Node distribution by level
        System.out.println("\nNodes by level:");
        var nodesByLevel = tetree.getNodeCountByLevel();
        for (Map.Entry<Byte, Integer> entry : nodesByLevel.entrySet()) {
            System.out.printf("  Level %d: %d nodes%n", entry.getKey(), entry.getValue());
        }
        
        // Performance metrics
        var metrics = tetree.getMetrics();
        System.out.println("\nPerformance Metrics:");
        System.out.printf("  Cache hit rate: %.1f%%%n", metrics.cacheHitRate() * 100);
        // Neighbor query and traversal time tracking not available
        System.out.printf("  Total neighbor queries: %d%n", metrics.neighborQueryCount());
        System.out.printf("  Total traversals: %d%n", metrics.traversalCount());
        
        System.out.println("\n=== Profiling Test Complete ===");
    }
    
    // Position generation helpers
    
    private Point3f generateRandomPosition(int maxCoord) {
        return new Point3f(
            random.nextFloat() * maxCoord,
            random.nextFloat() * maxCoord,
            random.nextFloat() * maxCoord
        );
    }
    
    private Point3f generateClusteredPosition(int maxCoord) {
        // Create clusters around specific points
        int numClusters = 10;
        int cluster = random.nextInt(numClusters);
        
        float baseX = (cluster % 3) * (maxCoord / 3.0f) + maxCoord / 6.0f;
        float baseY = ((cluster / 3) % 3) * (maxCoord / 3.0f) + maxCoord / 6.0f;
        float baseZ = (cluster / 9) * (maxCoord / 2.0f) + maxCoord / 4.0f;
        
        // Add gaussian noise around cluster center
        float spread = 50.0f;
        float x = baseX + (float) (random.nextGaussian() * spread);
        float y = baseY + (float) (random.nextGaussian() * spread);
        float z = baseZ + (float) (random.nextGaussian() * spread);
        
        // Clamp to valid range
        return new Point3f(
            Math.max(0, Math.min(maxCoord, x)),
            Math.max(0, Math.min(maxCoord, y)),
            Math.max(0, Math.min(maxCoord, z))
        );
    }
    
    private Point3f generateHierarchicalPosition(int maxCoord) {
        // Generate positions at different scales
        float scale = (float) Math.pow(2, random.nextInt(8)); // 1, 2, 4, 8, ..., 128
        float gridSize = maxCoord / scale;
        
        int gridX = random.nextInt((int) scale);
        int gridY = random.nextInt((int) scale);
        int gridZ = random.nextInt((int) scale);
        
        float x = gridX * gridSize + random.nextFloat() * gridSize;
        float y = gridY * gridSize + random.nextFloat() * gridSize;
        float z = gridZ * gridSize + random.nextFloat() * gridSize;
        
        return new Point3f(
            Math.min(maxCoord - 1, x),
            Math.min(maxCoord - 1, y),
            Math.min(maxCoord - 1, z)
        );
    }
    
    // Test entity class
    static class TestEntity {
        private final String id;
        private Point3f position;
        private final float size;
        
        TestEntity(String id, Point3f position, float size) {
            this.id = id;
            this.position = new Point3f(position);
            this.size = size;
        }
        
        public String getId() { return id; }
        public Point3f getPosition() { return new Point3f(position); }
        public void setPosition(Point3f pos) { this.position = new Point3f(pos); }
        public float getSize() { return size; }
    }
}