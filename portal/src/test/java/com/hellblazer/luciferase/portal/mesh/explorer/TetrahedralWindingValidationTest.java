/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that all tetrahedra in the visualization have consistent face winding for proper rendering. The key
 * requirement is that the winding order matches the sign of the tetrahedron's volume.
 *
 * @author hal.hildebrand
 */
public class TetrahedralWindingValidationTest {

    /**
     * Test all 6 tetrahedron types for winding consistency
     */
    @Test
    public void testAllTypesWindingConsistency() {
        // Only type 0 is allowed at level 0 (root)
        // We'll test different types at level 1
        for (byte type = 0; type < 6; type++) {
            // Create a level 1 tetrahedron with the given type
            // At level 1, cell size is 2^19 = 524288
            Tet tet = new Tet(0, 0, 0, (byte) 1, type);
            Point3i[] intVertices = tet.coordinates();
            Point3f[] vertices = convertToFloat(intVertices);

            double volume = computeSignedVolume(vertices);
            System.out.printf("Type %d tetrahedron volume: %f%n", type, volume);

            // Each type should have consistent winding
            validateWindingConsistency(vertices, volume);
        }
    }

    /**
     * Test that all faces have non-zero area
     */
    @Test
    public void testFaceAreas() {
        Tet rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        Point3i[] intVertices = rootTet.coordinates();
        Point3f[] vertices = convertToFloat(intVertices);

        // Each face should have positive area
        double area1 = computeFaceArea(vertices[0], vertices[2], vertices[1]);
        assertTrue(area1 > 0, "Face 0-2-1 should have positive area: " + area1);

        double area2 = computeFaceArea(vertices[0], vertices[1], vertices[3]);
        assertTrue(area2 > 0, "Face 0-1-3 should have positive area: " + area2);

        double area3 = computeFaceArea(vertices[0], vertices[3], vertices[2]);
        assertTrue(area3 > 0, "Face 0-3-2 should have positive area: " + area3);

        double area4 = computeFaceArea(vertices[1], vertices[2], vertices[3]);
        assertTrue(area4 > 0, "Face 1-2-3 should have positive area: " + area4);
    }

    /**
     * Test that face winding is reversed for negative volume tetrahedra
     */
    @Test
    public void testNegativeVolumeWindingReversal() {
        // Create a tetrahedron that we know will have negative volume
        // by using the opposite vertex order
        Point3f[] vertices = new Point3f[] { new Point3f(0, 0, 0),      // v0
                                             new Point3f(0, 0, 100),    // v3 (swapped with v1)
                                             new Point3f(0, 100, 0),    // v2
                                             new Point3f(100, 0, 0)     // v1 (swapped with v3)
        };

        double volume = computeSignedVolume(vertices);
        assertTrue(volume < 0, "Test tetrahedron should have negative volume: " + volume);

        // For negative volume, the visualization reverses the winding
        // This means normals should still point outward when using reversed faces
        validateWindingConsistency(vertices, volume);
    }

    /**
     * Test subdivided children maintain consistent winding
     */
    @Test
    public void testSubdividedChildrenConsistency() {
        // Test S0 tetrahedron subdivision
        Tet rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);

        // Get children using geometric subdivision
        Tet[] children = rootTet.geometricSubdivide();
        assertEquals(8, children.length, "Should have 8 children");

        // Test each child
        for (int i = 0; i < children.length; i++) {
            Tet child = children[i];
            Point3i[] intVertices = child.subdivisionCoordinates();
            Point3f[] vertices = convertToFloat(intVertices);

            double volume = computeSignedVolume(vertices);
            System.out.printf("Child %d volume: %f%n", i, volume);

            // Each child should have consistent winding
            validateWindingConsistency(vertices, volume);
        }
    }

    /**
     * Test that the visualization face definitions match the expected winding
     */
    @Test
    public void testVisualizationFaceDefinitions() {
        // The visualization defines faces as:
        // Positive volume faces:
        //   0, 0, 2, 2, 1, 1,  // Face 0-2-1 (base, viewed from below)
        //   0, 0, 1, 1, 3, 3,  // Face 0-1-3 (front right)
        //   0, 0, 3, 3, 2, 2,  // Face 0-3-2 (back left)
        //   1, 1, 2, 2, 3, 3   // Face 1-2-3 (top, viewed from above)

        // Test a simple tetrahedron
        Point3f[] vertices = new Point3f[] { new Point3f(0, 0, 0),      // v0
                                             new Point3f(100, 0, 0),    // v1
                                             new Point3f(0, 100, 0),    // v2
                                             new Point3f(0, 0, 100)     // v3
        };

        double volume = computeSignedVolume(vertices);
        System.out.println("Test tetrahedron volume: " + volume);

        // Validate face normals for the visualization's face definitions
        validateVisualizationFaces(vertices, volume > 0);
    }

    /**
     * Test that the visualization correctly handles tetrahedra with different volume signs
     */
    @Test
    public void testVisualizationWindingConsistency() {
        // Create root S0 tetrahedron
        Tet rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);

        // Get vertices
        Point3i[] intVertices = rootTet.coordinates();
        Point3f[] vertices = convertToFloat(intVertices);

        // Compute volume to determine expected winding
        double volume = computeSignedVolume(vertices);

        // The visualization should handle both positive and negative volumes correctly
        // by adjusting the face winding in createTransparentTetrahedron()
        System.out.println("Root tetrahedron (type 0) volume: " + volume);

        // Verify that face normals are consistent with volume sign
        validateWindingConsistency(vertices, volume);
    }

    // Helper methods

    private Point3f computeCentroid(Point3f[] vertices) {
        return new Point3f((vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f,
                           (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f,
                           (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f);
    }

    private double computeFaceArea(Point3f v0, Point3f v1, Point3f v2) {
        Vector3f edge1 = new Vector3f();
        edge1.sub(v1, v0);

        Vector3f edge2 = new Vector3f();
        edge2.sub(v2, v0);

        Vector3f cross = new Vector3f();
        cross.cross(edge1, edge2);

        return cross.length() / 2.0;
    }

    private double computeSignedVolume(Point3f[] vertices) {
        // Volume = (1/6) * det(v1-v0, v2-v0, v3-v0)
        float dx1 = vertices[1].x - vertices[0].x;
        float dy1 = vertices[1].y - vertices[0].y;
        float dz1 = vertices[1].z - vertices[0].z;

        float dx2 = vertices[2].x - vertices[0].x;
        float dy2 = vertices[2].y - vertices[0].y;
        float dz2 = vertices[2].z - vertices[0].z;

        float dx3 = vertices[3].x - vertices[0].x;
        float dy3 = vertices[3].y - vertices[0].y;
        float dz3 = vertices[3].z - vertices[0].z;

        // Compute determinant (scalar triple product)
        float det = dx1 * (dy2 * dz3 - dz2 * dy3) - dy1 * (dx2 * dz3 - dz2 * dx3) + dz1 * (dx2 * dy3 - dy2 * dx3);

        return det / 6.0;
    }

    private Point3f[] convertToFloat(Point3i[] intVertices) {
        Point3f[] vertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(intVertices[i].x, intVertices[i].y, intVertices[i].z);
        }
        return vertices;
    }

    private void validateFaceNormalConsistency(Point3f[] vertices, int i0, int i1, int i2, Point3f centroid,
                                               boolean expectOutward, String faceName) {
        // Compute face normal
        Vector3f v1 = new Vector3f();
        v1.sub(vertices[i1], vertices[i0]);

        Vector3f v2 = new Vector3f();
        v2.sub(vertices[i2], vertices[i0]);

        Vector3f normal = new Vector3f();
        normal.cross(v1, v2);
        normal.normalize();

        // Compute face centroid
        Point3f faceCentroid = new Point3f((vertices[i0].x + vertices[i1].x + vertices[i2].x) / 3.0f,
                                           (vertices[i0].y + vertices[i1].y + vertices[i2].y) / 3.0f,
                                           (vertices[i0].z + vertices[i1].z + vertices[i2].z) / 3.0f);

        // Vector from tetrahedron centroid to face centroid
        Vector3f centroidToFace = new Vector3f();
        centroidToFace.sub(faceCentroid, centroid);
        centroidToFace.normalize();

        // Check consistency
        float dot = normal.dot(centroidToFace);
        if (expectOutward) {
            assertTrue(dot > 0, faceName + " normal should point outward for positive volume. Dot: " + dot);
        } else {
            assertTrue(dot < 0, faceName + " normal should point inward for negative volume. Dot: " + dot);
        }
    }

    private void validateVisualizationFaces(Point3f[] vertices, boolean positiveVolume) {
        Point3f centroid = computeCentroid(vertices);

        if (positiveVolume) {
            // Positive volume face definitions from TetreeVisualization
            validateFaceNormalConsistency(vertices, 0, 2, 1, centroid, true, "Viz Face 0-2-1");
            validateFaceNormalConsistency(vertices, 0, 1, 3, centroid, true, "Viz Face 0-1-3");
            validateFaceNormalConsistency(vertices, 0, 3, 2, centroid, true, "Viz Face 0-3-2");
            validateFaceNormalConsistency(vertices, 1, 2, 3, centroid, true, "Viz Face 1-2-3");
        } else {
            // Negative volume face definitions (reversed winding)
            validateFaceNormalConsistency(vertices, 0, 1, 2, centroid, false, "Viz Face 0-1-2");
            validateFaceNormalConsistency(vertices, 0, 3, 1, centroid, false, "Viz Face 0-3-1");
            validateFaceNormalConsistency(vertices, 0, 2, 3, centroid, false, "Viz Face 0-2-3");
            validateFaceNormalConsistency(vertices, 1, 3, 2, centroid, false, "Viz Face 1-3-2");
        }
    }

    private void validateWindingConsistency(Point3f[] vertices, double volume) {
        // Compute centroid
        Point3f centroid = computeCentroid(vertices);

        // For each face, verify that the normal direction is consistent with volume sign
        boolean expectOutward = volume > 0;

        // Face 0-2-1
        validateFaceNormalConsistency(vertices, 0, 2, 1, centroid, expectOutward, "Face 0-2-1");

        // Face 0-1-3
        validateFaceNormalConsistency(vertices, 0, 1, 3, centroid, expectOutward, "Face 0-1-3");

        // Face 0-3-2
        validateFaceNormalConsistency(vertices, 0, 3, 2, centroid, expectOutward, "Face 0-3-2");

        // Face 1-2-3
        validateFaceNormalConsistency(vertices, 1, 2, 3, centroid, expectOutward, "Face 1-2-3");
    }
}
