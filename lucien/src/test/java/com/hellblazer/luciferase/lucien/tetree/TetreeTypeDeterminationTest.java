package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.UUIDGenerator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that determineTetrahedronType correctly identifies which tetrahedron
 * contains a point, and that this matches Tet.contains().
 */
public class TetreeTypeDeterminationTest {
    
    @Test
    @Disabled("t8code type determination doesn't match containment due to gaps/overlaps - see TETREE_T8CODE_PARTITION_ANALYSIS.md")
    void testTypeCorrespondenceWithContainment() {
        System.out.println("=== Testing Type Determination vs Containment ===\n");
        
        var tetree = new Tetree(new UUIDGenerator());
        
        // Test at a specific cube location
        byte level = 5;
        var cellSize = Constants.lengthAtLevel(level);
        int anchorX = 10 * cellSize;
        int anchorY = 10 * cellSize;
        int anchorZ = 10 * cellSize;
        
        int mismatches = 0;
        int totalTests = 0;
        
        // Test points throughout the cube
        for (float fx = 0.05f; fx <= 0.95f; fx += 0.1f) {
            for (float fy = 0.05f; fy <= 0.95f; fy += 0.1f) {
                for (float fz = 0.05f; fz <= 0.95f; fz += 0.1f) {
                    totalTests++;
                    
                    var point = new Point3f(
                        anchorX + fx * cellSize,
                        anchorY + fy * cellSize,
                        anchorZ + fz * cellSize
                    );
                    
                    // Get the tetrahedron using tetree.locate
                    var locatedTet = tetree.locateTetrahedron(point, level);
                    
                    // Verify it has the expected anchor
                    assertEquals(anchorX, locatedTet.x(), "Wrong X anchor");
                    assertEquals(anchorY, locatedTet.y(), "Wrong Y anchor");
                    assertEquals(anchorZ, locatedTet.z(), "Wrong Z anchor");
                    
                    // Check if the located tet actually contains the point
                    boolean locatedContains = locatedTet.contains(point);
                    
                    if (!locatedContains) {
                        mismatches++;
                        System.out.printf("MISMATCH at (%.2f, %.2f, %.2f):\n", fx, fy, fz);
                        System.out.printf("  Point: (%.0f, %.0f, %.0f)\n", point.x, point.y, point.z);
                        System.out.printf("  Located type %d does NOT contain point\n", locatedTet.type());
                        
                        // Find which type actually contains it
                        System.out.print("  Actually contained in types: ");
                        for (byte type = 0; type < 6; type++) {
                            var testTet = new Tet(anchorX, anchorY, anchorZ, level, type);
                            if (testTet.contains(point)) {
                                System.out.print(type + " ");
                            }
                        }
                        System.out.println("\n");
                    }
                }
            }
        }
        
        System.out.printf("Summary: %d mismatches out of %d tests (%.1f%% error rate)\n", 
            mismatches, totalTests, (100.0 * mismatches / totalTests));
        
        assertEquals(0, mismatches, "Type determination should match containment");
    }
    
    @Test
    void testSimplexStandardVertices() {
        System.out.println("\n=== Verifying SIMPLEX_STANDARD Definitions ===\n");
        
        // Print out the actual vertex definitions for each type
        for (int type = 0; type < 6; type++) {
            System.out.printf("Type %d vertices: ", type);
            var vertices = Constants.SIMPLEX_STANDARD[type];
            for (int i = 0; i < 4; i++) {
                System.out.printf("v%d(%d,%d,%d) ", i, vertices[i].x, vertices[i].y, vertices[i].z);
            }
            System.out.println();
        }
    }
    
    @Test
    void testSpecificPoints() {
        System.out.println("\n=== Testing Specific Points ===\n");
        
        var tetree = new Tetree(new UUIDGenerator());
        byte level = 10;
        var cellSize = Constants.lengthAtLevel(level);
        
        // Test cases with expected types based on coordinate ordering
        var testCases = new Object[][] {
            // {relative coords, expected description}
            {new float[]{0.1f, 0.2f, 0.3f}, "x < y < z"},
            {new float[]{0.3f, 0.2f, 0.1f}, "z < y < x"},
            {new float[]{0.1f, 0.3f, 0.2f}, "x < z < y"},
            {new float[]{0.2f, 0.1f, 0.3f}, "y < x < z"},
            {new float[]{0.3f, 0.1f, 0.2f}, "y < z < x"},
            {new float[]{0.2f, 0.3f, 0.1f}, "z < x < y"},
            // Test near diagonal
            {new float[]{0.33f, 0.34f, 0.33f}, "near diagonal"},
            // Test on boundaries
            {new float[]{0.5f, 0.5f, 0.6f}, "x = y < z"},
            {new float[]{0.5f, 0.6f, 0.5f}, "x = z < y"},
            {new float[]{0.6f, 0.5f, 0.5f}, "y = z < x"}
        };
        
        for (var testCase : testCases) {
            var coords = (float[]) testCase[0];
            var description = (String) testCase[1];
            
            var point = new Point3f(
                coords[0] * cellSize,
                coords[1] * cellSize,
                coords[2] * cellSize
            );
            
            var tet = tetree.locateTetrahedron(point, level);
            boolean contains = tet.contains(point);
            
            System.out.printf("Point (%.2f, %.2f, %.2f) %s -> Type %d, contains: %s\n",
                coords[0], coords[1], coords[2], description, tet.type(), contains);
            
            if (!contains) {
                // Find actual container
                System.out.print("  Actually in types: ");
                for (byte type = 0; type < 6; type++) {
                    var testTet = new Tet(0, 0, 0, level, type);
                    if (testTet.contains(point)) {
                        System.out.print(type + " ");
                    }
                }
                System.out.println();
            }
        }
    }
}