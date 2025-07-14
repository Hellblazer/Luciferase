package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Verify containment using barycentric coordinates to validate S0-S5 implementation
 */
public class TetContainmentVerificationTest {
    
    @Test
    void verifyS0S5UsingBarycentricCoordinates() {
        byte level = 5;
        int h = 1 << (MortonCurve.MAX_REFINEMENT_LEVEL - level);
        
        System.out.println("=== S0-S5 Containment Verification Using Barycentric Coordinates ===");
        System.out.println("Cube size (h): " + h);
        
        // Test each tetrahedron type
        for (byte type = 0; type <= 5; type++) {
            System.out.printf("\n--- Type %d (S%d) ---\n", type, type);
            
            Tet tet = new Tet(0, 0, 0, level, type);
            Point3i[] vertices = tet.coordinates();
            
            // Print vertices
            System.out.println("Vertices:");
            for (int i = 0; i < 4; i++) {
                System.out.printf("  V%d: %s\n", i, vertices[i]);
            }
            
            // Test containment at several points
            testContainmentAtPoint(tet, vertices, h/2f, h/2f, h/2f, "Cube center");
            testContainmentAtPoint(tet, vertices, h/4f, h/4f, h/4f, "Near origin");
            testContainmentAtPoint(tet, vertices, 3*h/4f, 3*h/4f, 3*h/4f, "Near opposite");
            
            // Compute volume
            float volume = computeTetrahedronVolume(vertices);
            System.out.printf("Volume: %.2f (expected %.2f)\n", volume, (float)h*h*h/6);
        }
        
        // Test coverage
        System.out.println("\n=== Coverage Test ===");
        int gridSize = 10;
        int[] histogram = new int[7];
        
        for (int i = 0; i <= gridSize; i++) {
            for (int j = 0; j <= gridSize; j++) {
                for (int k = 0; k <= gridSize; k++) {
                    float x = (float)i * h / gridSize;
                    float y = (float)j * h / gridSize;
                    float z = (float)k * h / gridSize;
                    
                    int count = 0;
                    for (byte type = 0; type <= 5; type++) {
                        Tet tet = new Tet(0, 0, 0, level, type);
                        Point3i[] vertices = tet.coordinates();
                        if (containsPointBarycentric(vertices, x, y, z)) {
                            count++;
                        }
                    }
                    
                    histogram[count]++;
                }
            }
        }
        
        System.out.println("Containment histogram:");
        int total = 0;
        for (int i = 0; i <= 6; i++) {
            if (histogram[i] > 0) {
                System.out.printf("  %d tets: %d points (%.1f%%)\n", 
                    i, histogram[i], 100.0 * histogram[i] / ((gridSize+1)*(gridSize+1)*(gridSize+1)));
                total += histogram[i];
            }
        }
        System.out.println("Total points: " + total);
    }
    
    private void testContainmentAtPoint(Tet tet, Point3i[] vertices, float x, float y, float z, String desc) {
        Point3f p = new Point3f(x, y, z);
        
        // Test with tet's contains method
        boolean tetContains = tet.contains(p);
        
        // Test with barycentric coordinates
        boolean baryContains = containsPointBarycentric(vertices, x, y, z);
        
        System.out.printf("  %s (%.1f, %.1f, %.1f): Tet=%s, Bary=%s %s\n", 
            desc, x, y, z, tetContains, baryContains,
            tetContains == baryContains ? "✓" : "MISMATCH!");
    }
    
    /**
     * Check if point is inside tetrahedron using barycentric coordinates.
     * A point is inside if all barycentric coordinates are non-negative.
     */
    private boolean containsPointBarycentric(Point3i[] v, float px, float py, float pz) {
        // Convert to float arrays for easier manipulation
        float[] p = {px, py, pz};
        float[][] verts = new float[4][3];
        for (int i = 0; i < 4; i++) {
            verts[i][0] = v[i].x;
            verts[i][1] = v[i].y;
            verts[i][2] = v[i].z;
        }
        
        // Compute barycentric coordinates
        float[] bary = computeBarycentricCoordinates(verts, p);
        
        // Check if all are non-negative (with small epsilon for numerical errors)
        float epsilon = -1e-6f;
        return bary[0] >= epsilon && bary[1] >= epsilon && 
               bary[2] >= epsilon && bary[3] >= epsilon;
    }
    
    /**
     * Compute barycentric coordinates of point p with respect to tetrahedron vertices
     */
    private float[] computeBarycentricCoordinates(float[][] v, float[] p) {
        // Create matrix with vertices relative to v0
        float[][] mat = new float[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mat[i][j] = v[j+1][i] - v[0][i];
            }
        }
        
        // Vector from v0 to p
        float[] vec = new float[3];
        for (int i = 0; i < 3; i++) {
            vec[i] = p[i] - v[0][i];
        }
        
        // Solve for barycentric coordinates of v1, v2, v3
        float[] bary123 = solve3x3(mat, vec);
        
        // Compute barycentric coordinate for v0
        float bary0 = 1.0f - bary123[0] - bary123[1] - bary123[2];
        
        return new float[]{bary0, bary123[0], bary123[1], bary123[2]};
    }
    
    /**
     * Solve 3x3 linear system using Cramer's rule
     */
    private float[] solve3x3(float[][] A, float[] b) {
        float det = determinant3x3(A);
        
        if (Math.abs(det) < 1e-10) {
            // Degenerate tetrahedron
            return new float[]{0, 0, 0};
        }
        
        float[] x = new float[3];
        
        // Use Cramer's rule
        for (int i = 0; i < 3; i++) {
            float[][] Ai = new float[3][3];
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    Ai[j][k] = (k == i) ? b[j] : A[j][k];
                }
            }
            x[i] = determinant3x3(Ai) / det;
        }
        
        return x;
    }
    
    /**
     * Compute 3x3 matrix determinant
     */
    private float determinant3x3(float[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
               m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
               m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }
    
    /**
     * Compute tetrahedron volume
     */
    private float computeTetrahedronVolume(Point3i[] v) {
        // Volume = |det(v1-v0, v2-v0, v3-v0)| / 6
        float[][] mat = new float[3][3];
        for (int i = 0; i < 3; i++) {
            mat[0][i] = v[i+1].x - v[0].x;
            mat[1][i] = v[i+1].y - v[0].y;
            mat[2][i] = v[i+1].z - v[0].z;
        }
        
        return Math.abs(determinant3x3(mat)) / 6.0f;
    }
}
