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

/**
 * Test the effect of cached Hilbert indices on MutableGrid performance.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class CachedHilbertSortingTest {
    
    @Test
    public void testCachedHilbertPerformance() {
        var entropy = new Random(0x666);
        var radius = 16000.0f;
        var center = new Point3f(radius + 100, radius + 100, radius + 100);
        
        // Create Hilbert comparator for our space
        // Points are in sphere of radius 16000 centered at (16100, 16100, 16100)
        // Plus movement of ±5, clamped to [0, ∞)
        float minCoord = 0;
        float maxCoord = 32105; // (center + radius) + movement = 32100 + 5
        HilbertCurveComparator comparator = new HilbertCurveComparator(
            minCoord, minCoord, minCoord,
            maxCoord, maxCoord, maxCoord
        );
        
        // Generate vertices using HilbertVertex
        var grid = new MutableGrid();
        List<HilbertVertex> sites = new ArrayList<>();
        for (var p : Vertex.getRandomPoints(entropy, 256, radius, true)) {
            p.add(center);
            HilbertVertex hv = new HilbertVertex(p);
            hv.setHilbertComparator(comparator);
            grid.track(p, entropy);
            sites.add(hv);
        }
        
        // Run tests
        System.out.println("=== Cached Hilbert Performance Test ===");
        System.out.println("Vertices: " + sites.size());
        
        // Test 1: Without any sorting
        long withoutSorting = testWithoutSorting(grid, sites, entropy);
        
        // Test 2: With Hilbert sorting (cached indices)
        long withCachedSorting = testWithCachedHilbertSorting(grid, sites, entropy);
        
        // Test 3: With regular Hilbert sorting (no caching)
        List<Vertex> regularVertices = new ArrayList<>(sites);
        long withRegularSorting = testWithRegularHilbertSorting(grid, regularVertices, entropy, comparator);
        
        System.out.printf("Without sorting: %.3f ms/iteration%n", withoutSorting / 1_000_000.0);
        System.out.printf("With cached Hilbert sorting: %.3f ms/iteration%n", withCachedSorting / 1_000_000.0);
        System.out.printf("With regular Hilbert sorting: %.3f ms/iteration%n", withRegularSorting / 1_000_000.0);
        System.out.printf("Cached vs No Sort speedup: %.2fx%n", (double) withoutSorting / withCachedSorting);
        System.out.printf("Cached vs Regular Sort speedup: %.2fx%n", (double) withRegularSorting / withCachedSorting);
    }
    
    private long testWithoutSorting(MutableGrid grid, List<HilbertVertex> sites, Random entropy) {
        int iterations = 1000;
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            // Move vertices
            for (var site : sites) {
                site.moveBy(Vertex.randomPoint(entropy, -5f, 5f));
                if (site.x < 0.0f) site.x = 0.0f;
                if (site.y < 0.0f) site.y = 0.0f;
                if (site.z < 0.0f) site.z = 0.0f;
            }
            
            // Rebuild without sorting
            grid.rebuild(entropy);
        }
        
        return (System.nanoTime() - startTime) / iterations;
    }
    
    private long testWithCachedHilbertSorting(MutableGrid grid, List<HilbertVertex> sites, Random entropy) {
        int iterations = 1000;
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            // Move vertices (this automatically clears cached indices)
            for (var site : sites) {
                site.moveBy(Vertex.randomPoint(entropy, -5f, 5f));
                if (site.x < 0.0f) site.x = 0.0f;
                if (site.y < 0.0f) site.y = 0.0f;
                if (site.z < 0.0f) site.z = 0.0f;
            }
            
            // Sort using cached Hilbert indices
            Collections.sort(sites);
            
            // Rebuild
            grid.rebuild(entropy);
        }
        
        return (System.nanoTime() - startTime) / iterations;
    }
    
    private long testWithRegularHilbertSorting(MutableGrid grid, List<Vertex> sites, Random entropy, HilbertCurveComparator comparator) {
        int iterations = 1000;
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            // Move vertices
            for (var site : sites) {
                site.moveBy(Vertex.randomPoint(entropy, -5f, 5f));
                if (site.x < 0.0f) site.x = 0.0f;
                if (site.y < 0.0f) site.y = 0.0f;
                if (site.z < 0.0f) site.z = 0.0f;
            }
            
            // Sort using regular comparator (recalculates every time)
            Collections.sort(sites, comparator);
            
            // Rebuild
            grid.rebuild(entropy);
        }
        
        return (System.nanoTime() - startTime) / iterations;
    }
    
    @Test
    public void testSortingOverhead() {
        // Test to measure just the sorting overhead
        var entropy = new Random(0x666);
        var radius = 16000.0f;
        var center = new Point3f(radius + 100, radius + 100, radius + 100);
        
        HilbertCurveComparator comparator = new HilbertCurveComparator(
            0, 0, 0, 32200, 32200, 32200
        );
        
        System.out.println("\n=== Sorting Overhead Analysis ===");
        
        int[] sizes = {100, 256, 500, 1000};
        for (int size : sizes) {
            // Generate vertices
            List<HilbertVertex> cachedVertices = new ArrayList<>();
            List<Vertex> regularVertices = new ArrayList<>();
            
            for (var p : Vertex.getRandomPoints(entropy, size, radius, true)) {
                p.add(center);
                HilbertVertex hv = new HilbertVertex(p);
                hv.setHilbertComparator(comparator);
                cachedVertices.add(hv);
                regularVertices.add(new Vertex(p));
            }
            
            // Measure sorting time
            int sortIterations = 1000;
            
            // Cached sorting
            long cachedStart = System.nanoTime();
            for (int i = 0; i < sortIterations; i++) {
                // Simulate position changes (clears cache)
                if (i % 10 == 0) {
                    for (var v : cachedVertices) {
                        v.clearHilbertIndex();
                    }
                }
                Collections.sort(cachedVertices);
            }
            long cachedTime = System.nanoTime() - cachedStart;
            
            // Regular sorting
            long regularStart = System.nanoTime();
            for (int i = 0; i < sortIterations; i++) {
                Collections.sort(regularVertices, comparator);
            }
            long regularTime = System.nanoTime() - regularStart;
            
            System.out.printf("Size %4d: Cached %.3f μs/sort, Regular %.3f μs/sort, Speedup %.2fx%n",
                size,
                cachedTime / (sortIterations * 1000.0),
                regularTime / (sortIterations * 1000.0),
                (double) regularTime / cachedTime
            );
        }
    }
    
    @Test
    public void testMixedVertexTypes() {
        // Test that we can mix HilbertVertex and regular Vertex
        var comparator = new HilbertCurveComparator();
        
        List<Vertex> mixed = new ArrayList<>();
        mixed.add(new Vertex(0.1f, 0.2f, 0.3f));
        
        HilbertVertex hv = new HilbertVertex(0.4f, 0.5f, 0.6f);
        hv.setHilbertComparator(comparator);
        mixed.add(hv);
        
        mixed.add(new Vertex(0.7f, 0.8f, 0.9f));
        
        // Should be able to sort without issues
        Collections.sort(mixed);
        
        // Verify order is consistent
        for (int i = 1; i < mixed.size(); i++) {
            assert mixed.get(i-1).compareTo(mixed.get(i)) <= 0;
        }
    }
}