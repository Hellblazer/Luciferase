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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTTopology.
 *
 * @author hal.hildebrand
 */
class ESVTTopologyTest {

    @Test
    void testConstants() {
        assertEquals(8, ESVTTopology.BEY_BRANCHING_FACTOR);
        assertEquals(6, ESVTTopology.TET_TYPE_COUNT);
        assertEquals(4, ESVTTopology.FACES_PER_TET);
        assertEquals(6, ESVTTopology.EDGES_PER_TET);
        assertEquals(4, ESVTTopology.VERTICES_PER_TET);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testGetAllChildTypes(int parentType) {
        var childTypes = ESVTTopology.getAllChildTypes(parentType);
        assertEquals(8, childTypes.length);

        for (int childType : childTypes) {
            assertTrue(childType >= 0 && childType <= 5,
                "Child type should be 0-5, got " + childType);
        }
    }

    @Test
    void testCornerChildrenHaveSameTypeAsParent() {
        for (int parentType = 0; parentType < 6; parentType++) {
            var childTypes = ESVTTopology.getAllChildTypes(parentType);
            for (int i = 0; i < 4; i++) {
                assertEquals(parentType, childTypes[i],
                    "Corner child " + i + " should have parent type");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void testGetChildrenAtFace(int faceIndex) {
        var children = ESVTTopology.getChildrenAtFace(faceIndex);
        assertEquals(4, children.length);

        for (int child : children) {
            assertTrue(child >= 0 && child <= 7,
                "Child index should be 0-7, got " + child);
        }
    }

    @Test
    void testFace0HasOctahedralChildren() {
        var children = ESVTTopology.getChildrenAtFace(0);
        assertArrayEquals(new int[]{4, 5, 6, 7}, children,
            "Face 0 (opposite v0) should have octahedral children");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void testIsCornerChild(int childIndex) {
        assertTrue(ESVTTopology.isCornerChild(childIndex));
        assertFalse(ESVTTopology.isOctahedralChild(childIndex));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7})
    void testIsOctahedralChild(int childIndex) {
        assertTrue(ESVTTopology.isOctahedralChild(childIndex));
        assertFalse(ESVTTopology.isCornerChild(childIndex));
    }

    @Test
    void testCornerVertex() {
        for (int i = 0; i < 4; i++) {
            assertEquals(i, ESVTTopology.getCornerVertex(i));
        }
    }

    @Test
    void testCornerVertexForOctahedralChildThrows() {
        for (int i = 4; i < 8; i++) {
            int child = i;
            assertThrows(IllegalArgumentException.class,
                () -> ESVTTopology.getCornerVertex(child));
        }
    }

    @Test
    void testSiblingFaceSharing() {
        // Test that face sharing returns valid data
        for (int a = 0; a < 8; a++) {
            var siblings = ESVTTopology.getShareFaceSiblings(a);
            // Some children may have 0 siblings with shared faces, that's valid
            assertTrue(siblings.size() >= 0 && siblings.size() <= 7,
                "Sibling count should be 0-7");

            for (var sharing : siblings) {
                assertTrue(sharing.siblingIndex() >= 0 && sharing.siblingIndex() <= 7);
                assertTrue(sharing.sharedFace() >= 0 && sharing.sharedFace() <= 3);
                assertNotEquals(a, sharing.siblingIndex(), "Should not share face with self");
            }
        }
    }

    @Test
    void testSharedFaceIsMinusOneForSameChild() {
        for (int i = 0; i < 8; i++) {
            assertEquals(-1, ESVTTopology.getSharedFace(i, i),
                "Same child should not share face with itself");
        }
    }

    @Test
    void testGetFaceAdjacency() {
        for (int face = 0; face < 4; face++) {
            var adj = ESVTTopology.getFaceAdjacency(face, true);
            assertEquals(face, adj.faceIndex());
            assertEquals(4, adj.touchingChildren().length);
            assertFalse(adj.isExternal());
        }
    }

    @Test
    void testExternalFaceAdjacency() {
        var adj = ESVTTopology.getFaceAdjacency(0, false);
        assertTrue(adj.isExternal());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testGetEdgeInfo(int edgeIndex) {
        var info = ESVTTopology.getEdgeInfo(edgeIndex);
        assertEquals(edgeIndex, info.edgeIndex());
        assertTrue(info.vertex0() >= 0 && info.vertex0() <= 3);
        assertTrue(info.vertex1() >= 0 && info.vertex1() <= 3);
        assertNotEquals(info.vertex0(), info.vertex1());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void testGetEdgesAtVertex(int vertexIndex) {
        var edges = ESVTTopology.getEdgesAtVertex(vertexIndex);

        // Each vertex of a tetrahedron has exactly 3 incident edges
        assertEquals(3, edges.size());

        for (int edge : edges) {
            var info = ESVTTopology.getEdgeInfo(edge);
            assertTrue(info.vertex0() == vertexIndex || info.vertex1() == vertexIndex);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void testGetFacesAtVertex(int vertexIndex) {
        var faces = ESVTTopology.getFacesAtVertex(vertexIndex);

        // Each vertex of a tetrahedron is on exactly 3 faces
        assertEquals(3, faces.size());

        // Vertex v is NOT on face v (face v is opposite to vertex v)
        assertFalse(faces.contains(vertexIndex));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void testGetFacesAtEdge(int edgeIndex) {
        var faces = ESVTTopology.getFacesAtEdge(edgeIndex);

        // Each edge of a tetrahedron is shared by exactly 2 faces
        assertEquals(2, faces.size());

        // Verify the faces don't contain the edge endpoints
        var info = ESVTTopology.getEdgeInfo(edgeIndex);
        for (int face : faces) {
            assertNotEquals(info.vertex0(), face);
            assertNotEquals(info.vertex1(), face);
        }
    }

    @Test
    void testFacesShareEdge() {
        // Any two distinct faces share exactly one edge
        for (int f1 = 0; f1 < 4; f1++) {
            for (int f2 = 0; f2 < 4; f2++) {
                if (f1 != f2) {
                    assertTrue(ESVTTopology.facesShareEdge(f1, f2));
                } else {
                    assertFalse(ESVTTopology.facesShareEdge(f1, f2));
                }
            }
        }
    }

    @Test
    void testGetSharedEdge() {
        // Test all pairs of distinct faces
        for (int f1 = 0; f1 < 4; f1++) {
            for (int f2 = 0; f2 < 4; f2++) {
                int sharedEdge = ESVTTopology.getSharedEdge(f1, f2);

                if (f1 == f2) {
                    assertEquals(-1, sharedEdge);
                } else {
                    assertTrue(sharedEdge >= 0 && sharedEdge <= 5);

                    // Verify the shared edge connects vertices not opposite to either face
                    var edgeInfo = ESVTTopology.getEdgeInfo(sharedEdge);
                    assertNotEquals(f1, edgeInfo.vertex0());
                    assertNotEquals(f1, edgeInfo.vertex1());
                    assertNotEquals(f2, edgeInfo.vertex0());
                    assertNotEquals(f2, edgeInfo.vertex1());
                }
            }
        }
    }

    @Test
    void testDepthToLevel() {
        for (int depth = 0; depth < 10; depth++) {
            assertEquals(depth, ESVTTopology.depthToLevel(depth));
        }
    }

    @Test
    void testDepthToLevelNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.depthToLevel(-1));
    }

    @Test
    void testGetScaleAtLevel() {
        assertEquals(1.0f, ESVTTopology.getScaleAtLevel(0), 1e-6f);
        assertEquals(0.5f, ESVTTopology.getScaleAtLevel(1), 1e-6f);
        assertEquals(0.25f, ESVTTopology.getScaleAtLevel(2), 1e-6f);
        assertEquals(0.125f, ESVTTopology.getScaleAtLevel(3), 1e-6f);
    }

    @Test
    void testGetMaxNodesAtLevel() {
        assertEquals(1L, ESVTTopology.getMaxNodesAtLevel(0));
        assertEquals(8L, ESVTTopology.getMaxNodesAtLevel(1));
        assertEquals(64L, ESVTTopology.getMaxNodesAtLevel(2));
        assertEquals(512L, ESVTTopology.getMaxNodesAtLevel(3));
    }

    @Test
    void testInvalidFaceIndexThrows() {
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.getChildrenAtFace(-1));
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.getChildrenAtFace(4));
    }

    @Test
    void testInvalidChildIndexThrows() {
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.isCornerChild(-1));
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.isCornerChild(8));
    }

    @Test
    void testInvalidEdgeIndexThrows() {
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.getEdgeInfo(-1));
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.getEdgeInfo(6));
    }

    @Test
    void testInvalidVertexIndexThrows() {
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.getEdgesAtVertex(-1));
        assertThrows(IllegalArgumentException.class, () -> ESVTTopology.getEdgesAtVertex(4));
    }

    @Test
    void testTetrahedronHas6UniqueEdges() {
        // Verify we have exactly 6 distinct edges
        var allEdges = new java.util.HashSet<String>();
        for (int e = 0; e < 6; e++) {
            var info = ESVTTopology.getEdgeInfo(e);
            int v0 = Math.min(info.vertex0(), info.vertex1());
            int v1 = Math.max(info.vertex0(), info.vertex1());
            allEdges.add(v0 + "-" + v1);
        }
        assertEquals(6, allEdges.size(), "Tetrahedron should have 6 unique edges");
    }

    @Test
    void testChildTypeDelegation() {
        // Verify ESVTTopology.getChildType delegates to ESVTNodeGeometry
        for (int parentType = 0; parentType < 6; parentType++) {
            for (int child = 0; child < 8; child++) {
                assertEquals(
                    ESVTNodeGeometry.getChildType(parentType, child),
                    ESVTTopology.getChildType(parentType, child)
                );
            }
        }
    }
}
