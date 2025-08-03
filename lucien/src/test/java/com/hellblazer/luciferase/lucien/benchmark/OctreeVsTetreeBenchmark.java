package com.hellblazer.luciferase.lucien.benchmark;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Head-to-head performance comparison of Octree vs Tetree
 */
public class OctreeVsTetreeBenchmark {

    private static final int   WARMUP_ITERATIONS    = 100;
    private static final int   BENCHMARK_ITERATIONS = 1000;
    private static final int   MAX_ENTITY_COUNT     = Integer.parseInt(
        System.getProperty("performance.max.entities", "10000"));
    private static final int[] ALL_ENTITY_COUNTS    = { 100, 1000, 10000 };
    private static final int[] ENTITY_COUNTS        = java.util.Arrays.stream(ALL_ENTITY_COUNTS)
        .filter(size -> size <= MAX_ENTITY_COUNT)
        .toArray();
    private static final int   K_NEIGHBORS          = 10;
    private static final float SEARCH_RADIUS        = 50.0f;
    private static final byte  TEST_LEVEL           = 10;

    @Test
    public void comparePerformance() {
        System.out.println("=== OCTREE vs TETREE PERFORMANCE COMPARISON ===");
        System.out.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.println();

        for (int entityCount : ENTITY_COUNTS) {
            System.out.println("\n=== Testing with " + entityCount + " entities ===");
            runComparison(entityCount);
        }
    }

    @BeforeEach
    void checkEnvironment() {
        // Skip if running in any CI environment
        assumeFalse(CIEnvironmentCheck.isRunningInCI(), CIEnvironmentCheck.getSkipMessage());
    }

    private long benchmarkInsertion(Octree<LongEntityID, String> index, List<TestEntity> entities) {
        // Warmup
        for (int i = 0; i < Math.min(WARMUP_ITERATIONS, entities.size()); i++) {
            var e = entities.get(i);
            index.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        // Clear all entities
        for (var e : entities.subList(0, Math.min(WARMUP_ITERATIONS, entities.size()))) {
            index.removeEntity(e.id);
        }

        // Benchmark
        var start = System.nanoTime();
        for (var e : entities) {
            index.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        return System.nanoTime() - start;
    }

    private long benchmarkInsertion(Tetree<LongEntityID, String> index, List<TestEntity> entities) {
        // Warmup
        for (int i = 0; i < Math.min(WARMUP_ITERATIONS, entities.size()); i++) {
            var e = entities.get(i);
            index.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        // Clear all entities
        for (var e : entities.subList(0, Math.min(WARMUP_ITERATIONS, entities.size()))) {
            index.removeEntity(e.id);
        }

        // Benchmark
        var start = System.nanoTime();
        for (var e : entities) {
            index.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        return System.nanoTime() - start;
    }

    private long benchmarkKNN(Octree<LongEntityID, String> index, List<Point3f> queryPoints) {
        var totalTime = 0L;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var query = queryPoints.get(i % queryPoints.size());
            var start = System.nanoTime();
            index.kNearestNeighbors(query, K_NEIGHBORS, Float.MAX_VALUE);
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private long benchmarkKNN(Tetree<LongEntityID, String> index, List<Point3f> queryPoints) {
        var totalTime = 0L;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var query = queryPoints.get(i % queryPoints.size());
            var start = System.nanoTime();
            index.kNearestNeighbors(query, K_NEIGHBORS, Float.MAX_VALUE);
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private long benchmarkRangeQuery(Octree<LongEntityID, String> index, List<Point3f> queryPoints) {
        var totalTime = 0L;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var center = queryPoints.get(i % queryPoints.size());
            var start = System.nanoTime();
            // Use k-NN with limited radius for range query
            index.kNearestNeighbors(center, Integer.MAX_VALUE, SEARCH_RADIUS);
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private long benchmarkRangeQuery(Tetree<LongEntityID, String> index, List<Point3f> queryPoints) {
        var totalTime = 0L;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var center = queryPoints.get(i % queryPoints.size());
            var start = System.nanoTime();
            // Use k-NN with limited radius for range query
            index.kNearestNeighbors(center, Integer.MAX_VALUE, SEARCH_RADIUS);
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private long benchmarkRemoval(Octree<LongEntityID, String> index, List<TestEntity> entities) {
        var random = ThreadLocalRandom.current();
        var removals = Math.min(1000, entities.size());
        var totalTime = 0L;

        for (int i = 0; i < removals; i++) {
            var e = entities.get(random.nextInt(entities.size()));
            var start = System.nanoTime();
            index.removeEntity(e.id);
            totalTime += System.nanoTime() - start;
        }
        return totalTime / removals;
    }

    private long benchmarkRemoval(Tetree<LongEntityID, String> index, List<TestEntity> entities) {
        var random = ThreadLocalRandom.current();
        var removals = Math.min(1000, entities.size());
        var totalTime = 0L;

        for (int i = 0; i < removals; i++) {
            var e = entities.get(random.nextInt(entities.size()));
            var start = System.nanoTime();
            index.removeEntity(e.id);
            totalTime += System.nanoTime() - start;
        }
        return totalTime / removals;
    }

    private long benchmarkUpdate(Octree<LongEntityID, String> index, List<TestEntity> entities) {
        var random = ThreadLocalRandom.current();
        var updates = Math.min(1000, entities.size());
        var totalTime = 0L;

        for (int i = 0; i < updates; i++) {
            var e = entities.get(random.nextInt(entities.size()));
            // Move within 5% of current position to stay within valid bounds
            var moveRange = 50.0f; // 5% of 1000 max coordinate
            var newPos = new Point3f(e.position.x + random.nextFloat(-moveRange, moveRange),
                                     e.position.y + random.nextFloat(-moveRange, moveRange),
                                     e.position.z + random.nextFloat(-moveRange, moveRange));
            // Clamp to valid coordinate range [0.1, 999.9]
            newPos.x = Math.max(0.1f, Math.min(999.9f, newPos.x));
            newPos.y = Math.max(0.1f, Math.min(999.9f, newPos.y));
            newPos.z = Math.max(0.1f, Math.min(999.9f, newPos.z));

            var start = System.nanoTime();
            index.updateEntity(e.id, newPos, TEST_LEVEL);
            totalTime += System.nanoTime() - start;
        }
        return totalTime / updates;
    }

    private long benchmarkUpdate(Tetree<LongEntityID, String> index, List<TestEntity> entities) {
        var random = ThreadLocalRandom.current();
        var updates = Math.min(1000, entities.size());
        var totalTime = 0L;

        for (int i = 0; i < updates; i++) {
            var e = entities.get(random.nextInt(entities.size()));
            var newPos = new Point3f(Math.max(0.1f, e.position.x + random.nextFloat(-10, 10)),
                                     Math.max(0.1f, e.position.y + random.nextFloat(-10, 10)),
                                     Math.max(0.1f, e.position.z + random.nextFloat(-10, 10)));

            var start = System.nanoTime();
            index.updateEntity(e.id, newPos, TEST_LEVEL);
            totalTime += System.nanoTime() - start;
        }
        return totalTime / updates;
    }

    private List<TestEntity> generateEntities(int count) {
        var entities = new ArrayList<TestEntity>(count);
        var random = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            entities.add(new TestEntity(new LongEntityID(i),
                                        new Point3f(random.nextFloat(0, 1000), random.nextFloat(0, 1000),
                                                    random.nextFloat(0, 1000)), "Entity" + i));
        }
        return entities;
    }

    private List<Point3f> generateQueryPoints(int count) {
        var points = new ArrayList<Point3f>(count);
        var random = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            points.add(new Point3f(random.nextFloat(0, 1000), random.nextFloat(0, 1000), random.nextFloat(0, 1000)));
        }
        return points;
    }

    private long getUsedMemory() {
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private void measureMemoryUsage(int entityCount) {
        System.gc();
        var baseMemory = getUsedMemory();

        // Measure Octree memory
        var idGen1 = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGen1);
        var entities = generateEntities(entityCount);
        for (var e : entities) {
            octree.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        System.gc();
        var octreeMemory = getUsedMemory() - baseMemory;

        // Clear and measure Tetree memory
        octree = null;
        System.gc();
        baseMemory = getUsedMemory();

        var idGen2 = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGen2);
        for (var e : entities) {
            tetree.insert(e.id, e.position, TEST_LEVEL, e.data);
        }
        System.gc();
        var tetreeMemory = getUsedMemory() - baseMemory;

        System.out.printf("Octree Memory: %.2f MB%n", octreeMemory / 1024.0 / 1024.0);
        System.out.printf("Tetree Memory: %.2f MB (%.1f%% of Octree)%n", tetreeMemory / 1024.0 / 1024.0,
                          (tetreeMemory * 100.0) / octreeMemory);
    }

    private void printComparison(String operation, long octreeTime, long tetreeTime, int operations) {
        var octreeMs = octreeTime / 1_000_000.0;
        var tetreeMs = tetreeTime / 1_000_000.0;
        var ratio = tetreeMs / octreeMs;
        var winner = octreeTime < tetreeTime ? "Octree" : "Tetree";
        var speedup = Math.max(octreeMs, tetreeMs) / Math.min(octreeMs, tetreeMs);

        System.out.printf("Octree: %.3f ms%n", octreeMs);
        System.out.printf("Tetree: %.3f ms (%.2fx)%n", tetreeMs, ratio);
        System.out.printf("Winner: %s (%.1fx faster)%n", winner, speedup);

        if (operations > 1) {
            System.out.printf("Per-operation: Octree=%.3f μs, Tetree=%.3f μs%n", (octreeTime / 1000.0) / operations,
                              (tetreeTime / 1000.0) / operations);
        }
    }

    private void runComparison(int entityCount) {
        // Generate test data
        var entities = generateEntities(entityCount);
        var queryPoints = generateQueryPoints(100);

        // Create indices
        var idGen = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGen);
        var tetree = new Tetree<LongEntityID, String>(idGen);

        // 1. Insertion Performance
        System.out.println("\n1. INSERTION PERFORMANCE:");
        var octreeInsertTime = benchmarkInsertion(octree, entities);
        var tetreeInsertTime = benchmarkInsertion(tetree, entities);
        printComparison("Insertion", octreeInsertTime, tetreeInsertTime, entityCount);

        // 2. K-NN Search Performance
        System.out.println("\n2. K-NEAREST NEIGHBOR SEARCH:");
        var octreeKnnTime = benchmarkKNN(octree, queryPoints);
        var tetreeKnnTime = benchmarkKNN(tetree, queryPoints);
        printComparison("K-NN Search", octreeKnnTime, tetreeKnnTime, queryPoints.size());

        // 3. Range Query Performance
        System.out.println("\n3. RANGE QUERY PERFORMANCE:");
        var octreeRangeTime = benchmarkRangeQuery(octree, queryPoints);
        var tetreeRangeTime = benchmarkRangeQuery(tetree, queryPoints);
        printComparison("Range Query", octreeRangeTime, tetreeRangeTime, queryPoints.size());

        // 4. Update Performance
        System.out.println("\n4. UPDATE PERFORMANCE:");
        var octreeUpdateTime = benchmarkUpdate(octree, entities);
        var tetreeUpdateTime = benchmarkUpdate(tetree, entities);
        printComparison("Update", octreeUpdateTime, tetreeUpdateTime, Math.min(1000, entityCount));

        // 5. Removal Performance
        System.out.println("\n5. REMOVAL PERFORMANCE:");
        var octreeRemoveTime = benchmarkRemoval(octree, entities);
        var tetreeRemoveTime = benchmarkRemoval(tetree, entities);
        printComparison("Removal", octreeRemoveTime, tetreeRemoveTime, Math.min(1000, entityCount));

        // 6. Memory Usage
        System.out.println("\n6. MEMORY USAGE:");
        measureMemoryUsage(entityCount);
    }

    private static class TestEntity {
        final LongEntityID id;
        final Point3f      position;
        final String       data;

        TestEntity(LongEntityID id, Point3f position, String data) {
            this.id = id;
            this.position = position;
            this.data = data;
        }
    }
}
