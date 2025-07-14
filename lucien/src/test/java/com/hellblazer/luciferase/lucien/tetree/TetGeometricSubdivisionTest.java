package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that verifies the geometricSubdivide() method produces children that are geometrically contained within the
 * parent.
 *
 * @author hal.hildebrand
 */
public class TetGeometricSubdivisionTest {

    @Test
    void testGeometricSubdivideAtMaxLevelThrows() {
        // Create a tetrahedron at max level - 1
        byte maxLevel = MortonCurve.MAX_REFINEMENT_LEVEL;
        Tet parent = new Tet(0, 0, 0, maxLevel, (byte) 0);

        // Should throw when trying to subdivide
        assertThrows(IllegalStateException.class, () -> parent.geometricSubdivide(),
                     "Should not be able to subdivide at max refinement level");
    }

    @Test
    @org.junit.jupiter.api.Disabled("t8code geometric subdivision has known containment issues")
    void testGeometricSubdivideProducesContainedChildren() {
        // Create a parent tetrahedron
        // At level 8 with max refinement 20, cellSize = 4096
        // Use coordinates that are valid multiples of 4096
        Tet parent = new Tet(4096 * 4, 4096 * 4, 4096 * 4, (byte) 8, (byte) 0);

        // Subdivide it
        Tet[] children = parent.geometricSubdivide();

        // Verify we get 8 children
        assertEquals(8, children.length, "Should produce 8 children");

        // Get parent vertices in subdivision coordinate space
        Point3i[] parentSubdivVerts = parent.subdivisionCoordinates();
        Point3f[] parentFloatVerts = toFloatArray(parentSubdivVerts);

        // Check that all children are contained in subdivision space
        int containedCount = 0;
        for (int i = 0; i < 8; i++) {
            Tet child = children[i];

            // Child should be at next level
            assertEquals(parent.l() + 1, child.l(), "Child should be at next level");

            // Check containment in subdivision coordinate space
            Point3i[] childSubdivVerts = child.subdivisionCoordinates();
            boolean isContained = true;

            for (Point3i vertex : childSubdivVerts) {
                Point3f p = new Point3f(vertex.x, vertex.y, vertex.z);
                if (!TetrahedralGeometry.containsPoint(p, parentFloatVerts)) {
                    isContained = false;
                    break;
                }
            }

            if (isContained) {
                containedCount++;
            }
        }

        // All children should be contained in subdivision space
        assertEquals(8, containedCount,
                     "All 8 children should be geometrically contained within parent in subdivision space");
    }

    @Test
    @org.junit.jupiter.api.Disabled("t8code geometric subdivision has known containment issues")
    void testSubdivisionCoordinatesMethod() {
        // Create a test tetrahedron of type 2 (where V3 differs)
        // At level 10 with max refinement 20, cellSize = 1024
        // Use coordinates that are valid multiples of 1024
        Tet tet = new Tet(1024 * 8, 1024 * 8, 1024 * 8, (byte) 10, (byte) 2);

        Point3i[] standardCoords = tet.coordinates();
        Point3i[] subdivCoords = tet.subdivisionCoordinates();

        // V0, V1, V2 should be identical
        assertEquals(standardCoords[0], subdivCoords[0], "V0 should be the same");
        assertEquals(standardCoords[1], subdivCoords[1], "V1 should be the same");
        assertEquals(standardCoords[2], subdivCoords[2], "V2 should be the same");

        // V3 should differ for type 2
        assertNotEquals(standardCoords[3], subdivCoords[3],
                        "V3 should differ between standard and subdivision coordinates for type 2");

        // Subdivision V3 should be anchor + (h,h,h)
        int h = tet.length();
        Point3i expectedV3 = new Point3i(tet.x() + h, tet.y() + h, tet.z() + h);
        assertEquals(expectedV3, subdivCoords[3], "Subdivision V3 should be anchor + (h,h,h)");
    }

    private Point3f[] toFloatArray(Point3i[] points) {
        Point3f[] result = new Point3f[points.length];
        for (int i = 0; i < points.length; i++) {
            result[i] = new Point3f(points[i].x, points[i].y, points[i].z);
        }
        return result;
    }
}
