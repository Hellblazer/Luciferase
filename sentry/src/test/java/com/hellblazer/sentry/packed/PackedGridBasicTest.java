package com.hellblazer.sentry.packed;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for PackedGrid data structure
 */
public class PackedGridBasicTest {
    
    @Test
    public void testInitialization() {
        PackedGrid grid = new PackedGrid();
        
        // Should have 4 corner vertices
        assertEquals(4, grid.getVertexCount(), "Should have 4 corner vertices");
        
        // Should have 1 initial tetrahedron
        assertEquals(1, grid.getTetrahedronCapacity(), "Should have 1 initial tetrahedron");
        
        // Check the initial tetrahedron
        TetrahedronProxy tet0 = new TetrahedronProxy(grid, 0);
        assertTrue(tet0.isValid(), "Initial tetrahedron should be valid");
        
        // Should use corner vertices 0,1,2,3
        assertEquals(0, tet0.a());
        assertEquals(1, tet0.b());
        assertEquals(2, tet0.c());
        assertEquals(3, tet0.d());
        
        // Should have no neighbors (on convex hull)
        assertNull(tet0.neighborA());
        assertNull(tet0.neighborB());
        assertNull(tet0.neighborC());
        assertNull(tet0.neighborD());
    }
    
    @Test
    public void testVertexAddition() {
        PackedGrid grid = new PackedGrid();
        
        // Add a new vertex
        int v4 = grid.addVertex(10.0f, 20.0f, 30.0f);
        assertEquals(4, v4, "New vertex should have index 4");
        assertEquals(5, grid.getVertexCount(), "Should have 5 vertices total");
        
        // Verify coordinates
        float[] coords = new float[3];
        grid.getVertexCoords(v4, coords);
        assertEquals(10.0f, coords[0], 0.001f);
        assertEquals(20.0f, coords[1], 0.001f);
        assertEquals(30.0f, coords[2], 0.001f);
    }
    
    @Test
    public void testTetrahedronAllocation() {
        PackedGrid grid = new PackedGrid();
        
        // Allocate new tetrahedra
        int t1 = grid.allocateTetrahedron();
        assertEquals(1, t1, "Second tetrahedron should have index 1");
        
        int t2 = grid.allocateTetrahedron();
        assertEquals(2, t2, "Third tetrahedron should have index 2");
        
        assertEquals(3, grid.getTetrahedronCapacity(), "Should have capacity for 3 tetrahedra");
        
        // New tetrahedra should be invalid until initialized
        TetrahedronProxy tet1 = new TetrahedronProxy(grid, t1);
        assertEquals(-1, tet1.a(), "Uninitialized vertex should be -1");
    }
    
    @Test
    public void testTetrahedronDeletion() {
        PackedGrid grid = new PackedGrid();
        
        // Allocate and set up a tetrahedron
        int t1 = grid.allocateTetrahedron();
        grid.setVertex(t1, 0, 0);
        grid.setVertex(t1, 1, 1);
        grid.setVertex(t1, 2, 2);
        grid.setVertex(t1, 3, 4);
        
        TetrahedronProxy tet1 = new TetrahedronProxy(grid, t1);
        assertTrue(tet1.isValid(), "Tetrahedron should be valid before deletion");
        
        // Delete it
        grid.deleteTetrahedron(t1);
        assertFalse(tet1.isValid(), "Tetrahedron should be invalid after deletion");
        
        // All vertices should be -1
        assertEquals(-1, tet1.a());
        assertEquals(-1, tet1.b());
        assertEquals(-1, tet1.c());
        assertEquals(-1, tet1.d());
        
        // Should be reused on next allocation
        int t2 = grid.allocateTetrahedron();
        assertEquals(t1, t2, "Deleted tetrahedron slot should be reused");
    }
    
    @Test
    public void testNeighborRelationships() {
        PackedGrid grid = new PackedGrid();
        
        // Create two tetrahedra
        int t1 = grid.allocateTetrahedron();
        int t2 = grid.allocateTetrahedron();
        
        // Set up vertices (not important for this test)
        for (int i = 0; i < 4; i++) {
            grid.setVertex(t1, i, i);
            grid.setVertex(t2, i, i);
        }
        
        // Set bidirectional neighbor relationship
        // t1 face 0 <-> t2 face 2
        grid.setNeighbors(t1, 0, t2, 2);
        
        // Verify forward direction
        TetrahedronProxy tet1 = new TetrahedronProxy(grid, t1);
        TetrahedronProxy neighbor = tet1.neighborA();
        assertNotNull(neighbor);
        assertEquals(t2, neighbor.getIndex());
        
        // Verify reverse direction
        TetrahedronProxy tet2 = new TetrahedronProxy(grid, t2);
        neighbor = tet2.neighborC();
        assertNotNull(neighbor);
        assertEquals(t1, neighbor.getIndex());
    }
    
    @Test
    public void testOrientationCalculations() {
        PackedGrid grid = new PackedGrid();
        
        // The initial tetrahedron with corner vertices
        TetrahedronProxy tet0 = new TetrahedronProxy(grid, 0);
        
        // Test point at origin (should be inside)
        Point3f origin = new Point3f(0, 0, 0);
        
        // All orientations should be positive (point inside)
        assertTrue(tet0.orientationWrtA(origin) > 0, "Origin should be on positive side of face A");
        assertTrue(tet0.orientationWrtB(origin) > 0, "Origin should be on positive side of face B");
        assertTrue(tet0.orientationWrtC(origin) > 0, "Origin should be on positive side of face C");
        assertTrue(tet0.orientationWrtD(origin) > 0, "Origin should be on positive side of face D");
        
        // Test point far outside
        Point3f outside = new Point3f(1e8f, 1e8f, 1e8f);
        
        // At least one orientation should be negative (point outside)
        boolean hasNegative = false;
        for (int face = 0; face < 4; face++) {
            if (tet0.orientationWrt(face, outside) < 0) {
                hasNegative = true;
                break;
            }
        }
        assertTrue(hasNegative, "Point outside should have at least one negative orientation");
    }
    
    @Test
    public void testDeleteWithNeighbors() {
        PackedGrid grid = new PackedGrid();
        
        // Create three tetrahedra in a chain
        int t1 = grid.allocateTetrahedron();
        int t2 = grid.allocateTetrahedron();
        int t3 = grid.allocateTetrahedron();
        
        // Set up vertices
        for (int i = 0; i < 4; i++) {
            grid.setVertex(t1, i, i);
            grid.setVertex(t2, i, i);
            grid.setVertex(t3, i, i);
        }
        
        // Connect them: t1 <-> t2 <-> t3
        grid.setNeighbors(t1, 0, t2, 1);
        grid.setNeighbors(t2, 2, t3, 3);
        
        // Delete middle tetrahedron
        grid.deleteTetrahedron(t2);
        
        // t1 should no longer have t2 as neighbor
        TetrahedronProxy tet1 = new TetrahedronProxy(grid, t1);
        assertNull(tet1.neighborA(), "t1 should have no neighbor after t2 deletion");
        
        // t3 should no longer have t2 as neighbor
        TetrahedronProxy tet3 = new TetrahedronProxy(grid, t3);
        assertNull(tet3.neighborD(), "t3 should have no neighbor after t2 deletion");
    }
}