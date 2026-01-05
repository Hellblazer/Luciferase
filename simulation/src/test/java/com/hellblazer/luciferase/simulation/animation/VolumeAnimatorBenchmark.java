package com.hellblazer.luciferase.simulation.animation;

import com.hellblazer.luciferase.simulation.animation.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmarks for VolumeAnimator.
 *
 * @author hal.hildebrand
 */
@Disabled("Performance benchmarks - run manually")
class VolumeAnimatorBenchmark {

    private static final int WARMUP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 10;

    @Test
    void benchmarkTrackingPerformance() {
        var entityCounts = new int[]{100, 500, 1000, 5000};

        System.out.println("\n=== VolumeAnimator Tracking Benchmark ===");
        System.out.println("Entity Count | Avg Time (ms) | Std Dev (ms)");
        System.out.println("-------------|---------------|-------------");

        for (int entityCount : entityCounts) {
            var positions = generateRandomPositions(entityCount);

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                benchmarkBaseline(positions);
            }

            // Benchmark baseline
            var baselineTimes = new ArrayList<Long>();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                var time = benchmarkBaseline(positions);
                baselineTimes.add(time);
            }
            var baselineAvg = average(baselineTimes);
            var baselineStdDev = stdDev(baselineTimes, baselineAvg);

            System.out.printf("%12d | %13.2f | %11.2f%n",
                entityCount,
                baselineAvg / 1_000_000.0,
                baselineStdDev / 1_000_000.0);

            // Sanity check: should complete in reasonable time
            assertTrue(baselineAvg / 1_000_000.0 < 1000.0,
                String.format("Tracking %d entities should complete < 1000ms", entityCount));
        }
    }

    @Test
    void benchmarkMemoryUsage() {
        System.out.println("\n=== VolumeAnimator Memory Benchmark ===");

        var entityCounts = new int[]{1000, 5000, 10000};

        for (int entityCount : entityCounts) {
            // Force GC before measurement
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }

            var beforeMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            var animator = new VolumeAnimator("bench-memory");
            var positions = generateRandomPositions(entityCount);

            for (var pos : positions) {
                animator.track(pos);
            }

            var afterMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            var memUsed = (afterMem - beforeMem) / (1024 * 1024); // MB

            System.out.printf("Entities: %d, Memory: %d MB%n", entityCount, memUsed);
        }
    }

    private long benchmarkBaseline(List<Point3f> positions) {
        var animator = new VolumeAnimator("bench-baseline");

        var start = System.nanoTime();
        for (var pos : positions) {
            animator.track(pos);
        }
        return System.nanoTime() - start;
    }

    private List<Point3f> generateRandomPositions(int count) {
        var random = new Random(42); // Fixed seed for reproducibility
        var positions = new ArrayList<Point3f>(count);

        for (int i = 0; i < count; i++) {
            var x = random.nextFloat() * 32200f;
            var y = random.nextFloat() * 32200f;
            var z = random.nextFloat() * 32200f;
            positions.add(new Point3f(x, y, z));
        }

        return positions;
    }

    private double average(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private double stdDev(List<Long> values, double mean) {
        var variance = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average()
            .orElse(0.0);
        return Math.sqrt(variance);
    }
}
