package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to understand why some points aren't contained in any tetrahedron.
 * Avoids ambiguous points on shared vertices/edges/faces.
 */
public class TetreeContainmentDebugTest {
    
    @Test
    @Disabled("t8code tetrahedra have gaps - interior points not contained in any tetrahedron - see TETREE_T8CODE_PARTITION_ANALYSIS.md")
    void testInteriorPointsOnly() {
        System.out.println("=== Testing Interior Points (No Ambiguity) ===\n");
        
        // Use level 10 for easier numbers
        byte level = 10;
        var cellSize = Constants.lengthAtLevel(level);
        System.out.printf("Level %d, cell size = %d\n\n", level, cellSize);
        
        // Test only interior points that should be unambiguously in one tetrahedron
        // Avoid 0.5 (center/C7), edges, and faces
        float[][] interiorPoints = {
            {0.1f, 0.2f, 0.3f},   // Should be in one specific tet
            {0.2f, 0.1f, 0.3f},   // Different ordering
            {0.3f, 0.1f, 0.2f},   // Different ordering
            {0.15f, 0.25f, 0.35f}, // Another interior point
            {0.4f, 0.3f, 0.2f},   // Yet another
            {0.05f, 0.1f, 0.2f}   // The problematic point from before
        };
        
        for (float[] p : interiorPoints) {
            Point3f point = new Point3f(p[0] * cellSize, p[1] * cellSize, p[2] * cellSize);
            System.out.printf("Testing point (%.2f, %.2f, %.2f) = (%.0f, %.0f, %.0f):\n", 
                p[0], p[1], p[2], point.x, point.y, point.z);
            
            int containerCount = 0;
            for (byte type = 0; type < 6; type++) {
                var tet = new Tet(0, 0, 0, level, type);
                if (tet.contains(point)) {
                    System.out.printf("  Contained in type %d\n", type);
                    containerCount++;
                    
                    // Print the tetrahedron vertices for debugging
                    var vertices = tet.coordinates();
                    System.out.println("    Vertices:");
                    for (int i = 0; i < 4; i++) {
                        System.out.printf("      v%d: (%d, %d, %d)\n", i, 
                            vertices[i].x, vertices[i].y, vertices[i].z);
                    }
                }
            }
            
            System.out.printf("  Total containers: %d\n\n", containerCount);
            
            // Interior points should be in exactly one tetrahedron
            assertEquals(1, containerCount, 
                String.format("Interior point (%.2f, %.2f, %.2f) should be in exactly one tetrahedron", 
                    p[0], p[1], p[2]));
        }
    }
    
    @Test
    void testSmallCellSize() {
        System.out.println("=== Testing with Smaller Cell Size ===\n");
        
        // Test at level 20 where cell size is small
        byte level = 20;
        var cellSize = Constants.lengthAtLevel(level);
        System.out.printf("Level %d, cell size = %d\n\n", level, cellSize);
        
        // The same relative coordinates but with smaller cells
        float[][] testPoints = {
            {0.05f, 0.1f, 0.2f},
            {0.1f, 0.2f, 0.3f},
            {0.2f, 0.3f, 0.4f}
        };
        
        for (float[] p : testPoints) {
            Point3f point = new Point3f(p[0] * cellSize, p[1] * cellSize, p[2] * cellSize);
            System.out.printf("Point (%.2f, %.2f, %.2f) = (%.1f, %.1f, %.1f):\n", 
                p[0], p[1], p[2], point.x, point.y, point.z);
            
            int containerCount = 0;
            for (byte type = 0; type < 6; type++) {
                var tet = new Tet(0, 0, 0, level, type);
                if (tet.contains(point)) {
                    containerCount++;
                    System.out.printf("  Contained in type %d\n", type);
                }
            }
            
            System.out.printf("  Containers: %d\n\n", containerCount);
        }
    }
    
    @Test  
    void testAmbiguousPoints() {
        System.out.println("=== Testing Known Ambiguous Points ===\n");
        
        byte level = 10;
        var cellSize = Constants.lengthAtLevel(level);
        
        // These points are on shared features and SHOULD be ambiguous
        float[][] ambiguousPoints = {
            {0.0f, 0.0f, 0.0f},   // C0 - shared by all 6
            {1.0f, 1.0f, 1.0f},   // C7 - shared by all 6
            {0.5f, 0.5f, 0.5f},   // Center (half of C7) - shared by all 6
            {1.0f, 0.0f, 0.0f},   // C1 - shared by types 0,1
            {0.0f, 1.0f, 0.0f},   // C2 - shared by types 2,3
            {0.0f, 0.0f, 1.0f},   // C4 - shared by types 4,5
            {0.5f, 0.0f, 0.0f},   // Edge midpoint
            {0.5f, 0.5f, 0.0f}    // Face center
        };
        
        for (float[] p : ambiguousPoints) {
            Point3f point = new Point3f(p[0] * cellSize, p[1] * cellSize, p[2] * cellSize);
            System.out.printf("Ambiguous point (%.1f, %.1f, %.1f):\n", p[0], p[1], p[2]);
            
            System.out.print("  Contained in types: ");
            for (byte type = 0; type < 6; type++) {
                var tet = new Tet(0, 0, 0, level, type);
                if (tet.contains(point)) {
                    System.out.printf("%d ", type);
                }
            }
            System.out.println("\n");
        }
    }
}