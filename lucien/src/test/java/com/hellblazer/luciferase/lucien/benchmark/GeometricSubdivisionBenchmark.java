package com.hellblazer.luciferase.lucien.benchmark;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

/**
 * Benchmark for the geometric subdivision performance. Measures the cost of subdividing tetrahedra geometrically.
 *
 * @author hal.hildebrand
 */
public class GeometricSubdivisionBenchmark {

    private static final int WARMUP_ITERATIONS    = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;

    @Test
    void benchmarkGeometricSubdivision() {
        System.out.println("\n=== GEOMETRIC SUBDIVISION PERFORMANCE BENCHMARK ===");
        System.out.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        // Test at different levels
        byte[] levels = { 5, 8, 10, 12, 15 };

        for (byte level : levels) {
            benchmarkAtLevel(level);
        }

        // Compare with regular child() method
        System.out.println("\n=== COMPARISON WITH GRID-BASED child() METHOD ===");
        compareWithChildMethod();
    }

    private void benchmarkAtLevel(byte level) {
        System.out.printf("\nLevel %d (cell size: %d):\n", level, 1 << (20 - level));

        // Create test tetrahedra at this level
        Tet[] testTets = new Tet[6];
        int cellSize = 1 << (20 - level);
        // Use smaller multiplier to stay within bounds
        int coord = Math.min(cellSize * 10, (1 << 21) - cellSize);
        for (byte type = 0; type < 6; type++) {
            testTets[type] = new Tet(coord, coord, coord, level, type);
        }

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Tet tet : testTets) {
                tet.geometricSubdivide();
            }
        }

        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            for (Tet tet : testTets) {
                tet.geometricSubdivide();
            }
        }
        long endTime = System.nanoTime();

        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        double perOperationUs = (endTime - startTime) / 1000.0 / (BENCHMARK_ITERATIONS * 6);

        System.out.printf("  Total time: %.3f ms\n", totalTimeMs);
        System.out.printf("  Per subdivision: %.3f μs\n", perOperationUs);
        System.out.printf("  Operations/second: %.0f\n", 1_000_000 / perOperationUs);
    }

    private void compareWithChildMethod() {
        byte level = 10;
        Tet testTet = new Tet(102400, 102400, 102400, level, (byte) 0);

        // Warmup both methods
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            testTet.geometricSubdivide();
            for (int j = 0; j < 8; j++) {
                testTet.child(j);
            }
        }

        // Benchmark geometric subdivision
        long startGeo = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            testTet.geometricSubdivide();
        }
        long endGeo = System.nanoTime();
        double geoTimeUs = (endGeo - startGeo) / 1000.0 / BENCHMARK_ITERATIONS;

        // Benchmark child() method (8 calls)
        long startChild = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            for (int j = 0; j < 8; j++) {
                testTet.child(j);
            }
        }
        long endChild = System.nanoTime();
        double childTimeUs = (endChild - startChild) / 1000.0 / BENCHMARK_ITERATIONS;

        System.out.printf("\ngeometricSubdivide(): %.3f μs\n", geoTimeUs);
        System.out.printf("8x child() calls: %.3f μs\n", childTimeUs);
        System.out.printf("Ratio: %.2fx\n", geoTimeUs / childTimeUs);

        if (geoTimeUs > childTimeUs) {
            System.out.printf("child() method is %.1fx faster\n", geoTimeUs / childTimeUs);
        } else {
            System.out.printf("geometricSubdivide() is %.1fx faster\n", childTimeUs / geoTimeUs);
        }
    }
}
