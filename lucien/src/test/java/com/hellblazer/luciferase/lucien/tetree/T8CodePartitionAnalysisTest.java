package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Deep analysis of why t8code tetrahedra don't partition the cube.
 */
public class T8CodePartitionAnalysisTest {
    
    @Test
    void analyzePartitionIssues() {
        System.out.println("=== T8Code Partition Analysis ===\n");
        
        // First, let's verify the vertices form valid tetrahedra
        System.out.println("1. Verifying tetrahedra are valid (non-degenerate):");
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 21, type);
            Point3i[] vertices = tet.coordinates();
            double volume = computeTetrahedronVolume(vertices);
            System.out.printf("   Type %d volume: %.6f (valid: %s)\n", 
                type, volume, volume > 1e-6 ? "YES" : "NO");
        }
        
        // Check total volume
        System.out.println("\n2. Checking total volume coverage:");
        double totalVolume = 0;
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 21, type);
            totalVolume += computeTetrahedronVolume(tet.coordinates());
        }
        System.out.printf("   Total volume: %.6f (expected: 1.0)\n", totalVolume);
        System.out.printf("   Volume coverage: %.1f%%\n", totalVolume * 100);
        
        // Analyze interior points
        System.out.println("\n3. Analyzing interior point containment:");
        analyzeInteriorContainment();
        
        // Analyze the gap structure
        System.out.println("\n4. Analyzing gap structure:");
        analyzeGapStructure();
        
        // Check if this is a known tetrahedral decomposition
        System.out.println("\n5. Checking decomposition type:");
        checkDecompositionType();
    }
    
    private void analyzeInteriorContainment() {
        // Test points well inside the cube, away from boundaries
        float[][] testPoints = {
            {0.25f, 0.25f, 0.25f},
            {0.75f, 0.75f, 0.75f},
            {0.5f, 0.5f, 0.5f},
            {0.3f, 0.4f, 0.5f},
            {0.1f, 0.2f, 0.3f},
            {0.6f, 0.7f, 0.8f}
        };
        
        for (float[] p : testPoints) {
            Point3f point = new Point3f(p[0], p[1], p[2]);
            int containCount = 0;
            List<Integer> containers = new ArrayList<>();
            
            for (byte type = 0; type < 6; type++) {
                Tet tet = new Tet(0, 0, 0, (byte) 21, type);
                if (tet.contains(point)) {
                    containCount++;
                    containers.add((int) type);
                }
            }
            
            System.out.printf("   Point (%.2f, %.2f, %.2f): contained in %d tets %s\n",
                p[0], p[1], p[2], containCount, containers);
        }
    }
    
    private void analyzeGapStructure() {
        // Find a specific gap point and analyze it
        Point3f gapPoint = null;
        
        // Search for a gap point
        for (int i = 1; i < 10; i++) {
            for (int j = 1; j < 10; j++) {
                for (int k = 1; k < 10; k++) {
                    float x = i / 10.0f;
                    float y = j / 10.0f;
                    float z = k / 10.0f;
                    Point3f p = new Point3f(x, y, z);
                    
                    boolean inAnyTet = false;
                    for (byte type = 0; type < 6; type++) {
                        Tet tet = new Tet(0, 0, 0, (byte) 21, type);
                        if (tet.contains(p)) {
                            inAnyTet = true;
                            break;
                        }
                    }
                    
                    if (!inAnyTet) {
                        gapPoint = p;
                        break;
                    }
                }
                if (gapPoint != null) break;
            }
            if (gapPoint != null) break;
        }
        
        if (gapPoint != null) {
            System.out.printf("   Found gap at (%.2f, %.2f, %.2f)\n", 
                gapPoint.x, gapPoint.y, gapPoint.z);
            
            // Analyze distances to each tetrahedron
            System.out.println("   Distances to each tetrahedron:");
            for (byte type = 0; type < 6; type++) {
                Tet tet = new Tet(0, 0, 0, (byte) 21, type);
                Point3i[] vertices = tet.coordinates();
                
                // Compute centroid
                float cx = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
                float cy = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
                float cz = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;
                
                float dist = (float) Math.sqrt(
                    Math.pow(gapPoint.x - cx, 2) + 
                    Math.pow(gapPoint.y - cy, 2) + 
                    Math.pow(gapPoint.z - cz, 2)
                );
                
                System.out.printf("     Type %d centroid (%.2f, %.2f, %.2f), distance: %.3f\n",
                    type, cx, cy, cz, dist);
            }
        }
    }
    
    private void checkDecompositionType() {
        // Check if this matches known decompositions
        
        // Check if it's a Kuhn subdivision (which uses 6 tetrahedra)
        System.out.println("   Checking for Kuhn subdivision pattern...");
        
        // In Kuhn subdivision, all 6 tetrahedra share the main diagonal
        // from (0,0,0) to (1,1,1)
        boolean allShareDiagonal = true;
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 21, type);
            Point3i[] vertices = tet.coordinates();
            
            boolean hasOrigin = false;
            boolean hasOpposite = false;
            
            for (Point3i v : vertices) {
                if (v.x == 0 && v.y == 0 && v.z == 0) hasOrigin = true;
                if (v.x == 1 && v.y == 1 && v.z == 1) hasOpposite = true;
            }
            
            if (!hasOrigin || !hasOpposite) {
                allShareDiagonal = false;
                System.out.printf("     Type %d does NOT contain diagonal (0,0,0)-(1,1,1)\n", type);
            }
        }
        
        if (allShareDiagonal) {
            System.out.println("   ✓ All tetrahedra share the main diagonal - consistent with Kuhn subdivision");
        } else {
            System.out.println("   ✗ Not all tetrahedra share the main diagonal - NOT a standard Kuhn subdivision");
        }
        
        // Check edge patterns
        System.out.println("\n   Edge analysis:");
        Map<String, Integer> edgeCount = new HashMap<>();
        
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 21, type);
            Point3i[] vertices = tet.coordinates();
            
            // Count all edges
            for (int i = 0; i < 4; i++) {
                for (int j = i + 1; j < 4; j++) {
                    String edge = edgeKey(vertices[i], vertices[j]);
                    edgeCount.merge(edge, 1, Integer::sum);
                }
            }
        }
        
        // Analyze edge sharing
        int sharedEdges = 0;
        for (Map.Entry<String, Integer> entry : edgeCount.entrySet()) {
            if (entry.getValue() > 1) {
                sharedEdges++;
                if (entry.getValue() > 2) {
                    System.out.printf("     Edge %s shared by %d tetrahedra\n", 
                        entry.getKey(), entry.getValue());
                }
            }
        }
        System.out.printf("   Total edges: %d, Shared edges: %d\n", 
            edgeCount.size(), sharedEdges);
    }
    
    private String edgeKey(Point3i v1, Point3i v2) {
        // Create a canonical key for an edge
        if (v1.x < v2.x || (v1.x == v2.x && v1.y < v2.y) || 
            (v1.x == v2.x && v1.y == v2.y && v1.z < v2.z)) {
            return String.format("(%d,%d,%d)-(%d,%d,%d)", 
                v1.x, v1.y, v1.z, v2.x, v2.y, v2.z);
        } else {
            return String.format("(%d,%d,%d)-(%d,%d,%d)", 
                v2.x, v2.y, v2.z, v1.x, v1.y, v1.z);
        }
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
}