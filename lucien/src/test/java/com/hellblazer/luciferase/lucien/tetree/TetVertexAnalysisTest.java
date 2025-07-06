package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;

/**
 * Analyze the vertices of the 6 tetrahedra in a cube.
 */
public class TetVertexAnalysisTest {
    
    @Test
    void analyzeTetrahedralVertices() {
        // At level 5, examine the 6 tetrahedra in a single cube
        byte level = 5;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Use cube at origin for simplicity
        int cubeX = 0;
        int cubeY = 0; 
        int cubeZ = 0;
        
        System.out.println("=== Tetrahedral Vertices Analysis ===");
        System.out.printf("Cube at (%d, %d, %d) with size %d%n", cubeX, cubeY, cubeZ, cellSize);
        System.out.println("\nExpected cube vertices:");
        System.out.println("V0: (0, 0, 0)");
        System.out.println("V1: (65536, 0, 0)");
        System.out.println("V2: (0, 65536, 0)");
        System.out.println("V3: (65536, 65536, 0)");
        System.out.println("V4: (0, 0, 65536)");
        System.out.println("V5: (65536, 0, 65536)");
        System.out.println("V6: (0, 65536, 65536)");
        System.out.println("V7: (65536, 65536, 65536)");
        
        System.out.println("\nTetrahedra vertices:");
        
        for (byte type = 0; type <= 5; type++) {
            System.out.printf("\nType %d tetrahedron:%n", type);
            Tet tet = new Tet(cubeX, cubeY, cubeZ, level, type);
            
            // Get standard coordinates
            Point3i[] coords = tet.coordinates();
            System.out.println("Standard coordinates:");
            for (int i = 0; i < 4; i++) {
                System.out.printf("  V%d: (%d, %d, %d)%n", i, coords[i].x, coords[i].y, coords[i].z);
            }
            
            // Get subdivision coordinates
            Point3i[] subCoords = tet.subdivisionCoordinates();
            System.out.println("Subdivision coordinates:");
            for (int i = 0; i < 4; i++) {
                System.out.printf("  V%d: (%d, %d, %d)%n", i, subCoords[i].x, subCoords[i].y, subCoords[i].z);
            }
            
            // Check if this matches expected cube decomposition
            System.out.println("Expected tetrahedron based on S0-S5 decomposition:");
            printExpectedTetrahedron(type, cellSize);
        }
    }
    
    private void printExpectedTetrahedron(byte type, int h) {
        // Based on the standard cube decomposition into 6 tetrahedra
        // From the literature on tetrahedral meshes
        switch (type) {
            case 0:
                // S0: Vertices 0, 1, 3, 7
                System.out.println("  Should use cube vertices: V0, V1, V3, V7");
                System.out.printf("  Expected: (0,0,0), (%d,0,0), (%d,%d,0), (%d,%d,%d)%n", 
                    h, h, h, h, h, h);
                break;
            case 1:
                // S1: Vertices 0, 2, 3, 7
                System.out.println("  Should use cube vertices: V0, V2, V3, V7");
                System.out.printf("  Expected: (0,0,0), (0,%d,0), (%d,%d,0), (%d,%d,%d)%n",
                    h, h, h, h, h, h);
                break;
            case 2:
                // S2: Vertices 0, 4, 5, 7
                System.out.println("  Should use cube vertices: V0, V4, V5, V7");
                System.out.printf("  Expected: (0,0,0), (0,0,%d), (%d,0,%d), (%d,%d,%d)%n",
                    h, h, h, h, h, h);
                break;
            case 3:
                // S3: Vertices 0, 4, 6, 7
                System.out.println("  Should use cube vertices: V0, V4, V6, V7");
                System.out.printf("  Expected: (0,0,0), (0,0,%d), (0,%d,%d), (%d,%d,%d)%n",
                    h, h, h, h, h, h);
                break;
            case 4:
                // S4: Vertices 0, 1, 5, 7
                System.out.println("  Should use cube vertices: V0, V1, V5, V7");
                System.out.printf("  Expected: (0,0,0), (%d,0,0), (%d,0,%d), (%d,%d,%d)%n",
                    h, h, h, h, h, h);
                break;
            case 5:
                // S5: Vertices 0, 2, 6, 7
                System.out.println("  Should use cube vertices: V0, V2, V6, V7");
                System.out.printf("  Expected: (0,0,0), (0,%d,0), (0,%d,%d), (%d,%d,%d)%n",
                    h, h, h, h, h, h);
                break;
        }
    }
}