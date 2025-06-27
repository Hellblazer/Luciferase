package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.tetree.TMIndex128Clean.TMIndex128Bit;
import com.hellblazer.luciferase.lucien.tetree.TMIndex128Clean.TetId;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Performance comparison between current Tet.tmIndex() and TMIndex128Clean
 */
public class TMIndexPerformanceComparison {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int TEST_ITERATIONS   = 100_000;
    private static final int NUM_TEST_CASES    = 100;

    private static long benchmarkCurrentImplementation(List<TestCase> testCases) {
        long startTime = System.nanoTime();

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            TestCase tc = testCases.get(i % testCases.size());
            TetreeKey result = tc.tet.tmIndex();
            // Prevent optimization
            if (result == null) {
                throw new RuntimeException();
            }
        }

        long totalTime = System.nanoTime() - startTime;
        return totalTime / TEST_ITERATIONS;
    }

    private static void benchmarkLevel(List<TestCase> allTestCases, int targetLevel) {
        // Filter test cases for this level
        List<TestCase> levelCases = allTestCases.stream().filter(tc -> tc.level == targetLevel).toList();

        if (levelCases.isEmpty()) {
            return;
        }

        System.out.printf("Level %d:\n", targetLevel);

        // Benchmark current Tet.tmIndex()
        long currentImplTime = benchmarkCurrentImplementation(levelCases);

        // Benchmark TMIndex128Clean
        long newImplTime = benchmarkNewImplementation(levelCases);

        // Calculate speedup
        double speedup = (double) currentImplTime / newImplTime;

        System.out.printf("  Current (BigInteger): %,d ns/op\n", currentImplTime);
        System.out.printf("  New (128-bit):        %,d ns/op\n", newImplTime);
        System.out.printf("  Speedup:              %.2fx\n", speedup);
        System.out.println();
    }

    private static long benchmarkNewImplementation(List<TestCase> testCases) {
        long startTime = System.nanoTime();

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            TestCase tc = testCases.get(i % testCases.size());
            TMIndex128Bit result = TMIndex128Clean.encode(tc.tetId);
            // Prevent optimization
            if (result == null) {
                throw new RuntimeException();
            }
        }

        long totalTime = System.nanoTime() - startTime;
        return totalTime / TEST_ITERATIONS;
    }

    private static void compareMemoryUsage() {
        System.out.println("\nMemory Usage Comparison:");
        System.out.println("------------------------");

        // TMIndex128Bit
        TMIndex128Bit tmIndex = new TMIndex128Bit(-1L, -1L);

        System.out.println("TMIndex128Bit (2 longs): ~" + estimateObjectSize(tmIndex) + " bytes");
    }

    private static int estimateObjectSize(Object obj) {
        // Rough estimates based on JVM object layout
        if (obj instanceof TetreeKey) {
            // Object header (16) + byte level (1+padding=4) + 2 longs (16)
            return 36;
        } else if (obj instanceof TMIndex128Bit) {
            // Object header (16) + 2 longs (16)
            return 32;
        }
        return 0;
    }

    private static List<TestCase> generateTestCases(int count) {
        List<TestCase> cases = new ArrayList<>();
        Random rand = new Random(42); // Fixed seed for reproducibility

        for (int i = 0; i < count; i++) {
            int level = 5 + (i % 17); // Levels 5-21
            int maxCoord = Math.min(1000, (1 << level) - 1);
            int x = rand.nextInt(maxCoord);
            int y = rand.nextInt(maxCoord);
            int z = rand.nextInt(maxCoord);

            // Create TetId with computed type first to get the type
            TetId tetId = TMIndex128Clean.createWithComputedType(x, y, z, level);

            // Create Tet instance with the same coordinates and type
            Tet tet = new Tet(x, y, z, (byte) level, (byte) tetId.type);

            cases.add(new TestCase(tet, tetId, level));
        }

        return cases;
    }

    public static void main(String[] args) {
        System.out.println("TM-Index Performance Comparison");
        System.out.println("===============================\n");

        // Generate test data
        List<TestCase> testCases = generateTestCases(NUM_TEST_CASES);

        // Warm up both implementations
        warmUp(testCases);

        // Run benchmarks
        System.out.println("Benchmark Results (average of " + TEST_ITERATIONS + " operations):");
        System.out.println("----------------------------------------------------------------");

        for (int level : new int[] { 5, 10, 15, 20, 21 }) {
            benchmarkLevel(testCases, level);
        }

        // Memory usage comparison
        compareMemoryUsage();
    }

    private static void warmUp(List<TestCase> testCases) {
        System.out.println("Warming up...");

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            TestCase tc = testCases.get(i % testCases.size());

            // Warm up current implementation
            tc.tet.tmIndex();

            // Warm up new implementation
            TMIndex128Clean.encode(tc.tetId);
        }

        System.out.println("Warm-up complete.\n");
    }

    private static class TestCase {
        final Tet   tet;
        final TetId tetId;
        final int   level;

        TestCase(Tet tet, TetId tetId, int level) {
            this.tet = tet;
            this.tetId = tetId;
            this.level = level;
        }
    }
}
