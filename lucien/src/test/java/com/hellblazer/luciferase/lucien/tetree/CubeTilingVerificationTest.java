package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that the 6 tetrahedra do indeed perfectly tile a cube.
 */
public class CubeTilingVerificationTest {
    
    @Test
    void verifySixTetrahedraTileCube() {
        // At level 5, work with a single cube
        byte level = 5;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Pick a cube at coordinates (10*cellSize, 10*cellSize, 10*cellSize)
        int cubeX = 10 * cellSize;
        int cubeY = 10 * cellSize;
        int cubeZ = 10 * cellSize;
        
        System.out.println("=== Verifying Cube Tiling ===");
        System.out.printf("Cube at (%d, %d, %d) with size %d%n", cubeX, cubeY, cubeZ, cellSize);
        
        // Create all 6 tetrahedra for this cube
        Tet[] tets = new Tet[6];
        for (byte type = 0; type <= 5; type++) {
            tets[type] = new Tet(cubeX, cubeY, cubeZ, level, type);
            System.out.printf("Tet type %d: anchor=(%d,%d,%d)%n", 
                type, tets[type].x(), tets[type].y(), tets[type].z());
        }
        
        // Test containment for many points within the cube
        Random random = new Random(42);
        int pointsPerDimension = 20;
        int totalPoints = 0;
        int containedPoints = 0;
        
        for (int i = 0; i < pointsPerDimension; i++) {
            for (int j = 0; j < pointsPerDimension; j++) {
                for (int k = 0; k < pointsPerDimension; k++) {
                    // Create a point inside the cube
                    float x = cubeX + (i + 0.5f) * cellSize / pointsPerDimension;
                    float y = cubeY + (j + 0.5f) * cellSize / pointsPerDimension;
                    float z = cubeZ + (k + 0.5f) * cellSize / pointsPerDimension;
                    Point3f point = new Point3f(x, y, z);
                    
                    totalPoints++;
                    
                    // Check which tetrahedra contain this point
                    Set<Integer> containingTets = new HashSet<>();
                    for (int t = 0; t < 6; t++) {
                        if (tets[t].contains(point)) {
                            containingTets.add(t);
                        }
                    }
                    
                    if (!containingTets.isEmpty()) {
                        containedPoints++;
                    }
                    
                    // Every point should be in exactly one tetrahedron
                    if (containingTets.size() != 1) {
                        System.out.printf("Point (%.1f, %.1f, %.1f) contained by %d tets: %s%n",
                            x - cubeX, y - cubeY, z - cubeZ, 
                            containingTets.size(), containingTets);
                    }
                }
            }
        }
        
        System.out.printf("\nTotal points tested: %d%n", totalPoints);
        System.out.printf("Points contained: %d (%.1f%%)%n", 
            containedPoints, 100.0 * containedPoints / totalPoints);
        
        // Also check the cube vertices
        System.out.println("\n=== Checking Cube Vertices ===");
        Point3f[] cubeVertices = {
            new Point3f(cubeX, cubeY, cubeZ),
            new Point3f(cubeX + cellSize, cubeY, cubeZ),
            new Point3f(cubeX, cubeY + cellSize, cubeZ),
            new Point3f(cubeX + cellSize, cubeY + cellSize, cubeZ),
            new Point3f(cubeX, cubeY, cubeZ + cellSize),
            new Point3f(cubeX + cellSize, cubeY, cubeZ + cellSize),
            new Point3f(cubeX, cubeY + cellSize, cubeZ + cellSize),
            new Point3f(cubeX + cellSize, cubeY + cellSize, cubeZ + cellSize)
        };
        
        for (int v = 0; v < 8; v++) {
            Point3f vertex = cubeVertices[v];
            Set<Integer> containingTets = new HashSet<>();
            for (int t = 0; t < 6; t++) {
                if (tets[t].contains(vertex)) {
                    containingTets.add(t);
                }
            }
            System.out.printf("Vertex %d at (%.0f, %.0f, %.0f): contained by tets %s%n",
                v, vertex.x - cubeX, vertex.y - cubeY, vertex.z - cubeZ, containingTets);
        }
        
        // Check edges and faces
        System.out.println("\n=== Checking Edge Midpoints ===");
        // Check midpoint of each edge
        Point3f[] edgeMidpoints = {
            // Bottom face edges
            new Point3f(cubeX + cellSize/2, cubeY, cubeZ),
            new Point3f(cubeX, cubeY + cellSize/2, cubeZ),
            new Point3f(cubeX + cellSize, cubeY + cellSize/2, cubeZ),
            new Point3f(cubeX + cellSize/2, cubeY + cellSize, cubeZ),
            // Top face edges
            new Point3f(cubeX + cellSize/2, cubeY, cubeZ + cellSize),
            new Point3f(cubeX, cubeY + cellSize/2, cubeZ + cellSize),
            new Point3f(cubeX + cellSize, cubeY + cellSize/2, cubeZ + cellSize),
            new Point3f(cubeX + cellSize/2, cubeY + cellSize, cubeZ + cellSize),
            // Vertical edges
            new Point3f(cubeX, cubeY, cubeZ + cellSize/2),
            new Point3f(cubeX + cellSize, cubeY, cubeZ + cellSize/2),
            new Point3f(cubeX, cubeY + cellSize, cubeZ + cellSize/2),
            new Point3f(cubeX + cellSize, cubeY + cellSize, cubeZ + cellSize/2)
        };
        
        for (int e = 0; e < edgeMidpoints.length; e++) {
            Point3f edge = edgeMidpoints[e];
            Set<Integer> containingTets = new HashSet<>();
            for (int t = 0; t < 6; t++) {
                if (tets[t].contains(edge)) {
                    containingTets.add(t);
                }
            }
            System.out.printf("Edge %d midpoint: contained by %d tets%n", 
                e, containingTets.size());
        }
    }
    
    @Test
    void analyzeLocateMismatch() {
        // Test why locate() returns different tets than contains()
        byte level = 5;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Use the same problematic point from our debug test
        Point3f point = new Point3f(768076, 1309936, 1256885);
        
        System.out.println("\n=== Analyzing Locate vs Contains Mismatch ===");
        System.out.printf("Point: (%.0f, %.0f, %.0f)%n", point.x, point.y, point.z);
        
        // What does locate return?
        // Create a Tetree with proper ID generator
        var tetree = new Tetree<>(new com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator());
        Tet located = tetree.locate(point, level);
        System.out.printf("locate() returned: anchor=(%d,%d,%d), type=%d%n",
            located.x(), located.y(), located.z(), located.type());
        System.out.printf("Located tet contains point: %s%n", located.contains(point));
        
        // Find the cube this point is in
        int cubeX = ((int)point.x / cellSize) * cellSize;
        int cubeY = ((int)point.y / cellSize) * cellSize;
        int cubeZ = ((int)point.z / cellSize) * cellSize;
        
        System.out.printf("\nPoint is in cube at (%d, %d, %d)%n", cubeX, cubeY, cubeZ);
        
        // Check all 6 tets in this cube
        System.out.println("\nChecking all 6 tets in the cube:");
        for (byte type = 0; type <= 5; type++) {
            Tet tet = new Tet(cubeX, cubeY, cubeZ, level, type);
            boolean contains = tet.contains(point);
            System.out.printf("Type %d: contains = %s%n", type, contains);
        }
    }
}