package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.luciferase.lucien.Tetree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3d;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Experimental test exploring tetrahedral coordinate concepts inspired by
 * https://gist.github.com/paniq/3afdb420b5d94bf99e36
 * 
 * This demonstrates how pure tetrahedral coordinate operations could enhance
 * our current Bey red refinement tetrahedral space-filling curve implementation.
 * 
 * @author hal.hildebrand
 */
public class TetrahedralCoordinateTest {
    
    private static final double DIVR2 = 1.0 / Math.sqrt(2.0);
    
    /**
     * Convert Cartesian coordinates to tetrahedral coordinates
     * Inspired by the coordinate transformation in the gist
     */
    private Vector3d cartesianToTetrahedral(Point3f cartesian) {
        // Simplified tetrahedral coordinate transformation
        // In a full implementation, this would use the complete transformation matrix
        double x = cartesian.x;
        double y = cartesian.y; 
        double z = cartesian.z;
        
        // Basic tetrahedral transformation (simplified from gist)
        double tx = (x + y + z) * DIVR2;
        double ty = (x - y) * DIVR2;
        double tz = (x + y - 2*z) * DIVR2 / Math.sqrt(3.0);
        
        return new Vector3d(tx, ty, tz);
    }
    
    /**
     * Calculate distance in tetrahedral space
     * Demonstrates tetrahedral-native distance calculation
     */
    private double tetrahedralDistance(Vector3d t1, Vector3d t2) {
        double dx = t1.x - t2.x;
        double dy = t1.y - t2.y;
        double dz = t1.z - t2.z;
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }
    
    /**
     * Calculate taxicab distance in tetrahedral space
     * Alternative distance metric from the gist
     */
    private double tetrahedralTaxicabDistance(Vector3d t1, Vector3d t2) {
        return Math.abs(t1.x - t2.x) + Math.abs(t1.y - t2.y) + Math.abs(t1.z - t2.z);
    }

    @Test
    @DisplayName("Test tetrahedral coordinate transformation concepts")
    void testTetrahedralCoordinateTransformation() {
        // Test points in Cartesian space
        Point3f origin = new Point3f(0.0f, 0.0f, 0.0f);
        Point3f point1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f point2 = new Point3f(200.0f, 200.0f, 200.0f);
        
        // Transform to tetrahedral coordinates
        Vector3d tetOrigin = cartesianToTetrahedral(origin);
        Vector3d tetPoint1 = cartesianToTetrahedral(point1);
        Vector3d tetPoint2 = cartesianToTetrahedral(point2);
        
        // Verify transformations produce different coordinates
        assertNotEquals(tetOrigin, tetPoint1);
        assertNotEquals(tetPoint1, tetPoint2);
        
        // Test distance calculations in tetrahedral space
        double euclideanDist = tetrahedralDistance(tetPoint1, tetPoint2);
        double taxicabDist = tetrahedralTaxicabDistance(tetPoint1, tetPoint2);
        
        assertTrue(euclideanDist > 0, "Euclidean distance should be positive");
        assertTrue(taxicabDist > 0, "Taxicab distance should be positive");
        assertTrue(taxicabDist >= euclideanDist, "Taxicab distance should be >= Euclidean distance");
    }

    @Test
    @DisplayName("Compare tetrahedral vs Cartesian spatial relationships")
    void compareTetrahedralVsCartesianSpatialRelationships() {
        Tetree<String> tetree = new Tetree<>(new TreeMap<>());
        
        // Test points that form a regular tetrahedron
        Point3f[] tetrahedronVertices = {
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(100.0f, 0.0f, 0.0f),
            new Point3f(50.0f, 86.6f, 0.0f),  // sqrt(3)/2 * 100 ≈ 86.6
            new Point3f(50.0f, 28.87f, 81.65f) // Regular tetrahedron height
        };
        
        Vector3d[] tetCoords = new Vector3d[tetrahedronVertices.length];
        for (int i = 0; i < tetrahedronVertices.length; i++) {
            tetCoords[i] = cartesianToTetrahedral(tetrahedronVertices[i]);
        }
        
        // Analyze spatial relationships in both coordinate systems
        byte level = 10;
        
        for (int i = 0; i < tetrahedronVertices.length; i++) {
            // Get tetrahedral cell containing this point
            Tet tet = tetree.locate(tetrahedronVertices[i], level);
            assertNotNull(tet, "Should locate tetrahedron for vertex " + i);
            
            // Verify the tet has a valid type (0-5 for Bey subdivision)
            assertTrue(tet.type() >= 0 && tet.type() <= 5, 
                "Tetrahedron type should be valid for vertex " + i);
        }
        
        // Calculate pairwise distances in both coordinate systems
        for (int i = 0; i < tetrahedronVertices.length; i++) {
            for (int j = i + 1; j < tetrahedronVertices.length; j++) {
                // Cartesian distance
                Point3f p1 = tetrahedronVertices[i];
                Point3f p2 = tetrahedronVertices[j];
                double cartesianDist = Math.sqrt(
                    Math.pow(p1.x - p2.x, 2) + 
                    Math.pow(p1.y - p2.y, 2) + 
                    Math.pow(p1.z - p2.z, 2)
                );
                
                // Tetrahedral distance
                double tetrahedralDist = tetrahedralDistance(tetCoords[i], tetCoords[j]);
                
                assertTrue(cartesianDist > 0, "Cartesian distance should be positive");
                assertTrue(tetrahedralDist > 0, "Tetrahedral distance should be positive");
                
                // The distances should be related but not necessarily equal
                // due to the coordinate transformation
            }
        }
    }

    @Test
    @DisplayName("Explore tetrahedral neighborhood concepts")
    void exploreTetrahedralNeighborhoodConcepts() {
        // The gist mentions 12-point vertex neighborhoods
        // Test how this might relate to our face neighbor operations
        
        Tet centerTet = new Tet(1000, 2000, 3000, (byte) 10, (byte) 0);
        
        // Test all 4 face neighbors (tetrahedral faces)
        for (int face = 0; face < 4; face++) {
            Tet.FaceNeighbor neighbor = centerTet.faceNeighbor(face);
            assertNotNull(neighbor, "Face neighbor should exist for face " + face);
            
            Tet neighborTet = neighbor.tet();
            
            // Convert both tetrahedra centers to tetrahedral coordinates
            Point3f centerPoint = new Point3f(centerTet.x(), centerTet.y(), centerTet.z());
            Point3f neighborPoint = new Point3f(neighborTet.x(), neighborTet.y(), neighborTet.z());
            
            Vector3d centerTetCoord = cartesianToTetrahedral(centerPoint);
            Vector3d neighborTetCoord = cartesianToTetrahedral(neighborPoint);
            
            // In tetrahedral coordinates, neighbors should have specific relationships
            double distance = tetrahedralDistance(centerTetCoord, neighborTetCoord);
            assertTrue(distance >= 0, "Neighbors should be at non-negative distance in tetrahedral space");
            
            // The distance should be finite and reasonable
            assertTrue(Double.isFinite(distance), "Distance should be finite");
        }
    }

    @Test
    @DisplayName("Test coordinate system consistency")
    void testCoordinateSystemConsistency() {
        // Test that our coordinate transformations are consistent
        Point3f originalPoint = new Point3f(123.45f, 67.89f, 234.56f);
        
        // Transform to tetrahedral and verify properties
        Vector3d tetCoord = cartesianToTetrahedral(originalPoint);
        
        // Basic consistency checks
        assertFalse(Double.isNaN(tetCoord.x), "Tetrahedral X should not be NaN");
        assertFalse(Double.isNaN(tetCoord.y), "Tetrahedral Y should not be NaN");
        assertFalse(Double.isNaN(tetCoord.z), "Tetrahedral Z should not be NaN");
        
        assertFalse(Double.isInfinite(tetCoord.x), "Tetrahedral X should not be infinite");
        assertFalse(Double.isInfinite(tetCoord.y), "Tetrahedral Y should not be infinite");
        assertFalse(Double.isInfinite(tetCoord.z), "Tetrahedral Z should not be infinite");
        
        // Test that the origin maps to a predictable location
        Vector3d tetOrigin = cartesianToTetrahedral(new Point3f(0, 0, 0));
        assertEquals(0.0, tetOrigin.x, 1e-10, "Origin should map to (0,0,0) in tetrahedral space");
        assertEquals(0.0, tetOrigin.y, 1e-10, "Origin should map to (0,0,0) in tetrahedral space");
        assertEquals(0.0, tetOrigin.z, 1e-10, "Origin should map to (0,0,0) in tetrahedral space");
    }
}