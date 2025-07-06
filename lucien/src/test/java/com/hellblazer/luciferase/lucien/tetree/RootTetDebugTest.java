package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Debug the root tetrahedron after S0-S5 fix
 */
public class RootTetDebugTest {
    
    @Test
    void analyzeRootTetrahedron() {
        System.out.println("=== Root Tetrahedron Analysis ===");
        
        // Create root tetrahedron
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        Point3i[] vertices = root.coordinates();
        
        System.out.println("Root tet vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.printf("  V%d: %s\n", i, vertices[i]);
        }
        
        // Test various points
        testPoint(root, 0, 0, 0, "Origin");
        testPoint(root, 100, 100, 100, "Point (100,100,100)");
        testPoint(root, 500, 500, 500, "Point (500,500,500)");
        testPoint(root, 1000000, 0, 0, "Point (1M,0,0)");
        testPoint(root, 1000000, 1000000, 0, "Point (1M,1M,0)");
        testPoint(root, 1000000, 1000000, 1000000, "Point (1M,1M,1M)");
        
        // Test bounds
        int maxCoord = 1 << TetreeKey.MAX_REFINEMENT_LEVEL;
        System.out.printf("\nMax coordinate: %d\n", maxCoord);
        testPoint(root, maxCoord/2, maxCoord/2, maxCoord/2, "Midpoint");
        testPoint(root, maxCoord, maxCoord, maxCoord, "Max corner");
    }
    
    private void testPoint(Tet tet, float x, float y, float z, String desc) {
        boolean contains = tet.containsUltraFast(x, y, z);
        System.out.printf("  %s (%.0f, %.0f, %.0f): %s\n", 
            desc, x, y, z, contains ? "INSIDE" : "OUTSIDE");
    }
    
    @Test 
    void testLocatePointMethod() {
        System.out.println("\n=== locatePointBeyRefinementFromRoot Analysis ===");
        
        // Test the failing points
        testLocate(100, 100, 100, "Point (100,100,100)");
        testLocate(1024, 512, 256, "Point (1024,512,256)");
        testLocate(0, 0, 0, "Origin");
        testLocate(500, 500, 500, "Point (500,500,500)");
    }
    
    private void testLocate(float x, float y, float z, String desc) {
        System.out.printf("\n%s (%.0f, %.0f, %.0f):\n", desc, x, y, z);
        
        for (byte level = 0; level <= 3; level++) {
            var result = Tet.locatePointBeyRefinementFromRoot(x, y, z, level);
            if (result != null) {
                boolean contains = result.containsUltraFast(x, y, z);
                System.out.printf("  Level %d: Found tet at (%d,%d,%d) type %d, level %d, contains=%s\n", 
                    level, result.x(), result.y(), result.z(), result.type(), result.l(), contains);
            } else {
                System.out.printf("  Level %d: null\n", level);
            }
        }
    }
}