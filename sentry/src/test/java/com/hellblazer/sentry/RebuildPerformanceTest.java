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

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Performance test for rebuild operations with detailed profiling
 */
public class RebuildPerformanceTest {
    
    @Test
    public void testRebuild256Points() {
        System.out.println("\nRebuild Performance Test - 256 Points");
        System.out.println("=====================================\n");
        
        Random random = new Random(42);
        
        // Create test points
        List<Vertex> vertices = createTestVertices(256, random);
        
        // Test with pooled allocation
        System.out.println("Testing POOLED allocation strategy:");
        testRebuildPerformance(vertices, MutableGrid.AllocationStrategy.POOLED, random);
        
        // Test with direct allocation
        System.out.println("\nTesting DIRECT allocation strategy:");
        testRebuildPerformance(vertices, MutableGrid.AllocationStrategy.DIRECT, random);
        
        // Detailed pooling analysis
        System.out.println("\nDetailed Pooling Analysis:");
        analyzePoolingOverhead(vertices, random);
    }
    
    private void testRebuildPerformance(List<Vertex> vertices, MutableGrid.AllocationStrategy strategy, Random random) {
        MutableGrid grid = new MutableGrid(strategy);
        
        // Initial insertion
        for (Vertex v : vertices) {
            grid.track(v, random);
        }
        
        // Warmup
        for (int i = 0; i < 5; i++) {
            grid.rebuild(vertices, random);
        }
        
        // Timing test
        int iterations = 10;
        long totalTime = 0;
        
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            grid.rebuild(vertices, random);
            long elapsed = System.nanoTime() - start;
            totalTime += elapsed;
        }
        
        double avgTimeMs = (totalTime / iterations) / 1_000_000.0;
        System.out.printf("  Average rebuild time: %.2f ms\n", avgTimeMs);
        
        if (grid.getAllocator() != null) {
            System.out.println("  Allocator stats: " + grid.getAllocator().getStatistics());
        }
    }
    
    private void analyzePoolingOverhead(List<Vertex> vertices, Random random) {
        MutableGrid grid = new MutableGrid(MutableGrid.AllocationStrategy.POOLED);
        
        // Initial insertion
        for (Vertex v : vertices) {
            grid.track(v, random);
        }
        
        // Profile different phases of rebuild
        long releaseTime = 0;
        long clearTime = 0;
        long reinitTime = 0;
        long insertTime = 0;
        
        int iterations = 5;
        
        for (int i = 0; i < iterations; i++) {
            // Measure release phase
            long start = System.nanoTime();
            grid.releaseAllTetrahedronsForProfiling();
            releaseTime += System.nanoTime() - start;
            
            // Measure clear phase
            start = System.nanoTime();
            grid.clearVerticesForProfiling();
            clearTime += System.nanoTime() - start;
            
            // Measure reinit phase
            start = System.nanoTime();
            grid.reinitializeForProfiling();
            reinitTime += System.nanoTime() - start;
            
            // Measure insertion phase
            start = System.nanoTime();
            for (Vertex v : vertices) {
                var containedIn = grid.locate(v, grid.getLastTetrahedronForProfiling(), random);
                if (containedIn != null) {
                    grid.addForProfiling(v, containedIn);
                }
            }
            insertTime += System.nanoTime() - start;
        }
        
        System.out.printf("  Release phase: %.2f ms (%.1f%%)\n", 
            releaseTime / iterations / 1_000_000.0,
            100.0 * releaseTime / (releaseTime + clearTime + reinitTime + insertTime));
        System.out.printf("  Clear phase: %.2f ms (%.1f%%)\n", 
            clearTime / iterations / 1_000_000.0,
            100.0 * clearTime / (releaseTime + clearTime + reinitTime + insertTime));
        System.out.printf("  Reinit phase: %.2f ms (%.1f%%)\n", 
            reinitTime / iterations / 1_000_000.0,
            100.0 * reinitTime / (releaseTime + clearTime + reinitTime + insertTime));
        System.out.printf("  Insert phase: %.2f ms (%.1f%%)\n", 
            insertTime / iterations / 1_000_000.0,
            100.0 * insertTime / (releaseTime + clearTime + reinitTime + insertTime));
        
        // Analyze pool operations
        if (grid.getAllocator() instanceof PooledAllocator) {
            PooledAllocator pooled = (PooledAllocator) grid.getAllocator();
            TetrahedronPool pool = pooled.getPool();
            
            System.out.println("\n  Pool Statistics:");
            System.out.println("  " + pool.getStatistics());
            
            // Test pool acquire/release overhead
            testPoolOverhead(pool);
        }
    }
    
    private void testPoolOverhead(TetrahedronPool pool) {
        System.out.println("\n  Pool Operation Overhead:");
        
        Vertex[] vertices = new Vertex[] {
            new Vertex(0, 0, 0),
            new Vertex(1, 0, 0),
            new Vertex(0, 1, 0),
            new Vertex(0, 0, 1)
        };
        
        // Test acquire overhead
        int ops = 10000;
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            Tetrahedron t = pool.acquire(vertices);
            t.delete();
            pool.release(t);
        }
        long elapsed = System.nanoTime() - start;
        
        System.out.printf("    Acquire/Release pair: %.2f ns per operation\n", 
            (double) elapsed / ops);
        
        // Test batch operations
        Tetrahedron[] batch = new Tetrahedron[100];
        start = System.nanoTime();
        for (int i = 0; i < batch.length; i++) {
            batch[i] = pool.acquire(vertices);
            batch[i].delete();
        }
        pool.releaseBatch(batch, batch.length);
        elapsed = System.nanoTime() - start;
        
        System.out.printf("    Batch acquire/release (100 items): %.2f μs total\n", 
            elapsed / 1000.0);
    }
    
    private List<Vertex> createTestVertices(int count, Random random) {
        List<Vertex> vertices = new ArrayList<>(count);
        
        // Create a mix of distributions
        // Random points (60%)
        int randomCount = (int) (count * 0.6);
        for (int i = 0; i < randomCount; i++) {
            float x = 100 + random.nextFloat() * 800;
            float y = 100 + random.nextFloat() * 800;
            float z = 100 + random.nextFloat() * 800;
            vertices.add(new Vertex(new Point3f(x, y, z)));
        }
        
        // Grid-aligned points (20%)
        int gridSize = (int) Math.sqrt(count * 0.2);
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                vertices.add(new Vertex(new Point3f(200 + i * 50, 200 + j * 50, 500)));
            }
        }
        
        // Clustered points (20%)
        Point3f center = new Point3f(600, 600, 600);
        int clusterCount = count - vertices.size();
        for (int i = 0; i < clusterCount; i++) {
            float x = center.x + (random.nextFloat() - 0.5f) * 100;
            float y = center.y + (random.nextFloat() - 0.5f) * 100;
            float z = center.z + (random.nextFloat() - 0.5f) * 100;
            vertices.add(new Vertex(new Point3f(x, y, z)));
        }
        
        return vertices;
    }
}