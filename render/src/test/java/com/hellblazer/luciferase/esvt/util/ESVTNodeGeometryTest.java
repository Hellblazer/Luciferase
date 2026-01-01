/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTNodeGeometry.
 *
 * @author hal.hildebrand
 */
class ESVTNodeGeometryTest {

    private static final float EPSILON = 1e-5f;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testGetVerticesReturns4Points(int tetType) {
        var vertices = ESVTNodeGeometry.getVertices(tetType);
        assertEquals(4, vertices.length);
        for (var v : vertices) {
            assertNotNull(v);
            assertTrue(v.x >= 0 && v.x <= 1);
            assertTrue(v.y >= 0 && v.y <= 1);
            assertTrue(v.z >= 0 && v.z <= 1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testAllTypesShareOriginAndOppositeCorner(int tetType) {
        var vertices = ESVTNodeGeometry.getVertices(tetType);

        // All S0-S5 tetrahedra share vertex (0,0,0) and (1,1,1)
        boolean hasOrigin = false;
        boolean hasOpposite = false;

        for (var v : vertices) {
            if (Math.abs(v.x) < EPSILON && Math.abs(v.y) < EPSILON && Math.abs(v.z) < EPSILON) {
                hasOrigin = true;
            }
            if (Math.abs(v.x - 1) < EPSILON && Math.abs(v.y - 1) < EPSILON && Math.abs(v.z - 1) < EPSILON) {
                hasOpposite = true;
            }
        }

        assertTrue(hasOrigin, "Type " + tetType + " should have vertex at (0,0,0)");
        assertTrue(hasOpposite, "Type " + tetType + " should have vertex at (1,1,1)");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testCentroidIsInsideUnitCube(int tetType) {
        var centroid = ESVTNodeGeometry.getCentroid(tetType);

        assertTrue(centroid.x > 0 && centroid.x < 1);
        assertTrue(centroid.y > 0 && centroid.y < 1);
        assertTrue(centroid.z > 0 && centroid.z < 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testCentroidIsAverageOfVertices(int tetType) {
        var vertices = ESVTNodeGeometry.getVertices(tetType);
        var centroid = ESVTNodeGeometry.getCentroid(tetType);

        float expectedX = 0, expectedY = 0, expectedZ = 0;
        for (var v : vertices) {
            expectedX += v.x;
            expectedY += v.y;
            expectedZ += v.z;
        }
        expectedX /= 4;
        expectedY /= 4;
        expectedZ /= 4;

        assertEquals(expectedX, centroid.x, EPSILON);
        assertEquals(expectedY, centroid.y, EPSILON);
        assertEquals(expectedZ, centroid.z, EPSILON);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testVolumeIsPositive(int tetType) {
        float volume = ESVTNodeGeometry.getVolume(tetType, 1.0f);
        assertTrue(volume > 0, "Volume should be positive for type " + tetType);
    }

    @Test
    void testSixTetrahedraFillUnitCube() {
        // 6 tetrahedra with equal volume should fill the unit cube
        // Unit cube volume = 1.0
        // Each tetrahedron volume = 1/6

        float totalVolume = 0;
        for (int type = 0; type < 6; type++) {
            totalVolume += ESVTNodeGeometry.getVolume(type, 1.0f);
        }

        assertEquals(1.0f, totalVolume, 0.01f, "Six tetrahedra should fill unit cube");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testFaceNormalsAreNormalized(int tetType) {
        for (int face = 0; face < 4; face++) {
            var normal = ESVTNodeGeometry.getFaceNormal(tetType, face);
            assertEquals(1.0f, normal.length(), EPSILON);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testFaceNormalsPointOutward(int tetType) {
        var vertices = ESVTNodeGeometry.getVertices(tetType);
        var centroid = ESVTNodeGeometry.getCentroid(tetType);

        for (int face = 0; face < 4; face++) {
            var normal = ESVTNodeGeometry.getFaceNormal(tetType, face);
            var faceVerts = ESVTNodeGeometry.getFaceVertices(tetType, face);

            // Face centroid
            var faceCentroid = new Point3f(
                (faceVerts[0].x + faceVerts[1].x + faceVerts[2].x) / 3.0f,
                (faceVerts[0].y + faceVerts[1].y + faceVerts[2].y) / 3.0f,
                (faceVerts[0].z + faceVerts[1].z + faceVerts[2].z) / 3.0f
            );

            // Vector from centroid to face centroid should align with normal
            var toFace = new Vector3f(
                faceCentroid.x - centroid.x,
                faceCentroid.y - centroid.y,
                faceCentroid.z - centroid.z
            );

            assertTrue(normal.dot(toFace) > 0,
                "Face " + face + " normal should point outward for type " + tetType);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testInscribedRadiusIsPositive(int tetType) {
        float r = ESVTNodeGeometry.getInscribedRadius(tetType, 1.0f);
        assertTrue(r > 0, "Inscribed radius should be positive");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testCircumscribedRadiusIsGreaterThanInscribed(int tetType) {
        float inR = ESVTNodeGeometry.getInscribedRadius(tetType, 1.0f);
        float outR = ESVTNodeGeometry.getCircumscribedRadius(tetType, 1.0f);
        assertTrue(outR > inR, "Circumscribed radius should be > inscribed radius");
    }

    @Test
    void testContainsPointAtCentroid() {
        var origin = new Point3f(0, 0, 0);
        for (int type = 0; type < 6; type++) {
            var centroid = ESVTNodeGeometry.getCentroid(type);
            assertTrue(ESVTNodeGeometry.containsPoint(centroid, type, origin, 1.0f),
                "Centroid should be inside type " + type);
        }
    }

    @Test
    void testContainsPointOutsideReturnsfalse() {
        var origin = new Point3f(0, 0, 0);
        var outside = new Point3f(2.0f, 2.0f, 2.0f);

        for (int type = 0; type < 6; type++) {
            assertFalse(ESVTNodeGeometry.containsPoint(outside, type, origin, 1.0f),
                "Point (2,2,2) should be outside type " + type);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testChildTypesDerivedCorrectly(int parentType) {
        for (int child = 0; child < 8; child++) {
            int childType = ESVTNodeGeometry.getChildType(parentType, child);
            assertTrue(childType >= 0 && childType <= 5,
                "Child type should be 0-5, got " + childType);
        }
    }

    @Test
    void testCornerChildrenHaveSameTypeAsParent() {
        // Children 0-3 (corner children) inherit parent type
        for (int parentType = 0; parentType < 6; parentType++) {
            for (int corner = 0; corner < 4; corner++) {
                int childType = ESVTNodeGeometry.getChildType(parentType, corner);
                assertEquals(parentType, childType,
                    "Corner child " + corner + " should have same type as parent");
            }
        }
    }

    @Test
    void testChildVerticesHaveCorrectCount() {
        for (int parentType = 0; parentType < 6; parentType++) {
            for (int child = 0; child < 8; child++) {
                var childVerts = ESVTNodeGeometry.getChildVertices(parentType, child, 0.5f);
                assertEquals(4, childVerts.length);
            }
        }
    }

    @Test
    void testBoundsContainAllVertices() {
        var origin = new Point3f(0, 0, 0);
        for (int type = 0; type < 6; type++) {
            var bounds = ESVTNodeGeometry.getBounds(type, origin, 1.0f);
            var vertices = ESVTNodeGeometry.getVertices(type, origin, 1.0f);

            for (var v : vertices) {
                assertTrue(v.x >= bounds.min().x - EPSILON && v.x <= bounds.max().x + EPSILON);
                assertTrue(v.y >= bounds.min().y - EPSILON && v.y <= bounds.max().y + EPSILON);
                assertTrue(v.z >= bounds.min().z - EPSILON && v.z <= bounds.max().z + EPSILON);
            }
        }
    }

    @Test
    void testGeometryRecordContainsAllData() {
        for (int type = 0; type < 6; type++) {
            var geom = ESVTNodeGeometry.getGeometry(type, 1.0f);

            assertNotNull(geom.vertices());
            assertEquals(4, geom.vertices().length);
            assertNotNull(geom.centroid());
            assertTrue(geom.volume() > 0);
            assertTrue(geom.inscribedRadius() > 0);
            assertTrue(geom.circumscribedRadius() > 0);
        }
    }

    @Test
    void testInvalidTypeThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> ESVTNodeGeometry.getVertices(-1));
        assertThrows(IllegalArgumentException.class, () -> ESVTNodeGeometry.getVertices(6));
    }

    @Test
    void testInvalidFaceIndexThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> ESVTNodeGeometry.getFaceNormal(0, -1));
        assertThrows(IllegalArgumentException.class, () -> ESVTNodeGeometry.getFaceNormal(0, 4));
    }

    @Test
    void testInvalidChildIndexThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> ESVTNodeGeometry.getChildType(0, -1));
        assertThrows(IllegalArgumentException.class, () -> ESVTNodeGeometry.getChildType(0, 8));
    }

    @Test
    void testScaledVertices() {
        var origin = new Point3f(1.0f, 2.0f, 3.0f);
        float scale = 0.5f;

        var vertices = ESVTNodeGeometry.getVertices(0, origin, scale);

        // All vertices should be within origin + [0, scale] in each dimension
        for (var v : vertices) {
            assertTrue(v.x >= origin.x && v.x <= origin.x + scale);
            assertTrue(v.y >= origin.y && v.y <= origin.y + scale);
            assertTrue(v.z >= origin.z && v.z <= origin.z + scale);
        }
    }

    @Test
    void testScaledCentroid() {
        var origin = new Point3f(1.0f, 2.0f, 3.0f);
        float scale = 2.0f;

        var centroid = ESVTNodeGeometry.getCentroid(0, origin, scale);
        var baseCentroid = ESVTNodeGeometry.getCentroid(0);

        assertEquals(origin.x + baseCentroid.x * scale, centroid.x, EPSILON);
        assertEquals(origin.y + baseCentroid.y * scale, centroid.y, EPSILON);
        assertEquals(origin.z + baseCentroid.z * scale, centroid.z, EPSILON);
    }

    @Test
    void testVolumeScalesWithCube() {
        float baseVolume = ESVTNodeGeometry.getVolume(0, 1.0f);
        float scaledVolume = ESVTNodeGeometry.getVolume(0, 2.0f);

        // Volume should scale with cube of scale factor
        assertEquals(baseVolume * 8.0f, scaledVolume, EPSILON);
    }
}
