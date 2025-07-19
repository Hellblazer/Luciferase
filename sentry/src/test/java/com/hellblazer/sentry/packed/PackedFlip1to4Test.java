package com.hellblazer.sentry.packed;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 1-to-4 flip operation
 */
public class PackedFlip1to4Test {
    
    @Test
    public void testFlip1to4BasicStructure() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        // Add a vertex to insert into the initial tetrahedron
        int newVertex = grid.addVertex(0, 0, 0);
        
        // Perform 1-to-4 flip on the initial tetrahedron
        List<Integer> ears = new ArrayList<>();
        int result = flips.flip1to4(0, newVertex, ears);
        
        // Should return a valid tetrahedron index
        assertTrue(result > 0, "Should return valid tetrahedron index");
        
        // Original tetrahedron should be deleted
        assertFalse(grid.isValidTetrahedron(0), "Original tetrahedron should be deleted");
        
        // Should create 4 new tetrahedra (indices 1, 2, 3, 4)
        for (int i = 1; i <= 4; i++) {
            assertTrue(grid.isValidTetrahedron(i), "Tetrahedron " + i + " should exist");
        }
        
        // Each new tetrahedron should contain the new vertex
        for (int i = 1; i <= 4; i++) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, i);
            assertTrue(tet.hasVertex(newVertex), 
                "Tetrahedron " + i + " should contain new vertex");
        }
        
        // Initial tetrahedron has no neighbors, so ears should be empty
        assertEquals(0, ears.size(), "Should have 0 faces in ears list for initial flip");
    }
    
    @Test
    public void testFlip1to4NeighborConnectivity() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        int newVertex = grid.addVertex(0, 0, 0);
        List<Integer> ears = new ArrayList<>();
        flips.flip1to4(0, newVertex, ears);
        
        // Check internal connectivity between new tetrahedra
        // Each new tet should have 3 internal neighbors
        for (int i = 1; i <= 4; i++) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, i);
            int internalNeighbors = 0;
            
            for (int face = 0; face < 4; face++) {
                TetrahedronProxy neighbor = tet.getNeighbor(face);
                if (neighbor != null) {
                    int nIdx = neighbor.getIndex();
                    if (nIdx >= 1 && nIdx <= 4) {
                        internalNeighbors++;
                    }
                }
            }
            
            assertEquals(3, internalNeighbors, 
                "Tetrahedron " + i + " should have 3 internal neighbors");
        }
    }
    
    @Test
    public void testFlip1to4VertexDistribution() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        // The initial tetrahedron has vertices 0,1,2,3 (the four corners)
        int newVertex = grid.addVertex(0, 0, 0);
        List<Integer> ears = new ArrayList<>();
        flips.flip1to4(0, newVertex, ears);
        
        // Each original face should be preserved in one of the new tets
        // Face ABC (opposite D/3) should be in one tet
        // Face ABD (opposite C/2) should be in another
        // Face ACD (opposite B/1) should be in another
        // Face BCD (opposite A/0) should be in another
        
        // Count how many times each original vertex appears
        int[] vertexCounts = new int[4];
        for (int i = 1; i <= 4; i++) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, i);
            for (int v = 0; v < 4; v++) {
                if (tet.hasVertex(v)) {
                    vertexCounts[v]++;
                }
            }
        }
        
        // Each original vertex should appear in exactly 3 of the new tets
        for (int v = 0; v < 4; v++) {
            assertEquals(3, vertexCounts[v], 
                "Original vertex " + v + " should appear in 3 new tetrahedra");
        }
    }
    
    @Test
    public void testFlip1to4PreservesOrientation() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        // Test point inside the original tetrahedron, offset to avoid exact coplanarity
        Point3f testPoint = new Point3f(123.456f, 234.567f, 345.678f);
        
        // Verify it's inside the original
        TetrahedronProxy original = new TetrahedronProxy(grid, 0);
        boolean insideOriginal = true;
        for (int face = 0; face < 4; face++) {
            if (original.orientationWrt(face, testPoint) < 0) {
                insideOriginal = false;
                break;
            }
        }
        assertTrue(insideOriginal, "Test point should be inside original tetrahedron");
        
        // Perform flip
        int newVertex = grid.addVertex(50, 50, 50); // Inside the tet
        List<Integer> ears = new ArrayList<>();
        flips.flip1to4(0, newVertex, ears);
        
        // The test point should be inside at least one and at most one of the new tets
        // (allowing for numerical precision issues)
        int containingTets = 0;
        int tetContaining = -1;
        for (int i = 1; i <= 4; i++) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, i);
            boolean inside = true;
            for (int face = 0; face < 4; face++) {
                // Use a small epsilon for numerical tolerance
                if (tet.orientationWrt(face, testPoint) < -1e-10) {
                    inside = false;
                    break;
                }
            }
            if (inside) {
                containingTets++;
                tetContaining = i;
            }
        }
        
        assertTrue(containingTets >= 1, 
            "Test point should be inside at least one new tetrahedron");
        
        // Also verify the new tetrahedra partition the space correctly
        // by checking that the new vertex is inside all four new tets
        Point3f newVertexPoint = new Point3f(50, 50, 50);
        for (int i = 1; i <= 4; i++) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, i);
            // The new vertex should be on the positive side of all faces
            // except the one where it IS the vertex (face opposite to it)
            for (int face = 0; face < 4; face++) {
                if (face != 3) { // face 3 is opposite vertex D (the new vertex)
                    double orient = tet.orientationWrt(face, newVertexPoint);
                    assertTrue(orient >= 0, 
                        "New vertex should be on positive side of face " + face + " in tet " + i);
                }
            }
        }
    }
    
    @Test
    public void testFlip1to4WithExternalNeighbor() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        // Create an external tetrahedron
        int v4 = grid.addVertex(0, 0, 0);
        int external = grid.createTetrahedron(0, 1, 2, v4);
        
        // Connect it to face 3 of initial tet
        grid.setNeighbors(0, 3, external, 3);
        
        // Perform flip on initial tet
        List<Integer> ears = new ArrayList<>();
        int result = flips.flip1to4(0, v4, ears);
        
        // One of the new tets should be connected to the external tet
        boolean foundExternalConnection = false;
        for (int i = 1; i <= 4; i++) {
            for (int face = 0; face < 4; face++) {
                if (grid.getNeighbor(i, face) == external) {
                    foundExternalConnection = true;
                    // Verify bidirectional connection
                    boolean foundReverse = false;
                    for (int f = 0; f < 4; f++) {
                        if (grid.getNeighbor(external, f) == i) {
                            foundReverse = true;
                            break;
                        }
                    }
                    assertTrue(foundReverse, 
                        "External neighbor should have reverse connection");
                    break;
                }
            }
            if (foundExternalConnection) break;
        }
        
        assertTrue(foundExternalConnection, 
            "One new tet should connect to external neighbor");
    }
}