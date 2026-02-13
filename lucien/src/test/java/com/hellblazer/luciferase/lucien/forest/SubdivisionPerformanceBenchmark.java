/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Performance benchmarks for tetrahedral forest subdivision operations.
 *
 * Measures:
 * - Per-operation subdivision time (target: <10ms)
 * - Entity redistribution time (target: <5ms per 1000 entities)
 * - Hierarchy traversal time (target: <1ms per root-to-leaf path)
 *
 * Compares dual-path tetrahedral subdivision (CUBIC_TO_TET → TET_TO_SUBTET)
 * against octant subdivision for baseline.
 *
 * <h3>JVM Warmup Strategy</h3>
 * Uses light warmup (5 iterations) for representative "typical" performance,
 * not peak C2-optimized performance. For rigorous JIT analysis, use JMH framework.
 *
 * <h3>GC Impact</h3>
 * Run with: -Xlog:gc* to observe GC impact on measurements.
 *
 * <h3>Usage</h3>
 * Run with: mvn test -pl lucien -Dtest=SubdivisionPerformanceBenchmark -Pperformance -da
 * Use -da to disable assertions for accurate measurements.
 * Use -Dperformance.strict=true to fail tests if targets not met.
 *
 * @author hal.hildebrand
 */
@Disabled("Performance benchmark - enable manually or run with -Pperformance")
public class SubdivisionPerformanceBenchmark {

    // Benchmark configuration
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 20;
    private static final Random RANDOM = new Random(42); // Fixed seed for reproducibility

    // Entity position configuration
    private static final float MIN_POSITION = 50.0f;
    private static final float POSITION_RANGE = 900.0f; // Entities distributed in [50, 950]

    // Forest bounds configuration
    private static final float FOREST_MIN = 0.0f;
    private static final float FOREST_MAX = 1000.0f;

    // Subdivision wait configuration
    private static final int MAX_SUBDIVISION_WAIT_MS = 5000;
    private static final int SUBDIVISION_POLL_INTERVAL_MS = 50;

    // Performance validation (use -Dperformance.strict=true to enable)
    private static final boolean STRICT_MODE = Boolean.getBoolean("performance.strict");
    private static final double TARGET_SUBDIVISION_MS = 10.0;
    private static final double TARGET_REDISTRIBUTION_MS_PER_1000 = 5.0;
    private static final double TARGET_TRAVERSAL_MS = 1.0;

    private EntityIDGenerator<LongEntityID> idGenerator;
    private List<Point3f> testPositions;

    @BeforeEach
    void setUp() {
        var idCounter = new AtomicLong(0);
        idGenerator = () -> new LongEntityID(idCounter.getAndIncrement());
        testPositions = new ArrayList<>();
    }

    @BeforeEach
    void checkEnvironment() {
        // Skip in CI environment - these are manual performance benchmarks
        String ci = System.getenv("CI");
        assumeFalse("true".equals(ci), "Skipping performance benchmark in CI environment");
    }

    /**
     * Benchmark per-operation subdivision time.
     * Target: <10ms per subdivision operation.
     */
    @Test
    void benchmarkSubdivisionTime() {
        System.out.println("\n=== Per-Operation Subdivision Time ===");
        System.out.println("Target: <10ms per operation\n");

        int[] entityCounts = {10, 20, 50, 100};
        boolean anyFailures = false;

        for (int entityCount : entityCounts) {
            // Test CUBIC_TO_TET (cubic → 6 tets)
            long tetSubdivisionTime = measureTetrahedralSubdivision(entityCount, false);

            // Test TET_TO_SUBTET (tet → 8 subtets) - cascade
            long tetCascadeTime = measureTetrahedralSubdivision(entityCount, true);

            // Test OCTANT for baseline
            long octantTime = measureOctantSubdivision(entityCount);

            double tetMs = tetSubdivisionTime / 1_000_000.0;
            double tetCascadeMs = tetCascadeTime / 1_000_000.0;

            System.out.printf("Entities: %d%n", entityCount);
            System.out.printf("  Tetrahedral (cubic→6 tets):  %6.2f ms %s%n",
                tetMs, tetMs < TARGET_SUBDIVISION_MS ? "✓" : "✗ (exceeds target)");
            System.out.printf("  Tetrahedral (cascade):        %6.2f ms %s%n",
                tetCascadeMs, tetCascadeMs < TARGET_SUBDIVISION_MS ? "✓" : "✗ (exceeds target)");
            System.out.printf("  Octant (baseline):            %6.2f ms%n",
                octantTime / 1_000_000.0);
            System.out.printf("  Speedup vs Octant:            %.2fx (cubic→6), %.2fx (cascade)%n",
                (double) octantTime / tetSubdivisionTime,
                (double) octantTime / tetCascadeTime);
            System.out.println();

            if (tetMs >= TARGET_SUBDIVISION_MS || tetCascadeMs >= TARGET_SUBDIVISION_MS) {
                anyFailures = true;
            }
        }

        if (STRICT_MODE && anyFailures) {
            throw new AssertionError("Subdivision performance targets not met (see output above)");
        }
    }

    /**
     * Benchmark entity redistribution during subdivision.
     * Target: <5ms per 1000 entities.
     */
    @Test
    void benchmarkEntityRedistribution() {
        System.out.println("\n=== Entity Redistribution Time ===");
        System.out.println("Target: <5ms per 1000 entities\n");

        int[] entityCounts = {100, 500, 1000, 2000, 5000};
        boolean anyFailures = false;

        for (int entityCount : entityCounts) {
            long tetRedistTime = measureTetrahedralRedistribution(entityCount);
            long octantRedistTime = measureOctantRedistribution(entityCount);

            double tetTimePerThousand = (tetRedistTime / 1_000_000.0) / (entityCount / 1000.0);
            double octantTimePerThousand = (octantRedistTime / 1_000_000.0) / (entityCount / 1000.0);

            System.out.printf("Entities: %d%n", entityCount);
            System.out.printf("  Tetrahedral: %6.2f ms total, %6.2f ms/1000 entities %s%n",
                tetRedistTime / 1_000_000.0,
                tetTimePerThousand,
                tetTimePerThousand < TARGET_REDISTRIBUTION_MS_PER_1000 ? "✓" : "✗ (exceeds target)");
            System.out.printf("  Octant:      %6.2f ms total, %6.2f ms/1000 entities%n",
                octantRedistTime / 1_000_000.0,
                octantTimePerThousand);
            System.out.printf("  Speedup vs Octant: %.2fx%n",
                (double) octantRedistTime / tetRedistTime);
            System.out.println();

            if (tetTimePerThousand >= TARGET_REDISTRIBUTION_MS_PER_1000) {
                anyFailures = true;
            }
        }

        if (STRICT_MODE && anyFailures) {
            throw new AssertionError("Redistribution performance targets not met (see output above)");
        }
    }

    /**
     * Benchmark hierarchy traversal time.
     * Target: <1ms per root-to-leaf path.
     */
    @Test
    void benchmarkHierarchyTraversal() {
        System.out.println("\n=== Hierarchy Traversal Time ===");
        System.out.println("Target: <1ms per root-to-leaf path\n");

        // Test different hierarchy depths
        int[] depths = {1, 2, 3}; // levels deep
        boolean anyFailures = false;

        for (int depth : depths) {
            long tetTraversalTime = measureTetrahedralTraversal(depth);
            long octantTraversalTime = measureOctantTraversal(depth);

            double tetMs = tetTraversalTime / 1_000_000.0;

            System.out.printf("Hierarchy depth: %d levels%n", depth);
            System.out.printf("  Tetrahedral: %6.3f ms per path %s%n",
                tetMs, tetMs < TARGET_TRAVERSAL_MS ? "✓" : "✗ (exceeds target)");
            System.out.printf("  Octant:      %6.3f ms per path%n",
                octantTraversalTime / 1_000_000.0);
            System.out.printf("  Speedup vs Octant: %.2fx%n",
                (double) octantTraversalTime / tetTraversalTime);
            System.out.println();

            if (tetMs >= TARGET_TRAVERSAL_MS) {
                anyFailures = true;
            }
        }

        if (STRICT_MODE && anyFailures) {
            throw new AssertionError("Traversal performance targets not met (see output above)");
        }
    }

    /**
     * Benchmark memory usage comparison.
     * Measures heap memory consumption for tetrahedral vs octant forests.
     */
    @Test
    void benchmarkMemoryUsage() {
        System.out.println("\n=== Memory Usage Comparison ===");
        System.out.println("Note: Run with sufficient warmup for accurate GC-stabilized measurements\n");

        int[] entityCounts = {100, 500, 1000};

        for (int entityCount : entityCounts) {
            // Measure tetrahedral memory
            System.gc();
            try {
                Thread.sleep(100); // Allow GC to settle
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Runtime runtime = Runtime.getRuntime();
            long tetBeforeMem = runtime.totalMemory() - runtime.freeMemory();

            var tetForest = createAndSubdivideTetrahedralForest(entityCount, false);

            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long tetAfterMem = runtime.totalMemory() - runtime.freeMemory();
            long tetMemUsed = tetAfterMem - tetBeforeMem;

            tetForest.shutdown();

            // Measure octant memory
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long octBeforeMem = runtime.totalMemory() - runtime.freeMemory();

            var octForest = createAndSubdivideOctantForest(entityCount);

            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long octAfterMem = runtime.totalMemory() - runtime.freeMemory();
            long octMemUsed = octAfterMem - octBeforeMem;

            octForest.shutdown();

            System.out.printf("Entities: %d%n", entityCount);
            System.out.printf("  Tetrahedral: %6.2f KB%n", tetMemUsed / 1024.0);
            System.out.printf("  Octant:      %6.2f KB%n", octMemUsed / 1024.0);
            System.out.printf("  Ratio (Tet/Oct): %.2fx%n",
                tetMemUsed > 0 ? (double) tetMemUsed / octMemUsed : 0.0);
            System.out.println();
        }

        System.out.println("Note: Memory measurements are approximate due to GC behavior.");
        System.out.println("      For precise profiling, use a dedicated memory profiler.");
    }

    /**
     * Summary benchmark comparing all metrics side-by-side.
     */
    @Test
    void benchmarkComprehensiveSummary() {
        System.out.println("\n=== COMPREHENSIVE PERFORMANCE SUMMARY ===");
        System.out.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB\n");

        // Test with moderate entity count for balanced results
        int testEntityCount = 50;
        int testRedistCount = 1000;
        int testDepth = 2;

        System.out.println("Test Configuration:");
        System.out.printf("  Subdivision entities: %d%n", testEntityCount);
        System.out.printf("  Redistribution entities: %d%n", testRedistCount);
        System.out.printf("  Hierarchy depth: %d levels%n%n", testDepth);

        // Measure all operations
        long tetSubdiv = measureTetrahedralSubdivision(testEntityCount, false);
        long octSubdiv = measureOctantSubdivision(testEntityCount);

        long tetRedist = measureTetrahedralRedistribution(testRedistCount);
        long octRedist = measureOctantRedistribution(testRedistCount);

        long tetTraversal = measureTetrahedralTraversal(testDepth);
        long octTraversal = measureOctantTraversal(testDepth);

        System.out.println("RESULTS:");
        System.out.println("┌─────────────────────────┬──────────────┬──────────────┬──────────┐");
        System.out.println("│ Operation               │ Tetrahedral  │ Octant       │ Speedup  │");
        System.out.println("├─────────────────────────┼──────────────┼──────────────┼──────────┤");
        System.out.printf("│ Subdivision             │ %8.2f ms │ %8.2f ms │ %6.2fx │%n",
            tetSubdiv / 1_000_000.0, octSubdiv / 1_000_000.0, (double) octSubdiv / tetSubdiv);
        System.out.printf("│ Redistribution (1k ent) │ %8.2f ms │ %8.2f ms │ %6.2fx │%n",
            tetRedist / 1_000_000.0, octRedist / 1_000_000.0, (double) octRedist / tetRedist);
        System.out.printf("│ Hierarchy traversal     │ %8.3f ms │ %8.3f ms │ %6.2fx │%n",
            tetTraversal / 1_000_000.0, octTraversal / 1_000_000.0, (double) octTraversal / tetTraversal);
        System.out.println("└─────────────────────────┴──────────────┴──────────────┴──────────┘");

        System.out.println("\nTARGET VALIDATION:");
        boolean subdivPass = tetSubdiv / 1_000_000.0 < 10.0;
        boolean redistPass = (tetRedist / 1_000_000.0) / (testRedistCount / 1000.0) < 5.0;
        boolean traversalPass = tetTraversal / 1_000_000.0 < 1.0;

        System.out.printf("  Per-operation subdivision (<10ms):       %s %.2f ms%n",
            subdivPass ? "✓ PASS" : "✗ FAIL", tetSubdiv / 1_000_000.0);
        System.out.printf("  Entity redistribution (<5ms/1000 ent):   %s %.2f ms%n",
            redistPass ? "✓ PASS" : "✗ FAIL", (tetRedist / 1_000_000.0) / (testRedistCount / 1000.0));
        System.out.printf("  Hierarchy traversal (<1ms/path):         %s %.3f ms%n",
            traversalPass ? "✓ PASS" : "✗ FAIL", tetTraversal / 1_000_000.0);

        if (subdivPass && redistPass && traversalPass) {
            System.out.println("\n✓ ALL PERFORMANCE TARGETS MET");
        } else {
            System.out.println("\n✗ Some performance targets not met - see above for details");
            if (STRICT_MODE) {
                throw new AssertionError("Comprehensive benchmark performance targets not met");
            }
        }
    }

    // Helper methods for tetrahedral subdivision benchmarks

    private long measureTetrahedralSubdivision(int entityCount, boolean cascade) {
        long totalTime = 0;

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAndSubdivideTetrahedralForest(entityCount, cascade);
        }

        // Measurement
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            var forest = createAndSubdivideTetrahedralForest(entityCount, cascade);
            long end = System.nanoTime();
            totalTime += (end - start);
            forest.shutdown();
        }

        return totalTime / MEASUREMENT_ITERATIONS;
    }

    private long measureTetrahedralRedistribution(int entityCount) {
        long totalTime = 0;

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAndSubdivideTetrahedralForest(entityCount, false);
        }

        // Measurement - focus on the redistribution portion of subdivision
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var forest = createTetrahedralForest();
            var root = populateForest(forest, entityCount);

            // Measure just the checkAndAdapt call which includes redistribution
            long start = System.nanoTime();
            forest.checkAndAdapt();
            long end = System.nanoTime();

            // Wait for async subdivision to complete
            waitForSubdivision(root, 5000);

            totalTime += (end - start);
            forest.shutdown();
        }

        return totalTime / MEASUREMENT_ITERATIONS;
    }

    private long measureTetrahedralTraversal(int depth) {
        // Create forest with specified depth
        var forest = createMultiLevelTetrahedralForest(depth);
        var leaves = forest.getLeaves();

        if (leaves.isEmpty()) {
            return 0;
        }

        long totalTime = 0;
        int pathCount = Math.min(100, leaves.size()); // Test up to 100 paths

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (int j = 0; j < pathCount; j++) {
                var leaf = leaves.get(j % leaves.size());
                forest.getAncestors(leaf.getTreeId());
            }
        }

        // Measurement
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            for (int j = 0; j < pathCount; j++) {
                var leaf = leaves.get(j % leaves.size());
                long start = System.nanoTime();
                forest.getAncestors(leaf.getTreeId());
                long end = System.nanoTime();
                totalTime += (end - start);
            }
        }

        forest.shutdown();
        return totalTime / (MEASUREMENT_ITERATIONS * pathCount);
    }

    // Helper methods for octant subdivision benchmarks

    private long measureOctantSubdivision(int entityCount) {
        long totalTime = 0;

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAndSubdivideOctantForest(entityCount);
        }

        // Measurement
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            var forest = createAndSubdivideOctantForest(entityCount);
            long end = System.nanoTime();
            totalTime += (end - start);
            forest.shutdown();
        }

        return totalTime / MEASUREMENT_ITERATIONS;
    }

    private long measureOctantRedistribution(int entityCount) {
        long totalTime = 0;

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createAndSubdivideOctantForest(entityCount);
        }

        // Measurement
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var forest = createOctantForest();
            var root = populateForest(forest, entityCount);

            long start = System.nanoTime();
            forest.checkAndAdapt();
            long end = System.nanoTime();

            waitForSubdivision(root, 5000);

            totalTime += (end - start);
            forest.shutdown();
        }

        return totalTime / MEASUREMENT_ITERATIONS;
    }

    private long measureOctantTraversal(int depth) {
        // Create forest with specified depth
        var forest = createMultiLevelOctantForest(depth);
        var leaves = forest.getLeaves();

        if (leaves.isEmpty()) {
            return 0;
        }

        long totalTime = 0;
        int pathCount = Math.min(100, leaves.size());

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (int j = 0; j < pathCount; j++) {
                var leaf = leaves.get(j % leaves.size());
                forest.getAncestors(leaf.getTreeId());
            }
        }

        // Measurement
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            for (int j = 0; j < pathCount; j++) {
                var leaf = leaves.get(j % leaves.size());
                long start = System.nanoTime();
                forest.getAncestors(leaf.getTreeId());
                long end = System.nanoTime();
                totalTime += (end - start);
            }
        }

        forest.shutdown();
        return totalTime / (MEASUREMENT_ITERATIONS * pathCount);
    }

    // Forest creation helpers

    private AdaptiveForest<MortonKey, LongEntityID, String> createTetrahedralForest() {
        var forestConfig = ForestConfig.defaultConfig();
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL)
            .maxEntitiesPerTree(10)
            .minTreeVolume(1.0f)
            .enableAutoSubdivision(false)
            .build();

        return new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "tet-perf-test");
    }

    private AdaptiveForest<MortonKey, LongEntityID, String> createOctantForest() {
        var forestConfig = ForestConfig.defaultConfig();
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.OCTANT)
            .maxEntitiesPerTree(10)
            .minTreeVolume(1.0f)
            .enableAutoSubdivision(false)
            .build();

        return new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "oct-perf-test");
    }

    private TreeNode<?, ?, ?> populateForest(AdaptiveForest<MortonKey, LongEntityID, String> forest, int entityCount) {
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(FOREST_MIN, FOREST_MIN, FOREST_MIN),
            new Point3f(FOREST_MAX, FOREST_MAX, FOREST_MAX)
        );
        var metadata = TreeMetadata.builder()
            .name("PerfRoot")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);

        // Add entities
        for (int i = 0; i < entityCount; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(
                RANDOM.nextFloat() * POSITION_RANGE + MIN_POSITION,
                RANDOM.nextFloat() * POSITION_RANGE + MIN_POSITION,
                RANDOM.nextFloat() * POSITION_RANGE + MIN_POSITION
            );
            spatialIndex.insert(entityId, position, (byte) 0, "Entity-" + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        return forest.getTree(rootId);
    }

    private AdaptiveForest<MortonKey, LongEntityID, String> createAndSubdivideTetrahedralForest(int entityCount, boolean cascade) {
        var forest = createTetrahedralForest();
        var root = populateForest(forest, entityCount);

        forest.checkAndAdapt();
        waitForSubdivision(root, 5000);

        if (cascade && root.isSubdivided() && !root.getChildTreeIds().isEmpty()) {
            // Trigger second-level subdivision on first child
            var firstChildId = root.getChildTreeIds().get(0);
            var firstChild = forest.getTree(firstChildId);
            var childIndex = firstChild.getSpatialIndex();

            // Add more entities to trigger cascade
            for (int i = 0; i < entityCount; i++) {
                var entityId = idGenerator.generateID();
                var position = new Point3f(
                    RANDOM.nextFloat() * 100.0f + 50.0f,
                    RANDOM.nextFloat() * 100.0f + 50.0f,
                    RANDOM.nextFloat() * 100.0f + 50.0f
                );
                childIndex.insert(entityId, position, (byte) 0, "Child-" + i);
                forest.trackEntityInsertion(firstChildId, entityId, position);
            }

            forest.checkAndAdapt();
            waitForSubdivision(firstChild, 5000);
        }

        return forest;
    }

    private AdaptiveForest<MortonKey, LongEntityID, String> createAndSubdivideOctantForest(int entityCount) {
        var forest = createOctantForest();
        var root = populateForest(forest, entityCount);

        forest.checkAndAdapt();
        waitForSubdivision(root, 5000);

        return forest;
    }

    private AdaptiveForest<MortonKey, LongEntityID, String> createMultiLevelTetrahedralForest(int depth) {
        var forest = createTetrahedralForest();
        var root = populateForest(forest, 50);

        forest.checkAndAdapt();
        waitForSubdivision(root, 5000);

        // Create additional levels
        for (int level = 1; level < depth; level++) {
            var currentLevelTrees = forest.getTreesAtLevel(level);
            if (currentLevelTrees.isEmpty()) break;

            for (var tree : currentLevelTrees) {
                var index = tree.getSpatialIndex();
                for (int i = 0; i < 50; i++) {
                    var entityId = idGenerator.generateID();
                    var position = new Point3f(
                        RANDOM.nextFloat() * 100.0f,
                        RANDOM.nextFloat() * 100.0f,
                        RANDOM.nextFloat() * 100.0f
                    );
                    index.insert(entityId, position, (byte) 0, "L" + level + "-" + i);
                    forest.trackEntityInsertion(tree.getTreeId(), entityId, position);
                }
            }

            forest.checkAndAdapt();
            // Wait for subdivisions at this level to complete (polling-based, not sleep-based)
            waitForLevelSubdivision(forest, level, MAX_SUBDIVISION_WAIT_MS);
        }

        return forest;
    }

    private AdaptiveForest<MortonKey, LongEntityID, String> createMultiLevelOctantForest(int depth) {
        var forest = createOctantForest();
        var root = populateForest(forest, 50);

        forest.checkAndAdapt();
        waitForSubdivision(root, 5000);

        // Create additional levels
        for (int level = 1; level < depth; level++) {
            var currentLevelTrees = forest.getTreesAtLevel(level);
            if (currentLevelTrees.isEmpty()) break;

            for (var tree : currentLevelTrees) {
                var index = tree.getSpatialIndex();
                for (int i = 0; i < 50; i++) {
                    var entityId = idGenerator.generateID();
                    var position = new Point3f(
                        RANDOM.nextFloat() * 100.0f,
                        RANDOM.nextFloat() * 100.0f,
                        RANDOM.nextFloat() * 100.0f
                    );
                    index.insert(entityId, position, (byte) 0, "L" + level + "-" + i);
                    forest.trackEntityInsertion(tree.getTreeId(), entityId, position);
                }
            }

            forest.checkAndAdapt();
            // Wait for subdivisions at this level to complete (polling-based, not sleep-based)
            waitForLevelSubdivision(forest, level, MAX_SUBDIVISION_WAIT_MS);
        }

        return forest;
    }

    /**
     * Wait for tree subdivision to complete with polling.
     *
     * @param tree tree to wait for
     * @param maxWaitMs maximum wait time in milliseconds
     * @throws IllegalStateException if subdivision doesn't complete within timeout (in strict mode)
     */
    private void waitForSubdivision(TreeNode<?, ?, ?> tree, int maxWaitMs) {
        int waited = 0;
        while (waited < maxWaitMs) {
            try {
                Thread.sleep(SUBDIVISION_POLL_INTERVAL_MS);
                waited += SUBDIVISION_POLL_INTERVAL_MS;
                if (tree.isSubdivided()) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("WARNING: Subdivision wait interrupted after " + waited + "ms");
                return;
            }
        }

        // Subdivision did not complete within timeout
        String warning = String.format(
            "WARNING: Tree %s subdivision did not complete within %dms (waited %dms). " +
            "This may invalidate benchmark results.",
            tree.getTreeId(), maxWaitMs, waited
        );
        System.err.println(warning);

        if (STRICT_MODE) {
            throw new IllegalStateException(warning);
        }
    }

    /**
     * Wait for multiple trees at a given level to subdivide.
     * Uses polling with timeout to avoid imprecise sleep-based waiting.
     *
     * @param forest forest containing trees
     * @param level hierarchy level to check
     * @param maxWaitMs maximum wait time in milliseconds
     */
    private void waitForLevelSubdivision(AdaptiveForest<MortonKey, LongEntityID, String> forest,
                                          int level, int maxWaitMs) {
        int waited = 0;
        while (waited < maxWaitMs) {
            var treesAtLevel = forest.getTreesAtLevel(level);
            boolean allSubdivided = true;

            for (var tree : treesAtLevel) {
                if (!tree.isSubdivided() && tree.getSpatialIndex().entityCount() > 10) {
                    allSubdivided = false;
                    break;
                }
            }

            if (allSubdivided) {
                return;
            }

            try {
                Thread.sleep(SUBDIVISION_POLL_INTERVAL_MS);
                waited += SUBDIVISION_POLL_INTERVAL_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
