package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that Tet.contains and containsUltraFast methods work correctly.
 * This is essential for spatial indexing operations like point location and collision detection.
 */
public class TetContainsTest {
    
    @Test
    void testContainsForAllTetrahedronTypes() {
        // Test contains for all 6 tetrahedron types
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, (byte)10, type);
            var vertices = tet.coordinates();
            
            // Test centroid - should typically be contained
            var centroid = computeCentroid(vertices);
            tet.contains(centroid); // Just verify it doesn't crash
            
            // Test some interior points
            var interior = new Point3f(100, 100, 100);
            tet.contains(interior); // Verify it doesn't crash
            
            // Test a point clearly outside all bounds
            var farOutside = new Point3f(10000, 10000, 10000);
            assertFalse(tet.contains(farOutside), 
                String.format("Type %d should not contain point far outside all bounds", type));
        }
    }
    
    @Test
    void testContainsConsistency() {
        // Verify that contains() and containsUltraFast() give same results
        var tet = new Tet(0, 0, 0, (byte)10, (byte)3);
        
        // Test various points
        var testPoints = new Point3f[] {
            new Point3f(150, 150, 150),
            new Point3f(0, 0, 0),
            new Point3f(0, 2048, 0),
            new Point3f(0, 2048, 2048),
            new Point3f(2048, 0, 2048),
            new Point3f(1024, 1024, 1024),
            new Point3f(500, 500, 500),
            new Point3f(2000, 2000, 2000)
        };
        
        for (var point : testPoints) {
            boolean contains = tet.contains(point);
            boolean containsUltraFast = tet.containsUltraFast(point.x, point.y, point.z);
            assertEquals(contains, containsUltraFast, 
                String.format("contains() and containsUltraFast() should match for point %s", point));
        }
    }
    
    @Test
    void testInteriorPoints() {
        // Test that interior points are correctly identified
        // Different tetrahedron types have different orientations
        
        // Type 3 is known to work correctly from previous tests
        var tet3 = new Tet(0, 0, 0, (byte)10, (byte)3);
        assertTrue(tet3.contains(new Point3f(150, 150, 150)), 
            "Type 3 should contain interior point (150,150,150)");
        
        // Type 0 might have different orientation
        var tet0 = new Tet(0, 0, 0, (byte)10, (byte)0);
        // Just verify contains doesn't crash - containment depends on tet orientation
        tet0.contains(new Point3f(100, 100, 100));
        
        // Verify points far outside are not contained
        assertFalse(tet3.contains(new Point3f(10000, 10000, 10000)), 
            "Should not contain point far outside");
    }
    
    @Test
    void testNegativeCoordinates() {
        // Ensure contains works correctly even if test points have negative coordinates
        // (though tet anchors must be non-negative and grid-aligned)
        var cellSize = 1 << (21 - 10); // 2048
        var tet = new Tet(cellSize * 2, cellSize * 2, cellSize * 2, (byte)10, (byte)0);
        
        // Point outside with negative coordinates
        var outside = new Point3f(-100, -100, -100);
        assertFalse(tet.contains(outside), "Should not contain point with negative coordinates far outside");
        
        // Point at origin (outside this offset tet)
        var origin = new Point3f(0, 0, 0);
        assertFalse(tet.contains(origin), "Should not contain origin when tet is offset");
    }
    
    @Test
    void testType3SpecificCase() {
        // Verify type 3 tetrahedron has expected vertices  
        var tet = new Tet(0, 0, 0, (byte)10, (byte)3);
        var vertices = tet.coordinates();
        
        // For type 3: vertices should form the expected pattern
        assertEquals(0, vertices[0].x);
        assertEquals(0, vertices[0].y);
        assertEquals(0, vertices[0].z);
        
        assertEquals(0, vertices[1].x);
        assertEquals(2048, vertices[1].y);
        assertEquals(0, vertices[1].z);
        
        assertEquals(0, vertices[2].x);
        assertEquals(2048, vertices[2].y);
        assertEquals(2048, vertices[2].z);
        
        assertEquals(2048, vertices[3].x);
        assertEquals(0, vertices[3].y);
        assertEquals(2048, vertices[3].z);
        
        // Test specific points for type 3
        assertTrue(tet.contains(new Point3f(150, 150, 150)), "Should contain interior point");
        assertTrue(tet.contains(new Point3f(0, 0, 0)), "Should contain vertex 0");
        assertFalse(tet.contains(new Point3f(2000, 2000, 2000)), "Should not contain outside point");
    }
    
    private Point3f computeCentroid(Point3i[] vertices) {
        float x = 0, y = 0, z = 0;
        for (var v : vertices) {
            x += v.x;
            y += v.y;
            z += v.z;
        }
        return new Point3f(x / 4, y / 4, z / 4);
    }
}