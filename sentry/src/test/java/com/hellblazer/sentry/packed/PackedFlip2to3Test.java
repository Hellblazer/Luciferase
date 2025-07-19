package com.hellblazer.sentry.packed;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 2-to-3 flip operation
 */
public class PackedFlip2to3Test {
    
    @Test
    public void testFlip2to3BasicStructure() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        // First create a configuration with two tets sharing a face
        // We'll do a 1-to-4 flip first to get multiple tets
        int v4 = grid.addVertex(0, 0, 0);
        List<Integer> ears = new ArrayList<>();
        flips.flip1to4(0, v4, ears);
        
        // Now we have 4 tets. Two of them (let's say 1 and 2) share a face
        // We need to identify a shared face
        TetrahedronProxy tet1 = new TetrahedronProxy(grid, 1);
        TetrahedronProxy tet2 = new TetrahedronProxy(grid, 2);
        
        // Find the shared face between tet1 and tet2
        int sharedFace = -1;
        int neighborIndex = -1;
        for (int face = 0; face < 4; face++) {
            TetrahedronProxy neighbor = tet1.getNeighbor(face);
            if (neighbor != null && neighbor.getIndex() == 2) {
                sharedFace = face;
                neighborIndex = 2;
                break;
            }
        }
        
        if (sharedFace == -1) {
            // Try another pair
            for (int face = 0; face < 4; face++) {
                TetrahedronProxy neighbor = tet1.getNeighbor(face);
                if (neighbor != null && neighbor.getIndex() == 3) {
                    sharedFace = face;
                    neighborIndex = 3;
                    break;
                }
            }
        }
        
        assertTrue(sharedFace >= 0, "Should find a shared face");
        
        // Perform 2-to-3 flip on the shared face
        int encodedFace = 1 * 4 + sharedFace;
        int[] newTets = flips.flip2to3(encodedFace);
        
        // Should create 3 new tetrahedra
        assertEquals(3, newTets.length, "Should create 3 new tetrahedra");
        
        // All new tets should be valid
        for (int t : newTets) {
            assertTrue(grid.isValidTetrahedron(t), "New tet " + t + " should be valid");
        }
        
        // Original tets should be deleted
        assertFalse(grid.isValidTetrahedron(1), "Original tet 1 should be deleted");
        assertFalse(grid.isValidTetrahedron(neighborIndex), 
            "Adjacent tet " + neighborIndex + " should be deleted");
    }
    
    @Test  
    public void testFlip2to3CreatesProperConnectivity() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        // Create a more controlled setup
        // Add vertices for two adjacent tetrahedra
        int v4 = grid.addVertex(0, 0, 100);
        int v5 = grid.addVertex(0, 0, -100);
        
        // Create two tets that share face 0,1,2
        int t1 = grid.createTetrahedron(0, 1, 2, v4);
        int t2 = grid.createTetrahedron(0, 1, 2, v5);
        
        // Connect them
        grid.setNeighbors(t1, 3, t2, 3); // Both share face opposite vertex 3
        
        // Perform flip
        int encodedFace = t1 * 4 + 3;
        int[] newTets = flips.flip2to3(encodedFace);
        
        // The three new tets should share the edge from v4 to v5
        // Each pair should be neighbors
        int neighborCount = 0;
        for (int i = 0; i < 3; i++) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, newTets[i]);
            for (int face = 0; face < 4; face++) {
                TetrahedronProxy neighbor = tet.getNeighbor(face);
                if (neighbor != null) {
                    for (int j = 0; j < 3; j++) {
                        if (neighbor.getIndex() == newTets[j] && i != j) {
                            neighborCount++;
                        }
                    }
                }
            }
        }
        
        // Each tet should have 2 neighbors among the new tets
        // Total neighbor relationships = 3 * 2 / 2 = 3
        assertTrue(neighborCount >= 3, 
            "New tets should be properly connected");
    }
    
    @Test
    public void testFlip2to3PreservesExternalNeighbors() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        // Create setup with external neighbors
        int v4 = grid.addVertex(0, 0, 100);
        int v5 = grid.addVertex(0, 0, -100);
        int v6 = grid.addVertex(100, 0, 0);
        
        // Create the two tets to flip
        int t1 = grid.createTetrahedron(0, 1, 2, v4);
        int t2 = grid.createTetrahedron(0, 1, 2, v5);
        grid.setNeighbors(t1, 3, t2, 3);
        
        // Create external neighbors
        int ext1 = grid.createTetrahedron(0, 1, v4, v6);
        int ext2 = grid.createTetrahedron(1, 2, v5, v6);
        
        // Connect external neighbors
        grid.setNeighbors(t1, 0, ext1, 2); // t1 face 0 to ext1 face 2
        grid.setNeighbors(t2, 1, ext2, 0); // t2 face 1 to ext2 face 0
        
        // Perform flip
        int encodedFace = t1 * 4 + 3;
        int[] newTets = flips.flip2to3(encodedFace);
        
        // Check that external neighbors are still connected
        boolean foundExt1Connection = false;
        boolean foundExt2Connection = false;
        
        for (int t : newTets) {
            for (int face = 0; face < 4; face++) {
                int neighbor = grid.getNeighbor(t, face);
                if (neighbor == ext1) foundExt1Connection = true;
                if (neighbor == ext2) foundExt2Connection = true;
            }
        }
        
        assertTrue(foundExt1Connection, 
            "External neighbor 1 should still be connected");
        assertTrue(foundExt2Connection, 
            "External neighbor 2 should still be connected");
    }
    
    @Test
    public void testFlip2to3EdgeSharing() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        // Create controlled setup
        int v4 = grid.addVertex(0, 0, 100);
        int v5 = grid.addVertex(0, 0, -100);
        
        int t1 = grid.createTetrahedron(0, 1, 2, v4);
        int t2 = grid.createTetrahedron(0, 1, 2, v5);
        grid.setNeighbors(t1, 3, t2, 3);
        
        // Perform flip
        int encodedFace = t1 * 4 + 3;
        int[] newTets = flips.flip2to3(encodedFace);
        
        // All three new tets should share the edge v4-v5
        for (int t : newTets) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, t);
            assertTrue(tet.hasVertex(v4), 
                "New tet " + t + " should contain vertex v4");
            assertTrue(tet.hasVertex(v5), 
                "New tet " + t + " should contain vertex v5");
        }
        
        // Each new tet should have exactly 2 vertices from the shared face
        int[] sharedFaceVerts = {0, 1, 2};
        for (int t : newTets) {
            TetrahedronProxy tet = new TetrahedronProxy(grid, t);
            int count = 0;
            for (int v : sharedFaceVerts) {
                if (tet.hasVertex(v)) count++;
            }
            assertEquals(2, count, 
                "Each new tet should have exactly 2 vertices from shared face");
        }
    }
}