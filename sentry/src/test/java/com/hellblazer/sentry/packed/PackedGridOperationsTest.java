package com.hellblazer.sentry.packed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for basic tetrahedron operations
 */
public class PackedGridOperationsTest {
    
    @Test
    public void testCreateTetrahedron() {
        PackedGrid grid = new PackedGrid();
        
        // Add some vertices
        int v4 = grid.addVertex(1.0f, 2.0f, 3.0f);
        int v5 = grid.addVertex(4.0f, 5.0f, 6.0f);
        int v6 = grid.addVertex(7.0f, 8.0f, 9.0f);
        int v7 = grid.addVertex(10.0f, 11.0f, 12.0f);
        
        // Create a new tetrahedron
        int t1 = grid.createTetrahedron(v4, v5, v6, v7);
        assertEquals(1, t1, "Should be second tetrahedron (index 1)");
        
        // Verify vertices
        TetrahedronProxy tet = new TetrahedronProxy(grid, t1);
        assertEquals(v4, tet.a());
        assertEquals(v5, tet.b());
        assertEquals(v6, tet.c());
        assertEquals(v7, tet.d());
        
        // Test getVertex by ordinal
        assertEquals(v4, tet.getVertex(0));
        assertEquals(v5, tet.getVertex(1));
        assertEquals(v6, tet.getVertex(2));
        assertEquals(v7, tet.getVertex(3));
    }
    
    @Test
    public void testGetVertices() {
        PackedGrid grid = new PackedGrid();
        
        TetrahedronProxy tet0 = new TetrahedronProxy(grid, 0);
        int[] vertices = tet0.getVertices();
        
        assertEquals(4, vertices.length);
        assertEquals(0, vertices[0]); // Corner 0
        assertEquals(1, vertices[1]); // Corner 1
        assertEquals(2, vertices[2]); // Corner 2
        assertEquals(3, vertices[3]); // Corner 3
    }
    
    @Test
    public void testHasVertex() {
        PackedGrid grid = new PackedGrid();
        TetrahedronProxy tet0 = new TetrahedronProxy(grid, 0);
        
        // Test all four corners
        assertTrue(tet0.hasVertex(0));
        assertTrue(tet0.hasVertex(1));
        assertTrue(tet0.hasVertex(2));
        assertTrue(tet0.hasVertex(3));
        
        // Test non-existent vertex
        assertFalse(tet0.hasVertex(4));
        assertFalse(tet0.hasVertex(-1));
    }
    
    @Test
    public void testOrdinalOf() {
        PackedGrid grid = new PackedGrid();
        TetrahedronProxy tet0 = new TetrahedronProxy(grid, 0);
        
        // Test all four corners
        assertEquals(0, tet0.ordinalOf(0));
        assertEquals(1, tet0.ordinalOf(1));
        assertEquals(2, tet0.ordinalOf(2));
        assertEquals(3, tet0.ordinalOf(3));
        
        // Test non-existent vertex
        assertEquals(-1, tet0.ordinalOf(4));
        assertEquals(-1, tet0.ordinalOf(-1));
    }
    
    @Test
    public void testSetTetrahedronVertices() {
        PackedGrid grid = new PackedGrid();
        
        // Create a tetrahedron
        int t1 = grid.allocateTetrahedron();
        
        // Set all vertices at once
        grid.setTetrahedronVertices(t1, 3, 2, 1, 0);
        
        // Verify
        TetrahedronProxy tet = new TetrahedronProxy(grid, t1);
        assertEquals(3, tet.a());
        assertEquals(2, tet.b());
        assertEquals(1, tet.c());
        assertEquals(0, tet.d());
    }
    
    @Test
    public void testFindFaceWithVertices() {
        PackedGrid grid = new PackedGrid();
        TetrahedronProxy tet0 = new TetrahedronProxy(grid, 0);
        
        // Initial tetrahedron has vertices 0,1,2,3
        // Face 0 (opposite A/0): contains B,C,D = 1,2,3
        assertEquals(0, tet0.findFaceWithVertices(1, 2, 3));
        assertEquals(0, tet0.findFaceWithVertices(3, 1, 2)); // Different order
        assertEquals(0, tet0.findFaceWithVertices(2, 3, 1)); // Different order
        
        // Face 1 (opposite B/1): contains A,C,D = 0,2,3
        assertEquals(1, tet0.findFaceWithVertices(0, 2, 3));
        assertEquals(1, tet0.findFaceWithVertices(3, 0, 2));
        
        // Face 2 (opposite C/2): contains A,B,D = 0,1,3
        assertEquals(2, tet0.findFaceWithVertices(0, 1, 3));
        assertEquals(2, tet0.findFaceWithVertices(1, 3, 0));
        
        // Face 3 (opposite D/3): contains A,B,C = 0,1,2
        assertEquals(3, tet0.findFaceWithVertices(0, 1, 2));
        assertEquals(3, tet0.findFaceWithVertices(2, 0, 1));
        
        // Non-existent face
        assertEquals(-1, tet0.findFaceWithVertices(0, 1, 4)); // Vertex 4 doesn't exist
        assertEquals(-1, tet0.findFaceWithVertices(4, 5, 6)); // None of these vertices exist
    }
    
    @Test
    public void testTetrahedronCreationAndModification() {
        PackedGrid grid = new PackedGrid();
        
        // Add vertices for two tetrahedra
        int[] newVerts = new int[8];
        for (int i = 0; i < 8; i++) {
            newVerts[i] = grid.addVertex(i * 1.0f, i * 2.0f, i * 3.0f);
        }
        
        // Create two tetrahedra
        int t1 = grid.createTetrahedron(newVerts[0], newVerts[1], newVerts[2], newVerts[3]);
        int t2 = grid.createTetrahedron(newVerts[4], newVerts[5], newVerts[6], newVerts[7]);
        
        // Set them as neighbors
        grid.setNeighbors(t1, 0, t2, 1);
        
        // Verify neighbor relationship
        TetrahedronProxy tet1 = new TetrahedronProxy(grid, t1);
        TetrahedronProxy tet2 = new TetrahedronProxy(grid, t2);
        
        assertEquals(t2, tet1.neighborA().getIndex());
        assertEquals(t1, tet2.neighborB().getIndex());
        
        // Modify vertices
        grid.setVertex(t1, 0, newVerts[7]); // Change vertex A
        assertEquals(newVerts[7], tet1.a());
        
        // Verify tetrahedron count
        assertEquals(3, grid.getTetrahedronCapacity()); // Initial + 2 new ones
    }
}