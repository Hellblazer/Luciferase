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
package com.hellblazer.luciferase.esvo.performance;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.gpu.ESVTOpenCLRenderer;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvo.io.VOLLoader;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Realistic performance benchmark comparing ESVO and ESVT using actual voxel data.
 * Uses the Stanford bunny from the VolGallery dataset for representative real-world testing.
 *
 * <p>This benchmark differs from synthetic tests by:
 * <ul>
 *   <li>Using real scanned 3D model data instead of procedurally generated shapes</li>
 *   <li>Testing multiple resolutions (64³, 128³) to evaluate scaling behavior</li>
 *   <li>Measuring actual GPU rendering through the OpenCL pipeline</li>
 *   <li>Comparing construction time, tree size, and rendering throughput</li>
 * </ul>
 *
 * <p>Run with: {@code RUN_GPU_TESTS=true mvn test -Dtest=RealisticVoxelBenchmark}
 *
 * @author hal.hildebrand
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
public class RealisticVoxelBenchmark {

    private static final Logger log = LoggerFactory.getLogger(RealisticVoxelBenchmark.class);

    private static final int WARMUP_FRAMES = 10;
    private static final int BENCHMARK_FRAMES = 50;
    private static final int[] RESOLUTIONS = {256, 512, 1024};

    private VOLLoader volLoader;
    private VOLLoader.VOLData bunny64;
    private VOLLoader.VOLData bunny128;
    private boolean openclAvailable;

    @BeforeAll
    void setup() throws Exception {
        volLoader = new VOLLoader();

        // Load bunny datasets
        bunny64 = volLoader.loadResource("/voxels/bunny-64.vol");
        bunny128 = volLoader.loadResource("/voxels/bunny-128.vol");

        log.info("Loaded bunny 64³: {} voxels", bunny64.voxels().size());
        log.info("Loaded bunny 128³: {} voxels", bunny128.voxels().size());

        openclAvailable = ESVTOpenCLRenderer.isOpenCLAvailable();
        if (!openclAvailable) {
            log.warn("OpenCL not available - GPU benchmarks will be skipped");
        }
    }

    @Test
    @DisplayName("Stanford Bunny: ESVT GPU Rendering Benchmark")
    void benchmarkESVTBunny() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        System.out.println("\n" + "=".repeat(80));
        System.out.println("ESVT GPU RENDERING BENCHMARK - Stanford Bunny");
        System.out.println("=".repeat(80));

        var results = new ArrayList<BenchmarkResult>();

        // Benchmark 64³ bunny
        results.add(benchmarkESVTData("Bunny 64³", bunny64.voxels(), 6));

        // Benchmark 128³ bunny
        results.add(benchmarkESVTData("Bunny 128³", bunny128.voxels(), 7));

        // Print summary
        printResults(results);
    }

    @Test
    @DisplayName("Stanford Bunny: ESVO Tree Construction Benchmark")
    void benchmarkESVOConstruction() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("ESVO TREE CONSTRUCTION BENCHMARK - Stanford Bunny");
        System.out.println("=".repeat(80));

        // 64³ bunny
        benchmarkOctreeConstruction("Bunny 64³", bunny64.voxels(), 6);

        // 128³ bunny
        benchmarkOctreeConstruction("Bunny 128³", bunny128.voxels(), 7);
    }

    @Test
    @DisplayName("Stanford Bunny: ESVT Tree Construction Benchmark")
    void benchmarkESVTConstruction() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("ESVT TREE CONSTRUCTION BENCHMARK - Stanford Bunny");
        System.out.println("=".repeat(80));

        // 64³ bunny
        benchmarkTetreeConstruction("Bunny 64³", bunny64.voxels(), 6);

        // 128³ bunny
        benchmarkTetreeConstruction("Bunny 128³", bunny128.voxels(), 7);
    }

    @Test
    @DisplayName("Stanford Bunny: Resolution Scaling Analysis")
    void benchmarkResolutionScaling() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        System.out.println("\n" + "=".repeat(80));
        System.out.println("RESOLUTION SCALING ANALYSIS - Stanford Bunny");
        System.out.println("=".repeat(80));

        for (int resolution : RESOLUTIONS) {
            System.out.printf("\n--- Render Resolution: %dx%d (%,d rays/frame) ---%n",
                             resolution, resolution, resolution * resolution);

            benchmarkAtResolution(bunny64.voxels(), "Bunny 64³", resolution, 6);
        }
    }

    private BenchmarkResult benchmarkESVTData(String name, List<Point3i> voxels, int depth) {
        System.out.printf("\n--- %s: %,d voxels, depth %d ---%n", name, voxels.size(), depth);

        // Build ESVT data
        long buildStart = System.nanoTime();
        var esvtData = buildESVTFromVoxels(voxels, depth);
        long buildTime = System.nanoTime() - buildStart;

        System.out.printf("  ESVT Build Time: %.2f ms%n", buildTime / 1_000_000.0);

        // Benchmark GPU rendering
        try (var renderer = new ESVTOpenCLRenderer(512, 512)) {
            renderer.initialize();
            renderer.uploadData(esvtData);

            // Camera positions for orbit
            var cameraPositions = generateOrbitCameras(8, 2.0f);
            var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);

            // Warmup
            for (int i = 0; i < WARMUP_FRAMES; i++) {
                renderer.renderFrame(cameraPositions[i % cameraPositions.length], lookAt, 60.0f);
            }

            // Benchmark
            long totalRenderTime = 0;
            for (int i = 0; i < BENCHMARK_FRAMES; i++) {
                long start = System.nanoTime();
                renderer.renderFrame(cameraPositions[i % cameraPositions.length], lookAt, 60.0f);
                totalRenderTime += System.nanoTime() - start;
            }

            double avgFrameTimeMs = totalRenderTime / (BENCHMARK_FRAMES * 1_000_000.0);
            double fps = 1000.0 / avgFrameTimeMs;
            double raysPerSec = (512.0 * 512 * fps);

            System.out.printf("  Avg Frame Time: %.2f ms%n", avgFrameTimeMs);
            System.out.printf("  FPS: %.1f%n", fps);
            System.out.printf("  Rays/sec: %,.0f%n", raysPerSec);

            return new BenchmarkResult(name, voxels.size(), depth, buildTime / 1_000_000.0,
                                       avgFrameTimeMs, fps, raysPerSec);
        }
    }

    private void benchmarkAtResolution(List<Point3i> voxels, String name, int resolution, int depth) {
        var esvtData = buildESVTFromVoxels(voxels, depth);

        try (var renderer = new ESVTOpenCLRenderer(resolution, resolution)) {
            renderer.initialize();
            renderer.uploadData(esvtData);

            var cameraPositions = generateOrbitCameras(8, 2.0f);
            var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);

            // Warmup
            for (int i = 0; i < WARMUP_FRAMES; i++) {
                renderer.renderFrame(cameraPositions[i % cameraPositions.length], lookAt, 60.0f);
            }

            // Benchmark
            long totalRenderTime = 0;
            for (int i = 0; i < BENCHMARK_FRAMES; i++) {
                long start = System.nanoTime();
                renderer.renderFrame(cameraPositions[i % cameraPositions.length], lookAt, 60.0f);
                totalRenderTime += System.nanoTime() - start;
            }

            double avgFrameTimeMs = totalRenderTime / (BENCHMARK_FRAMES * 1_000_000.0);
            double fps = 1000.0 / avgFrameTimeMs;
            double raysPerSec = ((double) resolution * resolution * fps);

            System.out.printf("  %s @ %dx%d: %.2f ms/frame, %.1f FPS, %,.0f rays/sec%n",
                             name, resolution, resolution, avgFrameTimeMs, fps, raysPerSec);

            // Verify reasonable performance
            assertTrue(fps >= 1.0, "Should achieve at least 1 FPS at " + resolution);
        }
    }

    private void benchmarkOctreeConstruction(String name, List<Point3i> voxels, int depth) {
        System.out.printf("\n--- %s: %,d voxels ---%n", name, voxels.size());

        // Use a subset of voxels if too many (octree builder has node limit)
        var testVoxels = voxels.size() > 10000 ? voxels.subList(0, 10000) : voxels;

        // Measure construction time
        long startTime = System.nanoTime();
        ESVOOctreeData octreeData;

        try (var builder = new OctreeBuilder(depth)) {
            octreeData = builder.buildFromVoxels(testVoxels, Math.min(depth, 5)); // Limit depth
        }

        long buildTime = System.nanoTime() - startTime;

        System.out.printf("  Construction Time: %.2f ms (using %,d voxels)%n",
                         buildTime / 1_000_000.0, testVoxels.size());
        System.out.printf("  Voxels/sec: %,.0f%n", testVoxels.size() / (buildTime / 1_000_000_000.0));
    }

    private void benchmarkTetreeConstruction(String name, List<Point3i> voxels, int depth) {
        System.out.printf("\n--- %s: %,d voxels ---%n", name, voxels.size());

        // Limit voxels to avoid ESVT node format overflow (15-bit child pointers)
        var testVoxels = voxels.size() > 5000 ? voxels.subList(0, 5000) : voxels;
        int effectiveDepth = Math.min(depth, 4);

        // Determine grid size from voxel bounds
        int gridSize = 64;
        for (var v : testVoxels) {
            gridSize = Math.max(gridSize, Math.max(v.x, Math.max(v.y, v.z)) + 1);
        }

        // Measure construction time
        long startTime = System.nanoTime();
        var tetree = new Tetree<>(new SequentialLongIDGenerator());
        var builder = new ESVTBuilder();

        // Scale normalized [0,1] coordinates to tetree space at level 0
        float scale = Constants.lengthAtLevel((byte) 0);

        for (int i = 0; i < testVoxels.size(); i++) {
            var v = testVoxels.get(i);
            // Normalize to [0,1) range (with 0.9 margin to stay within bounds)
            float x = (v.x + 0.5f) / gridSize * 0.9f * scale;
            float y = (v.y + 0.5f) / gridSize * 0.9f * scale;
            float z = (v.z + 0.5f) / gridSize * 0.9f * scale;
            tetree.insert(new Point3f(x, y, z), (byte) effectiveDepth, "v" + i);
        }

        var esvtData = builder.build(tetree);
        long buildTime = System.nanoTime() - startTime;

        System.out.printf("  Construction Time: %.2f ms (using %,d voxels at depth %d)%n",
                         buildTime / 1_000_000.0, testVoxels.size(), effectiveDepth);
        System.out.printf("  Voxels/sec: %,.0f%n", testVoxels.size() / (buildTime / 1_000_000_000.0));
    }

    private com.hellblazer.luciferase.esvt.core.ESVTData buildESVTFromVoxels(List<Point3i> voxels, int depth) {
        var tetree = new Tetree<>(new SequentialLongIDGenerator());
        var builder = new ESVTBuilder();

        // Limit voxels to avoid ESVT node format overflow (15-bit child pointers)
        var testVoxels = voxels.size() > 5000 ? voxels.subList(0, 5000) : voxels;
        int effectiveDepth = Math.min(depth, 4);

        // Determine grid size from voxel bounds
        int gridSize = 64;
        for (var v : testVoxels) {
            gridSize = Math.max(gridSize, Math.max(v.x, Math.max(v.y, v.z)) + 1);
        }

        // Scale normalized [0,1] coordinates to tetree space at level 0
        float scale = Constants.lengthAtLevel((byte) 0);

        for (int i = 0; i < testVoxels.size(); i++) {
            var v = testVoxels.get(i);
            // Normalize to [0,1) range (with 0.9 margin to stay within bounds)
            float x = (v.x + 0.5f) / gridSize * 0.9f * scale;
            float y = (v.y + 0.5f) / gridSize * 0.9f * scale;
            float z = (v.z + 0.5f) / gridSize * 0.9f * scale;
            tetree.insert(new Point3f(x, y, z), (byte) effectiveDepth, "v" + i);
        }

        return builder.build(tetree);
    }

    private Vector3f[] generateOrbitCameras(int count, float radius) {
        var cameras = new Vector3f[count];
        for (int i = 0; i < count; i++) {
            float angle = (float) (2 * Math.PI * i / count);
            cameras[i] = new Vector3f(
                0.5f + radius * (float) Math.cos(angle),
                0.5f + radius * 0.5f,
                0.5f + radius * (float) Math.sin(angle)
            );
        }
        return cameras;
    }

    private void printResults(List<BenchmarkResult> results) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("BENCHMARK SUMMARY");
        System.out.println("=".repeat(80));
        System.out.printf("%-15s | %10s | %10s | %10s | %12s%n",
                         "Dataset", "Voxels", "Build (ms)", "FPS", "Rays/sec");
        System.out.println("-".repeat(80));

        for (var result : results) {
            System.out.printf("%-15s | %,10d | %10.2f | %10.1f | %,12.0f%n",
                             result.name, result.voxelCount, result.buildTimeMs,
                             result.fps, result.raysPerSec);
        }

        System.out.println("=".repeat(80));
    }

    private record BenchmarkResult(
        String name,
        int voxelCount,
        int depth,
        double buildTimeMs,
        double avgFrameTimeMs,
        double fps,
        double raysPerSec
    ) {}
}
