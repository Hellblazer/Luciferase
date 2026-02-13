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
 * Run with: mvn test -pl lucien -Dtest=SubdivisionPerformanceBenchmark -Pperformance
 * Disable assertions for accurate measurements: -da
 *
 * @author hal.hildebrand
 */
@Disabled("Performance benchmark - enable manually or run with -Pperformance")
public class SubdivisionPerformanceBenchmark {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 20;
    private static final Random RANDOM = new Random(42); // Fixed seed for reproducibility

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

        for (int entityCount : entityCounts) {
            // Test CUBIC_TO_TET (cubic → 6 tets)
            long tetSubdivisionTime = measureTetrahedralSubdivision(entityCount, false);

            // Test TET_TO_SUBTET (tet → 8 subtets) - cascade
            long tetCascadeTime = measureTetrahedralSubdivision(entityCount, true);

            // Test OCTANT for baseline
            long octantTime = measureOctantSubdivision(entityCount);

            System.out.printf("Entities: %d%n", entityCount);
            System.out.printf("  Tetrahedral (cubic→6 tets):  %6.2f ms %s%n",
                tetSubdivisionTime / 1_000_000.0,
                tetSubdivisionTime / 1_000_000.0 < 10.0 ? "✓" : "✗ (exceeds target)");
            System.out.printf("  Tetrahedral (cascade):        %6.2f ms %s%n",
                tetCascadeTime / 1_000_000.0,
                tetCascadeTime / 1_000_000.0 < 10.0 ? "✓" : "✗ (exceeds target)");
            System.out.printf("  Octant (baseline):            %6.2f ms%n",
                octantTime / 1_000_000.0);
            System.out.printf("  Speedup vs Octant:            %.2fx (cubic→6), %.2fx (cascade)%n",
                (double) octantTime / tetSubdivisionTime,
                (double) octantTime / tetCascadeTime);
            System.out.println();
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

        for (int entityCount : entityCounts) {
            long tetRedistTime = measureTetrahedralRedistribution(entityCount);
            long octantRedistTime = measureOctantRedistribution(entityCount);

            double tetTimePerThousand = (tetRedistTime / 1_000_000.0) / (entityCount / 1000.0);
            double octantTimePerThousand = (octantRedistTime / 1_000_000.0) / (entityCount / 1000.0);

            System.out.printf("Entities: %d%n", entityCount);
            System.out.printf("  Tetrahedral: %6.2f ms total, %6.2f ms/1000 entities %s%n",
                tetRedistTime / 1_000_000.0,
                tetTimePerThousand,
                tetTimePerThousand < 5.0 ? "✓" : "✗ (exceeds target)");
            System.out.printf("  Octant:      %6.2f ms total, %6.2f ms/1000 entities%n",
                octantRedistTime / 1_000_000.0,
                octantTimePerThousand);
            System.out.printf("  Speedup vs Octant: %.2fx%n",
                (double) octantRedistTime / tetRedistTime);
            System.out.println();
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

        for (int depth : depths) {
            long tetTraversalTime = measureTetrahedralTraversal(depth);
            long octantTraversalTime = measureOctantTraversal(depth);

            System.out.printf("Hierarchy depth: %d levels%n", depth);
            System.out.printf("  Tetrahedral: %6.3f ms per path %s%n",
                tetTraversalTime / 1_000_000.0,
                tetTraversalTime / 1_000_000.0 < 1.0 ? "✓" : "✗ (exceeds target)");
            System.out.printf("  Octant:      %6.3f ms per path%n",
                octantTraversalTime / 1_000_000.0);
            System.out.printf("  Speedup vs Octant: %.2fx%n",
                (double) octantTraversalTime / tetTraversalTime);
            System.out.println();
        }
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
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(1000.0f, 1000.0f, 1000.0f)
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
                RANDOM.nextFloat() * 900.0f + 50.0f,
                RANDOM.nextFloat() * 900.0f + 50.0f,
                RANDOM.nextFloat() * 900.0f + 50.0f
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
            Thread.yield(); // Give adaptation thread time to work
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
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
            Thread.yield();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return forest;
    }

    private void waitForSubdivision(TreeNode<?, ?, ?> tree, int maxWaitMs) {
        int waited = 0;
        while (waited < maxWaitMs) {
            try {
                Thread.sleep(50);
                waited += 50;
                if (tree.isSubdivided()) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
