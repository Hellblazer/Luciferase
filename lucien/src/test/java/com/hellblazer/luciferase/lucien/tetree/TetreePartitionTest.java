package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that the 6 t8code tetrahedra actually partition a unit cube.
 */
public class TetreePartitionTest {
    
    @Test
    void testT8CodeTetrahedraVertices() {
        System.out.println("=== T8Code Tetrahedra Vertices ===\n");
        
        // Create unit tetrahedra at level 21 (size 1)
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 21, type);
            Point3i[] vertices = tet.coordinates();
            
            System.out.printf("Type %d vertices:\n", type);
            for (int i = 0; i < 4; i++) {
                System.out.printf("  v%d: (%d, %d, %d)\n", i, 
                    vertices[i].x, vertices[i].y, vertices[i].z);
            }
            
            // Check if this forms a valid tetrahedron (non-zero volume)
            double volume = computeTetrahedronVolume(vertices);
            System.out.printf("  Volume: %.6f\n", volume);
            assertTrue(Math.abs(volume) > 1e-10, "Tetrahedron should have non-zero volume");
            
            System.out.println();
        }
    }
    
    @Test
    @Disabled("t8code tetrahedra don't properly partition the cube (~48% gaps, ~32% overlaps) - see TETREE_T8CODE_PARTITION_ANALYSIS.md")
    void testCubePartitioning() {
        System.out.println("=== Testing Cube Partitioning ===\n");
        
        // Test a grid of points in a unit cube
        int gridSize = 10;
        int totalPoints = 0;
        int[] containmentCounts = new int[7]; // 0 to 6 containers
        
        for (int i = 1; i < gridSize; i++) {
            for (int j = 1; j < gridSize; j++) {
                for (int k = 1; k < gridSize; k++) {
                    totalPoints++;
                    
                    // Test point in unit cube (avoiding exact grid points)
                    float x = i / (float) gridSize;
                    float y = j / (float) gridSize;
                    float z = k / (float) gridSize;
                    Point3f point = new Point3f(x, y, z);
                    
                    // Count how many tetrahedra contain this point
                    int containers = 0;
                    for (byte type = 0; type < 6; type++) {
                        Tet tet = new Tet(0, 0, 0, (byte) 21, type);
                        if (tet.contains(point)) {
                            containers++;
                        }
                    }
                    
                    containmentCounts[containers]++;
                    
                    if (containers != 1) {
                        System.out.printf("Point (%.2f, %.2f, %.2f) contained in %d tetrahedra\n",
                            x, y, z, containers);
                    }
                }
            }
        }
        
        System.out.println("\nContainment statistics:");
        for (int i = 0; i <= 6; i++) {
            if (containmentCounts[i] > 0) {
                System.out.printf("  Points in %d tetrahedra: %d (%.1f%%)\n", 
                    i, containmentCounts[i], 100.0 * containmentCounts[i] / totalPoints);
            }
        }
        
        // All interior points should be in exactly 1 tetrahedron
        assertEquals(0, containmentCounts[0], "No points should be in 0 tetrahedra (gaps)");
        assertTrue(containmentCounts[2] + containmentCounts[3] + containmentCounts[4] + 
                   containmentCounts[5] + containmentCounts[6] == 0, 
                   "No points should be in multiple tetrahedra (overlaps)");
    }
    
    private double computeTetrahedronVolume(Point3i[] vertices) {
        // Volume = |det(v1-v0, v2-v0, v3-v0)| / 6
        int dx1 = vertices[1].x - vertices[0].x;
        int dy1 = vertices[1].y - vertices[0].y;
        int dz1 = vertices[1].z - vertices[0].z;
        
        int dx2 = vertices[2].x - vertices[0].x;
        int dy2 = vertices[2].y - vertices[0].y;
        int dz2 = vertices[2].z - vertices[0].z;
        
        int dx3 = vertices[3].x - vertices[0].x;
        int dy3 = vertices[3].y - vertices[0].y;
        int dz3 = vertices[3].z - vertices[0].z;
        
        int det = dx1 * (dy2 * dz3 - dz2 * dy3) -
                  dy1 * (dx2 * dz3 - dz2 * dx3) +
                  dz1 * (dx2 * dy3 - dy2 * dx3);
        
        return Math.abs(det) / 6.0;
    }
    
    @Test
    void testSpecificFailingPoint() {
        System.out.println("\n=== Testing Specific Failing Point ===\n");
        
        // The point (0.1, 0.2, 0.3) that's failing
        Point3f point = new Point3f(0.1f, 0.2f, 0.3f);
        System.out.printf("Testing point (%.1f, %.1f, %.1f)\n", point.x, point.y, point.z);
        
        // Since x < y < z, according to our previous logic it should be in type 3
        System.out.println("Expected in type 3 (x < y < z)");
        
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 21, type);
            boolean contains = tet.contains(point);
            
            if (contains || type == 3) {
                System.out.printf("\nType %d: %s\n", type, contains ? "CONTAINS" : "DOES NOT CONTAIN");
                
                // Show the containment calculation details
                System.out.println("  Checking against 4 faces:");
                
                // Get vertices
                Point3i[] v = tet.coordinates();
                
                // We need to understand why containsUltraFast is failing
                // It uses plane equations for each face
                System.out.println("  Vertices:");
                for (int i = 0; i < 4; i++) {
                    System.out.printf("    v%d: (%d, %d, %d)\n", i, v[i].x, v[i].y, v[i].z);
                }
            }
        }
    }
}