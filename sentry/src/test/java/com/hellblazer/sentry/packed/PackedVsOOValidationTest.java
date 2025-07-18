package com.hellblazer.sentry.packed;

import com.hellblazer.sentry.*;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests comparing packed implementation behavior with OO implementation
 */
public class PackedVsOOValidationTest {
    
    @Test
    public void testInitialTetrahedronEquivalence() {
        // OO implementation
        MutableGrid ooGrid = new MutableGrid();
        // Access initial tetrahedron through locate with a point we know is inside
        Point3f insidePoint = new Point3f(0, 0, 0);
        Tetrahedron ooTet = ooGrid.locate(insidePoint, null);
        assertNotNull(ooTet);
        
        // Packed implementation
        PackedGrid packedGrid = new PackedGrid();
        TetrahedronProxy packedTet = new TetrahedronProxy(packedGrid, 0);
        
        // Both should have the same vertices (the four corners)
        Vertex[] ooVerts = ooTet.getVertices();
        int[] packedVerts = packedTet.getVertices();
        
        assertEquals(4, ooVerts.length);
        assertEquals(4, packedVerts.length);
        
        // The initial tetrahedron uses all four corners (0,1,2,3)
        for (int i = 0; i < 4; i++) {
            assertEquals(i, packedVerts[i]);
        }
        
        // Check orientations match for a test point
        Point3f testPoint = new Point3f(0, 0, 0);
        
        // Compare face orientations
        assertEquals(Math.signum(ooTet.orientationWrt(V.A, testPoint)),
                     Math.signum(packedTet.orientationWrtA(testPoint)),
                     "Face A orientation should match");
        
        assertEquals(Math.signum(ooTet.orientationWrt(V.B, testPoint)),
                     Math.signum(packedTet.orientationWrtB(testPoint)),
                     "Face B orientation should match");
        
        assertEquals(Math.signum(ooTet.orientationWrt(V.C, testPoint)),
                     Math.signum(packedTet.orientationWrtC(testPoint)),
                     "Face C orientation should match");
        
        assertEquals(Math.signum(ooTet.orientationWrt(V.D, testPoint)),
                     Math.signum(packedTet.orientationWrtD(testPoint)),
                     "Face D orientation should match");
    }
    
    @Test
    public void testVertexCoordinatesMatch() {
        // Get the four corners from both implementations
        Vertex[] ooCorners = Grid.getFourCorners();
        
        PackedGrid packedGrid = new PackedGrid();
        
        // Check all four corner coordinates match
        float[] coords = new float[3];
        for (int i = 0; i < 4; i++) {
            packedGrid.getVertexCoords(i, coords);
            
            assertEquals(ooCorners[i].x, coords[0], 0.001f, 
                "X coordinate of corner " + i + " should match");
            assertEquals(ooCorners[i].y, coords[1], 0.001f,
                "Y coordinate of corner " + i + " should match");
            assertEquals(ooCorners[i].z, coords[2], 0.001f,
                "Z coordinate of corner " + i + " should match");
        }
    }
    
    @Test
    public void testOrientationConsistency() {
        // Test multiple points to ensure orientation calculations are consistent
        Point3f[] testPoints = {
            new Point3f(0, 0, 0),           // Origin
            new Point3f(100, 100, 100),     // Inside
            new Point3f(-1e6f, 0, 0),       // Far outside
            new Point3f(0, 1e6f, 0),        // Far outside
            new Point3f(0, 0, -1e6f),       // Far outside
            new Point3f(1e6f, 1e6f, 1e6f)   // Far outside
        };
        
        MutableGrid ooGrid = new MutableGrid();
        Point3f origin = new Point3f(0, 0, 0);
        Tetrahedron ooTet = ooGrid.locate(origin, null);
        
        PackedGrid packedGrid = new PackedGrid();
        TetrahedronProxy packedTet = new TetrahedronProxy(packedGrid, 0);
        
        for (Point3f p : testPoints) {
            // All four faces should have matching orientations
            for (int face = 0; face < 4; face++) {
                V v = V.values()[face];
                double ooOrient = Math.signum(ooTet.orientationWrt(v, p));
                double packedOrient = Math.signum(packedTet.orientationWrt(face, p));
                
                assertEquals(ooOrient, packedOrient, 
                    "Face " + face + " orientation should match for point " + p);
            }
        }
    }
    
    @Test
    public void testContainmentBehavior() {
        MutableGrid ooGrid = new MutableGrid();
        PackedGrid packedGrid = new PackedGrid();
        TetrahedronProxy packedTet = new TetrahedronProxy(packedGrid, 0);
        
        // Test points that should be inside the big tetrahedron
        Point3f[] insidePoints = {
            new Point3f(0, 0, 0),
            new Point3f(1000, 1000, 1000),
            new Point3f(-1000, -1000, -1000),
            new Point3f(5000, -3000, 2000)
        };
        
        for (Point3f p : insidePoints) {
            // Check if point is inside by testing all face orientations
            boolean ooInside = true;
            boolean packedInside = true;
            
            // In OO implementation
            Tetrahedron ooTet = ooGrid.locate(p, null);
            for (V v : V.values()) {
                if (ooTet.orientationWrt(v, p) < 0) {
                    ooInside = false;
                    break;
                }
            }
            
            // In packed implementation
            for (int face = 0; face < 4; face++) {
                if (packedTet.orientationWrt(face, p) < 0) {
                    packedInside = false;
                    break;
                }
            }
            
            assertEquals(ooInside, packedInside,
                "Containment should match for point " + p);
        }
    }
    
    @Test
    public void testNeighborManagement() {
        // Test that neighbor relationships work the same way
        MutableGrid ooGrid = new MutableGrid();
        PackedGrid packedGrid = new PackedGrid();
        
        // Create additional tetrahedra in both implementations
        // In OO, we'd normally do this through flip operations, but for testing
        // we'll manually create the structure
        
        // Add a vertex and create a second tetrahedron in packed
        int v4 = packedGrid.addVertex(0, 0, 0);
        int t1 = packedGrid.createTetrahedron(0, 1, 2, v4);
        
        // Set up neighbor relationship
        packedGrid.setNeighbors(0, 3, t1, 3); // Connect face 3 of both
        
        // Verify bidirectional relationship
        TetrahedronProxy tet0 = new TetrahedronProxy(packedGrid, 0);
        TetrahedronProxy tet1 = new TetrahedronProxy(packedGrid, t1);
        
        assertNotNull(tet0.neighborD());
        assertNotNull(tet1.neighborD());
        assertEquals(t1, tet0.neighborD().getIndex());
        assertEquals(0, tet1.neighborD().getIndex());
        
        // Delete one and verify cleanup
        packedGrid.deleteTetrahedron(t1);
        assertNull(tet0.neighborD(), "Neighbor should be cleared after deletion");
    }
}