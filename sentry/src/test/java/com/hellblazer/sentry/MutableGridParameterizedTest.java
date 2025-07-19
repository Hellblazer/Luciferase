/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi system
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.sentry;

import com.hellblazer.sentry.packed.PackedMutableGrid;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import javax.vecmath.Point3f;
import java.util.Random;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized tests that run against both OO and packed implementations
 * to ensure consistent behavior.
 */
public class MutableGridParameterizedTest {
    
    /**
     * Abstraction layer to unify both implementations
     */
    interface GridAdapter {
        String getImplementationName();
        int track(Point3f p, Random random);
        int getVertexCount();
        boolean isValidVertex(int vertex);
        void getVertexCoords(int vertex, float[] coords);
    }
    
    static class OOGridAdapter implements GridAdapter {
        private final MutableGrid grid = new MutableGrid();
        private int vertexCounter = 0;
        
        @Override
        public String getImplementationName() {
            return "OO";
        }
        
        @Override
        public int track(Point3f p, Random random) {
            Vertex v = grid.track(p, random);
            // Return a simple counter as ID since Vertex doesn't expose an ID
            return v != null ? vertexCounter++ : -1;
        }
        
        @Override
        public int getVertexCount() {
            return grid.size();
        }
        
        @Override
        public boolean isValidVertex(int vertex) {
            return vertex >= 0;
        }
        
        @Override
        public void getVertexCoords(int vertex, float[] coords) {
            // In OO implementation, we need to traverse to find the vertex
            // This is a simplification - in real use you'd maintain a mapping
            coords[0] = 0;
            coords[1] = 0; 
            coords[2] = 0;
        }
    }
    
    static class PackedGridAdapter implements GridAdapter {
        private final PackedMutableGrid grid = new PackedMutableGrid();
        
        @Override
        public String getImplementationName() {
            return "Packed";
        }
        
        @Override
        public int track(Point3f p, Random random) {
            return grid.track(p, random);
        }
        
        @Override
        public int getVertexCount() {
            return grid.getVertexCount();
        }
        
        @Override
        public boolean isValidVertex(int vertex) {
            return vertex >= 0;
        }
        
        @Override
        public void getVertexCoords(int vertex, float[] coords) {
            grid.getVertexCoords(vertex, coords);
        }
    }
    
    static Stream<GridAdapter> gridImplementations() {
        return Stream.of(
            new OOGridAdapter(),
            new PackedGridAdapter()
        );
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("gridImplementations")
    public void testTrackSinglePoint(GridAdapter grid) {
        Random random = new Random(42);
        
        Point3f p = new Point3f(0, 0, 0);
        int vertex = grid.track(p, random);
        
        assertTrue(grid.isValidVertex(vertex), 
            grid.getImplementationName() + ": Should return valid vertex index");
        assertEquals(1, grid.getVertexCount(), 
            grid.getImplementationName() + ": Should have 1 tracked vertex");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("gridImplementations")
    public void testTrackMultiplePoints(GridAdapter grid) {
        Random random = new Random(42);
        
        // Track several points
        Point3f[] points = {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(0, 0, 100),
            new Point3f(50, 50, 50)
        };
        
        int[] vertices = new int[points.length];
        for (int i = 0; i < points.length; i++) {
            vertices[i] = grid.track(points[i], random);
            assertTrue(grid.isValidVertex(vertices[i]), 
                grid.getImplementationName() + ": Point " + i + " should be tracked");
        }
        
        assertEquals(points.length, grid.getVertexCount(), 
            grid.getImplementationName() + ": Should have tracked all points");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("gridImplementations")
    public void testTrackOutsideBounds(GridAdapter grid) {
        Random random = new Random(42);
        
        // Try to track a point far outside the encompassing tetrahedron
        Point3f outside = new Point3f(1e8f, 1e8f, 1e8f);
        int vertex = grid.track(outside, random);
        
        assertFalse(grid.isValidVertex(vertex), 
            grid.getImplementationName() + ": Should not track points outside bounds");
        assertEquals(0, grid.getVertexCount(), 
            grid.getImplementationName() + ": Should not have any tracked vertices");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("gridImplementations")
    public void testSequentialInsertion(GridAdapter grid) {
        Random random = new Random(42);
        
        // Insert points in a line with adequate spacing
        int numPoints = 10;
        for (int i = 0; i < numPoints; i++) {
            Point3f p = new Point3f(i * 100, 0, 0);
            int v = grid.track(p, random);
            assertTrue(grid.isValidVertex(v), 
                grid.getImplementationName() + ": Point " + i + " should be tracked");
        }
        
        assertEquals(numPoints, grid.getVertexCount(),
            grid.getImplementationName() + ": All points should be tracked");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("gridImplementations")
    public void testRandomPointCloud(GridAdapter grid) {
        Random random = new Random(42);
        
        // Insert random points within a cube
        int numPoints = 50;
        int tracked = 0;
        
        for (int i = 0; i < numPoints; i++) {
            float x = (random.nextFloat() - 0.5f) * 1000;
            float y = (random.nextFloat() - 0.5f) * 1000;
            float z = (random.nextFloat() - 0.5f) * 1000;
            
            Point3f p = new Point3f(x, y, z);
            int v = track(p, random);
            if (isValidVertex(v)) {
                tracked++;
            }
        }
        
        assertEquals(tracked, grid.getVertexCount(),
            grid.getImplementationName() + ": Tracked count should match vertex count");
        
        assertTrue(tracked > 0,
            grid.getImplementationName() + ": Should have tracked at least some points");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("gridImplementations")
    public void testDegenerateConfiguration(GridAdapter grid) {
        Random random = new Random(42);
        
        // Test coplanar points
        Point3f[] coplanar = {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(100, 100, 0)
        };
        
        for (Point3f p : coplanar) {
            int v = grid.track(p, random);
            assertTrue(grid.isValidVertex(v),
                grid.getImplementationName() + ": Should handle coplanar points");
        }
        
        // Add a non-coplanar point
        int v = grid.track(new Point3f(50, 50, 100), random);
        assertTrue(grid.isValidVertex(v),
            grid.getImplementationName() + ": Should handle transition from coplanar");
        
        assertEquals(5, grid.getVertexCount(),
            grid.getImplementationName() + ": All points should be tracked");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("gridImplementations")
    public void testVeryClosePoints(GridAdapter grid) {
        Random random = new Random(42);
        
        // Test points that are very close together
        float epsilon = 0.001f;
        Point3f[] closePoints = {
            new Point3f(0, 0, 0),
            new Point3f(epsilon, 0, 0),
            new Point3f(0, epsilon, 0),
            new Point3f(0, 0, epsilon)
        };
        
        int trackedCount = 0;
        for (Point3f p : closePoints) {
            int v = track(p, random);
            if (isValidVertex(v)) {
                trackedCount++;
            }
        }
        
        // Both implementations should handle very close points
        // though they might differ in exact behavior
        assertTrue(trackedCount > 0,
            grid.getImplementationName() + ": Should track at least some close points");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("gridImplementations")
    public void testSequentialInsertionSmallSpacing(GridAdapter grid) {
        Random random = new Random(42);
        
        // This is the problematic test case - points 10 units apart
        int numPoints = 10;
        int tracked = 0;
        
        for (int i = 0; i < numPoints; i++) {
            Point3f p = new Point3f(i * 10, 0, 0);
            int v = track(p, random);
            if (isValidVertex(v)) {
                tracked++;
            }
        }
        
        // Both implementations should now track all points
        assertEquals(numPoints, tracked,
            grid.getImplementationName() + " implementation should track all points");
    }
}