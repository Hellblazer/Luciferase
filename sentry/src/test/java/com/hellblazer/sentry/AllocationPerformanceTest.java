/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.sentry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Performance comparison between pooled and direct allocation strategies
 */
public class AllocationPerformanceTest {
    
    private static final int WARMUP_ITERATIONS = 5;
    private static final int TEST_ITERATIONS = 10;
    private static final int[] POINT_COUNTS = {100, 1000, 10000};
    private static final Random RANDOM = new Random(42);

    @BeforeEach
    public void setUp() {
        TetrahedronPoolContext.clear();
    }

    @AfterEach
    public void tearDown() {
        TetrahedronPoolContext.clear();
    }

    @Test
    public void compareAllocationStrategies() {
        System.out.println("\nAllocation Strategy Performance Comparison");
        System.out.println("==========================================\n");
        
        for (int pointCount : POINT_COUNTS) {
            System.out.printf("Testing with %d points:\n", pointCount);
            
            var points = generateRandomPoints(pointCount);
            
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                runTest(MutableGrid.AllocationStrategy.POOLED, points);
                runTest(MutableGrid.AllocationStrategy.DIRECT, points);
            }
            
            // Pooled allocation timing
            long pooledTotalTime = 0;
            long pooledTotalMemory = 0;
            for (int i = 0; i < TEST_ITERATIONS; i++) {
                var result = runTest(MutableGrid.AllocationStrategy.POOLED, points);
                pooledTotalTime += result.elapsedNanos;
                pooledTotalMemory += result.memoryUsed;
            }
            
            // Direct allocation timing
            long directTotalTime = 0;
            long directTotalMemory = 0;
            for (int i = 0; i < TEST_ITERATIONS; i++) {
                var result = runTest(MutableGrid.AllocationStrategy.DIRECT, points);
                directTotalTime += result.elapsedNanos;
                directTotalMemory += result.memoryUsed;
            }
            
            // Calculate averages
            double pooledAvgTimeMs = (pooledTotalTime / TEST_ITERATIONS) / 1_000_000.0;
            double directAvgTimeMs = (directTotalTime / TEST_ITERATIONS) / 1_000_000.0;
            long pooledAvgMemoryKB = (pooledTotalMemory / TEST_ITERATIONS) / 1024;
            long directAvgMemoryKB = (directTotalMemory / TEST_ITERATIONS) / 1024;
            
            // Print results
            System.out.printf("  Pooled:  %.2f ms, %d KB memory\n", pooledAvgTimeMs, pooledAvgMemoryKB);
            System.out.printf("  Direct:  %.2f ms, %d KB memory\n", directAvgTimeMs, directAvgMemoryKB);
            System.out.printf("  Speedup: %.2fx (pooled vs direct)\n", directAvgTimeMs / pooledAvgTimeMs);
            System.out.printf("  Memory:  %.2fx less with pooled\n\n", 
                (double) directAvgMemoryKB / pooledAvgMemoryKB);
        }
    }

    @Test
    public void testAllocationOverhead() {
        System.out.println("\nRaw Allocation Overhead Test");
        System.out.println("============================\n");
        
        var vertices = new Vertex[] {
            new Vertex(0, 0, 0),
            new Vertex(1, 0, 0),
            new Vertex(0, 1, 0),
            new Vertex(0, 0, 1)
        };
        
        int iterations = 1_000_000;
        
        // Test pooled allocator
        var pooledAllocator = new PooledAllocator(new TetrahedronPool());
        pooledAllocator.warmUp(1000);
        
        long pooledStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var t = pooledAllocator.acquire(vertices);
            pooledAllocator.release(t);
        }
        long pooledElapsed = System.nanoTime() - pooledStart;
        
        // Test direct allocator
        var directAllocator = new DirectAllocator();
        
        long directStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var t = directAllocator.acquire(vertices);
            directAllocator.release(t);
        }
        long directElapsed = System.nanoTime() - directStart;
        
        // Print results
        System.out.printf("Iterations: %d\n", iterations);
        System.out.printf("Pooled:  %.2f ns per acquire/release\n", (double) pooledElapsed / iterations);
        System.out.printf("Direct:  %.2f ns per acquire/release\n", (double) directElapsed / iterations);
        System.out.printf("Speedup: %.2fx (pooled vs direct)\n\n", 
            (double) directElapsed / pooledElapsed);
        
        // Print statistics
        System.out.println("Pooled Allocator Statistics:");
        System.out.println(pooledAllocator.getStatistics());
        System.out.printf("Reuse Rate: %.2f%%\n\n", pooledAllocator.getReuseRate() * 100);
        
        System.out.println("Direct Allocator Statistics:");
        System.out.println(directAllocator.getStatistics());
    }

    private TestResult runTest(MutableGrid.AllocationStrategy strategy, List<Vector3f> points) {
        System.gc();
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        long startTime = System.nanoTime();
        
        var grid = new MutableGrid(strategy);
        var random = new Random(42);
        for (var point : points) {
            grid.track(new Vertex(point), random);
        }
        
        long elapsedNanos = System.nanoTime() - startTime;
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memoryUsed = memoryAfter - memoryBefore;
        
        return new TestResult(elapsedNanos, memoryUsed);
    }

    private List<Vector3f> generateRandomPoints(int count) {
        var points = new ArrayList<Vector3f>(count);
        for (int i = 0; i < count; i++) {
            points.add(new Vector3f(
                RANDOM.nextFloat() * 10,
                RANDOM.nextFloat() * 10,
                RANDOM.nextFloat() * 10
            ));
        }
        return points;
    }

    private static class TestResult {
        final long elapsedNanos;
        final long memoryUsed;
        
        TestResult(long elapsedNanos, long memoryUsed) {
            this.elapsedNanos = elapsedNanos;
            this.memoryUsed = memoryUsed;
        }
    }
}