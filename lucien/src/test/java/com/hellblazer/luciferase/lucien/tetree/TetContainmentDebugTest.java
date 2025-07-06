package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Debug test to understand containment issues with S0-S5 tetrahedra
 */
public class TetContainmentDebugTest {
    
    @Test
    void debugSingleTetContainment() {
        // Test a simple type 0 tetrahedron at origin
        byte level = 5;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        Tet tet0 = new Tet(0, 0, 0, level, (byte) 0);
        
        System.out.println("=== Debug Type 0 Containment ===");
        System.out.println("Tetrahedron size (h): " + h);
        
        // Get vertices
        Point3i[] vertices = tet0.coordinates();
        System.out.println("\nVertices:");
        for (int i = 0; i < 4; i++) {
            System.out.printf("  V%d: %s%n", i, vertices[i]);
        }
        
        // Test specific points
        testPoint(tet0, 0.5f * h, 0.5f * h, 0.5f * h, "Cube center");
        testPoint(tet0, 0.25f * h, 0.25f * h, 0.25f * h, "Near origin");
        testPoint(tet0, 0.75f * h, 0.75f * h, 0.75f * h, "Near opposite corner");
        testPoint(tet0, 0.5f * h, 0.25f * h, 0.25f * h, "Lower middle");
        testPoint(tet0, 0.75f * h, 0.5f * h, 0.25f * h, "Face center");
        
        // Test boundary points
        testPoint(tet0, 0, 0, 0, "V0 (origin)");
        testPoint(tet0, h, 0, 0, "V1");
        testPoint(tet0, h, h, 0, "V2");
        testPoint(tet0, h, h, h, "V3");
        
        // Test all 6 types with cube center
        System.out.println("\n=== Cube Center Test for All Types ===");
        for (byte type = 0; type <= 5; type++) {
            Tet tet = new Tet(0, 0, 0, level, type);
            Point3f center = new Point3f(0.5f * h, 0.5f * h, 0.5f * h);
            boolean contains = tet.contains(center);
            System.out.printf("Type %d contains cube center: %s%n", type, contains);
        }
        
        // Count how many tetrahedra contain various points
        System.out.println("\n=== Coverage Analysis ===");
        int gridSize = 10;
        int[] histogram = new int[7]; // 0 to 6 containments
        
        for (int i = 0; i <= gridSize; i++) {
            for (int j = 0; j <= gridSize; j++) {
                for (int k = 0; k <= gridSize; k++) {
                    float x = (float) i * h / gridSize;
                    float y = (float) j * h / gridSize;
                    float z = (float) k * h / gridSize;
                    Point3f p = new Point3f(x, y, z);
                    
                    int count = 0;
                    for (byte type = 0; type <= 5; type++) {
                        Tet tet = new Tet(0, 0, 0, level, type);
                        if (tet.contains(p)) count++;
                    }
                    
                    histogram[count]++;
                    
                    if (count != 1 && i > 0 && i < gridSize && j > 0 && j < gridSize && k > 0 && k < gridSize) {
                        // Interior point with wrong containment
                        System.out.printf("Interior point (%.1f, %.1f, %.1f) contained by %d tets%n",
                            x, y, z, count);
                    }
                }
            }
        }
        
        System.out.println("\nContainment histogram:");
        for (int i = 0; i <= 6; i++) {
            if (histogram[i] > 0) {
                System.out.printf("  %d tets: %d points%n", i, histogram[i]);
            }
        }
    }
    
    private void testPoint(Tet tet, float x, float y, float z, String description) {
        Point3f p = new Point3f(x, y, z);
        boolean contains = tet.contains(p);
        System.out.printf("  Point (%.1f, %.1f, %.1f) [%s]: %s%n", 
            x, y, z, description, contains ? "INSIDE" : "OUTSIDE");
    }
    
    @Test
    void analyzeS0S5Pattern() {
        System.out.println("\n=== S0-S5 Pattern Analysis ===");
        
        // Show which cube vertices each tetrahedron uses
        String[] cubeVertexNames = {"V0(0,0,0)", "V1(h,0,0)", "V2(0,h,0)", "V3(h,h,0)", 
                                    "V4(0,0,h)", "V5(h,0,h)", "V6(0,h,h)", "V7(h,h,h)"};
        
        int[][] s0s5Vertices = {
            {0, 1, 3, 7}, // S0
            {0, 2, 3, 7}, // S1
            {0, 4, 5, 7}, // S2
            {0, 4, 6, 7}, // S3
            {0, 1, 5, 7}, // S4
            {0, 2, 6, 7}  // S5
        };
        
        for (int type = 0; type < 6; type++) {
            System.out.printf("Type %d (S%d) uses cube vertices: ", type, type);
            for (int v : s0s5Vertices[type]) {
                System.out.print(cubeVertexNames[v] + " ");
            }
            System.out.println();
        }
        
        // Analyze shared faces
        System.out.println("\n=== Shared Faces Analysis ===");
        for (int t1 = 0; t1 < 6; t1++) {
            for (int t2 = t1 + 1; t2 < 6; t2++) {
                int shared = countSharedVertices(s0s5Vertices[t1], s0s5Vertices[t2]);
                if (shared == 3) {
                    System.out.printf("Types %d and %d share a face%n", t1, t2);
                }
            }
        }
    }
    
    private int countSharedVertices(int[] v1, int[] v2) {
        int count = 0;
        for (int a : v1) {
            for (int b : v2) {
                if (a == b) count++;
            }
        }
        return count;
    }
}