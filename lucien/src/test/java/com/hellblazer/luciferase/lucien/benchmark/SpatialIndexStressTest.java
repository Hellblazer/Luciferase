/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.benchmark;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Comprehensive performance stress test for spatial indices Tests scale, distribution patterns, query performance,
 * update patterns, and memory pressure
 *
 * @author hal.hildebrand
 */
public class SpatialIndexStressTest {

    private static final float                     WORLD_SIZE           = 1000.0f;
    private static final byte                      MAX_LEVEL            = 10;
    private static final int                       WARMUP_ITERATIONS    = 50;
    private static final int                       BENCHMARK_ITERATIONS = 100;
    // Scale test configurations
    private static final int[]                     SCALE_ENTITY_COUNTS  = { 10_000,       // 10K - baseline
                                                                            100_000,      // 100K - medium scale
                                                                            1_000_000,    // 1M - large scale
                                                                            10_000_000
                                                                            // 10M - stress scale (if memory allows)
    };
    // K-NN configurations
    private static final int[]                     K_VALUES             = { 1, 10, 50, 100, 500, 1000 };
    // Range query sizes (as percentage of world size)
    private static final float[]                   RANGE_PERCENTAGES    = { 0.01f, 0.05f, 0.1f, 0.25f, 0.5f };
    // Update pattern configurations
    private static final int                       UPDATE_ITERATIONS    = 1000;
    private static final float                     PARTICLE_VELOCITY    = 1.0f;
    private static final float                     TELEPORT_RANGE       = WORLD_SIZE * 0.5f;
    private              SequentialLongIDGenerator idGenerator;

    @BeforeAll
    public static void beforeAll() {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("RUN_SPATIAL_INDEX_PERF_TESTS", "false")));
    }

    @Test
    public void runFullStressTest() {
        System.out.println("=== SPATIAL INDEX COMPREHENSIVE STRESS TEST ===");
        printSystemInfo();

        // Check available memory
        var runtime = Runtime.getRuntime();
        var maxMemory = runtime.maxMemory();
        System.out.printf("Max heap size: %.2f GB%n", maxMemory / (1024.0 * 1024.0 * 1024.0));

        // Test 1: Scale testing
        System.out.println("\n=== SCALE TESTING ===");
        for (int entityCount : SCALE_ENTITY_COUNTS) {
            // Skip large tests if insufficient memory
            var requiredMemory = estimateRequiredMemory(entityCount);
            if (requiredMemory > maxMemory * 0.8) {
                System.out.printf("Skipping %d entities (requires ~%.2f GB, available: %.2f GB)%n", entityCount,
                                  requiredMemory / (1024.0 * 1024.0 * 1024.0), maxMemory / (1024.0 * 1024.0 * 1024.0));
                continue;
            }

            System.out.printf("\n--- Testing with %d entities ---%n", entityCount);
            runScaleTest(entityCount);
        }

        // Test 2: Distribution patterns
        System.out.println("\n=== DISTRIBUTION PATTERN TESTING ===");
        var testEntityCount = 100_000; // Use consistent count for distribution tests
        runDistributionPatternTest("Uniform", testEntityCount, this::generateUniformEntities);
        runDistributionPatternTest("Gaussian Clusters", testEntityCount, this::generateGaussianClusters);
        runDistributionPatternTest("Worst Case (Single Location)", testEntityCount, this::generateSingleLocation);
        runDistributionPatternTest("City Distribution", testEntityCount, this::generateCityDistribution);

        // Test 3: Query performance under load
        System.out.println("\n=== QUERY PERFORMANCE UNDER LOAD ===");
        runQueryPerformanceTest(100_000);

        // Test 4: Update patterns
        System.out.println("\n=== UPDATE PATTERN TESTING ===");
        runUpdatePatternTest("Particle Simulation", 10_000, this::particleUpdate);
        runUpdatePatternTest("Teleportation", 10_000, this::teleportUpdate);
        runUpdatePatternTest("Growing/Shrinking", 10_000, this::growingShrinkingUpdate);

        // Test 5: Memory pressure
        System.out.println("\n=== MEMORY PRESSURE TESTING ===");
        runMemoryPressureTest();
    }

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        // Skip if running in CI
        assumeFalse(CIEnvironmentCheck.isRunningInCI(), CIEnvironmentCheck.getSkipMessage());
    }

    private double benchmarkKNN(SpatialIndex<?, LongEntityID, String> index, List<TestEntity> entities, int k) {
        var random = ThreadLocalRandom.current();
        var queryCount = 1000;

        // Warmup
        for (int i = 0; i < 100; i++) {
            var entity = entities.get(random.nextInt(entities.size()));
            index.kNearestNeighbors(entity.position, k, Float.MAX_VALUE);
        }

        // Benchmark
        var startTime = System.nanoTime();
        for (int i = 0; i < queryCount; i++) {
            var entity = entities.get(random.nextInt(entities.size()));
            index.kNearestNeighbors(entity.position, k, Float.MAX_VALUE);
        }
        var elapsed = System.nanoTime() - startTime;

        return elapsed / 1000.0 / queryCount; // microseconds per query
    }

    private double benchmarkRange(SpatialIndex<?, LongEntityID, String> index, List<TestEntity> entities,
                                  float radius) {
        var random = ThreadLocalRandom.current();
        var queryCount = 1000;

        // Warmup
        for (int i = 0; i < 100; i++) {
            var entity = entities.get(random.nextInt(entities.size()));
            var region = new Spatial.Cube(entity.position.x - radius, entity.position.y - radius,
                                          entity.position.z - radius, radius * 2);
            index.entitiesInRegion(region);
        }

        // Benchmark
        var startTime = System.nanoTime();
        for (int i = 0; i < queryCount; i++) {
            var entity = entities.get(random.nextInt(entities.size()));
            var region = new Spatial.Cube(entity.position.x - radius, entity.position.y - radius,
                                          entity.position.z - radius, radius * 2);
            index.entitiesInRegion(region);
        }
        var elapsed = System.nanoTime() - startTime;

        return elapsed / 1000.0 / queryCount; // microseconds per query
    }

    private Octree<LongEntityID, String> createOctree() {
        return new Octree<>(new SequentialLongIDGenerator());
    }

    private Tetree<LongEntityID, String> createTetree() {
        return new Tetree<>(new SequentialLongIDGenerator());
    }

    private long estimateRequiredMemory(int entityCount) {
        // Rough estimate: 1KB per entity for spatial index overhead
        return entityCount * 1024L;
    }

    private TreeStats gatherTreeStats(SpatialIndex<?, LongEntityID, String> index) {
        var stats = new TreeStats();

        // This would require adding methods to the SpatialIndex interface
        // For now, return placeholder values
        stats.nodeCount = -1; // Not available without interface changes
        stats.maxDepth = MAX_LEVEL;

        return stats;
    }

    // Entity generation methods

    private List<TestEntity> generateCityDistribution(int count) {
        var entities = new ArrayList<TestEntity>(count);
        var random = ThreadLocalRandom.current();

        // Simulate city-like distribution: dense center, sparse outskirts
        for (int i = 0; i < count; i++) {
            float distance = (float) Math.sqrt(random.nextDouble()) * WORLD_SIZE / 2;
            float angle1 = random.nextFloat() * (float) (2 * Math.PI);
            float angle2 = random.nextFloat() * (float) Math.PI;

            var position = new Point3f(WORLD_SIZE / 2 + distance * (float) Math.sin(angle2) * (float) Math.cos(angle1),
                                       WORLD_SIZE / 2 + distance * (float) Math.sin(angle2) * (float) Math.sin(angle1),
                                       WORLD_SIZE / 2 + distance * (float) Math.cos(angle2));

            // Clamp to world bounds
            position.x = Math.max(0, Math.min(WORLD_SIZE, position.x));
            position.y = Math.max(0, Math.min(WORLD_SIZE, position.y));
            position.z = Math.max(0, Math.min(WORLD_SIZE, position.z));

            entities.add(new TestEntity(idGenerator.generateID(), position));
        }

        return entities;
    }

    private List<TestEntity> generateGaussianClusters(int count) {
        var entities = new ArrayList<TestEntity>(count);
        var random = ThreadLocalRandom.current();
        var numClusters = 10;
        var entitiesPerCluster = count / numClusters;

        for (int cluster = 0; cluster < numClusters; cluster++) {
            // Random cluster center
            var centerX = random.nextFloat() * WORLD_SIZE;
            var centerY = random.nextFloat() * WORLD_SIZE;
            var centerZ = random.nextFloat() * WORLD_SIZE;
            var stdDev = WORLD_SIZE * 0.05f; // 5% of world size

            for (int i = 0; i < entitiesPerCluster; i++) {
                var position = new Point3f((float) (centerX + random.nextGaussian() * stdDev),
                                           (float) (centerY + random.nextGaussian() * stdDev),
                                           (float) (centerZ + random.nextGaussian() * stdDev));

                // Clamp to world bounds
                position.x = Math.max(0, Math.min(WORLD_SIZE, position.x));
                position.y = Math.max(0, Math.min(WORLD_SIZE, position.y));
                position.z = Math.max(0, Math.min(WORLD_SIZE, position.z));

                entities.add(new TestEntity(idGenerator.generateID(), position));
            }
        }

        return entities;
    }

    private List<TestEntity> generateSingleLocation(int count) {
        var entities = new ArrayList<TestEntity>(count);
        var position = new Point3f(WORLD_SIZE / 2, WORLD_SIZE / 2, WORLD_SIZE / 2);

        for (int i = 0; i < count; i++) {
            entities.add(new TestEntity(idGenerator.generateID(), new Point3f(position)));
        }

        return entities;
    }

    private List<TestEntity> generateUniformEntities(int count) {
        var entities = new ArrayList<TestEntity>(count);
        var random = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            var position = new Point3f(random.nextFloat() * WORLD_SIZE, random.nextFloat() * WORLD_SIZE,
                                       random.nextFloat() * WORLD_SIZE);
            entities.add(new TestEntity(idGenerator.generateID(), position));
        }

        return entities;
    }

    // Update patterns

    private GCStats getGCStats() {
        var stats = new GCStats();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            stats.count += gc.getCollectionCount();
            stats.time += gc.getCollectionTime();
        }
        return stats;
    }

    private long getUsedMemory() {
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private Point3f growingShrinkingUpdate(TestEntity entity) {
        // Move entities radially in/out from center
        var center = new Point3f(WORLD_SIZE / 2, WORLD_SIZE / 2, WORLD_SIZE / 2);
        var direction = new Vector3f(entity.position);
        direction.sub(center);

        var random = ThreadLocalRandom.current();
        if (random.nextBoolean()) {
            // Grow outward
            direction.scale(1.1f);
        } else {
            // Shrink inward
            direction.scale(0.9f);
        }

        var newPos = new Point3f(center);
        newPos.add(direction);

        // Clamp to bounds
        newPos.x = Math.max(0, Math.min(WORLD_SIZE, newPos.x));
        newPos.y = Math.max(0, Math.min(WORLD_SIZE, newPos.y));
        newPos.z = Math.max(0, Math.min(WORLD_SIZE, newPos.z));

        return newPos;
    }

    // Measurement utilities

    private long measureInsertion(SpatialIndex<?, LongEntityID, String> index, List<TestEntity> entities) {
        var startTime = System.nanoTime();
        for (var entity : entities) {
            index.insert(entity.id, entity.position, MAX_LEVEL, entity.data);
        }
        return System.nanoTime() - startTime;
    }

    private long measureUpdates(SpatialIndex<?, LongEntityID, String> index, List<TestEntity> entities,
                                UpdatePattern pattern) {
        var random = ThreadLocalRandom.current();
        var startTime = System.nanoTime();

        for (int i = 0; i < UPDATE_ITERATIONS; i++) {
            var entity = entities.get(random.nextInt(entities.size()));
            var newPosition = pattern.update(entity);
            index.updateEntity(entity.id, newPosition, MAX_LEVEL);
            entity.position = newPosition;
        }

        return System.nanoTime() - startTime;
    }

    private Point3f particleUpdate(TestEntity entity) {
        var random = ThreadLocalRandom.current();
        var velocity = new Vector3f((random.nextFloat() - 0.5f) * 2 * PARTICLE_VELOCITY,
                                    (random.nextFloat() - 0.5f) * 2 * PARTICLE_VELOCITY,
                                    (random.nextFloat() - 0.5f) * 2 * PARTICLE_VELOCITY);

        var newPos = new Point3f(entity.position);
        newPos.add(velocity);

        // Bounce off boundaries
        if (newPos.x < 0 || newPos.x > WORLD_SIZE) {
            velocity.x = -velocity.x;
        }
        if (newPos.y < 0 || newPos.y > WORLD_SIZE) {
            velocity.y = -velocity.y;
        }
        if (newPos.z < 0 || newPos.z > WORLD_SIZE) {
            velocity.z = -velocity.z;
        }

        // Clamp to bounds
        newPos.x = Math.max(0, Math.min(WORLD_SIZE, newPos.x));
        newPos.y = Math.max(0, Math.min(WORLD_SIZE, newPos.y));
        newPos.z = Math.max(0, Math.min(WORLD_SIZE, newPos.z));

        return newPos;
    }

    private void printSystemInfo() {
        System.out.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max Memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.println();
    }

    private void runDistributionPatternTest(String patternName, int entityCount,
                                            Function<Integer, List<TestEntity>> generator) {
        System.out.printf("\n--- %s Distribution ---%n", patternName);

        var entities = generator.apply(entityCount);

        // Test both implementations
        var octree = createOctree();
        var tetree = createTetree();

        // Measure insertion
        var octreeTime = measureInsertion(octree, entities);
        var tetreeTime = measureInsertion(tetree, entities);

        // Measure tree characteristics
        var octreeStats = gatherTreeStats(octree);
        var tetreeStats = gatherTreeStats(tetree);

        System.out.printf("Octree: insertion %.2f ms, nodes %d, max depth %d%n", octreeTime / 1_000_000.0,
                          octreeStats.nodeCount, octreeStats.maxDepth);
        System.out.printf("Tetree: insertion %.2f ms, nodes %d, max depth %d%n", tetreeTime / 1_000_000.0,
                          tetreeStats.nodeCount, tetreeStats.maxDepth);
        System.out.printf("Performance ratio: %.2fx%n", tetreeTime / (double) octreeTime);
    }

    // Helper methods

    private void runMemoryPressureTest() {
        System.out.println("\n--- Memory Pressure Test ---");

        // Force low memory conditions
        var runtime = Runtime.getRuntime();
        var maxMemory = runtime.maxMemory();
        var targetUsage = maxMemory * 0.8; // Use 80% of available memory

        // Allocate memory to create pressure
        var memoryHog = new ArrayList<byte[]>();
        var chunkSize = 1024 * 1024; // 1MB chunks
        var allocated = 0L;

        while (allocated < targetUsage * 0.5) {
            memoryHog.add(new byte[chunkSize]);
            allocated += chunkSize;
        }

        System.out.printf("Created memory pressure: %.2f GB allocated%n", allocated / (1024.0 * 1024.0 * 1024.0));

        // Test under memory pressure
        var entityCount = 10_000;
        var entities = generateUniformEntities(entityCount);

        // Measure GC impact
        var gcBefore = getGCStats();

        var octree = createOctree();
        var startTime = System.nanoTime();
        entities.forEach(e -> octree.insert(e.id, e.position, MAX_LEVEL, e.data));
        var octreeTime = System.nanoTime() - startTime;

        var gcAfterOctree = getGCStats();

        var tetree = createTetree();
        startTime = System.nanoTime();
        entities.forEach(e -> tetree.insert(e.id, e.position, MAX_LEVEL, e.data));
        var tetreeTime = System.nanoTime() - startTime;

        var gcAfterTetree = getGCStats();

        // Report results
        System.out.printf("Octree: %.2f ms, GC count: %d, GC time: %.2f ms%n", octreeTime / 1_000_000.0,
                          gcAfterOctree.count - gcBefore.count, (double) (gcAfterOctree.time - gcBefore.time));
        System.out.printf("Tetree: %.2f ms, GC count: %d, GC time: %.2f ms%n", tetreeTime / 1_000_000.0,
                          gcAfterTetree.count - gcAfterOctree.count,
                          (double) (gcAfterTetree.time - gcAfterOctree.time));

        // Clean up
        memoryHog.clear();
        System.gc();
    }

    private void runQueryPerformanceTest(int entityCount) {
        var entities = generateUniformEntities(entityCount);

        var octree = createOctree();
        var tetree = createTetree();

        // Insert entities
        entities.forEach(e -> octree.insert(e.id, e.position, MAX_LEVEL, e.data));
        entities.forEach(e -> tetree.insert(e.id, e.position, MAX_LEVEL, e.data));

        System.out.println("\n--- k-NN Query Performance ---");
        for (int k : K_VALUES) {
            System.out.printf("\nk=%d:%n", k);

            var octreeTime = benchmarkKNN(octree, entities, k);
            var tetreeTime = benchmarkKNN(tetree, entities, k);

            System.out.printf("  Octree: %.2f μs/query%n", octreeTime);
            System.out.printf("  Tetree: %.2f μs/query%n", tetreeTime);
            System.out.printf("  Tetree is %.2fx faster%n", octreeTime / tetreeTime);
        }

        System.out.println("\n--- Range Query Performance ---");
        for (float percentage : RANGE_PERCENTAGES) {
            var radius = WORLD_SIZE * percentage;
            System.out.printf("\nRadius=%.1f (%.1f%% of world):%n", radius, percentage * 100);

            var octreeTime = benchmarkRange(octree, entities, radius);
            var tetreeTime = benchmarkRange(tetree, entities, radius);

            System.out.printf("  Octree: %.2f μs/query%n", octreeTime);
            System.out.printf("  Tetree: %.2f μs/query%n", tetreeTime);
            System.out.printf("  Tetree is %.2fx faster%n", octreeTime / tetreeTime);
        }
    }

    private void runScaleTest(int entityCount) {
        var entities = generateUniformEntities(entityCount);

        // Test Octree
        System.out.println("\nOctree Scale Test:");
        var octreeMetrics = testScalePerformance(() -> createOctree(), entities, "Octree");

        // Test Tetree
        System.out.println("\nTetree Scale Test:");
        var tetreeMetrics = testScalePerformance(() -> createTetree(), entities, "Tetree");

        // Compare results
        System.out.println("\nScale Test Comparison:");
        System.out.printf("Insertion: Octree %.2fx faster%n",
                          tetreeMetrics.insertionTime / (double) octreeMetrics.insertionTime);
        System.out.printf("Memory: Tetree uses %.1f%% of Octree memory%n",
                          (tetreeMetrics.memoryUsed / (double) octreeMetrics.memoryUsed) * 100);
    }

    private void runUpdatePatternTest(String patternName, int entityCount, UpdatePattern updatePattern) {
        System.out.printf("\n--- %s Update Pattern ---%n", patternName);

        var entities = generateUniformEntities(entityCount);

        var octree = createOctree();
        var tetree = createTetree();

        // Initial insertion
        entities.forEach(e -> octree.insert(e.id, e.position, MAX_LEVEL, e.data));
        entities.forEach(e -> tetree.insert(e.id, e.position, MAX_LEVEL, e.data));

        // Measure updates
        var octreeTime = measureUpdates(octree, entities, updatePattern);
        var tetreeTime = measureUpdates(tetree, entities, updatePattern);

        System.out.printf("Octree: %.2f ms for %d updates (%.2f μs/update)%n", octreeTime / 1_000_000.0,
                          UPDATE_ITERATIONS, octreeTime / 1000.0 / UPDATE_ITERATIONS);
        System.out.printf("Tetree: %.2f ms for %d updates (%.2f μs/update)%n", tetreeTime / 1_000_000.0,
                          UPDATE_ITERATIONS, tetreeTime / 1000.0 / UPDATE_ITERATIONS);
        System.out.printf("Performance ratio: %.2fx%n", tetreeTime / (double) octreeTime);
    }

    private Point3f teleportUpdate(TestEntity entity) {
        var random = ThreadLocalRandom.current();
        return new Point3f(random.nextFloat() * WORLD_SIZE, random.nextFloat() * WORLD_SIZE,
                           random.nextFloat() * WORLD_SIZE);
    }

    private ScaleMetrics testScalePerformance(Supplier<SpatialIndex<?, LongEntityID, String>> indexSupplier,
                                              List<TestEntity> entities, String implementation) {

        var metrics = new ScaleMetrics();

        // Warm up
        for (int i = 0; i < 5; i++) {
            var index = indexSupplier.get();
            for (var entity : entities.subList(0, Math.min(1000, entities.size()))) {
                index.insert(entity.id, entity.position, MAX_LEVEL, entity.data);
            }
        }

        // Measure insertion with memory
        System.gc();
        var memoryBefore = getUsedMemory();
        var startTime = System.nanoTime();

        var index = indexSupplier.get();
        for (var entity : entities) {
            index.insert(entity.id, entity.position, MAX_LEVEL, entity.data);
        }

        metrics.insertionTime = System.nanoTime() - startTime;
        metrics.memoryUsed = getUsedMemory() - memoryBefore;

        // Measure query performance
        var random = ThreadLocalRandom.current();
        var queryCount = 1000;

        // k-NN queries
        startTime = System.nanoTime();
        for (int i = 0; i < queryCount; i++) {
            var entity = entities.get(random.nextInt(entities.size()));
            index.kNearestNeighbors(entity.position, 10, Float.MAX_VALUE);
        }
        metrics.knnTime = System.nanoTime() - startTime;

        // Range queries
        startTime = System.nanoTime();
        for (int i = 0; i < queryCount; i++) {
            var entity = entities.get(random.nextInt(entities.size()));
            // Ensure cube origin is non-negative for Tetree compatibility
            var originX = Math.max(0, entity.position.x - 50.0f);
            var originY = Math.max(0, entity.position.y - 50.0f);
            var originZ = Math.max(0, entity.position.z - 50.0f);
            var region = new Spatial.Cube(originX, originY, originZ, 100.0f);
            index.entitiesInRegion(region);
        }
        metrics.rangeTime = System.nanoTime() - startTime;

        // Print results
        System.out.printf("  Insertion: %.2f ms (%.2f μs/entity)%n", metrics.insertionTime / 1_000_000.0,
                          metrics.insertionTime / 1000.0 / entities.size());
        System.out.printf("  Memory: %.2f MB (%.2f KB/entity)%n", metrics.memoryUsed / (1024.0 * 1024.0),
                          metrics.memoryUsed / 1024.0 / entities.size());
        System.out.printf("  k-NN: %.2f μs/query%n", metrics.knnTime / 1000.0 / queryCount);
        System.out.printf("  Range: %.2f μs/query%n", metrics.rangeTime / 1000.0 / queryCount);

        return metrics;
    }

    // Inner classes

    @FunctionalInterface
    private interface UpdatePattern {
        Point3f update(TestEntity entity);
    }

    private static class TestEntity {
        final LongEntityID id;
        final String       data;
        Point3f position;

        TestEntity(LongEntityID id, Point3f position) {
            this.id = id;
            this.position = position;
            this.data = "Entity " + id.toString();
        }
    }

    private static class ScaleMetrics {
        long insertionTime;
        long memoryUsed;
        long knnTime;
        long rangeTime;
    }

    private static class TreeStats {
        int nodeCount;
        int maxDepth;
    }

    private static class GCStats {
        long count;
        long time;
    }
}
