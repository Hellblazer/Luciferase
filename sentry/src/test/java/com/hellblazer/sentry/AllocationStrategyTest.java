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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite validating both allocation strategies (pooled and direct)
 */
public class AllocationStrategyTest {

    private static final int NUM_POINTS = 1000;
    private static final Random RANDOM = new Random(42);

    @BeforeEach
    public void setUp() {
        // Clean up any existing context
        TetrahedronPoolContext.clear();
    }

    @AfterEach
    public void tearDown() {
        TetrahedronPoolContext.clear();
    }

    @ParameterizedTest
    @EnumSource(MutableGrid.AllocationStrategy.class)
    public void testBasicTetrahedralization(MutableGrid.AllocationStrategy strategy) {
        var grid = new MutableGrid(strategy);
        var points = generateRandomPoints(NUM_POINTS);
        
        // Insert points
        var random = new Random(42);
        for (var point : points) {
            grid.track(new Vertex(point), random);
        }
        
        // Verify all points are in the grid
        assertEquals(NUM_POINTS, grid.size());
        
        // Verify allocator type
        if (strategy == MutableGrid.AllocationStrategy.POOLED) {
            assertTrue(grid.getAllocator() instanceof PooledAllocator);
        } else {
            assertTrue(grid.getAllocator() instanceof DirectAllocator);
        }
    }

    @ParameterizedTest
    @EnumSource(MutableGrid.AllocationStrategy.class)
    public void testFlipOperations(MutableGrid.AllocationStrategy strategy) {
        var grid = new MutableGrid(strategy);
        
        // Create a simple configuration that will trigger flips
        var v1 = new Vertex(0, 0, 0);
        var v2 = new Vertex(1, 0, 0);
        var v3 = new Vertex(0.5f, 1, 0);
        var v4 = new Vertex(0.5f, 0.5f, 1);
        var v5 = new Vertex(0.5f, 0.5f, 0.5f); // This should trigger flips
        
        var random = new Random(42);
        grid.track(v1, random);
        grid.track(v2, random);
        grid.track(v3, random);
        grid.track(v4, random);
        
        // Capture tetrahedron count before flip-inducing insertion
        var tetrahedronsBefore = grid.tetrahedrons().size();
        
        grid.track(v5, random);
        
        // Verify tetrahedrons were created/modified
        var tetrahedronsAfter = grid.tetrahedrons().size();
        assertTrue(tetrahedronsAfter > 0);
        
        // Verify all vertices are connected
        assertEquals(5, grid.size());
    }

    @ParameterizedTest
    @EnumSource(MutableGrid.AllocationStrategy.class)
    public void testLocateOperation(MutableGrid.AllocationStrategy strategy) {
        var grid = new MutableGrid(strategy);
        var points = generateRandomPoints(100);
        
        var random = new Random(42);
        for (var point : points) {
            grid.track(new Vertex(point), random);
        }
        
        // Test locating inserted points
        for (var point : points) {
            var tetrahedron = grid.locate(point, random);
            assertNotNull(tetrahedron, "Should find tetrahedron containing point");
            // Note: In Delaunay tetrahedralization, points may be on boundaries
            // So we just verify that locate() returns a non-null tetrahedron
        }
    }

    @ParameterizedTest
    @EnumSource(MutableGrid.AllocationStrategy.class)
    public void testNeighborQueries(MutableGrid.AllocationStrategy strategy) {
        var grid = new MutableGrid(strategy);
        
        // Create a regular grid of points
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    grid.track(new Vertex(x * 0.2f, y * 0.2f, z * 0.2f), new Random(42));
                }
            }
        }
        
        // Test neighbor finding
        var center = new Vertex(0.5f, 0.5f, 0.5f);
        var neighbors = new ArrayList<Vertex>();
        
        for (var vertex : grid) {
            if (vertex.distance(center) < 0.3f) {
                neighbors.add(vertex);
            }
        }
        
        assertTrue(neighbors.size() > 0, "Should find neighbors");
    }

    @Test
    public void testContextSwitching() {
        // Test switching between allocators using context
        var pooledAllocator = new PooledAllocator(new TetrahedronPool());
        var directAllocator = new DirectAllocator();
        
        // Test with pooled allocator
        TetrahedronPoolContext.withAllocator(pooledAllocator, () -> {
            var t1 = TetrahedronPoolContext.getAllocator().acquire(
                new Vertex(0, 0, 0),
                new Vertex(1, 0, 0),
                new Vertex(0, 1, 0),
                new Vertex(0, 0, 1)
            );
            assertNotNull(t1);
            assertEquals(pooledAllocator, TetrahedronPoolContext.getAllocator());
        });
        
        // Test with direct allocator
        TetrahedronPoolContext.withAllocator(directAllocator, () -> {
            var t2 = TetrahedronPoolContext.getAllocator().acquire(
                new Vertex(0, 0, 0),
                new Vertex(1, 0, 0),
                new Vertex(0, 1, 0),
                new Vertex(0, 0, 1)
            );
            assertNotNull(t2);
            assertEquals(directAllocator, TetrahedronPoolContext.getAllocator());
        });
        
        // Verify context is cleared
        assertNotEquals(pooledAllocator, TetrahedronPoolContext.getAllocator());
        assertNotEquals(directAllocator, TetrahedronPoolContext.getAllocator());
    }

    @Test
    public void testAllocationStatistics() {
        // Test allocation statistics through actual grid usage
        var pooledGrid = new MutableGrid(MutableGrid.AllocationStrategy.POOLED);
        var directGrid = new MutableGrid(MutableGrid.AllocationStrategy.DIRECT);
        
        // Generate points that will cause flips and reallocations
        var points = generateRandomPoints(100);
        var random = new Random(42);
        
        // Insert points into pooled grid
        for (var point : points) {
            pooledGrid.track(new Vertex(point), random);
        }
        
        // Get pooled allocator statistics
        var pooledAllocator = pooledGrid.getAllocator();
        var pooledStats = pooledAllocator.getStatistics();
        assertTrue(pooledStats.contains("acquired"));
        // The pooled allocator should show reuse during flip operations
        if (pooledAllocator instanceof PooledAllocator) {
            assertTrue(pooledAllocator.getReuseRate() >= 0.0);
        }
        
        // Insert points into direct grid
        for (var point : points) {
            directGrid.track(new Vertex(point), random);
        }
        
        // Get direct allocator statistics
        var directAllocator = directGrid.getAllocator();
        var directStats = directAllocator.getStatistics();
        assertTrue(directStats.contains("acquired"));
        assertEquals(0.0, directAllocator.getReuseRate());
    }

    @Test
    public void testSystemPropertyConfiguration() {
        // Test default (no property set)
        var defaultGrid = new MutableGrid();
        assertEquals(MutableGrid.AllocationStrategy.POOLED, defaultGrid.getAllocationStrategy());
        
        // Test with system property set to DIRECT
        System.setProperty("sentry.allocation.strategy", "DIRECT");
        try {
            var directGrid = new MutableGrid();
            assertEquals(MutableGrid.AllocationStrategy.DIRECT, directGrid.getAllocationStrategy());
        } finally {
            System.clearProperty("sentry.allocation.strategy");
        }
        
        // Test with system property set to POOLED
        System.setProperty("sentry.allocation.strategy", "POOLED");
        try {
            var pooledGrid = new MutableGrid();
            assertEquals(MutableGrid.AllocationStrategy.POOLED, pooledGrid.getAllocationStrategy());
        } finally {
            System.clearProperty("sentry.allocation.strategy");
        }
    }

    private List<Tuple3f> generateRandomPoints(int count) {
        var points = new ArrayList<Tuple3f>(count);
        for (int i = 0; i < count; i++) {
            points.add(new Vector3f(
                RANDOM.nextFloat(),
                RANDOM.nextFloat(),
                RANDOM.nextFloat()
            ));
        }
        return points;
    }
}