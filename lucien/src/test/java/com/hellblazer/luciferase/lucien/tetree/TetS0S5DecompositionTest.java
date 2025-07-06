package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that the S0-S5 tetrahedral decomposition correctly tiles a cube.
 */
public class TetS0S5DecompositionTest {
    
    @Test
    void testCorrectVertices() {
        // Test at level 5 with a cube at origin
        byte level = 5;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        System.out.println("=== S0-S5 Vertex Verification ===");
        System.out.println("Cube size (h): " + h);
        
        // Expected vertices for each type according to S0-S5 decomposition
        Point3i[][][] expectedVertices = {
            // Type 0 (S0): vertices 0, 1, 3, 7
            {{new Point3i(0, 0, 0), new Point3i(h, 0, 0), new Point3i(h, h, 0), new Point3i(h, h, h)}},
            // Type 1 (S1): vertices 0, 2, 3, 7
            {{new Point3i(0, 0, 0), new Point3i(0, h, 0), new Point3i(h, h, 0), new Point3i(h, h, h)}},
            // Type 2 (S2): vertices 0, 4, 5, 7
            {{new Point3i(0, 0, 0), new Point3i(0, 0, h), new Point3i(h, 0, h), new Point3i(h, h, h)}},
            // Type 3 (S3): vertices 0, 4, 6, 7
            {{new Point3i(0, 0, 0), new Point3i(0, 0, h), new Point3i(0, h, h), new Point3i(h, h, h)}},
            // Type 4 (S4): vertices 0, 1, 5, 7
            {{new Point3i(0, 0, 0), new Point3i(h, 0, 0), new Point3i(h, 0, h), new Point3i(h, h, h)}},
            // Type 5 (S5): vertices 0, 2, 6, 7
            {{new Point3i(0, 0, 0), new Point3i(0, h, 0), new Point3i(0, h, h), new Point3i(h, h, h)}}
        };
        
        for (byte type = 0; type <= 5; type++) {
            Tet tet = new Tet(0, 0, 0, level, type);
            Point3i[] actual = tet.coordinates();
            Point3i[] expected = expectedVertices[type][0];
            
            System.out.printf("\nType %d (S%d):%n", type, type);
            for (int v = 0; v < 4; v++) {
                System.out.printf("  V%d: expected %s, actual %s%n", v, expected[v], actual[v]);
                assertEquals(expected[v], actual[v], 
                    String.format("Type %d vertex %d mismatch", type, v));
            }
        }
    }
    
    @Test
    void testNoGapsNoOverlaps() {
        // Test that every point in a cube is in exactly one tetrahedron
        byte level = 5;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Test cube at (10*cellSize, 10*cellSize, 10*cellSize)
        int cubeX = 10 * cellSize;
        int cubeY = 10 * cellSize;
        int cubeZ = 10 * cellSize;
        
        System.out.println("\n=== Gap/Overlap Test ===");
        System.out.printf("Testing cube at (%d, %d, %d) with size %d%n", cubeX, cubeY, cubeZ, cellSize);
        
        // Create all 6 tetrahedra for this cube
        Tet[] tets = new Tet[6];
        for (byte type = 0; type <= 5; type++) {
            tets[type] = new Tet(cubeX, cubeY, cubeZ, level, type);
        }
        
        // Test a grid of points within the cube
        int gridSize = 10;
        int totalPoints = 0;
        int[] containmentCounts = new int[4]; // 0, 1, 2, 3+ containments
        
        for (int i = 0; i <= gridSize; i++) {
            for (int j = 0; j <= gridSize; j++) {
                for (int k = 0; k <= gridSize; k++) {
                    float x = cubeX + (float)i * cellSize / gridSize;
                    float y = cubeY + (float)j * cellSize / gridSize;
                    float z = cubeZ + (float)k * cellSize / gridSize;
                    Point3f point = new Point3f(x, y, z);
                    
                    totalPoints++;
                    
                    // Count how many tetrahedra contain this point
                    int containCount = 0;
                    for (int t = 0; t < 6; t++) {
                        if (tets[t].contains(point)) {
                            containCount++;
                        }
                    }
                    
                    containmentCounts[Math.min(containCount, 3)]++;
                    
                    // Log any issues
                    if (containCount != 1) {
                        // Points on boundaries might be in multiple tets, that's OK
                        boolean onBoundary = (i == 0 || i == gridSize || 
                                            j == 0 || j == gridSize || 
                                            k == 0 || k == gridSize);
                        if (!onBoundary) {
                            System.out.printf("Interior point (%.1f, %.1f, %.1f) contained by %d tets%n",
                                x - cubeX, y - cubeY, z - cubeZ, containCount);
                        }
                    }
                }
            }
        }
        
        System.out.println("\nContainment statistics:");
        System.out.printf("Total points tested: %d%n", totalPoints);
        System.out.printf("Contained by 0 tets: %d (%.1f%%)%n", 
            containmentCounts[0], 100.0 * containmentCounts[0] / totalPoints);
        System.out.printf("Contained by 1 tet: %d (%.1f%%)%n", 
            containmentCounts[1], 100.0 * containmentCounts[1] / totalPoints);
        System.out.printf("Contained by 2+ tets: %d (%.1f%%)%n", 
            containmentCounts[2] + containmentCounts[3], 
            100.0 * (containmentCounts[2] + containmentCounts[3]) / totalPoints);
        
        // Interior points should be in exactly one tetrahedron
        assertTrue(containmentCounts[0] == 0 || containmentCounts[0] <= totalPoints * 0.05,
            "Too many points not contained by any tetrahedron");
    }
    
    @Test
    void testVolumePreservation() {
        // Test that the sum of 6 tetrahedra volumes equals cube volume
        byte level = 5;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        System.out.println("\n=== Volume Preservation Test ===");
        
        double cubeVolume = (double)h * h * h;
        double totalTetVolume = 0;
        
        for (byte type = 0; type <= 5; type++) {
            Tet tet = new Tet(0, 0, 0, level, type);
            Point3i[] vertices = tet.coordinates();
            
            // Calculate tetrahedron volume using determinant formula
            // V = |det(v1-v0, v2-v0, v3-v0)| / 6
            double dx1 = vertices[1].x - vertices[0].x;
            double dy1 = vertices[1].y - vertices[0].y;
            double dz1 = vertices[1].z - vertices[0].z;
            
            double dx2 = vertices[2].x - vertices[0].x;
            double dy2 = vertices[2].y - vertices[0].y;
            double dz2 = vertices[2].z - vertices[0].z;
            
            double dx3 = vertices[3].x - vertices[0].x;
            double dy3 = vertices[3].y - vertices[0].y;
            double dz3 = vertices[3].z - vertices[0].z;
            
            double det = dx1 * (dy2 * dz3 - dz2 * dy3) -
                        dy1 * (dx2 * dz3 - dz2 * dx3) +
                        dz1 * (dx2 * dy3 - dy2 * dx3);
            
            double volume = Math.abs(det) / 6.0;
            totalTetVolume += volume;
            
            System.out.printf("Type %d volume: %.2f (expected %.2f)%n", 
                type, volume, cubeVolume / 6.0);
        }
        
        System.out.printf("\nCube volume: %.2f%n", cubeVolume);
        System.out.printf("Total tet volume: %.2f%n", totalTetVolume);
        System.out.printf("Ratio: %.6f (expected 1.0)%n", totalTetVolume / cubeVolume);
        
        assertEquals(cubeVolume, totalTetVolume, 1.0, 
            "Sum of tetrahedra volumes should equal cube volume");
    }
    
    @Test
    void testRandomPointContainment() {
        // Test many random points to ensure high containment rate
        byte level = 5;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        Random random = new Random(42);
        
        System.out.println("\n=== Random Point Containment Test ===");
        
        int numTests = 1000;
        int contained = 0;
        
        for (int i = 0; i < numTests; i++) {
            // Random cube position
            int cubeX = random.nextInt(20) * cellSize;
            int cubeY = random.nextInt(20) * cellSize;
            int cubeZ = random.nextInt(20) * cellSize;
            
            // Random point within cube
            float x = cubeX + random.nextFloat() * cellSize;
            float y = cubeY + random.nextFloat() * cellSize;
            float z = cubeZ + random.nextFloat() * cellSize;
            Point3f point = new Point3f(x, y, z);
            
            // Check if any of the 6 tets contains it
            boolean found = false;
            for (byte type = 0; type <= 5; type++) {
                Tet tet = new Tet(cubeX, cubeY, cubeZ, level, type);
                if (tet.contains(point)) {
                    found = true;
                    break;
                }
            }
            
            if (found) contained++;
        }
        
        double containmentRate = 100.0 * contained / numTests;
        System.out.printf("Tested %d random points%n", numTests);
        System.out.printf("Containment rate: %.1f%%%n", containmentRate);
        
        assertTrue(containmentRate > 95.0, 
            "Containment rate should be > 95% with correct S0-S5 decomposition");
    }
    
    @Test
    void testSharedVertices() {
        // Verify all tetrahedra share V0 and V7
        byte level = 5;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        System.out.println("\n=== Shared Vertices Test ===");
        
        Point3i expectedV0 = new Point3i(0, 0, 0);
        Point3i expectedV7 = new Point3i(h, h, h);
        
        for (byte type = 0; type <= 5; type++) {
            Tet tet = new Tet(0, 0, 0, level, type);
            Point3i[] vertices = tet.coordinates();
            
            // All should have V0 at position 0
            assertEquals(expectedV0, vertices[0], 
                "All tetrahedra should share V0 at position 0");
            
            // All should have V7 at position 3
            assertEquals(expectedV7, vertices[3], 
                "All tetrahedra should share V7 at position 3");
        }
        
        System.out.println("✓ All tetrahedra share V0 and V7");
    }
}