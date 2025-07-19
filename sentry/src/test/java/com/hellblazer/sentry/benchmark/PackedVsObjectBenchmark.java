/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import com.hellblazer.sentry.packed.PackedMutableGrid;
import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Benchmark comparing packed (Structure-of-Arrays) vs object-oriented implementations
 * 
 * Phase 4.2 - Alternative Data Structures
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class PackedVsObjectBenchmark {
    
    @Test
    public void runBenchmark() {
        main(new String[0]);
    }
    
    public static void main(String[] args) {
        System.out.println("Sentry Packed vs Object-Oriented Benchmark - Phase 4.2");
        System.out.println("====================================================\n");
        
        // Test with different sizes
        int[] sizes = {100, 500, 1000, 2000, 5000};
        
        for (int size : sizes) {
            System.out.println("\n=== Testing with " + size + " vertices ===");
            benchmarkSize(size);
        }
        
        // Memory usage comparison
        System.out.println("\n=== Memory Usage Comparison ===");
        measureMemoryUsage();
    }
    
    private static void benchmarkSize(int vertexCount) {
        Random random = new Random(42);
        
        // Generate test points
        List<Point3f> points = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            points.add(new Point3f(
                random.nextFloat() * 100,
                random.nextFloat() * 100,
                random.nextFloat() * 100
            ));
        }
        
        // Benchmark object-oriented version
        long ooTime = benchmarkObjectOriented(points, random);
        
        // Benchmark packed version
        long packedTime = benchmarkPacked(points, random);
        
        // Calculate improvement
        double improvement = ((double)(ooTime - packedTime) / ooTime) * 100;
        
        System.out.printf("Object-Oriented: %.2f ms\n", ooTime / 1_000_000.0);
        System.out.printf("Packed (SoA):    %.2f ms\n", packedTime / 1_000_000.0);
        System.out.printf("Improvement:     %.1f%%\n", improvement);
    }
    
    private static long benchmarkObjectOriented(List<Point3f> points, Random random) {
        // Warm up
        for (int i = 0; i < 3; i++) {
            MutableGrid grid = new MutableGrid();
            for (Point3f p : points.subList(0, Math.min(10, points.size()))) {
                grid.track(p, random);
            }
        }
        
        // Actual benchmark
        long startTime = System.nanoTime();
        
        MutableGrid grid = new MutableGrid();
        for (Point3f p : points) {
            grid.track(p, random);
        }
        
        // Perform some queries to test overall performance
        for (int i = 0; i < 100; i++) {
            Point3f query = points.get(random.nextInt(points.size()));
            grid.locate(query, random);
        }
        
        long endTime = System.nanoTime();
        
        // Verify correctness
        System.out.println("  OO Tetrahedra: " + grid.tetrahedrons().size());
        
        return endTime - startTime;
    }
    
    private static long benchmarkPacked(List<Point3f> points, Random random) {
        // Warm up
        for (int i = 0; i < 3; i++) {
            PackedMutableGrid grid = new PackedMutableGrid();
            for (Point3f p : points.subList(0, Math.min(10, points.size()))) {
                grid.track(p, random);
            }
        }
        
        // Actual benchmark
        long startTime = System.nanoTime();
        
        PackedMutableGrid grid = new PackedMutableGrid();
        for (Point3f p : points) {
            grid.track(p, random);
        }
        
        // Perform some queries
        for (int i = 0; i < 100; i++) {
            Point3f query = points.get(random.nextInt(points.size()));
            grid.locate(query, -1, random);
        }
        
        long endTime = System.nanoTime();
        
        // Verify correctness
        System.out.println("  Packed Tetrahedra: " + grid.getValidTetrahedronCount());
        
        return endTime - startTime;
    }
    
    private static void measureMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        Random random = new Random(42);
        int size = 10000;
        
        // Generate points
        List<Point3f> points = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            points.add(new Point3f(
                random.nextFloat() * 100,
                random.nextFloat() * 100,
                random.nextFloat() * 100
            ));
        }
        
        // Measure object-oriented
        System.gc();
        Thread.yield();
        long beforeOO = runtime.totalMemory() - runtime.freeMemory();
        
        MutableGrid ooGrid = new MutableGrid();
        for (Point3f p : points) {
            ooGrid.track(p, random);
        }
        
        System.gc();
        Thread.yield();
        long afterOO = runtime.totalMemory() - runtime.freeMemory();
        long ooMemory = afterOO - beforeOO;
        
        // Clear for packed test
        ooGrid = null;
        System.gc();
        Thread.yield();
        
        // Measure packed
        long beforePacked = runtime.totalMemory() - runtime.freeMemory();
        
        PackedMutableGrid packedGrid = new PackedMutableGrid();
        for (Point3f p : points) {
            packedGrid.track(p, random);
        }
        
        System.gc();
        Thread.yield();
        long afterPacked = runtime.totalMemory() - runtime.freeMemory();
        long packedMemory = afterPacked - beforePacked;
        
        // Results
        System.out.printf("Object-Oriented memory: %.2f MB\n", ooMemory / 1_048_576.0);
        System.out.printf("Packed memory:          %.2f MB\n", packedMemory / 1_048_576.0);
        System.out.printf("Memory reduction:       %.1f%%\n", 
            ((double)(ooMemory - packedMemory) / ooMemory) * 100);
        
        // Per-tetrahedron memory - get fresh instances for accurate count
        ooGrid = new MutableGrid();
        for (Point3f p : points) {
            ooGrid.track(p, random);
        }
        int tetCount = ooGrid.tetrahedrons().size();
        System.out.printf("\nPer-tetrahedron memory:\n");
        System.out.printf("  Object-Oriented: %d bytes\n", ooMemory / tetCount);
        System.out.printf("  Packed:          %d bytes\n", packedMemory / tetCount);
    }
}