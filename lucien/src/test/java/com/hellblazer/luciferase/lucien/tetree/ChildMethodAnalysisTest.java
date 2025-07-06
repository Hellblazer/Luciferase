package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Analyzes why the child() method doesn't produce fully contained children
 *
 * @author hal.hildebrand
 */
public class ChildMethodAnalysisTest {

    @Test
    void analyzeChildMethodContainment() {
        System.out.println("\n=== ANALYZING CHILD() METHOD CONTAINMENT ===\n");

        // Create a simple parent
        Tet parent = new Tet(0, 0, 0, (byte) 5, (byte) 0);
        System.out.println("Parent: " + parent);

        // Print parent vertices
        Point3i[] parentVerts = parent.coordinates();
        System.out.println("Parent vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.printf("  V%d: (%d, %d, %d)\n", i, parentVerts[i].x, parentVerts[i].y, parentVerts[i].z);
        }

        // Analyze each child
        System.out.println("\nChildren analysis:");
        for (int i = 0; i < 8; i++) {
            Tet child = parent.child(i);
            System.out.printf("\nChild %d: pos=(%d,%d,%d), type=%d\n", i, child.x(), child.y(), child.z(),
                              child.type());

            // Get child vertices
            Point3i[] childVerts = child.coordinates();

            // Check each vertex
            Point3f[] parentFloatVerts = toFloatArray(parentVerts);
            for (int v = 0; v < 4; v++) {
                Point3f p = new Point3f(childVerts[v].x, childVerts[v].y, childVerts[v].z);
                boolean inside = TetrahedralGeometry.containsPoint(p, parentFloatVerts);
                System.out.printf("    V%d: (%d, %d, %d) - %s\n", v, childVerts[v].x, childVerts[v].y, childVerts[v].z,
                                  inside ? "inside" : "OUTSIDE");
            }
        }
    }

    @Test
    void compareWithTetrahedralSubdivision() {
        System.out.println("\n=== COMPARING WITH TetrahedralSubdivision CLASS ===\n");

        // Create equivalent parent in both systems
        int level = 5;
        int h = 1 << (30 - level); // TetrahedralSubdivision uses max level 30

        // TetrahedralSubdivision parent
        TetrahedralSubdivision.Tet tsParent = new TetrahedralSubdivision.Tet(level, 0, 0, 0, 0);
        TetrahedralSubdivision.Tet[] tsChildren = TetrahedralSubdivision.subdivide(tsParent);

        // Our Tet parent - need to scale coordinates
        // Our system uses 20 bits, theirs uses 30, so scale factor is 2^9 = 512
        Tet ourParent = new Tet(0, 0, 0, (byte) level, (byte) 0);

        System.out.println("TetrahedralSubdivision children:");
        for (int i = 0; i < 8; i++) {
            TetrahedralSubdivision.Tet tsc = tsChildren[i];
            System.out.printf("  Child %d: pos=(%d,%d,%d), type=%d\n", i, tsc.x, tsc.y, tsc.z, tsc.type);
        }

        System.out.println("\nOur Tet.child() children:");
        for (int i = 0; i < 8; i++) {
            Tet child = ourParent.child(i);
            System.out.printf("  Child %d: pos=(%d,%d,%d), type=%d\n", i, child.x(), child.y(), child.z(),
                              child.type());
        }

        // The key difference: TetrahedralSubdivision computes exact midpoints
        // based on the mathematical subdivision, while our child() method
        // might be using a different algorithm
    }

    @Test
    void testCorrectSubdivisionAlgorithm() {
        System.out.println("\n=== IMPLEMENTING CORRECT SUBDIVISION ===\n");

        // Create all 8 children using the correct algorithm
        Tet parent = new Tet(0, 0, 0, (byte) 5, (byte) 0);

        // Get parent vertices
        Point3i[] verts = parent.coordinates();
        Point3i x0 = verts[0];
        Point3i x1 = verts[1];
        Point3i x2 = verts[2];
        Point3i x3 = verts[3];

        System.out.println("Parent vertices:");
        System.out.printf("  x0: (%d,%d,%d)\n", x0.x, x0.y, x0.z);
        System.out.printf("  x1: (%d,%d,%d)\n", x1.x, x1.y, x1.z);
        System.out.printf("  x2: (%d,%d,%d)\n", x2.x, x2.y, x2.z);
        System.out.printf("  x3: (%d,%d,%d)\n", x3.x, x3.y, x3.z);

        // Compute midpoints
        Point3i x01 = midpoint(x0, x1);
        Point3i x02 = midpoint(x0, x2);
        Point3i x03 = midpoint(x0, x3);
        Point3i x12 = midpoint(x1, x2);
        Point3i x13 = midpoint(x1, x3);
        Point3i x23 = midpoint(x2, x3);

        System.out.println("\nMidpoints:");
        System.out.printf("  x01: (%d,%d,%d)\n", x01.x, x01.y, x01.z);
        System.out.printf("  x02: (%d,%d,%d)\n", x02.x, x02.y, x02.z);
        System.out.printf("  x03: (%d,%d,%d)\n", x03.x, x03.y, x03.z);
        System.out.printf("  x12: (%d,%d,%d)\n", x12.x, x12.y, x12.z);
        System.out.printf("  x13: (%d,%d,%d)\n", x13.x, x13.y, x13.z);
        System.out.printf("  x23: (%d,%d,%d)\n", x23.x, x23.y, x23.z);

        // The 8 children in Bey order have these anchor points:
        // T0: x0, T1: x01, T2: x02, T3: x03
        // T4: x01, T5: x01, T6: x02, T7: x02

        System.out.println("\nCorrect child anchors (Bey order):");
        Point3i[] anchors = { x0, x01, x02, x03, x01, x01, x02, x02 };
        for (int i = 0; i < 8; i++) {
            System.out.printf("  Child %d anchor: (%d,%d,%d)\n", i, anchors[i].x, anchors[i].y, anchors[i].z);
        }
    }

    private Point3i midpoint(Point3i a, Point3i b) {
        return new Point3i((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2);
    }

    private Point3f[] toFloatArray(Point3i[] points) {
        Point3f[] result = new Point3f[points.length];
        for (int i = 0; i < points.length; i++) {
            result[i] = new Point3f(points[i].x, points[i].y, points[i].z);
        }
        return result;
    }
}
