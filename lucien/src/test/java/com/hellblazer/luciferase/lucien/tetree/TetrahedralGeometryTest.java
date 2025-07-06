package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to understand the tetrahedral geometry and why containment is failing.
 */
public class TetrahedralGeometryTest {
    
    @Test
    void testTetCoordinatesAndContainment() {
        System.out.println("=== Testing Tet Coordinates and Containment ===\n");
        
        // Test at level 10 with a specific cube
        byte level = 10;
        var cellSize = Constants.lengthAtLevel(level);
        int anchorX = 0;
        int anchorY = 0;
        int anchorZ = 0;
        
        System.out.printf("Level %d, cell size = %d\n", level, cellSize);
        System.out.printf("Cube anchor at (%d, %d, %d)\n\n", anchorX, anchorY, anchorZ);
        
        // Print the vertices for each tetrahedron type
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(anchorX, anchorY, anchorZ, level, type);
            var coords = tet.coordinates();
            
            System.out.printf("Type %d tetrahedron vertices:\n", type);
            for (int i = 0; i < 4; i++) {
                System.out.printf("  v%d: (%d, %d, %d)\n", i, coords[i].x, coords[i].y, coords[i].z);
            }
            
            // Test containment at the centroid
            float cx = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4.0f;
            float cy = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4.0f;
            float cz = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4.0f;
            Point3f centroid = new Point3f(cx, cy, cz);
            
            boolean containsCentroid = tet.contains(centroid);
            System.out.printf("  Centroid (%.1f, %.1f, %.1f) contained: %s\n", cx, cy, cz, containsCentroid);
            
            // Test a point inside the cube
            Point3f testPoint = new Point3f(cellSize * 0.5f, cellSize * 0.5f, cellSize * 0.5f);
            boolean containsTest = tet.contains(testPoint);
            System.out.printf("  Test point (%.1f, %.1f, %.1f) contained: %s\n", 
                testPoint.x, testPoint.y, testPoint.z, containsTest);
            
            System.out.println();
        }
        
        // Test specific problem points
        System.out.println("Testing specific problem points:");
        float[][] problemPoints = {
            {0.05f, 0.1f, 0.2f},
            {0.05f, 0.1f, 0.25f},
            {0.05f, 0.15f, 0.25f}
        };
        
        for (float[] p : problemPoints) {
            Point3f point = new Point3f(p[0] * cellSize, p[1] * cellSize, p[2] * cellSize);
            System.out.printf("\nPoint (%.2f, %.2f, %.2f) = (%.0f, %.0f, %.0f):\n", 
                p[0], p[1], p[2], point.x, point.y, point.z);
            
            int containerCount = 0;
            for (byte type = 0; type < 6; type++) {
                var tet = new Tet(anchorX, anchorY, anchorZ, level, type);
                if (tet.contains(point)) {
                    System.out.printf("  Contained in type %d\n", type);
                    containerCount++;
                }
            }
            if (containerCount == 0) {
                System.out.println("  NOT CONTAINED IN ANY TETRAHEDRON!");
            }
        }
    }
    
    @Test
    void testSimplexStandardCoordinates() {
        System.out.println("\n=== Testing SIMPLEX_STANDARD Coordinates ===\n");
        
        // For a unit cube, show the actual vertices
        for (int type = 0; type < 6; type++) {
            System.out.printf("Type %d: ", type);
            var vertices = Constants.SIMPLEX_STANDARD[type];
            for (int i = 0; i < 4; i++) {
                System.out.printf("(%d,%d,%d) ", vertices[i].x, vertices[i].y, vertices[i].z);
            }
            System.out.println();
        }
        
        // Check if the vertices actually span a unit cube
        System.out.println("\nChecking cube coverage:");
        Point3i[] cubeVertices = {
            new Point3i(0, 0, 0), new Point3i(1, 0, 0),
            new Point3i(0, 1, 0), new Point3i(1, 1, 0),
            new Point3i(0, 0, 1), new Point3i(1, 0, 1),
            new Point3i(0, 1, 1), new Point3i(1, 1, 1)
        };
        
        for (var vertex : cubeVertices) {
            System.out.printf("Cube vertex (%d,%d,%d) appears in types: ", vertex.x, vertex.y, vertex.z);
            for (int type = 0; type < 6; type++) {
                var tetVertices = Constants.SIMPLEX_STANDARD[type];
                for (var tv : tetVertices) {
                    if (tv.x == vertex.x && tv.y == vertex.y && tv.z == vertex.z) {
                        System.out.print(type + " ");
                        break;
                    }
                }
            }
            System.out.println();
        }
    }
    
    @Test
    void testTetLength() {
        System.out.println("\n=== Testing Tet.length() ===\n");
        
        for (byte level = 0; level <= 5; level++) {
            var tet = new Tet(0, 0, 0, level, (byte)0);
            var length = tet.length();
            var expected = Constants.lengthAtLevel(level);
            System.out.printf("Level %d: tet.length() = %d, Constants.lengthAtLevel() = %d\n",
                level, length, expected);
            assertEquals(expected, length);
        }
    }
}