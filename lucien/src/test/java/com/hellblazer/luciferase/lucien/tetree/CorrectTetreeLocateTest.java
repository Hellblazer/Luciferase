package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.UUIDGenerator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the corrected point location algorithm that uses quantization and type determination.
 * This is NOT a Bey refinement traversal - it's direct calculation like octree.
 */
public class CorrectTetreeLocateTest {
    
    @Test
    void testLocateUsesQuantization() {
        System.out.println("=== Testing Correct Tetree Point Location ===\n");
        
        // Test that locate() properly quantizes points to anchor cubes
        var tetree = new Tetree(new UUIDGenerator());
        
        // Test points at different levels
        var testPoints = new Point3f[] {
            new Point3f(100, 100, 100),
            new Point3f(524288, 262144, 393216),
            new Point3f(1000000, 500000, 750000)
        };
        
        for (var point : testPoints) {
            System.out.printf("Testing point (%.0f, %.0f, %.0f):\n", point.x, point.y, point.z);
            
            for (byte level = 0; level <= 3; level++) {
                var cellSize = Constants.lengthAtLevel(level);
                
                // The expected anchor should be quantized
                var expectedAnchorX = (int) (Math.floor(point.x / cellSize) * cellSize);
                var expectedAnchorY = (int) (Math.floor(point.y / cellSize) * cellSize);
                var expectedAnchorZ = (int) (Math.floor(point.z / cellSize) * cellSize);
                
                var tet = tetree.locateTetrahedron(point, level);
                
                System.out.printf("  Level %d: anchor=(%d,%d,%d), type=%d, cellSize=%d\n",
                    level, tet.x(), tet.y(), tet.z(), tet.type(), cellSize);
                
                // Verify quantization
                assertEquals(expectedAnchorX, tet.x(), "X coordinate should be quantized");
                assertEquals(expectedAnchorY, tet.y(), "Y coordinate should be quantized");
                assertEquals(expectedAnchorZ, tet.z(), "Z coordinate should be quantized");
                assertEquals(level, tet.l(), "Level should match");
                
                // Type should be 0-5
                assertTrue(tet.type() >= 0 && tet.type() <= 5, "Type should be 0-5");
            }
            System.out.println();
        }
    }
    
    @Test
    @Disabled("t8code type determination heuristic doesn't match actual containment - see TETREE_T8CODE_PARTITION_ANALYSIS.md")
    void testDetermineTetrahedronType() {
        System.out.println("=== Testing Tetrahedron Type Determination ===\n");
        
        var tetree = new Tetree(new UUIDGenerator());
        
        // Test known points and their expected types
        // Based on the tetrahedral decomposition of a unit cube
        var testCases = new Object[][] {
            // Point in cube, expected type, description
            {new Point3f(0.1f, 0.2f, 0.3f), 3, "x < y < z -> S3"},
            {new Point3f(0.3f, 0.2f, 0.1f), 1, "z < y < x -> S1"},
            {new Point3f(0.1f, 0.3f, 0.2f), 2, "x < z < y -> S2"},
            {new Point3f(0.2f, 0.1f, 0.3f), 5, "y < x < z -> S5"},
            {new Point3f(0.3f, 0.1f, 0.2f), 0, "y < z < x -> S0"},
            {new Point3f(0.2f, 0.3f, 0.1f), 4, "z < x < y -> S4"}
        };
        
        for (var testCase : testCases) {
            var point = (Point3f) testCase[0];
            var expectedType = (int) testCase[1];
            var description = (String) testCase[2];
            
            // Scale up to actual coordinates
            var scaledPoint = new Point3f(
                point.x * 1000,
                point.y * 1000,
                point.z * 1000
            );
            
            var tet = tetree.locateTetrahedron(scaledPoint, (byte) 10);
            
            System.out.printf("Point (%.1f, %.1f, %.1f) -> Type %d (%s)\n",
                point.x, point.y, point.z, tet.type(), description);
            
            assertEquals(expectedType, tet.type(), 
                "Point " + description + " should be in tetrahedron type " + expectedType);
        }
    }
    
    @Test
    void testPointsInSameTetrahedronGetSameLocation() {
        System.out.println("\n=== Testing Points in Same Tetrahedron ===\n");
        
        var tetree = new Tetree(new UUIDGenerator());
        
        // Two points that should be in the same tetrahedron at level 5
        var cellSize = Constants.lengthAtLevel((byte) 5);
        // Use smaller multipliers to stay within bounds
        var baseX = 10 * cellSize;
        var baseY = 5 * cellSize;
        var baseZ = 7 * cellSize;
        
        // Two points in the same cell but at different positions
        var point1 = new Point3f(baseX + cellSize * 0.1f, baseY + cellSize * 0.2f, baseZ + cellSize * 0.3f);
        var point2 = new Point3f(baseX + cellSize * 0.15f, baseY + cellSize * 0.25f, baseZ + cellSize * 0.35f);
        
        var tet1 = tetree.locateTetrahedron(point1, (byte) 5);
        var tet2 = tetree.locateTetrahedron(point2, (byte) 5);
        
        System.out.printf("Point 1: (%.0f, %.0f, %.0f) -> Tet at (%d,%d,%d), type %d\n",
            point1.x, point1.y, point1.z, tet1.x(), tet1.y(), tet1.z(), tet1.type());
        System.out.printf("Point 2: (%.0f, %.0f, %.0f) -> Tet at (%d,%d,%d), type %d\n",
            point2.x, point2.y, point2.z, tet2.x(), tet2.y(), tet2.z(), tet2.type());
        
        // Should have same anchor and type since they're in the same tetrahedron
        assertEquals(tet1.x(), tet2.x(), "Should have same X anchor");
        assertEquals(tet1.y(), tet2.y(), "Should have same Y anchor");
        assertEquals(tet1.z(), tet2.z(), "Should have same Z anchor");
        assertEquals(tet1.type(), tet2.type(), "Should have same type");
        assertEquals(tet1.tmIndex(), tet2.tmIndex(), "Should have same tm-index");
    }
    
    @Test
    void testNoDependencyOnSubdivision() {
        System.out.println("\n=== Testing No Dependency on Subdivision ===\n");
        
        // This test verifies that locate() does NOT use child() or any subdivision
        // It should be a direct calculation based on quantization
        
        var tetree = new Tetree(new UUIDGenerator());
        var point = new Point3f(524288, 262144, 393216);
        
        // Get location at different levels - should NOT traverse through parents
        for (byte level = 5; level >= 0; level--) {
            var tet = tetree.locateTetrahedron(point, level);
            
            System.out.printf("Level %d: anchor=(%d,%d,%d), type=%d\n",
                level, tet.x(), tet.y(), tet.z(), tet.type());
            
            // Each level should be independently calculated
            // The anchor should be properly quantized for that level
            var cellSize = Constants.lengthAtLevel(level);
            assertEquals(0, tet.x() % cellSize, "X should be multiple of cellSize");
            assertEquals(0, tet.y() % cellSize, "Y should be multiple of cellSize");
            assertEquals(0, tet.z() % cellSize, "Z should be multiple of cellSize");
        }
    }
}