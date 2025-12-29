/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * CPU Performance Benchmark for ESVT (Efficient Sparse Voxel Tetrahedra) traversal.
 *
 * <p>Measures rays/ms baseline for CPU-based ray traversal to establish
 * performance targets for GPU implementation.
 *
 * <p><b>Run with:</b> {@code RUN_PERFORMANCE_TESTS=true mvn test -Dtest=ESVTTraversalBenchmark}
 *
 * <p><b>Key Metrics:</b>
 * <ul>
 *   <li>Rays per millisecond (primary metric)</li>
 *   <li>Hit rate (percentage of rays finding leaves)</li>
 *   <li>Average traversal iterations per ray</li>
 *   <li>Performance scaling with tree depth</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class ESVTTraversalBenchmark {

    // Benchmark configuration
    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 10;
    private static final int[] RAY_COUNTS = {1_000, 10_000, 100_000};
    private static final int[] TREE_DEPTHS = {1, 2, 3};  // Depth 1=9 nodes, 2=73 nodes, 3=585 nodes

    private ESVTTraversal traversal;
    private Random random;

    @BeforeEach
    void setUp() {
        traversal = new ESVTTraversal();
        random = new Random(42); // Fixed seed for reproducibility
    }

    // Tetrahedron type 0 centroid: (0+1+1+1)/4, (0+0+0+1)/4, (0+0+1+1)/4 = (0.75, 0.25, 0.5)
    private static final float TET0_CENTROID_X = 0.75f;
    private static final float TET0_CENTROID_Y = 0.25f;
    private static final float TET0_CENTROID_Z = 0.5f;

    /**
     * Quick sanity check that traversal works on a simple tree.
     */
    @Test
    void testBasicTraversal() {
        var nodes = createSimpleTree(3);
        var rays = generateRays(100, TET0_CENTROID_X, TET0_CENTROID_Y, TET0_CENTROID_Z);

        int hits = 0;
        for (var ray : rays) {
            var result = traversal.castRay(ray, nodes, 0);
            if (result.isHit()) {
                hits++;
            }
        }

        System.out.println("Basic traversal: " + hits + "/100 rays hit");
    }

    /**
     * Comprehensive CPU performance benchmark.
     * Run with: RUN_PERFORMANCE_TESTS=true mvn test -Dtest=ESVTTraversalBenchmark#benchmarkCPUTraversal
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_PERFORMANCE_TESTS", matches = "true")
    void benchmarkCPUTraversal() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("ESVT CPU Traversal Performance Benchmark");
        System.out.println("=".repeat(70));
        System.out.println();

        var results = new ArrayList<BenchmarkResult>();

        for (int depth : TREE_DEPTHS) {
            System.out.println("Tree Depth: " + depth);
            System.out.println("-".repeat(50));

            var nodes = createDenseTree(depth);
            System.out.printf("  Nodes created: %,d%n", nodes.length);

            for (int rayCount : RAY_COUNTS) {
                var rays = generateRays(rayCount, 0.5f, 0.5f, 0.5f);

                // Warmup
                for (int w = 0; w < WARMUP_ITERATIONS; w++) {
                    runTraversal(rays, nodes);
                }

                // Benchmark
                long totalHits = 0;
                long totalIterations = 0;
                long totalTimeNs = 0;

                for (int b = 0; b < BENCHMARK_ITERATIONS; b++) {
                    long startNs = System.nanoTime();
                    var runResult = runTraversal(rays, nodes);
                    long endNs = System.nanoTime();

                    totalTimeNs += (endNs - startNs);
                    totalHits += runResult.hits;
                    totalIterations += runResult.totalIterations;
                }

                double avgTimeMs = totalTimeNs / 1_000_000.0 / BENCHMARK_ITERATIONS;
                double raysPerMs = rayCount / avgTimeMs;
                double hitRate = 100.0 * totalHits / (rayCount * BENCHMARK_ITERATIONS);
                double avgIterations = (double) totalIterations / (rayCount * BENCHMARK_ITERATIONS);

                var result = new BenchmarkResult(depth, rayCount, raysPerMs, hitRate, avgIterations, avgTimeMs);
                results.add(result);

                System.out.printf("  %,8d rays: %,.0f rays/ms, %.1f%% hits, %.1f avg iterations, %.2f ms%n",
                    rayCount, raysPerMs, hitRate, avgIterations, avgTimeMs);
            }
            System.out.println();
        }

        // Summary
        printSummary(results);
    }

    /**
     * Benchmark with varying ray coherence (important for GPU performance).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_PERFORMANCE_TESTS", matches = "true")
    void benchmarkRayCoherence() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("ESVT Ray Coherence Benchmark");
        System.out.println("=".repeat(70));
        System.out.println();

        int rayCount = 100_000;
        int depth = 3;
        var nodes = createDenseTree(depth);

        // Different ray generation patterns
        String[] patterns = {"random", "coherent_grid", "coherent_cone"};

        for (String pattern : patterns) {
            var rays = generateRaysWithPattern(rayCount, pattern);

            // Warmup
            for (int w = 0; w < WARMUP_ITERATIONS; w++) {
                runTraversal(rays, nodes);
            }

            // Benchmark
            long totalTimeNs = 0;
            long totalHits = 0;

            for (int b = 0; b < BENCHMARK_ITERATIONS; b++) {
                long startNs = System.nanoTime();
                var result = runTraversal(rays, nodes);
                totalTimeNs += (System.nanoTime() - startNs);
                totalHits += result.hits;
            }

            double avgTimeMs = totalTimeNs / 1_000_000.0 / BENCHMARK_ITERATIONS;
            double raysPerMs = rayCount / avgTimeMs;
            double hitRate = 100.0 * totalHits / (rayCount * BENCHMARK_ITERATIONS);

            System.out.printf("%-20s: %,.0f rays/ms, %.1f%% hits%n", pattern, raysPerMs, hitRate);
        }
    }

    /**
     * Single-threaded vs multi-threaded comparison.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_PERFORMANCE_TESTS", matches = "true")
    void benchmarkParallelTraversal() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("ESVT Parallel Traversal Benchmark");
        System.out.println("=".repeat(70));
        System.out.println();

        int rayCount = 1_000_000;
        int depth = 3;
        var nodes = createDenseTree(depth);
        var rays = generateRays(rayCount, TET0_CENTROID_X, TET0_CENTROID_Y, TET0_CENTROID_Z);

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        System.out.println("Available processors: " + availableProcessors);
        System.out.println();

        // Single-threaded baseline
        long singleStartNs = System.nanoTime();
        runTraversal(rays, nodes);
        long singleTimeNs = System.nanoTime() - singleStartNs;
        double singleRaysPerMs = rayCount / (singleTimeNs / 1_000_000.0);

        System.out.printf("Single-threaded: %,.0f rays/ms%n", singleRaysPerMs);

        // Multi-threaded using parallel streams
        long parallelStartNs = System.nanoTime();
        java.util.Arrays.stream(rays)
            .parallel()
            .forEach(ray -> {
                var localTraversal = ESVTTraversal.create();
                localTraversal.castRay(ray, nodes, 0);
            });
        long parallelTimeNs = System.nanoTime() - parallelStartNs;
        double parallelRaysPerMs = rayCount / (parallelTimeNs / 1_000_000.0);

        System.out.printf("Parallel (%d threads): %,.0f rays/ms%n", availableProcessors, parallelRaysPerMs);
        System.out.printf("Speedup: %.2fx%n", parallelRaysPerMs / singleRaysPerMs);
    }

    // === Helper Methods ===

    private TraversalResult runTraversal(ESVTRay[] rays, ESVTNodeUnified[] nodes) {
        int hits = 0;
        long totalIterations = 0;

        for (var ray : rays) {
            var result = traversal.castRay(ray, nodes, 0);
            if (result.isHit()) {
                hits++;
            }
            totalIterations += result.iterations;
        }

        return new TraversalResult(hits, totalIterations);
    }

    /**
     * Create a simple tree with root and all 8 leaf children.
     */
    private ESVTNodeUnified[] createSimpleTree(int depth) {
        int nodeCount = 1 + 8; // Root + 8 children
        var nodes = new ESVTNodeUnified[nodeCount];

        // Root node
        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setValid(true);
        nodes[0].setChildMask(0b11111111); // All 8 children
        nodes[0].setLeafMask(0b11111111);  // All are leaves
        nodes[0].setChildPtr(1);

        // All 8 children (all leaves)
        for (int i = 1; i <= 8; i++) {
            byte childType = TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[0][i - 1];
            nodes[i] = new ESVTNodeUnified(childType);
            nodes[i].setValid(true);
        }

        return nodes;
    }

    /**
     * Create a densely populated tree at specified depth.
     *
     * <p>Tree structure: Each internal node has ALL 8 children from Bey subdivision.
     * Deepest level nodes mark all children as leaves.
     *
     * <p>Important: In ESVT, a "leaf" is indicated by setting the corresponding bit
     * in the parent's leafMask. The leaf child itself still needs to exist as a node.
     *
     * <p>Note: Different entry faces touch different children:
     * <ul>
     *   <li>Face 0: children {4, 5, 6, 7}</li>
     *   <li>Face 1: children {2, 3, 6, 7}</li>
     *   <li>Face 2: children {1, 3, 5, 7}</li>
     *   <li>Face 3: children {1, 2, 4, 5}</li>
     * </ul>
     */
    private ESVTNodeUnified[] createDenseTree(int depth) {
        if (depth < 1) depth = 1;
        if (depth > 4) depth = 4; // Limit to avoid huge trees (8^5 = 32768 nodes)

        // Calculate total nodes for an 8-way branching tree
        // Level 0: 1 node, Level 1: 8 nodes, Level 2: 64 nodes
        // Total for depth d = (8^(d+1) - 1) / 7
        int totalNodes = (int) ((Math.pow(8, depth + 1) - 1) / 7);

        // Limit to reasonable size
        totalNodes = Math.min(totalNodes, 50_000);

        var nodes = new ESVTNodeUnified[totalNodes];

        // Initialize all nodes
        for (int i = 0; i < totalNodes; i++) {
            nodes[i] = new ESVTNodeUnified((byte) (i % 6));
            nodes[i].setValid(true);
        }

        // Build tree level by level
        var currentLevel = new ArrayList<Integer>();
        var nextLevel = new ArrayList<Integer>();
        currentLevel.add(0);

        int nextFreeIdx = 1;
        int level = 0;

        while (!currentLevel.isEmpty() && level < depth) {
            boolean isSecondToLast = (level == depth - 1);

            for (int parentIdx : currentLevel) {
                if (nextFreeIdx + 8 > totalNodes) {
                    // No room for 8 children, make partial
                    int available = totalNodes - nextFreeIdx;
                    if (available > 0) {
                        int mask = (1 << available) - 1;
                        nodes[parentIdx].setChildMask(mask);
                        nodes[parentIdx].setLeafMask(mask);
                        nodes[parentIdx].setChildPtr(nextFreeIdx);
                        nextFreeIdx += available;
                    }
                    continue;
                }

                // Set up all 8 children
                nodes[parentIdx].setChildMask(0b11111111); // All 8 children
                nodes[parentIdx].setChildPtr(nextFreeIdx);

                if (isSecondToLast) {
                    // Children at this level are leaves
                    nodes[parentIdx].setLeafMask(0b11111111);
                    // Create leaf nodes
                    for (int c = 0; c < 8; c++) {
                        int childIdx = nextFreeIdx + c;
                        nodes[childIdx].setChildMask(0);
                        nodes[childIdx].setLeafMask(0);
                    }
                } else {
                    // Children are internal nodes
                    nodes[parentIdx].setLeafMask(0b00000000);
                    for (int c = 0; c < 8; c++) {
                        nextLevel.add(nextFreeIdx + c);
                    }
                }

                nextFreeIdx += 8;
            }

            currentLevel = nextLevel;
            nextLevel = new ArrayList<>();
            level++;
        }

        return nodes;
    }

    // Type 0 tetrahedron vertices: (0,0,0), (1,0,0), (1,0,1), (1,1,1)
    private static final float[][] TET0_VERTS = {
        {0, 0, 0},
        {1, 0, 0},
        {1, 0, 1},
        {1, 1, 1}
    };

    /**
     * Generate rays guaranteed to hit the type-0 tetrahedron.
     * Uses barycentric interpolation to generate random points INSIDE the tetrahedron.
     */
    private ESVTRay[] generateRays(int count, float targetX, float targetY, float targetZ) {
        var rays = new ESVTRay[count];

        for (int i = 0; i < count; i++) {
            // Generate random point INSIDE the tetrahedron using barycentric coords
            // Use the "sorting" method for uniform distribution
            float[] vals = {random.nextFloat(), random.nextFloat(), random.nextFloat()};
            Arrays.sort(vals);
            float b0 = vals[0];
            float b1 = vals[1] - vals[0];
            float b2 = vals[2] - vals[1];
            float b3 = 1.0f - vals[2];

            // Target point inside tetrahedron
            float tx = b0 * TET0_VERTS[0][0] + b1 * TET0_VERTS[1][0] + b2 * TET0_VERTS[2][0] + b3 * TET0_VERTS[3][0];
            float ty = b0 * TET0_VERTS[0][1] + b1 * TET0_VERTS[1][1] + b2 * TET0_VERTS[2][1] + b3 * TET0_VERTS[3][1];
            float tz = b0 * TET0_VERTS[0][2] + b1 * TET0_VERTS[1][2] + b2 * TET0_VERTS[2][2] + b3 * TET0_VERTS[3][2];

            // Random origin on a sphere outside the tetrahedron
            float theta = random.nextFloat() * 2 * (float) Math.PI;
            float phi = (float) Math.acos(2 * random.nextFloat() - 1);
            float r = 2.0f + random.nextFloat() * 0.5f; // Distance 2-2.5 from centroid

            float ox = TET0_CENTROID_X + r * (float) (Math.sin(phi) * Math.cos(theta));
            float oy = TET0_CENTROID_Y + r * (float) (Math.sin(phi) * Math.sin(theta));
            float oz = TET0_CENTROID_Z + r * (float) Math.cos(phi);

            // Direction toward target point inside tetrahedron
            float dx = tx - ox;
            float dy = ty - oy;
            float dz = tz - oz;
            float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            dx /= len;
            dy /= len;
            dz /= len;

            rays[i] = new ESVTRay(new Point3f(ox, oy, oz), new Vector3f(dx, dy, dz));
        }

        return rays;
    }

    /**
     * Generate rays with specific coherence patterns.
     */
    private ESVTRay[] generateRaysWithPattern(int count, String pattern) {
        return switch (pattern) {
            case "random" -> generateRays(count, 0.5f, 0.5f, 0.5f);
            case "coherent_grid" -> generateGridRays(count);
            case "coherent_cone" -> generateConeRays(count);
            default -> generateRays(count, 0.5f, 0.5f, 0.5f);
        };
    }

    /**
     * Generate rays in a grid pattern aimed at the tetrahedron.
     */
    private ESVTRay[] generateGridRays(int count) {
        int gridSize = (int) Math.sqrt(count);
        var rays = new ESVTRay[gridSize * gridSize];

        var origin = new Point3f(-1, -1, -1);

        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                // Interpolate within tetrahedron bounds using barycentric-like sampling
                float u = (x + 0.5f) / gridSize;
                float v = (y + 0.5f) / gridSize;

                // Simple interpolation within tetrahedron span
                // x: 0-1, y: 0-1, z: 0-1 but constrained to tetrahedron
                float tx = u;           // v1 is at x=1, v0 at x=0
                float ty = v * u;       // y grows with x (tetrahedron shape)
                float tz = u * (1-v);   // z constraint

                float dx = tx - origin.x;
                float dy = ty - origin.y;
                float dz = tz - origin.z;
                float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

                rays[y * gridSize + x] = new ESVTRay(
                    new Point3f(origin),
                    new Vector3f(dx/len, dy/len, dz/len)
                );
            }
        }

        return rays;
    }

    /**
     * Generate rays in a cone pattern aimed at the tetrahedron.
     */
    private ESVTRay[] generateConeRays(int count) {
        var rays = new ESVTRay[count];
        var origin = new Point3f(-1, TET0_CENTROID_Y, TET0_CENTROID_Z);
        float coneAngle = 0.2f; // radians - narrower cone for tetrahedron

        // Base direction toward centroid
        float baseDx = TET0_CENTROID_X - origin.x;
        float baseDy = TET0_CENTROID_Y - origin.y;
        float baseDz = TET0_CENTROID_Z - origin.z;
        float baseLen = (float) Math.sqrt(baseDx*baseDx + baseDy*baseDy + baseDz*baseDz);
        baseDx /= baseLen;
        baseDy /= baseLen;
        baseDz /= baseLen;

        for (int i = 0; i < count; i++) {
            float theta = random.nextFloat() * 2 * (float) Math.PI;
            float r = random.nextFloat() * coneAngle;

            // Perturb base direction within cone
            float cosR = (float) Math.cos(r);
            float sinR = (float) Math.sin(r);
            float cosTheta = (float) Math.cos(theta);
            float sinTheta = (float) Math.sin(theta);

            // Simple perturbation in perpendicular plane
            float dx = baseDx + sinR * cosTheta * 0.1f;
            float dy = baseDy + sinR * sinTheta * 0.1f;
            float dz = baseDz + sinR * 0.1f;
            float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

            rays[i] = new ESVTRay(new Point3f(origin), new Vector3f(dx/len, dy/len, dz/len));
        }

        return rays;
    }

    private void printSummary(ArrayList<BenchmarkResult> results) {
        System.out.println("=".repeat(70));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(70));
        System.out.println();

        // Find best performance
        double maxRaysPerMs = results.stream()
            .mapToDouble(r -> r.raysPerMs)
            .max()
            .orElse(0);

        // Find typical performance (depth 5, 100k rays)
        double typicalRaysPerMs = results.stream()
            .filter(r -> r.depth == 5 && r.rayCount == 100_000)
            .mapToDouble(r -> r.raysPerMs)
            .findFirst()
            .orElse(0);

        System.out.printf("Peak performance: %,.0f rays/ms%n", maxRaysPerMs);
        System.out.printf("Typical (depth 5, 100k rays): %,.0f rays/ms%n", typicalRaysPerMs);
        System.out.println();
        System.out.println("Performance target vs ESVO:");
        System.out.println("  Primary: 45-60% of ESVO performance");
        System.out.println("  Stretch: 60-70% of ESVO performance");
        System.out.println();

        // GPU comparison note
        System.out.println("Note: GPU tests require Linux/Windows with OpenGL 4.3+");
        System.out.println("      (macOS is limited to OpenGL 4.1, no compute shaders)");
    }

    /**
     * Benchmark beam optimization effectiveness.
     * Compares traversal with and without beam optimization.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_PERFORMANCE_TESTS", matches = "true")
    void benchmarkBeamOptimization() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("ESVT Beam Optimization Benchmark");
        System.out.println("=".repeat(70));
        System.out.println();

        int frameWidth = 256;
        int frameHeight = 256;
        int coarseSize = 4;
        int depth = 3;

        var nodes = createDenseTree(depth);
        System.out.printf("Tree depth: %d, Nodes: %d%n", depth, nodes.length);
        System.out.printf("Frame: %dx%d, Coarse: %dx%d (1/%d)%n",
            frameWidth, frameHeight, frameWidth/coarseSize, frameHeight/coarseSize, coarseSize);
        System.out.println();

        // Generate rays for full frame (grid pattern for coherence)
        int totalRays = frameWidth * frameHeight;
        var fineRays = generateFrameRays(frameWidth, frameHeight);
        int[][] finePixelCoords = generatePixelCoords(frameWidth, frameHeight);

        // Generate coarse rays
        int coarseWidth = (frameWidth + coarseSize - 1) / coarseSize + 1;
        int coarseHeight = (frameHeight + coarseSize - 1) / coarseSize + 1;
        var coarseRays = generateCoarseRays(frameWidth, frameHeight, coarseSize);

        // Benchmark WITHOUT beam optimization
        long startNoBeam = System.nanoTime();
        int hitsNoBeam = 0;
        long iterationsNoBeam = 0;
        for (var ray : fineRays) {
            var result = traversal.castRay(ray, nodes, 0);
            if (result.isHit()) hitsNoBeam++;
            iterationsNoBeam += result.iterations;
        }
        long timeNoBeam = System.nanoTime() - startNoBeam;

        // Benchmark WITH beam optimization
        var beamOpt = new ESVTBeamOptimization(frameWidth, frameHeight, coarseSize);

        // Re-generate rays (beam optimization modifies tMin)
        fineRays = generateFrameRays(frameWidth, frameHeight);
        coarseRays = generateCoarseRays(frameWidth, frameHeight, coarseSize);

        long startWithBeam = System.nanoTime();
        // Coarse pass
        beamOpt.executeCoarsePass(coarseRays, nodes, 0);
        // Fine pass with beam optimization
        int hitsWithBeam = 0;
        long iterationsWithBeam = 0;
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int idx = y * frameWidth + x;
                beamOpt.applyToRay(fineRays[idx], x, y);
                var result = traversal.castRay(fineRays[idx], nodes, 0);
                if (result.isHit()) hitsWithBeam++;
                iterationsWithBeam += result.iterations;
            }
        }
        long timeWithBeam = System.nanoTime() - startWithBeam;

        // Results
        double msNoBeam = timeNoBeam / 1_000_000.0;
        double msWithBeam = timeWithBeam / 1_000_000.0;
        double raysPerMsNoBeam = totalRays / msNoBeam;
        double raysPerMsWithBeam = totalRays / msWithBeam;
        double avgIterNoBeam = (double) iterationsNoBeam / totalRays;
        double avgIterWithBeam = (double) iterationsWithBeam / totalRays;

        System.out.println("Without Beam Optimization:");
        System.out.printf("  Time: %.2f ms, %.0f rays/ms%n", msNoBeam, raysPerMsNoBeam);
        System.out.printf("  Hits: %d (%.1f%%), Avg iterations: %.1f%n",
            hitsNoBeam, 100.0 * hitsNoBeam / totalRays, avgIterNoBeam);
        System.out.println();

        System.out.println("With Beam Optimization:");
        System.out.printf("  Time: %.2f ms, %.0f rays/ms%n", msWithBeam, raysPerMsWithBeam);
        System.out.printf("  Hits: %d (%.1f%%), Avg iterations: %.1f%n",
            hitsWithBeam, 100.0 * hitsWithBeam / totalRays, avgIterWithBeam);
        System.out.printf("  Coarse stats: %s%n", beamOpt.getStats());
        System.out.println();

        double iterReduction = 100.0 * (1 - (double) iterationsWithBeam / iterationsNoBeam);
        double speedup = raysPerMsWithBeam / raysPerMsNoBeam;

        System.out.println("Beam Optimization Impact:");
        System.out.printf("  Iteration reduction: %.1f%%%n", iterReduction);
        System.out.printf("  Speed change: %.2fx%n", speedup);
        System.out.println();

        // The beam optimization adds overhead (coarse pass + sampling)
        // but should reduce iterations. Net benefit depends on tree depth.
        if (iterReduction > 0) {
            System.out.println("H4 RESULT: Iteration reduction confirmed");
        } else {
            System.out.println("H4 RESULT: No iteration reduction (may need deeper trees)");
        }
    }

    /**
     * Generate rays covering a full frame in grid pattern.
     */
    private ESVTRay[] generateFrameRays(int width, int height) {
        var rays = new ESVTRay[width * height];
        var origin = new Point3f(-1, TET0_CENTROID_Y, TET0_CENTROID_Z);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Map pixel to target inside tetrahedron
                float u = (x + 0.5f) / width;
                float v = (y + 0.5f) / height;

                // Interpolate within tetrahedron bounds
                float tx = u * 0.8f + 0.1f;
                float ty = v * u * 0.8f + 0.1f;
                float tz = u * (1-v) * 0.8f + 0.1f;

                float dx = tx - origin.x;
                float dy = ty - origin.y;
                float dz = tz - origin.z;
                float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

                rays[y * width + x] = new ESVTRay(
                    new Point3f(origin),
                    new Vector3f(dx/len, dy/len, dz/len)
                );
            }
        }
        return rays;
    }

    /**
     * Generate coarse rays for beam optimization.
     */
    private ESVTRay[] generateCoarseRays(int frameWidth, int frameHeight, int coarseSize) {
        int coarseWidth = (frameWidth + coarseSize - 1) / coarseSize + 1;
        int coarseHeight = (frameHeight + coarseSize - 1) / coarseSize + 1;

        var rays = new ESVTRay[coarseWidth * coarseHeight];
        var origin = new Point3f(-1, TET0_CENTROID_Y, TET0_CENTROID_Z);

        for (int cy = 0; cy < coarseHeight; cy++) {
            for (int cx = 0; cx < coarseWidth; cx++) {
                // Map coarse pixel to frame center
                float u = ((cx * coarseSize) + coarseSize/2.0f) / frameWidth;
                float v = ((cy * coarseSize) + coarseSize/2.0f) / frameHeight;

                u = Math.min(u, 1.0f);
                v = Math.min(v, 1.0f);

                float tx = u * 0.8f + 0.1f;
                float ty = v * u * 0.8f + 0.1f;
                float tz = u * (1-v) * 0.8f + 0.1f;

                float dx = tx - origin.x;
                float dy = ty - origin.y;
                float dz = tz - origin.z;
                float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

                rays[cy * coarseWidth + cx] = new ESVTRay(
                    new Point3f(origin),
                    new Vector3f(dx/len, dy/len, dz/len)
                );
            }
        }
        return rays;
    }

    /**
     * Generate pixel coordinate array for beam optimization.
     */
    private int[][] generatePixelCoords(int width, int height) {
        var coords = new int[width * height][2];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                coords[y * width + x] = new int[]{x, y};
            }
        }
        return coords;
    }

    // === Result Classes ===

    private record TraversalResult(int hits, long totalIterations) {}

    private record BenchmarkResult(
        int depth,
        int rayCount,
        double raysPerMs,
        double hitRate,
        double avgIterations,
        double timeMs
    ) {}
}
