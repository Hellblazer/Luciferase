/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.performance;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.*;

import javax.vecmath.Point3f;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for spatial index performance testing. Provides common infrastructure for measuring performance
 * characteristics of SpatialIndex implementations.
 *
 * @author hal.hildebrand
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractSpatialIndexPerformanceTest<Key extends com.hellblazer.luciferase.lucien.SpatialKey<Key>, ID extends EntityID, Content> {

    protected static final boolean RUN_PERF_TESTS = Boolean.parseBoolean(
    System.getenv().getOrDefault("RUN_SPATIAL_INDEX_PERF_TESTS", "false"));

    // Create results directory in target folder for proper cleanup during builds
    protected static final Path              RESULTS_DIR      = Paths.get("performance-results");
    protected static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    // Test data sizes matching C++ scales
    protected static final int[] TEST_SIZES = { 50, 100, 200, 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 5000, 6000,
                                                7000, 8000, 10000, 50000 };

    // Smaller sizes for quick smoke tests
    protected static final int[] SMOKE_TEST_SIZES = { 50, 100, 500, 1000, 5000 };

    // Standard test bounds
    protected static final VolumeBounds DEFAULT_BOUNDS = new VolumeBounds(0, 0, 0, 1000, 1000, 1000);

    protected static final int                      DEFAULT_MAX_DEPTH  = 10;
    protected static final int                      WARMUP_ITERATIONS  = 3;
    protected static final int                      TEST_ITERATIONS    = 10;
    // Static collection to accumulate results across all test instances
    protected static final List<PerformanceMetrics> performanceResults = Collections.synchronizedList(
    new ArrayList<>());
    protected static       String                   testRunId;
    protected              Random                   random;

    /**
     * Assert performance doesn't regress beyond threshold
     */
    protected void assertNoPerformanceRegression(PerformanceMetrics baseline, PerformanceMetrics current,
                                                 double maxRegressionPercent) {
        double regressionPercent =
        ((double) (current.getElapsedNanos() - baseline.getElapsedNanos()) / baseline.getElapsedNanos()) * 100;

        Assertions.assertTrue(regressionPercent <= maxRegressionPercent,
                              String.format("Performance regression detected: %.1f%% (max allowed: %.1f%%)",
                                            regressionPercent, maxRegressionPercent));
    }

    /**
     * Create an entity ID generator
     */
    protected abstract SequentialLongIDGenerator createIDGenerator();

    /**
     * Create a spatial index instance for testing
     */
    protected abstract SpatialIndex<Key, ID, Content> createSpatialIndex(VolumeBounds bounds, int maxDepth);

    /**
     * Create test content for an entity
     */
    protected abstract Content createTestContent(int entityIndex);

    /**
     * Generate test entities with specified distribution
     */
    protected List<TestEntity> generateTestEntities(int count, SpatialDistribution distribution) {
        List<Point3f> positions = distribution.generate(count, DEFAULT_BOUNDS);
        List<TestEntity> entities = new ArrayList<>(count);

        SequentialLongIDGenerator idGen = createIDGenerator();
        for (int i = 0; i < count; i++) {
            entities.add(new TestEntity(idGen.generateID(), positions.get(i), createTestContent(i)));
        }

        return entities;
    }

    /**
     * Get the name of the implementation being tested (e.g., "Octree", "Tetree")
     */
    protected abstract String getImplementationName();

    /**
     * Measure the execution time and memory usage of a task
     */
    protected PerformanceMetrics measure(String operation, int entityCount, Runnable task) {
        // Force GC before measurement
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long memoryBefore = getUsedMemory();
        long startTime = System.nanoTime();

        task.run();

        long endTime = System.nanoTime();
        long memoryAfter = getUsedMemory();

        long elapsedNanos = endTime - startTime;
        long memoryUsed = Math.max(0, memoryAfter - memoryBefore);

        Map<String, Object> additionalMetrics = new HashMap<>();
        additionalMetrics.put("implementation", getImplementationName());

        return new PerformanceMetrics(operation, entityCount, elapsedNanos, memoryUsed, additionalMetrics);
    }

    /**
     * Run a performance test multiple times and return average metrics
     */
    protected PerformanceMetrics measureAverage(String operation, int entityCount, Runnable setupTask,
                                                Runnable testTask) {
        List<PerformanceMetrics> iterations = new ArrayList<>();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            setupTask.run();
            testTask.run();
        }

        // Actual measurements
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            setupTask.run();
            PerformanceMetrics metrics = measure(operation, entityCount, testTask);
            iterations.add(metrics);
        }

        return PerformanceMetrics.average(iterations);
    }

    /**
     * Perform JVM warmup iterations
     */
    protected void warmup(Runnable task) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            task.run();
        }
    }

    @BeforeEach
    void checkPerformanceTestsEnabled() {
        assumeTrue(RUN_PERF_TESTS, "Performance tests disabled. Set RUN_SPATIAL_INDEX_PERF_TESTS=true to enable.");

        random = new Random(42); // Fixed seed for reproducibility
        // Don't reset performanceResults - it's static and accumulates across all tests
    }

    @AfterAll
    void saveResults() throws IOException {
        if (RUN_PERF_TESTS && !performanceResults.isEmpty()) {
            Path resultFile = RESULTS_DIR.resolve(
            String.format("%s_%s_%s.csv", getImplementationName(), getClass().getSimpleName(), testRunId));
            PerformanceMetrics.exportToCSV(performanceResults, resultFile);
            // Clear results after saving for next test class
            performanceResults.clear();
        }
    }

    @BeforeAll
    void setupPerformanceTests() throws IOException {
        if (RUN_PERF_TESTS) {
            Files.createDirectories(RESULTS_DIR);
            testRunId = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        }
    }

    /**
     * Get current JVM heap usage
     */
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Test entity wrapper
     */
    protected static class TestEntity {
        public final EntityID id;
        public final Point3f  position;
        public final Object   content;

        public TestEntity(EntityID id, Point3f position, Object content) {
            this.id = id;
            this.position = position;
            this.content = content;
        }
    }
}
