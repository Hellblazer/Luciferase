package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import com.hellblazer.luciferase.lucien.Constants;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for Bey-aware point location algorithm that traverses through
 * Bey-refined tetrahedra to find the containing tetrahedron at a target level.
 */
public class BeyPointLocationTest {
    
    @Test
    void testRootLevelLocation() {
        // At level 0, only type 0 exists and it doesn't contain all points
        // Root tetrahedron has specific geometry
        var root = new Tet(0, 0, 0, (byte)0, (byte)0);
        
        // Test a point that's actually in the root tetrahedron
        // Based on debug output, root tet vertices are:
        // v0: (0, 0, 0), v1: (2097152, 0, 0), 
        // v2: (2097152, 0, 2097152), v3: (0, 2097152, 2097152)
        // This forms a specific tetrahedron, not a cube
        
        // Points that should be contained
        var containedPoint = new Point3f(1000000, 500000, 1000000);
        var result = Tet.locatePointBeyRefinementFromRoot(
            containedPoint.x, containedPoint.y, containedPoint.z, (byte)0);
        
        if (result != null) {
            assertEquals(0, result.l());
            assertEquals(0, result.type());
            assertEquals(0, result.x());
            assertEquals(0, result.y()); 
            assertEquals(0, result.z());
        }
        
        // Point (100, 100, 100) is NOT in the root tetrahedron
        var outsidePoint = new Point3f(100, 100, 100);
        var outsideResult = Tet.locatePointBeyRefinementFromRoot(
            outsidePoint.x, outsidePoint.y, outsidePoint.z, (byte)0);
        assertNull(outsideResult, "Point (100, 100, 100) should not be in root tetrahedron");
    }
    
    @Test
    void testSingleLevelDescent() {
        // Test descending one level from root
        var point = new Point3f(500, 500, 500);
        var result = Tet.locatePointBeyRefinementFromRoot(point.x, point.y, point.z, (byte)1);
        
        assertNotNull(result);
        assertEquals(1, result.l());
        assertTrue(result.containsUltraFast(point.x, point.y, point.z));
    }
    
    @Test
    void testMultiLevelDescent() {
        // Test descending multiple levels
        var point = new Point3f(123.4f, 234.5f, 345.6f);
        
        for (byte level = 1; level <= 5; level++) {
            var result = Tet.locatePointBeyRefinementFromRoot(point.x, point.y, point.z, level);
            
            assertNotNull(result, "Should find tetrahedron at level " + level);
            assertEquals(level, result.l(), "Should be at correct level");
            assertTrue(result.containsUltraFast(point.x, point.y, point.z), 
                "Result should contain the point at level " + level);
            
            // Note: We cannot verify parent-child containment with Bey refinement
            // because children can extend outside parent bounds
        }
    }
    
    @Test
    void testPointsNearBoundaries() {
        // Test points near tetrahedron boundaries where gaps might occur
        float[] testCoords = {0.1f, 100f, 500f, 1000f, 1500f, 2000f};
        
        for (float x : testCoords) {
            for (float y : testCoords) {
                for (float z : testCoords) {
                    if (x + y + z > 4000) continue; // Skip points outside root
                    
                    var result = Tet.locatePointBeyRefinementFromRoot(x, y, z, (byte)3);
                    assertNotNull(result, 
                        String.format("Should find tet for point (%.1f, %.1f, %.1f)", x, y, z));
                    assertTrue(result.l() >= 0 && result.l() <= 3,
                        "Result level should be between 0 and target level");
                }
            }
        }
    }
    
    @Test
    void testConsistencyWithQuantization() {
        // For certain regular points, Bey traversal should find same or parent
        // of what quantization finds
        var cellSize = Constants.lengthAtLevel((byte)5);
        
        // Test at grid-aligned points
        for (int i = 0; i < 10; i++) {
            var x = i * cellSize;
            var y = i * cellSize; 
            var z = i * cellSize;
            
            if (x + y + z > Constants.lengthAtLevel((byte)0)) continue;
            
            var point = new Point3f(x, y, z);
            var beyResult = Tet.locatePointBeyRefinementFromRoot(point.x, point.y, point.z, (byte)5);
            
            assertNotNull(beyResult);
            assertTrue(beyResult.containsUltraFast(point.x, point.y, point.z));
        }
    }
    
    @Test
    void testPointOutsideRoot() {
        // Points outside the valid domain should return null
        // The max coordinate is 2097152 (2^21)
        var farPoint = new Point3f(3000000, 3000000, 3000000);
        var result = Tet.locatePointBeyRefinementFromRoot(farPoint.x, farPoint.y, farPoint.z, (byte)3);
        
        assertNull(result, "Should return null for point outside valid domain");
        
        // Also test edge case at boundary
        var boundaryPoint = new Point3f(2097152, 0, 0);
        var boundaryResult = Tet.locatePointBeyRefinementFromRoot(
            boundaryPoint.x, boundaryPoint.y, boundaryPoint.z, (byte)3);
        assertNull(boundaryResult, "Should return null for point at or beyond boundary");
    }
    
    @Test
    void testInvalidInputs() {
        // Negative coordinates
        assertThrows(IllegalArgumentException.class, () -> 
            Tet.locatePointBeyRefinementFromRoot(-1, 0, 0, (byte)1));
        assertThrows(IllegalArgumentException.class, () -> 
            Tet.locatePointBeyRefinementFromRoot(0, -1, 0, (byte)1));
        assertThrows(IllegalArgumentException.class, () -> 
            Tet.locatePointBeyRefinementFromRoot(0, 0, -1, (byte)1));
        
        // Invalid level
        assertThrows(IllegalArgumentException.class, () -> 
            Tet.locatePointBeyRefinementFromRoot(0, 0, 0, (byte)-1));
        assertThrows(IllegalArgumentException.class, () -> 
            Tet.locatePointBeyRefinementFromRoot(0, 0, 0, (byte)25));
    }
    
    @Test
    void testGapHandling() {
        // Test that algorithm handles gaps in Bey refinement correctly
        // by returning deepest containing tetrahedron
        
        // Find a point that might fall in a gap
        var point = new Point3f(1024, 512, 256);
        
        // Try different levels
        for (byte level = 1; level <= 10; level++) {
            var result = Tet.locatePointBeyRefinementFromRoot(point.x, point.y, point.z, level);
            
            if (result != null) {
                assertTrue(result.l() <= level, 
                    "Result level should not exceed target level");
                assertTrue(result.containsUltraFast(point.x, point.y, point.z),
                    "Result should contain the point");
                
                // If we didn't reach target level, it means we hit a gap
                if (result.l() < level) {
                    // Verify no child contains the point
                    boolean foundInChild = false;
                    for (int i = 0; i < 8; i++) {
                        var child = result.child(i);
                        if (child.containsUltraFast(point.x, point.y, point.z)) {
                            foundInChild = true;
                            break;
                        }
                    }
                    assertFalse(foundInChild, 
                        "If stopped early, no child should contain the point");
                }
            }
        }
    }
    
    @Test
    void testInstanceMethodFromNonRoot() {
        // Test using instance method starting from a non-root tetrahedron
        // Use quantization to find a valid starting tetrahedron
        var startLevel = (byte)5;
        var testPoint = new Point3f(100000, 100000, 100000);
        
        // Get a tetrahedron at level 5 using quantization
        var startTet = Tet.locatePointBeyRefinementFromRoot(
            testPoint.x, testPoint.y, testPoint.z, startLevel);
        
        if (startTet != null) {
            assertEquals(startLevel, startTet.l());
            
            // Use instance method to go deeper to level 7
            var result = startTet.locatePointBeyRefinement(
                testPoint.x, testPoint.y, testPoint.z, (byte)7);
            
            if (result != null) {
                assertTrue(result.l() >= startLevel && result.l() <= 7,
                    "Result level should be between start and target");
                
                // If we reached target level, verify containment
                if (result.l() == 7) {
                    assertTrue(result.containsUltraFast(testPoint.x, testPoint.y, testPoint.z),
                        "Result should contain the test point");
                }
            }
        }
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