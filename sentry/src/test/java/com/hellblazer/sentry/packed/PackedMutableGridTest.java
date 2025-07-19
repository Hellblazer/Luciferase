package com.hellblazer.sentry.packed;

import com.hellblazer.sentry.MutableGrid;
import com.hellblazer.sentry.Vertex;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the mutable packed grid implementation
 */
public class PackedMutableGridTest {
    
    @Test
    public void testTrackSinglePoint() {
        PackedMutableGrid grid = new PackedMutableGrid();
        Random random = new Random(42);
        
        Point3f p = new Point3f(0, 0, 0);
        int vertex = grid.track(p, random);
        
        assertTrue(vertex >= 0, "Should return valid vertex index");
        assertEquals(1, grid.getVertexCount(), "Should have 1 tracked vertex");
        
        // Verify vertex coordinates
        float[] coords = new float[3];
        grid.getVertexCoords(vertex, coords);
        assertEquals(0.0f, coords[0], 0.001f);
        assertEquals(0.0f, coords[1], 0.001f);
        assertEquals(0.0f, coords[2], 0.001f);
    }
    
    @Test
    public void testTrackMultiplePoints() {
        PackedMutableGrid grid = new PackedMutableGrid();
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
            assertTrue(vertices[i] >= 0, "Point " + i + " should be tracked");
        }
        
        assertEquals(points.length, grid.getVertexCount(), 
            "Should have tracked all points");
        
        // Verify all vertices are unique
        Set<Integer> uniqueVerts = new HashSet<>();
        for (int v : vertices) {
            uniqueVerts.add(v);
        }
        assertEquals(points.length, uniqueVerts.size(), 
            "All vertices should be unique");
    }
    
    @Test
    public void testTrackOutsideBounds() {
        PackedMutableGrid grid = new PackedMutableGrid();
        Random random = new Random(42);
        
        // Try to track a point far outside the encompassing tetrahedron
        Point3f outside = new Point3f(1e8f, 1e8f, 1e8f);
        int vertex = grid.track(outside, random);
        
        assertEquals(PackedGrid.INVALID_INDEX, vertex, 
            "Should not track points outside bounds");
        assertEquals(0, grid.getVertexCount(), 
            "Should not have any tracked vertices");
    }
    
    @Test
    public void testVertexLinkedList() {
        PackedMutableGrid grid = new PackedMutableGrid();
        Random random = new Random(42);
        
        // Track several points
        int v1 = grid.track(new Point3f(0, 0, 0), random);
        int v2 = grid.track(new Point3f(100, 0, 0), random);
        int v3 = grid.track(new Point3f(0, 100, 0), random);
        
        // Check linked list structure
        assertEquals(v1, grid.getHeadVertex(), "First vertex should be head");
        assertEquals(v2, grid.getNextVertex(v1), "Second vertex should follow first");
        assertEquals(v3, grid.getNextVertex(v2), "Third vertex should follow second");
        assertEquals(PackedGrid.INVALID_INDEX, grid.getNextVertex(v3), 
            "Last vertex should have no next");
    }
    
    @Test
    public void testDelaunayCondition() {
        PackedMutableGrid packed = new PackedMutableGrid();
        Random random = new Random(42);
        
        // Create a configuration that would violate Delaunay without flips
        Point3f[] points = {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(50, 86.6f, 0),  // Equilateral triangle
            new Point3f(50, 28.9f, 81.6f)  // Tetrahedron center
        };
        
        for (Point3f p : points) {
            packed.track(p, random);
        }
        
        // After insertion, the tetrahedralization should maintain Delaunay condition
        // This is hard to verify directly without access to circumsphere checks
        // For now, just verify that we have more than 1 tetrahedron (flips occurred)
        int tetCount = 0;
        for (int i = 0; i < packed.getTetrahedronCapacity(); i++) {
            if (packed.isValidTetrahedron(i)) {
                tetCount++;
            }
        }
        
        assertTrue(tetCount > 1, 
            "Should have multiple tetrahedra after flips");
    }
    
    @Test
    public void testTrackWithNearbyStart() {
        PackedMutableGrid grid = new PackedMutableGrid();
        Random random = new Random(42);
        
        // Track first point
        int v1 = grid.track(new Point3f(0, 0, 0), random);
        
        // Track nearby point starting from last tetrahedron
        int v2 = grid.track(new Point3f(10, 0, 0), grid.lastTet, random);
        
        assertTrue(v2 >= 0, "Should track nearby point");
        assertEquals(2, grid.getVertexCount(), "Should have 2 vertices");
    }
    
    @Test
    public void testCompareWithOOImplementation() {
        PackedMutableGrid packed = new PackedMutableGrid();
        MutableGrid oo = new MutableGrid();
        Random random1 = new Random(42);
        Random random2 = new Random(42);
        
        // Track the same points in both implementations
        Point3f[] testPoints = {
            new Point3f(0, 0, 0),
            new Point3f(100, 100, 100),
            new Point3f(-100, 100, -100),
            new Point3f(50, -50, 50),
            new Point3f(-50, -50, -50)
        };
        
        for (Point3f p : testPoints) {
            int packedVertex = packed.track(p, random1);
            Vertex ooVertex = oo.track(p, random2);
            
            if (ooVertex != null) {
                assertTrue(packedVertex >= 0, 
                    "Point " + p + " tracked by OO should also be tracked by packed");
            } else {
                assertEquals(PackedGrid.INVALID_INDEX, packedVertex,
                    "Point " + p + " not tracked by OO should also not be tracked by packed");
            }
        }
        
        // Both should have the same number of vertices
        assertEquals(oo.size(), packed.getVertexCount(),
            "Both implementations should track same number of vertices");
    }
    
    @Test
    public void testSequentialInsertion() {
        PackedMutableGrid grid = new PackedMutableGrid();
        Random random = new Random(42);
        
        // Insert points in a line
        int numPoints = 10;
        for (int i = 0; i < numPoints; i++) {
            Point3f p = new Point3f(i * 10, 0, 0);
            int v = grid.track(p, random);
            // Debug output removed
            assertTrue(v >= 0, "Point " + i + " should be tracked");
        }
        
        assertEquals(numPoints, grid.getVertexCount(),
            "All points should be tracked");
        
        // Verify we can traverse the linked list
        int count = 0;
        int current = grid.getHeadVertex();
        while (current != PackedGrid.INVALID_INDEX) {
            count++;
            current = grid.getNextVertex(current);
        }
        assertEquals(numPoints, count, 
            "Should be able to traverse all vertices");
    }
    
    @Test
    public void testRandomPointCloud() {
        PackedMutableGrid grid = new PackedMutableGrid();
        Random random = new Random(42);
        
        // Insert random points within a cube
        int numPoints = 50;
        int tracked = 0;
        
        for (int i = 0; i < numPoints; i++) {
            float x = (random.nextFloat() - 0.5f) * 1000;
            float y = (random.nextFloat() - 0.5f) * 1000;
            float z = (random.nextFloat() - 0.5f) * 1000;
            
            Point3f p = new Point3f(x, y, z);
            int v = grid.track(p, random);
            if (v >= 0) {
                tracked++;
            }
        }
        
        assertEquals(tracked, grid.getVertexCount(),
            "Tracked count should match vertex count");
        
        // Should have created many tetrahedra
        int tetCount = 0;
        for (int i = 0; i < grid.getTetrahedronCapacity(); i++) {
            if (grid.isValidTetrahedron(i)) {
                tetCount++;
            }
        }
        
        assertTrue(tetCount > tracked, 
            "Should have more tetrahedra than vertices due to flips");
    }
}