package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the geometric utilities in TetrahedralGeometry
 */
public class TetrahedralGeometryEnhancementsTest {

    @Test
    void testContainsPoint() {
        // Create a simple tetrahedron
        Point3f[] vertices = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(0, 0, 100)
        };

        // Test center point (should be inside)
        Point3f center = new Point3f(25, 25, 25);
        assertTrue(TetrahedralGeometry.containsPoint(center, vertices),
            "Center point should be inside tetrahedron");

        // Test vertex (should be inside/on boundary)
        assertTrue(TetrahedralGeometry.containsPoint(vertices[0], vertices),
            "Vertex should be inside tetrahedron");

        // Test point outside
        Point3f outside = new Point3f(200, 200, 200);
        assertFalse(TetrahedralGeometry.containsPoint(outside, vertices),
            "Far point should be outside tetrahedron");

        // Test point near face
        Point3f nearFace = new Point3f(30, 30, 39);
        assertTrue(TetrahedralGeometry.containsPoint(nearFace, vertices),
            "Point near face should be inside");

        // Test point just outside face
        Point3f justOutside = new Point3f(35, 35, 31);
        assertFalse(TetrahedralGeometry.containsPoint(justOutside, vertices),
            "Point just outside face should be outside");
    }

    @Test
    void testContainsPointDegenerateTetrahedron() {
        // Create a degenerate tetrahedron (all points coplanar)
        Point3f[] vertices = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(50, 50, 0)
        };

        Point3f testPoint = new Point3f(25, 25, 0);
        assertFalse(TetrahedralGeometry.containsPoint(testPoint, vertices),
            "Degenerate tetrahedron should return false");
    }

    @Test
    void testContainsPointInvalidInput() {
        Point3f[] invalidVertices = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0)
        };

        Point3f point = new Point3f(25, 25, 25);

        assertThrows(IllegalArgumentException.class,
            () -> TetrahedralGeometry.containsPoint(point, invalidVertices),
            "Should throw exception for invalid vertex count");
    }
}
