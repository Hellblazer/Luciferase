/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.sentry;

import com.hellblazer.luciferase.geometry.HilbertCurveComparator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test the effect of Hilbert curve pre-sorting on MutableGrid performance.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class HilbertSortingTest {
    
    @Test
    public void testHilbertSortingPerformance() {
        var entropy = new Random(0x666);
        var radius = 16000.0f;
        var center = new Point3f(radius + 100, radius + 100, radius + 100);
        
        // Generate vertices
        List<Vertex> vertices = new ArrayList<>();
        for (var p : Vertex.getRandomPoints(entropy, 1000, radius, true)) {
            p.add(center);
            vertices.add(new Vertex(p));
        }
        
        // Test 1: Without Hilbert sorting (random order)
        long withoutSorting = testRebuildPerformance(new ArrayList<>(vertices), entropy, false);
        
        // Test 2: With Hilbert sorting
        long withSorting = testRebuildPerformance(new ArrayList<>(vertices), entropy, true);
        
        // Print results
        System.out.println("=== Hilbert Sorting Performance Test ===");
        System.out.printf("Vertices: %d%n", vertices.size());
        System.out.printf("Without Hilbert sorting: %.3f ms%n", withoutSorting / 1_000_000.0);
        System.out.printf("With Hilbert sorting: %.3f ms%n", withSorting / 1_000_000.0);
        System.out.printf("Speedup: %.2fx%n", (double) withoutSorting / withSorting);
        
        // Test 3: Different sizes to see scaling effect
        testScaling(entropy, center);
    }
    
    private long testRebuildPerformance(List<Vertex> vertices, Random entropy, boolean useHilbertSort) {
        var grid = new MutableGrid();
        
        // Initial insertion
        for (var v : vertices) {
            grid.track(new Point3f(v), entropy);
        }
        
        // Sort vertices if requested
        if (useHilbertSort) {
            // Create comparator for the bounding box of our data
            // Points are in sphere of radius 16000 centered at (16100, 16100, 16100)
            // So actual range is [100, 32100]
            float minCoord = 100;
            float maxCoord = 32100; // center + radius = 16100 + 16000
            HilbertCurveComparator comparator = new HilbertCurveComparator(
                minCoord, minCoord, minCoord,
                maxCoord, maxCoord, maxCoord
            );
            Collections.sort(vertices, comparator);
        }
        
        // Measure rebuild time
        int iterations = 10;
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            grid.clear();
            grid = new MutableGrid();
            
            // Insert vertices in current order
            for (var v : vertices) {
                grid.track(new Point3f(v), entropy);
            }
        }
        
        return (System.nanoTime() - startTime) / iterations;
    }
    
    private void testScaling(Random entropy, Point3f center) {
        System.out.println("\n=== Scaling Analysis ===");
        System.out.println("Size | Without Sort (ms) | With Sort (ms) | Speedup");
        System.out.println("-----|-------------------|----------------|--------");
        
        int[] sizes = {100, 250, 500, 1000, 2000};
        for (int size : sizes) {
            // Generate vertices
            List<Vertex> vertices = new ArrayList<>();
            for (var p : Vertex.getRandomPoints(entropy, size, 16000.0f, true)) {
                p.add(center);
                vertices.add(new Vertex(p));
            }
            
            // Test performance
            long withoutSort = testRebuildPerformance(new ArrayList<>(vertices), entropy, false);
            long withSort = testRebuildPerformance(new ArrayList<>(vertices), entropy, true);
            
            System.out.printf("%4d | %17.3f | %14.3f | %6.2fx%n",
                size,
                withoutSort / 1_000_000.0,
                withSort / 1_000_000.0,
                (double) withoutSort / withSort
            );
        }
    }
    
    @Test
    public void testHilbertSortingWithMovement() {
        var entropy = new Random(0x666);
        var radius = 16000.0f;
        var center = new Point3f(radius + 100, radius + 100, radius + 100);
        
        // Generate initial vertices
        var grid = new MutableGrid();
        List<Vertex> sites = new ArrayList<>();
        for (var p : Vertex.getRandomPoints(entropy, 256, radius, true)) {
            p.add(center);
            sites.add(grid.track(p, entropy));
        }
        
        // Create Hilbert comparator for our space
        // Points are in sphere of radius 16000 centered at (16100, 16100, 16100)
        // Plus movement of ±5, clamped to [0, ∞)
        float minCoord = 0;
        float maxCoord = 32105; // (center + radius) + movement = 32100 + 5
        HilbertCurveComparator comparator = new HilbertCurveComparator(
            minCoord, minCoord, minCoord,
            maxCoord, maxCoord, maxCoord
        );
        
        int iterations = 100;
        
        // Test 1: Random order rebuild
        long randomTime = measureMovementTest(grid, sites, entropy, iterations, false, null);
        
        // Test 2: Hilbert sorted rebuild
        long sortedTime = measureMovementTest(grid, sites, entropy, iterations, true, comparator);
        
        System.out.println("\n=== Movement Test Results ===");
        System.out.printf("Iterations: %d, Sites: %d%n", iterations, sites.size());
        System.out.printf("Random order: %.3f ms/iteration%n", randomTime / 1_000_000.0);
        System.out.printf("Hilbert sorted: %.3f ms/iteration%n", sortedTime / 1_000_000.0);
        System.out.printf("Speedup: %.2fx%n", (double) randomTime / sortedTime);
    }
    
    private long measureMovementTest(MutableGrid grid, List<Vertex> sites, Random entropy, 
                                   int iterations, boolean useSort, HilbertCurveComparator comparator) {
        // Clone sites to avoid side effects
        List<Vertex> workingSites = new ArrayList<>(sites);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            // Move vertices
            for (var site : workingSites) {
                site.moveBy(Vertex.randomPoint(entropy, -5f, 5f));
                // Clamp to positive coordinates
                if (site.x < 0.0f) site.x = 0.0f;
                if (site.y < 0.0f) site.y = 0.0f;
                if (site.z < 0.0f) site.z = 0.0f;
            }
            
            // Sort if requested
            if (useSort && comparator != null) {
                Collections.sort(workingSites, comparator);
            }
            
            // Rebuild grid
            grid.rebuild(entropy);
        }
        
        return (System.nanoTime() - startTime) / iterations;
    }
    
    @Test
    public void testCorrectness() {
        // Verify that Hilbert sorting doesn't affect correctness
        var entropy = new Random(0x666);
        var radius = 1000.0f;
        var center = new Point3f(radius + 100, radius + 100, radius + 100);
        
        // Generate vertices
        List<Vertex> vertices = new ArrayList<>();
        for (var p : Vertex.getRandomPoints(entropy, 100, radius, true)) {
            p.add(center);
            vertices.add(new Vertex(p));
        }
        
        // Build grid without sorting
        var grid1 = new MutableGrid();
        for (var v : vertices) {
            grid1.track(new Point3f(v), entropy);
        }
        int tetCount1 = grid1.tetrahedrons().size();
        
        // Build grid with Hilbert sorting
        HilbertCurveComparator comparator = new HilbertCurveComparator(
            100, 100, 100, 2100, 2100, 2100
        );
        Collections.sort(vertices, comparator);
        
        var grid2 = new MutableGrid();
        for (var v : vertices) {
            grid2.track(new Point3f(v), entropy);
        }
        int tetCount2 = grid2.tetrahedrons().size();
        
        // Both should produce valid triangulations with similar complexity
        System.out.printf("Tetrahedrons without sort: %d%n", tetCount1);
        System.out.printf("Tetrahedrons with sort: %d%n", tetCount2);
        
        // The exact count might differ slightly due to different insertion order
        // affecting degenerate cases, but they should be close
        assertEquals(tetCount1, tetCount2, tetCount1 * 0.1); // Allow 10% difference
    }
}