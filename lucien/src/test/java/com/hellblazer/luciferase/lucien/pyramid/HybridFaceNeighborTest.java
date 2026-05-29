/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridFaceNeighbor;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates cross-shape face neighbors (RDR-010 q3p Phase D): {@link Pyramid#faceNeighbor(int)},
 * {@link Tet#faceNeighborElement(int)}, and the {@link HybridFaceNeighbor} record, all ported from
 * t8code {@code t8_dpyramid_face_neighbour}.
 *
 * <p>The primary correctness proof is <b>geometric and table-independent</b>: two cells are face
 * neighbors iff they share the vertices of the abutting face — three for a triangular face, four for
 * the quadrilateral base. A wrong coordinate shift in the neighbor computation reduces the shared
 * vertex count and is caught here without consulting the connectivity tables the implementation uses.
 *
 * @author hal.hildebrand
 */
class HybridFaceNeighborTest {

    private static final byte LEVEL = 10;

    private static HashSet<Point3i> vertexSet(Object element) {
        var verts = (element instanceof Pyramid p) ? p.coordinates() : ((Tet) element).coordinates();
        return new HashSet<>(Arrays.asList(verts));
    }

    private static int sharedVertices(Object a, Object b) {
        var sa = vertexSet(a);
        sa.retainAll(vertexSet(b));
        return sa.size();
    }

    @Test
    void pyramidFaceNeighborsShareTheAbuttingFace() {
        // Geometric ground truth: triangular faces (0-3) share 3 vertices, the quad base (4) shares 4.
        var l = Constants.lengthAtLevel(LEVEL);
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            // Anchor well inside the domain so no face neighbor is clipped.
            var p = new Pyramid(4 * l, 4 * l, 4 * l, LEVEL, type);
            for (var f = 0; f < 5; f++) {
                var fn = p.faceNeighbor(f);
                assertNotNull(fn, "in-domain neighbor for face " + f + " type " + type);
                var expectedShared = (f == 4) ? 4 : 3;
                assertEquals(expectedShared, sharedVertices(p, fn.element()),
                             "face " + f + " (type " + type + ") shared vertices");
            }
        }
    }

    @Test
    void pyramidTriangularFacesNeighborTetsQuadBaseNeighborsPyramid() {
        var l = Constants.lengthAtLevel(LEVEL);
        var p = new Pyramid(4 * l, 4 * l, 4 * l, LEVEL, Pyramid.TYPE_6);
        // f0,f1 -> tet (t8code type 3 -> Luciferase 5); f2,f3 -> tet (t8code 0 -> Luciferase 4);
        // f4 -> opposite pyramid (Finding #15 translation).
        assertEquals(5, p.faceNeighbor(0).element().type());
        assertEquals(5, p.faceNeighbor(1).element().type());
        assertEquals(4, p.faceNeighbor(2).element().type());
        assertEquals(4, p.faceNeighbor(3).element().type());
        var base = p.faceNeighbor(4);
        assertInstanceOf(Pyramid.class, base.element());
        assertEquals(Pyramid.TYPE_7, base.element().type());
        // Triangular-face tet neighbors are shallowest tets (minTetLevel == level).
        assertEquals(LEVEL, p.faceNeighbor(0).element().minTetLevel());
        assertEquals(Pyramid.NO_TET_ANCESTOR, base.element().minTetLevel());
    }

    @Test
    void quadBaseNeighborIsReciprocal() {
        // Pyramid <-> pyramid across the base: type flips, and the neighbor's base neighbor is the
        // original. This round-trip is independent of cube-id / tree placement (pure z-shift).
        var l = Constants.lengthAtLevel(LEVEL);
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var p = new Pyramid(4 * l, 4 * l, 4 * l, LEVEL, type);
            var fn = p.faceNeighbor(4);
            assertEquals(4, fn.face(), "base reciprocal face is 4");
            var back = ((Pyramid) fn.element()).faceNeighbor(4);
            assertEquals(p, back.element(), "base neighbor's base neighbor is the original pyramid");
        }
    }

    @Test
    void pyramidFaceNeighborDomainBoundaryReturnsNull() {
        // A type-6 pyramid at z==0: its base (f4) neighbor would be at z = -length -> outside domain.
        var p = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        assertNull(p.faceNeighbor(4), "base neighbor below z=0 is out of domain");
    }

    @Test
    void pyramidFaceIndexBounds() {
        var p = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        assertThrows(IndexOutOfBoundsException.class, () -> p.faceNeighbor(5));
        assertThrows(IndexOutOfBoundsException.class, () -> p.faceNeighbor(-1));
    }

    @Test
    void pureTetreeFaceNeighborElementMatchesFaceNeighbor() {
        // Locked contract: a pure-Tetree tet's faceNeighborElement equals faceNeighbor for all types.
        var l = Constants.lengthAtLevel(LEVEL);
        for (byte type = 0; type < 6; type++) {
            var t = new Tet(4 * l, 4 * l, 4 * l, LEVEL, type);
            for (var f = 0; f < 4; f++) {
                var fn = t.faceNeighbor(f);
                var hfn = t.faceNeighborElement(f);
                if (fn == null) {
                    assertNull(hfn, "type " + type + " face " + f);
                } else {
                    assertNotNull(hfn);
                    assertEquals(fn.face(), hfn.face(), "type " + type + " face " + f + " reciprocal");
                    assertInstanceOf(Tet.class, hfn.element());
                    assertEquals(fn.tet(), hfn.element(), "type " + type + " face " + f + " neighbor");
                }
            }
        }
    }

    @Test
    void boundaryTetFaceNeighborCanBeAPyramid() {
        // Validates the tet->pyramid cross-type branch of Tet.faceNeighborElement(). t8code's
        // tet_pyra_face_connection is cube-id dependent: a tet registers as pyramid-touching only at
        // specific cube-ids relative to the pyramid, so we sweep all 8 cube-id placements. Where the
        // tet's reciprocal-face query DOES return a pyramid (structural adjacency holds), it must be
        // the original pyramid (a genuine inverse via a different code path). Full reciprocity across
        // every face is a forest-level property validated in Phase E with a real hybrid tree.
        var l = Constants.lengthAtLevel(LEVEL);
        var recovered = 0;
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            for (var cid = 0; cid < 8; cid++) {
                // base 4L is a multiple of 2L so it does not disturb the level-L cube-id bits.
                var ax = 4 * l + ((cid & 1) != 0 ? l : 0);
                var ay = 4 * l + ((cid & 2) != 0 ? l : 0);
                var az = 4 * l + ((cid & 4) != 0 ? l : 0);
                var p = new Pyramid(ax, ay, az, LEVEL, type);
                for (var f = 0; f < 4; f++) {
                    var fn = p.faceNeighbor(f);
                    if (fn == null || !(fn.element() instanceof Tet tet)) {
                        continue;
                    }
                    var back = tet.faceNeighborElement(fn.face());
                    if (back != null && back.element() instanceof Pyramid) {
                        assertEquals(p, back.element(),
                                     "tet->pyramid recovers the originating pyramid (type " + type + " cid " + cid
                                     + " face " + f + ")");
                        recovered++;
                    }
                }
            }
        }
        assertTrue(recovered > 0, "the tet->pyramid cross-type path must fire for valid placements");
    }

    @Test
    void deepPyramidRootedTetFaceNeighborFailsLoud() {
        // A deep pyramid-rooted tet (level > minTetLevel) of pyramid-capable type cannot have its
        // pyramid-boundary faces resolved: Luciferase's Bey subdivision diverges from t8code's dtet
        // tree below the pyramid boundary (RDR-010 Finding #16), so faceNeighborElement fails loud
        // rather than returning a silently-wrong neighbor. (The prior geometric ">=3 shared vertices"
        // test was invalid: Luciferase's non-conforming SFC face neighbors share 0-3 vertices.)
        var l = Constants.lengthAtLevel(LEVEL);
        var checked = 0;
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var p = new Pyramid(4 * l, 4 * l, 4 * l, LEVEL, type);
            for (var i = 0; i < 10; i++) {
                if (!(p.child(i) instanceof Tet shallow)) {
                    continue;
                }
                for (var k = 0; k < 8; k++) {
                    var deep = shallow.child(k); // minTetLevel == shallow.level < deep.level
                    assertTrue(deep.minTetLevel() < deep.l, "deep tet: minTetLevel < level");
                    var t8 = com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.LUC_TO_T8[deep.type];
                    if (t8 != 0 && t8 != 3) {
                        continue; // only pyramid-capable types hit the guard
                    }
                    var d = deep;
                    assertThrows(IllegalStateException.class, () -> d.faceNeighborElement(0),
                                 "deep pyramid-capable tet must fail loud (type " + type + ")");
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "must exercise at least one deep pyramid-capable tet");
    }

    @Test
    void hybridTetNonPyramidFaceStaysTetWithPropagatedMinTetLevel() {
        // A pyramid's triangular-face tet neighbor (minTetLevel==level): the faces of that tet that do
        // NOT cross back to the pyramid must yield tetrahedra carrying the propagated minTetLevel.
        var l = Constants.lengthAtLevel(LEVEL);
        var p = new Pyramid(4 * l, 4 * l, 4 * l, LEVEL, Pyramid.TYPE_6);
        var tet = (Tet) p.faceNeighbor(0).element(); // minTetLevel == LEVEL
        for (var f = 0; f < 4; f++) {
            var hfn = tet.faceNeighborElement(f);
            if (hfn != null && hfn.element() instanceof Tet nt) {
                assertEquals(LEVEL, nt.minTetLevel(), "tet neighbor carries propagated minTetLevel (face " + f + ")");
            }
        }
    }
}
