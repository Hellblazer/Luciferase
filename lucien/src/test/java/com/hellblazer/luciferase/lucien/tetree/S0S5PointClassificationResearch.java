package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.HashMap;
import java.util.Map;

/**
 * Research test to understand S0-S5 point classification patterns.
 * This will help design the deterministic geometric classification algorithm.
 */
public class S0S5PointClassificationResearch {
    
    @Test
    void analyzeS0S5ContainmentPatterns() {
        System.out.println("=== S0-S5 Point Classification Research ===\n");
        
        // Test with unit cube at origin
        byte level = 10;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level); // 2048
        
        // Create all 6 tetrahedra
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, level, type);
        }
        
        // Analyze containment patterns for grid points
        analyzeGridPoints(tets, h);
        
        // Analyze cube vertices
        analyzeCubeVertices(tets, h);
        
        // Analyze cube center and face centers
        analyzeKeyPoints(tets, h);
        
        // Analyze diagonal relationships
        analyzeDiagonalPatterns(tets, h);
    }
    
    private void analyzeGridPoints(Tet[] tets, int h) {
        System.out.println("Grid Point Analysis:");
        System.out.println("===================");
        
        int gridSize = 8;
        float step = (float) h / gridSize;
        
        for (int i = 0; i <= gridSize; i++) {
            for (int j = 0; j <= gridSize; j++) {
                for (int k = 0; k <= gridSize; k++) {
                    float x = i * step;
                    float y = j * step; 
                    float z = k * step;
                    Point3f point = new Point3f(x, y, z);
                    
                    // Find which tetrahedra contain this point
                    StringBuilder containedBy = new StringBuilder();
                    for (byte type = 0; type < 6; type++) {
                        if (tets[type].contains(point)) {
                            if (!containedBy.isEmpty()) containedBy.append(",");
                            containedBy.append(type);
                        }
                    }
                    
                    // Normalize coordinates to [0,1]
                    float nx = x / h;
                    float ny = y / h;
                    float nz = z / h;
                    
                    if (!containedBy.isEmpty()) {
                        System.out.printf("(%.2f,%.2f,%.2f) -> types [%s]%n", 
                            nx, ny, nz, containedBy.toString());
                    }
                }
            }
        }
        System.out.println();
    }
    
    private void analyzeCubeVertices(Tet[] tets, int h) {
        System.out.println("Cube Vertex Analysis:");
        System.out.println("====================");
        
        // All 8 cube vertices
        Point3f[] vertices = {
            new Point3f(0, 0, 0),     // V0
            new Point3f(h, 0, 0),     // V1
            new Point3f(0, h, 0),     // V2
            new Point3f(h, h, 0),     // V3
            new Point3f(0, 0, h),     // V4
            new Point3f(h, 0, h),     // V5
            new Point3f(0, h, h),     // V6
            new Point3f(h, h, h)      // V7
        };
        
        for (int v = 0; v < 8; v++) {
            Point3f vertex = vertices[v];
            System.out.printf("V%d (%.0f,%.0f,%.0f): ", v, vertex.x, vertex.y, vertex.z);
            
            for (byte type = 0; type < 6; type++) {
                if (tets[type].contains(vertex)) {
                    System.out.printf("S%d ", type);
                }
            }
            System.out.println();
        }
        System.out.println();
    }
    
    private void analyzeKeyPoints(Tet[] tets, int h) {
        System.out.println("Key Point Analysis:");
        System.out.println("==================");
        
        // Center and face centers
        Point3f[] keyPoints = {
            new Point3f(h/2f, h/2f, h/2f),   // Cube center
            new Point3f(h/2f, h/2f, 0),      // Bottom face center
            new Point3f(h/2f, h/2f, h),      // Top face center
            new Point3f(h/2f, 0, h/2f),      // Front face center
            new Point3f(h/2f, h, h/2f),      // Back face center
            new Point3f(0, h/2f, h/2f),      // Left face center
            new Point3f(h, h/2f, h/2f)       // Right face center
        };
        
        String[] names = {"Center", "Bottom", "Top", "Front", "Back", "Left", "Right"};
        
        for (int i = 0; i < keyPoints.length; i++) {
            Point3f point = keyPoints[i];
            System.out.printf("%s (%.1f,%.1f,%.1f): ", names[i], point.x, point.y, point.z);
            
            for (byte type = 0; type < 6; type++) {
                if (tets[type].contains(point)) {
                    System.out.printf("S%d ", type);
                }
            }
            System.out.println();
        }
        System.out.println();
    }
    
    private void analyzeDiagonalPatterns(Tet[] tets, int h) {
        System.out.println("Diagonal Pattern Analysis:");
        System.out.println("=========================");
        
        // Points along main diagonal from (0,0,0) to (h,h,h)
        for (int i = 0; i <= 10; i++) {
            float t = i / 10f;
            Point3f point = new Point3f(t * h, t * h, t * h);
            
            System.out.printf("Diagonal t=%.1f (%.0f,%.0f,%.0f): ", t, point.x, point.y, point.z);
            
            for (byte type = 0; type < 6; type++) {
                if (tets[type].contains(point)) {
                    System.out.printf("S%d ", type);
                }
            }
            System.out.println();
        }
        System.out.println();
    }
    
    @Test
    void analyzeGeometricRegions() {
        System.out.println("=== Geometric Region Analysis ===\n");
        
        byte level = 10;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Test specific geometric hypotheses
        testCoordinateDominance(h);
        testDiagonalSplit(h);
        testSymmetryPatterns(h);
    }
    
    private void testCoordinateDominance(int h) {
        System.out.println("Coordinate Dominance Test:");
        System.out.println("=========================");
        
        // Test if tetrahedron type relates to which coordinate dominates
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, (byte)10, type);
        }
        
        // Test points where one coordinate dominates
        testDominantRegion(tets, h, "X-dominant", h*0.8f, h*0.3f, h*0.3f);
        testDominantRegion(tets, h, "Y-dominant", h*0.3f, h*0.8f, h*0.3f);
        testDominantRegion(tets, h, "Z-dominant", h*0.3f, h*0.3f, h*0.8f);
        
        System.out.println();
    }
    
    private void testDominantRegion(Tet[] tets, int h, String name, float x, float y, float z) {
        Point3f point = new Point3f(x, y, z);
        System.out.printf("%s (%.2f,%.2f,%.2f): ", name, x/h, y/h, z/h);
        
        for (byte type = 0; type < 6; type++) {
            if (tets[type].contains(point)) {
                System.out.printf("S%d ", type);
            }
        }
        System.out.println();
    }
    
    private void testDiagonalSplit(int h) {
        System.out.println("Diagonal Split Test:");
        System.out.println("===================");
        
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, (byte)10, type);
        }
        
        // Test hypothesis: x+y+z splits the tetrahedra into two groups
        for (float sum = 0.3f; sum <= 2.7f; sum += 0.3f) {
            // Points with same sum but different distributions
            Point3f p1 = new Point3f(sum*h/3, sum*h/3, sum*h/3);           // Equal distribution
            Point3f p2 = new Point3f(sum*h*0.6f, sum*h*0.3f, sum*h*0.1f);  // Unequal distribution
            
            System.out.printf("Sum=%.1f Equal (%.2f,%.2f,%.2f): ", sum, 
                p1.x/h, p1.y/h, p1.z/h);
            for (byte type = 0; type < 6; type++) {
                if (tets[type].contains(p1)) System.out.printf("S%d ", type);
            }
            
            System.out.printf(" | Unequal (%.2f,%.2f,%.2f): ", 
                p2.x/h, p2.y/h, p2.z/h);
            for (byte type = 0; type < 6; type++) {
                if (tets[type].contains(p2)) System.out.printf("S%d ", type);
            }
            System.out.println();
        }
        System.out.println();
    }
    
    private void testSymmetryPatterns(int h) {
        System.out.println("Symmetry Pattern Test:");
        System.out.println("=====================");
        
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, (byte)10, type);
        }
        
        // Test symmetric points
        float mid = h * 0.5f;
        Point3f[] symmetricPoints = {
            new Point3f(mid + h*0.2f, mid - h*0.1f, mid + h*0.1f),
            new Point3f(mid - h*0.2f, mid + h*0.1f, mid - h*0.1f),
            new Point3f(mid + h*0.1f, mid + h*0.2f, mid - h*0.1f),
            new Point3f(mid - h*0.1f, mid - h*0.2f, mid + h*0.1f)
        };
        
        for (int i = 0; i < symmetricPoints.length; i++) {
            Point3f p = symmetricPoints[i];
            System.out.printf("Point %d (%.2f,%.2f,%.2f): ", i+1, p.x/h, p.y/h, p.z/h);
            
            for (byte type = 0; type < 6; type++) {
                if (tets[type].contains(p)) {
                    System.out.printf("S%d ", type);
                }
            }
            System.out.println();
        }
    }
}