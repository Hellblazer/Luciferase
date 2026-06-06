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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-014 AC2: edge->face t8code parity. Asserts the single canonical {@link TetreeConnectivity#EDGE_FACES}
 * table equals the geometric derivation — each edge is bounded by exactly the two faces that contain BOTH
 * of the edge's vertices, where face {@code i} is opposite vertex {@code i} (the t8code convention encoded
 * in {@link TetreeConnectivity#FACE_CORNERS}).
 *
 * <p>This guards against the latent defect documented in RDR-014 F4: the {@code TetreeNeighborFinder}
 * previously carried a WRONG inline edge->face table (e.g. edge 0 -> {0,2} instead of {2,3}). Both
 * {@code TetreeNeighborFinder} and {@code TetreeNeighborDetector} now consume {@code TetreeConnectivity.EDGE_FACES};
 * this test pins it to the geometry.
 *
 * @author hal.hildebrand
 */
class EdgeFaceTableParityTest {

    /**
     * Derive, from FACE_CORNERS, the two faces that contain both vertices of edge {@code e}, and assert
     * EDGE_FACES matches. Face geometry is type-independent (FACE_CORNERS is identical across all 6 types),
     * so deriving against type 0 is sufficient and is additionally cross-checked against every type below.
     */
    @Test
    void edgeFacesMatchesGeometricDerivation() {
        var faceCorners = TetreeConnectivity.FACE_CORNERS[0]; // type-independent

        for (int e = 0; e < TetreeConnectivity.EDGES_PER_TET; e++) {
            int va = TetreeConnectivity.EDGE_VERTICES[e][0];
            int vb = TetreeConnectivity.EDGE_VERTICES[e][1];

            // A face bounds edge (va,vb) iff its corner set contains BOTH va and vb.
            int[] derived = new int[TetreeConnectivity.FACES_PER_TET];
            int n = 0;
            for (int f = 0; f < TetreeConnectivity.FACES_PER_TET; f++) {
                if (faceContains(faceCorners[f], va) && faceContains(faceCorners[f], vb)) {
                    derived[n++] = f;
                }
            }
            assertEquals(2, n, "edge " + e + " (v" + va + "-v" + vb + ") must be bounded by exactly 2 faces");
            derived = Arrays.copyOf(derived, n);
            Arrays.sort(derived);

            int[] actual = TetreeConnectivity.EDGE_FACES[e].clone();
            Arrays.sort(actual);
            assertArrayEquals(derived, actual,
                              "EDGE_FACES[" + e + "] (v" + va + "-v" + vb + ") expected geometric "
                              + Arrays.toString(derived) + " but table had " + Arrays.toString(actual));
        }
    }

    /**
     * FACE_CORNERS is identical across all 6 types, so the derivation holds for every type. This guards
     * against a future type-dependent FACE_CORNERS edit silently invalidating the type-independent
     * EDGE_FACES table.
     */
    @Test
    void faceGeometryIsTypeIndependent() {
        var type0 = TetreeConnectivity.FACE_CORNERS[0];
        for (int t = 1; t < TetreeConnectivity.TET_TYPES; t++) {
            var faceCorners = TetreeConnectivity.FACE_CORNERS[t];
            for (int f = 0; f < TetreeConnectivity.FACES_PER_TET; f++) {
                assertArrayEquals(type0[f], faceCorners[f],
                                  "FACE_CORNERS face " + f + " differs between type 0 and type " + t
                                  + "; EDGE_FACES is type-independent and would no longer be sound");
            }
        }
    }

    /**
     * Pin the exact authoritative mapping (RDR-014 F4 derived table) so an accidental reorder is caught
     * even if the derivation logic above were itself altered.
     */
    @Test
    void edgeFacesEqualsAuthoritativeF4Table() {
        int[][] authoritative = { { 2, 3 }, { 1, 3 }, { 1, 2 }, { 0, 3 }, { 0, 2 }, { 0, 1 } };
        for (int e = 0; e < TetreeConnectivity.EDGES_PER_TET; e++) {
            assertArrayEquals(authoritative[e], TetreeConnectivity.EDGE_FACES[e],
                              "EDGE_FACES[" + e + "] diverged from the RDR-014 F4 authoritative table");
        }
    }

    /**
     * Behavioral tripwire for the LIVE same-level edge-neighbor path (RDR-014 F4). Phase 0 corrects the
     * table that {@code findEdgeNeighbors} actually consumes on the production same-level path (the
     * cross-level helpers are still empty stubs in this phase, so the method's output is exactly the
     * same-level face-neighbor set across the edge's two bounding faces). This test re-derives the two
     * bounding faces INDEPENDENTLY from {@code FACE_CORNERS} and asserts the live method's output equals
     * the face neighbors across those geometrically-correct faces. It FAILS against the old, wrong inline
     * table (e.g. edge 0 there used faces {0,2} instead of {2,3}), so it is a real guard for the behavior
     * change this phase introduces — not a tautology against the table.
     */
    @Test
    void findEdgeNeighborsUsesGeometricallyCorrectFaces() {
        var finder = new TetreeNeighborFinder();
        // Interior tet (all 4 faces have distinct same-level neighbors — cf. TetreeNeighborFinderTest).
        int cellSize = Constants.lengthAtLevel((byte) 3);
        var tet = new Tet(cellSize * 2, cellSize * 2, cellSize * 2, (byte) 3, (byte) 0);
        var faceCorners = TetreeConnectivity.FACE_CORNERS[tet.type()];

        boolean anyNonEmpty = false;
        for (int e = 0; e < TetreeConnectivity.EDGES_PER_TET; e++) {
            int va = TetreeConnectivity.EDGE_VERTICES[e][0];
            int vb = TetreeConnectivity.EDGE_VERTICES[e][1];

            // Independently derive the two faces bounding this edge from FACE_CORNERS.
            var expected = new HashSet<>();
            for (int f = 0; f < TetreeConnectivity.FACES_PER_TET; f++) {
                if (faceContains(faceCorners[f], va) && faceContains(faceCorners[f], vb)) {
                    var fn = finder.findFaceNeighbor(tet, f);
                    if (fn != null) {
                        expected.add(fn.tmIndex());
                    }
                }
            }

            Set<Object> actual = new HashSet<>(finder.findEdgeNeighbors(tet.tmIndex(), e));
            assertEquals(expected, actual,
                         "findEdgeNeighbors(edge " + e + ", v" + va + "-v" + vb + ") must equal the face "
                         + "neighbors across the geometrically-derived bounding faces; a mismatch means the "
                         + "live path is using a wrong edge->face mapping");
            anyNonEmpty |= !actual.isEmpty();
        }
        assertTrue(anyNonEmpty,
                   "interior tet must have at least one same-level edge neighbor — fixture is vacuous otherwise");
    }

    private static boolean faceContains(byte[] corners, int vertex) {
        for (byte c : corners) {
            if (c == vertex) {
                return true;
            }
        }
        return false;
    }
}
