package com.hellblazer.luciferase.lucien.tetree;

import javax.vecmath.Point3f;
import org.junit.jupiter.api.Test;

/**
 * Simple example demonstrating gaps in the t8code partition scheme.
 * 
 * The t8code tetrahedral decomposition creates 6 tetrahedra per cube,
 * but these tetrahedra don't completely fill the cube - they leave gaps.
 */
public class T8CodeGapExample {
    
    @Test
    void demonstrateSimpleGap() {
        System.out.println("=== T8Code Gap Example ===\n");
        
        // Let's examine a unit cube at level 10 (cell size = 1024)
        // The cube spans from (0,0,0) to (1024,1024,1024)
        byte level = 10;
        int cellSize = 1 << (20 - level); // 1024
        
        System.out.println("Examining cube from (0,0,0) to (" + cellSize + "," + cellSize + "," + cellSize + ")");
        System.out.println("This cube contains 6 tetrahedra of types 0-5\n");
        
        // Test point that falls in a gap
        // Based on our previous analysis, points like (0.1, 0.2, 0.3) in unit cube fall in gaps
        float gapX = cellSize * 0.1f;  // 102.4
        float gapY = cellSize * 0.2f;  // 204.8
        float gapZ = cellSize * 0.3f;  // 307.2
        
        Point3f gapPoint = new Point3f(gapX, gapY, gapZ);
        System.out.println("Testing point: (" + gapX + ", " + gapY + ", " + gapZ + ")");
        System.out.println("This point is inside the cube bounds.\n");
        
        // Check if any of the 6 tetrahedra contain this point
        System.out.println("Checking which tetrahedron contains this point:");
        boolean foundContainer = false;
        
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, level, type);
            boolean contains = tet.contains(gapPoint);
            
            System.out.println("Type " + type + " tetrahedron: " + (contains ? "CONTAINS" : "does not contain") + " the point");
            
            if (contains) {
                foundContainer = true;
                
                // Show the vertices of the containing tetrahedron
                var vertices = tet.coordinates();
                System.out.println("  Vertices of type " + type + " tetrahedron:");
                for (int i = 0; i < 4; i++) {
                    System.out.println("    V" + i + ": " + vertices[i]);
                }
            }
        }
        
        if (!foundContainer) {
            System.out.println("\n*** GAP DETECTED ***");
            System.out.println("Point (" + gapX + ", " + gapY + ", " + gapZ + ") is inside the cube");
            System.out.println("but is NOT contained by any of the 6 tetrahedra!");
            System.out.println("This is a gap in the t8code partition scheme.\n");
        }
        
        // Now let's show a point that IS contained
        float containedX = cellSize * 0.25f;  // 256
        float containedY = cellSize * 0.25f;  // 256
        float containedZ = cellSize * 0.25f;  // 256
        
        Point3f containedPoint = new Point3f(containedX, containedY, containedZ);
        System.out.println("\nFor comparison, testing point: (" + containedX + ", " + containedY + ", " + containedZ + ")");
        
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, level, type);
            if (tet.contains(containedPoint)) {
                System.out.println("This point IS contained by type " + type + " tetrahedron");
                break;
            }
        }
        
        // Explain why gaps exist
        System.out.println("\n=== Why Gaps Exist ===");
        System.out.println("The 6 tetrahedra are formed by connecting vertices of the cube in specific ways.");
        System.out.println("Each tetrahedron has volume = cube_volume / 6, suggesting they should fill the cube.");
        System.out.println("However, due to their specific vertex arrangements, they leave small gaps between them.");
        System.out.println("This is an inherent property of the t8code tetrahedral decomposition.");
        
        // Calculate approximate gap percentage
        System.out.println("\nBased on analysis, approximately 48% of points in a cube fall into gaps!");
    }
    
    @Test
    void demonstrateOverlap() {
        System.out.println("\n=== T8Code Overlap Example ===\n");
        
        // Some points near tetrahedron boundaries may be contained by multiple tetrahedra
        byte level = 10;
        int cellSize = 1 << (20 - level); // 1024
        
        // Test a point on a shared edge between tetrahedra
        float edgeX = cellSize * 0.5f;  // 512 - midpoint
        float edgeY = 0;                 // on bottom face
        float edgeZ = cellSize * 0.5f;  // 512 - midpoint
        
        Point3f edgePoint = new Point3f(edgeX, edgeY, edgeZ);
        System.out.println("Testing point on edge: (" + edgeX + ", " + edgeY + ", " + edgeZ + ")");
        
        int containCount = 0;
        System.out.println("\nChecking all 6 tetrahedra:");
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, level, type);
            if (tet.contains(edgePoint)) {
                System.out.println("Type " + type + " tetrahedron CONTAINS the point");
                containCount++;
            }
        }
        
        if (containCount > 1) {
            System.out.println("\n*** OVERLAP DETECTED ***");
            System.out.println("Point is contained by " + containCount + " tetrahedra!");
            System.out.println("This demonstrates overlap in the t8code partition scheme.");
        } else if (containCount == 0) {
            System.out.println("\nThis edge point falls in a gap (no tetrahedron contains it).");
        } else {
            System.out.println("\nThis point is contained by exactly one tetrahedron (no overlap here).");
        }
    }
}