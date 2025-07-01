package com.hellblazer.luciferase.lucien.tetree;

import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import org.junit.jupiter.api.Test;

import com.hellblazer.luciferase.lucien.tetree.TetrahedralGeometry;

/**
 * Validates the vertex computation algorithm in Tet.coordinates()
 * 
 * @author hal.hildebrand
 */
public class VertexComputationValidationTest {

    @Test
    void testVertexComputationForAllTypes() {
        // Test at level 10 (cell size = 2048)
        byte level = 10;
        int cellSize = 1 << (21 - level); // 2048
        int x = 2 * cellSize; // 4096 - aligned to grid
        int y = 3 * cellSize; // 6144 - aligned to grid  
        int z = 4 * cellSize; // 8192 - aligned to grid
        
        System.out.println("Vertex Computation Analysis for All Types");
        System.out.println("=========================================");
        System.out.println("Anchor: (" + x + ", " + y + ", " + z + ")");
        System.out.println("Level: " + level);
        
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(x, y, z, level, type);
            Point3i[] vertices = tet.coordinates();
            int h = tet.length();
            
            System.out.println("\nType " + type + ":");
            System.out.println("  Cell size (h): " + h);
            
            // Compute ei and ej
            int ei = type / 2;
            int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;
            System.out.println("  ei=" + ei + " (" + dimensionName(ei) + "), ej=" + ej + " (" + dimensionName(ej) + ")");
            
            // Print vertices
            for (int i = 0; i < 4; i++) {
                System.out.println("  V" + i + ": " + pointToString(vertices[i]));
            }
            
            // Validate vertex positions match expected pattern
            validateVertexPattern(vertices, x, y, z, h, type);
            
            // Calculate and validate volume
            float volume = calculateVolume(vertices);
            float expectedVolume = (float)h * (float)h * (float)h / 6.0f;
            System.out.println("  Volume: " + volume + " (expected: " + expectedVolume + ")");
            
            // Validate orientation (volume should be positive)
            assertTrue(volume > 0, "Negative volume for type " + type);
        }
    }
    
    @Test
    void testEdgeIdentification() {
        System.out.println("\nEdge Identification Test");
        System.out.println("========================");
        
        // Use grid-aligned coordinates
        byte level = 10;
        int cellSize = 1 << (21 - level);
        Tet tet = new Tet(2 * cellSize, 3 * cellSize, 4 * cellSize, level, (byte)0);
        Point3i[] vertices = tet.coordinates();
        
        // List all 6 edges
        System.out.println("Edges:");
        System.out.println("  Edge 0-1: " + pointToString(vertices[0]) + " to " + pointToString(vertices[1]));
        System.out.println("  Edge 0-2: " + pointToString(vertices[0]) + " to " + pointToString(vertices[2]));
        System.out.println("  Edge 0-3: " + pointToString(vertices[0]) + " to " + pointToString(vertices[3]));
        System.out.println("  Edge 1-2: " + pointToString(vertices[1]) + " to " + pointToString(vertices[2]));
        System.out.println("  Edge 1-3: " + pointToString(vertices[1]) + " to " + pointToString(vertices[3]));
        System.out.println("  Edge 2-3: " + pointToString(vertices[2]) + " to " + pointToString(vertices[3]));
        
        // Calculate edge midpoints
        System.out.println("\nEdge Midpoints:");
        Point3f[] midpoints = computeEdgeMidpoints(vertices);
        System.out.println("  M01: " + pointToString(midpoints[0]));
        System.out.println("  M02: " + pointToString(midpoints[1]));
        System.out.println("  M03: " + pointToString(midpoints[2]));
        System.out.println("  M12: " + pointToString(midpoints[3]));
        System.out.println("  M13: " + pointToString(midpoints[4]));
        System.out.println("  M23: " + pointToString(midpoints[5]));
    }
    
    @Test
    void testFaceIdentification() {
        System.out.println("\nFace Identification Test");
        System.out.println("========================");
        
        Tet tet = new Tet(0, 0, 0, (byte)10, (byte)0);
        Point3i[] vertices = tet.coordinates();
        
        System.out.println("Faces (each opposite to a vertex):");
        System.out.println("  Face 0 (opposite V0): V1-V2-V3");
        System.out.println("  Face 1 (opposite V1): V0-V2-V3");
        System.out.println("  Face 2 (opposite V2): V0-V1-V3");
        System.out.println("  Face 3 (opposite V3): V0-V1-V2");
    }
    
    @Test
    void testTypePairs() {
        System.out.println("\nType Pairs Analysis");
        System.out.println("===================");
        
        for (byte type = 0; type < 6; type++) {
            int ei = type / 2;
            int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;
            
            System.out.println("Type " + type + ": ei=" + ei + " (" + dimensionName(ei) + 
                             "), ej=" + ej + " (" + dimensionName(ej) + ")");
        }
        
        System.out.println("\nGrouped by primary axis:");
        System.out.println("  X-axis primary (ei=0): Types 0, 1");
        System.out.println("  Y-axis primary (ei=1): Types 2, 3");
        System.out.println("  Z-axis primary (ei=2): Types 4, 5");
    }
    
    private void validateVertexPattern(Point3i[] vertices, int x, int y, int z, int h, byte type) {
        // Verify V0 is at anchor
        assertEquals(x, vertices[0].x);
        assertEquals(y, vertices[0].y);
        assertEquals(z, vertices[0].z);
        
        // Compute expected positions based on type
        int ei = type / 2;
        int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;
        
        // V1 should be anchor + h in dimension ei
        Point3i expectedV1 = new Point3i(x, y, z);
        addToDimension(expectedV1, ei, h);
        assertEquals(expectedV1, vertices[1], "V1 mismatch for type " + type);
        
        // V2 should be anchor + h in ei + h in ej
        Point3i expectedV2 = new Point3i(x, y, z);
        addToDimension(expectedV2, ei, h);
        addToDimension(expectedV2, ej, h);
        assertEquals(expectedV2, vertices[2], "V2 mismatch for type " + type);
        
        // V3 should be anchor + h in (ei+1)%3 and (ei+2)%3
        Point3i expectedV3 = new Point3i(x, y, z);
        addToDimension(expectedV3, (ei + 1) % 3, h);
        addToDimension(expectedV3, (ei + 2) % 3, h);
        assertEquals(expectedV3, vertices[3], "V3 mismatch for type " + type);
    }
    
    private void addToDimension(Point3i point, int dimension, int h) {
        switch (dimension) {
            case 0 -> point.x += h;
            case 1 -> point.y += h;
            case 2 -> point.z += h;
            default -> throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
    }
    
    private float calculateVolume(Point3i[] vertices) {
        // Calculate tetrahedron volume using the formula:
        // V = |det(v1-v0, v2-v0, v3-v0)| / 6
        float x1 = vertices[1].x - vertices[0].x;
        float y1 = vertices[1].y - vertices[0].y;
        float z1 = vertices[1].z - vertices[0].z;
        
        float x2 = vertices[2].x - vertices[0].x;
        float y2 = vertices[2].y - vertices[0].y;
        float z2 = vertices[2].z - vertices[0].z;
        
        float x3 = vertices[3].x - vertices[0].x;
        float y3 = vertices[3].y - vertices[0].y;
        float z3 = vertices[3].z - vertices[0].z;
        
        // Calculate determinant
        float det = x1 * (y2 * z3 - y3 * z2) - 
                    y1 * (x2 * z3 - x3 * z2) + 
                    z1 * (x2 * y3 - x3 * y2);
        
        return Math.abs(det) / 6.0f;
    }
    
    private Point3f[] computeEdgeMidpoints(Point3i[] vertices) {
        Point3f[] midpoints = new Point3f[6];
        int index = 0;
        
        // Generate all 6 edges (combinations of 4 vertices taken 2 at a time)
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                midpoints[index++] = new Point3f(
                    (vertices[i].x + vertices[j].x) / 2.0f,
                    (vertices[i].y + vertices[j].y) / 2.0f,
                    (vertices[i].z + vertices[j].z) / 2.0f
                );
            }
        }
        
        return midpoints;
    }
    
    private String dimensionName(int dim) {
        return switch(dim) {
            case 0 -> "X";
            case 1 -> "Y";
            case 2 -> "Z";
            default -> "?";
        };
    }
    
    private String pointToString(Point3i p) {
        return "(" + p.x + ", " + p.y + ", " + p.z + ")";
    }
    
    private String pointToString(Point3f p) {
        return String.format("(%.1f, %.1f, %.1f)", p.x, p.y, p.z);
    }
}