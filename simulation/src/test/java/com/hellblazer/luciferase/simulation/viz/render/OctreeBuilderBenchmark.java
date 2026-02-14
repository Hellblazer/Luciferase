package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.geometry.Point3i;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Day 0 Performance Benchmark: OctreeBuilder.buildFromVoxels()
 *
 * Validates build time assumptions before Phase 2 implementation:
 * - Tests 10/100/1000 voxels at 16³/64³/128³ resolution
 * - 20 iterations per configuration
 * - Quality gates: P50<50ms, P99<200ms for 100-voxel at 64³
 *
 * Also verifies portal RenderService position-to-voxel conversion pattern.
 */
class OctreeBuilderBenchmark {
    private static final Logger log = LoggerFactory.getLogger(OctreeBuilderBenchmark.class);

    // Test configurations
    private static final int[] VOXEL_COUNTS = {10, 100, 1000};
    private static final int[] GRID_RESOLUTIONS = {16, 64, 128};
    private static final int ITERATIONS = 20;
    private static final int MAX_DEPTH = 10;

    // Performance gates for 100-voxel at 64³ resolution
    private static final double P50_GATE_MS = 50.0;
    private static final double P99_GATE_MS = 200.0;

    @Test
    void testOctreeBuilderPerformance() {
        log.info("=== Day 0: OctreeBuilder Performance Benchmark ===");
        log.info("Testing {} voxel counts × {} resolutions × {} iterations",
                VOXEL_COUNTS.length, GRID_RESOLUTIONS.length, ITERATIONS);

        var results = new ArrayList<BenchmarkResult>();

        for (int voxelCount : VOXEL_COUNTS) {
            for (int gridResolution : GRID_RESOLUTIONS) {
                var result = benchmarkConfiguration(voxelCount, gridResolution);
                results.add(result);

                log.info("Voxels: {}, Resolution: {}³ → P50: {}ms, P99: {}ms, Mean: {}ms, Max: {}ms",
                        voxelCount, gridResolution,
                        String.format("%.2f", result.p50Ms),
                        String.format("%.2f", result.p99Ms),
                        String.format("%.2f", result.meanMs),
                        String.format("%.2f", result.maxMs));
            }
        }

        // Verify quality gates for 100-voxel at 64³
        var criticalResult = results.stream()
                .filter(r -> r.voxelCount == 100 && r.gridResolution == 64)
                .findFirst()
                .orElseThrow();

        log.info("");
        log.info("=== Quality Gate Validation (100 voxels at 64³) ===");
        log.info("P50: {}ms (gate: <{}ms) - {}",
                String.format("%.2f", criticalResult.p50Ms),
                String.format("%.0f", P50_GATE_MS),
                criticalResult.p50Ms < P50_GATE_MS ? "PASS" : "FAIL");
        log.info("P99: {}ms (gate: <{}ms) - {}",
                String.format("%.2f", criticalResult.p99Ms),
                String.format("%.0f", P99_GATE_MS),
                criticalResult.p99Ms < P99_GATE_MS ? "PASS" : "FAIL");

        // Performance gate assertions
        assertTrue(criticalResult.p50Ms < P50_GATE_MS,
                String.format("P50 latency %.2fms exceeds gate of %.0fms",
                        criticalResult.p50Ms, P50_GATE_MS));
        assertTrue(criticalResult.p99Ms < P99_GATE_MS,
                String.format("P99 latency %.2fms exceeds gate of %.0fms",
                        criticalResult.p99Ms, P99_GATE_MS));

        // Recommendations
        log.info("");
        log.info("=== Recommendations ===");
        recommendPoolSize(results);
        recommendGridResolution(results);
    }

    @Test
    void testPortalRenderServicePositionToVoxelPattern() {
        log.info("=== Verifying Portal RenderService Position-to-Voxel Pattern ===");

        // Test data: positions in [0,1] coordinate space
        var positions = List.of(
                new Point3f(0.1f, 0.2f, 0.3f),
                new Point3f(0.5f, 0.5f, 0.5f),
                new Point3f(0.99f, 0.99f, 0.99f),
                new Point3f(-0.1f, 1.5f, 0.7f)  // Out of bounds test
        );

        int gridResolution = 64;
        var voxels = new ArrayList<Point3i>();

        // Portal RenderService pattern (RenderService.java:249-258)
        for (var pos : positions) {
            int x = (int) (pos.x * gridResolution);
            int y = (int) (pos.y * gridResolution);
            int z = (int) (pos.z * gridResolution);
            x = Math.max(0, Math.min(gridResolution - 1, x));
            y = Math.max(0, Math.min(gridResolution - 1, y));
            z = Math.max(0, Math.min(gridResolution - 1, z));
            voxels.add(new Point3i(x, y, z));
        }

        // Verify conversions
        assertEquals(4, voxels.size());

        // (0.1, 0.2, 0.3) → (6, 12, 19)
        assertEquals(6, voxels.get(0).x);
        assertEquals(12, voxels.get(0).y);
        assertEquals(19, voxels.get(0).z);

        // (0.5, 0.5, 0.5) → (32, 32, 32)
        assertEquals(32, voxels.get(1).x);
        assertEquals(32, voxels.get(1).y);
        assertEquals(32, voxels.get(1).z);

        // (0.99, 0.99, 0.99) → (63, 63, 63) - clamped to gridResolution-1
        assertEquals(63, voxels.get(2).x);
        assertEquals(63, voxels.get(2).y);
        assertEquals(63, voxels.get(2).z);

        // (-0.1, 1.5, 0.7) → (0, 63, 44) - clamped to valid range
        assertEquals(0, voxels.get(3).x);
        assertEquals(63, voxels.get(3).y);
        assertEquals(44, voxels.get(3).z);

        log.info("Portal RenderService position-to-voxel pattern verified correctly");
        log.info("  (0.1, 0.2, 0.3) → ({}, {}, {})", voxels.get(0).x, voxels.get(0).y, voxels.get(0).z);
        log.info("  (0.5, 0.5, 0.5) → ({}, {}, {})", voxels.get(1).x, voxels.get(1).y, voxels.get(1).z);
        log.info("  (0.99, 0.99, 0.99) → ({}, {}, {}) [clamped]", voxels.get(2).x, voxels.get(2).y, voxels.get(2).z);
        log.info("  (-0.1, 1.5, 0.7) → ({}, {}, {}) [clamped]", voxels.get(3).x, voxels.get(3).y, voxels.get(3).z);
    }

    private BenchmarkResult benchmarkConfiguration(int voxelCount, int gridResolution) {
        var latencies = new ArrayList<Long>();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            var voxels = generateRandomVoxels(voxelCount, gridResolution);

            long startNs = System.nanoTime();
            try (var builder = new OctreeBuilder(MAX_DEPTH)) {
                var octreeData = builder.buildFromVoxels(voxels, MAX_DEPTH);
                assertNotNull(octreeData, "Built octree should not be null");
                assertTrue(octreeData.nodeCount() > 0, "Built octree should have nodes");
            }
            long endNs = System.nanoTime();

            latencies.add(endNs - startNs);
        }

        // Sort for percentile calculation
        latencies.sort(Long::compareTo);

        // Calculate statistics
        double meanNs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long p50Ns = latencies.get((int) (ITERATIONS * 0.50));
        long p99Ns = latencies.get((int) (ITERATIONS * 0.99));
        long maxNs = latencies.get(ITERATIONS - 1);

        return new BenchmarkResult(
                voxelCount,
                gridResolution,
                meanNs / 1_000_000.0,
                p50Ns / 1_000_000.0,
                p99Ns / 1_000_000.0,
                maxNs / 1_000_000.0
        );
    }

    private List<Point3i> generateRandomVoxels(int count, int gridResolution) {
        var random = new Random(42); // Fixed seed for reproducibility
        var voxels = new ArrayList<Point3i>(count);

        for (int i = 0; i < count; i++) {
            int x = random.nextInt(gridResolution);
            int y = random.nextInt(gridResolution);
            int z = random.nextInt(gridResolution);
            voxels.add(new Point3i(x, y, z));
        }

        return voxels;
    }

    private void recommendPoolSize(List<BenchmarkResult> results) {
        // Find worst-case P99 latency for 1000 voxels at 128³
        var worstCase = results.stream()
                .filter(r -> r.voxelCount == 1000 && r.gridResolution == 128)
                .findFirst();

        if (worstCase.isPresent() && worstCase.get().p99Ms > 100.0) {
            log.info("RECOMMENDATION: P99 latency {}ms exceeds 100ms threshold",
                    String.format("%.2f", worstCase.get().p99Ms));
            log.info("  → Consider increasing buildPoolSize to 4 (current default: 2)");
            log.info("  → This allows more parallel builds under heavy load");
        } else {
            log.info("Current buildPoolSize=2 is adequate for measured workloads");
        }
    }

    private void recommendGridResolution(List<BenchmarkResult> results) {
        // Check if 128³ resolution causes excessive latency
        var highRes = results.stream()
                .filter(r -> r.gridResolution == 128)
                .mapToDouble(r -> r.p99Ms)
                .max();

        if (highRes.isPresent() && highRes.getAsDouble() > 200.0) {
            log.info("RECOMMENDATION: 128³ resolution shows P99 > {}ms",
                    String.format("%.0f", highRes.getAsDouble()));
            log.info("  → Consider reducing default gridResolution to 64");
            log.info("  → Trade-off: Lower resolution, but faster builds");
        } else {
            log.info("Current gridResolution=64 default is appropriate");
        }
    }

    /**
     * Benchmark result for a single configuration
     */
    private record BenchmarkResult(
            int voxelCount,
            int gridResolution,
            double meanMs,
            double p50Ms,
            double p99Ms,
            double maxMs
    ) {}
}
