package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import javax.vecmath.Point3f;

/**
 * Visualizes the geometric subdivision pattern for understanding
 * 
 * @author hal.hildebrand
 */
public class SubdivisionVisualizationTest {
    
    @Test
    void visualizeSubdivisionPattern() {
        System.out.println("Tetrahedral Subdivision Pattern Visualization");
        System.out.println("============================================\n");
        
        // Create a parent tetrahedron
        byte level = 10;
        int cellSize = 1 << (21 - level);
        Tet parent = new Tet(2 * cellSize, 3 * cellSize, 4 * cellSize, level, (byte)0);
        
        System.out.println("Parent Tetrahedron:");
        System.out.println("  Position: (" + parent.x() + ", " + parent.y() + ", " + parent.z() + ")");
        System.out.println("  Level: " + parent.l());
        System.out.println("  Type: " + parent.type());
        System.out.println("  Cell size: " + cellSize);
        
        // Get parent vertices
        Point3i[] vertices = parent.coordinates();
        System.out.println("\nParent Vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  V" + i + ": " + pointToString(vertices[i]));
        }
        
        // Calculate edge midpoints
        System.out.println("\nEdge Midpoints:");
        Point3f[] floatVerts = toFloatArray(vertices);
        Point3f[] midpoints = new Point3f[6];
        int idx = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                midpoints[idx] = midpoint(floatVerts[i], floatVerts[j]);
                System.out.println("  M" + i + j + ": " + pointToString(midpoints[idx]));
                idx++;
            }
        }
        
        // Calculate center (parent centroid)
        Point3f center = new Point3f(
            (floatVerts[0].x + floatVerts[1].x + floatVerts[2].x + floatVerts[3].x) / 4.0f,
            (floatVerts[0].y + floatVerts[1].y + floatVerts[2].y + floatVerts[3].y) / 4.0f,
            (floatVerts[0].z + floatVerts[1].z + floatVerts[2].z + floatVerts[3].z) / 4.0f
        );
        System.out.println("\nCenter Point: " + pointToString(center));
        
        // Show child assignments
        System.out.println("\nChild Assignments (from connectivity table):");
        String[] refNames = {"V0", "V1", "V2", "V3", "M01", "M02", "M03", "M12", "M13", "M23", "Center"};
        
        for (int child = 0; child < 8; child++) {
            byte[] refs = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child];
            System.out.print("  Child " + child + ": [");
            for (int i = 0; i < 4; i++) {
                if (i > 0) System.out.print(", ");
                System.out.print(refNames[refs[i]]);
            }
            
            // Add description
            String desc = switch(child) {
                case 0 -> " (Interior octahedral)";
                case 1 -> " (Corner at V0)";
                case 2 -> " (Corner at V1)";
                case 3 -> " (Corner at V2)";
                case 4 -> " (Corner at V3)";
                case 5, 6, 7 -> " (Octahedral)";
                default -> "";
            };
            System.out.println("]" + desc);
        }
        
        // Visualize octahedron
        System.out.println("\nOctahedron formed by edge midpoints:");
        System.out.println("  Vertices: M01, M02, M03, M12, M13, M23");
        System.out.println("  This octahedron is split into 4 tetrahedra");
        System.out.println("  using the center point");
        
        // Show splitting pattern
        System.out.println("\nOctahedral children from splitting:");
        System.out.println("  Child 0: [M01, M02, M03, Center]");
        System.out.println("  Child 5: [Center, M02, M03, M23]");
        System.out.println("  Child 6: [M01, Center, M13, M12]");
        System.out.println("  Child 7: [Center, M23, M13, M12]");
        
        // Verify volume relationships
        System.out.println("\nVolume Relationships:");
        float parentVolume = computeVolume(floatVerts);
        System.out.println("  Parent volume: " + parentVolume);
        System.out.println("  Expected child volume: " + (parentVolume / 8));
        System.out.println("  Volume ratio check: Each child should have 1/8 of parent volume");
    }
    
    @Test
    void visualizeGridFittingChallenge() {
        System.out.println("\nGrid Fitting Challenge Visualization");
        System.out.println("====================================\n");
        
        // Show a child that might not align perfectly to grid
        byte level = 5; // Coarser level to see the issue
        int cellSize = 1 << (21 - level);
        
        System.out.println("Grid cell size at level " + level + ": " + cellSize);
        System.out.println("\nExample geometric child centroid: (10234.5, 15678.3, 20122.7)");
        
        // Show quantization
        Point3f centroid = new Point3f(10234.5f, 15678.3f, 20122.7f);
        Point3i quantized = new Point3i(
            Math.round(centroid.x / cellSize) * cellSize,
            Math.round(centroid.y / cellSize) * cellSize,
            Math.round(centroid.z / cellSize) * cellSize
        );
        
        System.out.println("Quantized to grid: " + pointToString(quantized));
        System.out.println("Quantization error: " + 
            Math.abs(centroid.x - quantized.x) + ", " +
            Math.abs(centroid.y - quantized.y) + ", " +
            Math.abs(centroid.z - quantized.z));
        
        System.out.println("\nChallenge: The quantized position might place the child");
        System.out.println("tetrahedron partially outside the parent!");
    }
    
    private Point3f[] toFloatArray(Point3i[] points) {
        Point3f[] result = new Point3f[points.length];
        for (int i = 0; i < points.length; i++) {
            result[i] = new Point3f(points[i].x, points[i].y, points[i].z);
        }
        return result;
    }
    
    private Point3f midpoint(Point3f a, Point3f b) {
        return new Point3f(
            (a.x + b.x) / 2.0f,
            (a.y + b.y) / 2.0f,
            (a.z + b.z) / 2.0f
        );
    }
    
    private float computeVolume(Point3f[] verts) {
        // Tetrahedron volume = |det(v1-v0, v2-v0, v3-v0)| / 6
        float x1 = verts[1].x - verts[0].x;
        float y1 = verts[1].y - verts[0].y;
        float z1 = verts[1].z - verts[0].z;
        
        float x2 = verts[2].x - verts[0].x;
        float y2 = verts[2].y - verts[0].y;
        float z2 = verts[2].z - verts[0].z;
        
        float x3 = verts[3].x - verts[0].x;
        float y3 = verts[3].y - verts[0].y;
        float z3 = verts[3].z - verts[0].z;
        
        float det = x1 * (y2 * z3 - y3 * z2) - 
                    y1 * (x2 * z3 - x3 * z2) + 
                    z1 * (x2 * y3 - x3 * y2);
        
        return Math.abs(det) / 6.0f;
    }
    
    private String pointToString(Point3i p) {
        return "(" + p.x + ", " + p.y + ", " + p.z + ")";
    }
    
    private String pointToString(Point3f p) {
        return String.format("(%.1f, %.1f, %.1f)", p.x, p.y, p.z);
    }
}