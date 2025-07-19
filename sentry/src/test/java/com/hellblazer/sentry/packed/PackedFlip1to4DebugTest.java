package com.hellblazer.sentry.packed;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Debug test for flip 1-to-4 operation
 */
public class PackedFlip1to4DebugTest {
    
    @Test
    public void debugFlip1to4() {
        PackedGrid grid = new PackedGrid();
        PackedFlipOperations flips = new PackedFlipOperations(grid);
        
        System.out.println("Initial tetrahedron 0:");
        printTetrahedron(grid, 0);
        
        // Add a vertex to insert
        int newVertex = grid.addVertex(0, 0, 0);
        System.out.println("\nNew vertex " + newVertex + " at (0, 0, 0)");
        
        // Perform flip
        List<Integer> ears = new ArrayList<>();
        int result = flips.flip1to4(0, newVertex, ears);
        
        System.out.println("\nAfter flip, returned tet: " + result);
        System.out.println("Ears size: " + ears.size());
        
        // Print all new tetrahedra
        for (int i = 1; i <= 4; i++) {
            if (grid.isValidTetrahedron(i)) {
                System.out.println("\nTetrahedron " + i + ":");
                printTetrahedron(grid, i);
            }
        }
        
        // Check containment of test point
        Point3f testPoint = new Point3f(100, 100, 100);
        System.out.println("\nChecking containment of point " + testPoint + ":");
        
        for (int i = 1; i <= 4; i++) {
            if (grid.isValidTetrahedron(i)) {
                TetrahedronProxy tet = new TetrahedronProxy(grid, i);
                boolean inside = true;
                System.out.println("\nTet " + i + " orientations:");
                for (int face = 0; face < 4; face++) {
                    double orient = tet.orientationWrt(face, testPoint);
                    System.out.println("  Face " + face + ": " + orient);
                    if (orient < 0) {
                        inside = false;
                    }
                }
                System.out.println("  Inside: " + inside);
            }
        }
    }
    
    private void printTetrahedron(PackedGrid grid, int tetIndex) {
        TetrahedronProxy tet = new TetrahedronProxy(grid, tetIndex);
        System.out.println("  Vertices: [" + tet.a() + ", " + tet.b() + 
                          ", " + tet.c() + ", " + tet.d() + "]");
        System.out.println("  Neighbors:");
        for (int i = 0; i < 4; i++) {
            TetrahedronProxy n = tet.getNeighbor(i);
            System.out.println("    Face " + i + ": " + 
                              (n != null ? n.getIndex() : "null"));
        }
        
        // Print vertex coordinates
        float[] coords = new float[3];
        System.out.println("  Vertex coordinates:");
        for (int i = 0; i < 4; i++) {
            int vIdx = tet.getVertex(i);
            grid.getVertexCoords(vIdx, coords);
            System.out.println("    V" + i + " (idx " + vIdx + "): (" + 
                              coords[0] + ", " + coords[1] + ", " + coords[2] + ")");
        }
    }
}