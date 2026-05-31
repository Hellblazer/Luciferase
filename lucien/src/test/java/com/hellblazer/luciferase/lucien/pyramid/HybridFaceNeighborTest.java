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
        // f0,f1 -> tet type 3; f2,f3 -> tet type 0; f4 -> opposite pyramid. Tet type k IS t8code dtet
        // type k now (RDR-010 Luciferase-4pd), so no translation.
        assertEquals(3, p.faceNeighbor(0).element().type());
        assertEquals(3, p.faceNeighbor(1).element().type());
        assertEquals(0, p.faceNeighbor(2).element().type());
        assertEquals(0, p.faceNeighbor(3).element().type());
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
    void deepPyramidRootedTetFaceNeighborsAreConformingAndReciprocal() {
        // RDR-010 Luciferase-cjwr: deep pyramid-rooted tets (level > minTetLevel) now resolve their
        // pyramid-boundary faces via the ported t8code t8_dpyramid_tet_boundary corner-walk. Validation
        // is table-INDEPENDENT: when faceNeighborElement returns a Pyramid, the abutting face is a
        // conforming triangle, so the deep tet and the pyramid share exactly the 3 face vertices; and the
        // pyramid's reciprocal-face neighbor (computed by the separate Pyramid.faceNeighbor pi1.5 code
        // path) must geometrically be this same deep tet (cross-implementation involution).
        var counts = new int[2]; // [0] = pyramid returns, [1] = tet returns
        var depths = new HashSet<Integer>();
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            // Anchor deep enough that 3 extra refinement levels stay in-domain.
            var l3 = Constants.lengthAtLevel((byte) (LEVEL + 3));
            var p = new Pyramid(4 * l3, 4 * l3, 4 * l3, LEVEL, type);
            for (var i = 0; i < 10; i++) {
                if (p.child(i) instanceof Tet shallow) {
                    // Recurse up to 3 levels below the shallow tet → corner-walk of 1..3 iterations.
                    checkDeepTet(shallow, shallow, 3, counts, depths);
                }
            }
        }
        // Non-vacuity AND discrimination: the corner-walk must classify some deep faces as pyramid-bound
        // and some as tet-bound — proving it is not trivially returning one branch.
        // NOTE (cjwr Phase A scope): the pyramid-return branch is double-validated (conforming 3-vertex +
        // cross-path involution), so a false-POSITIVE cannot survive. The tet-return branch is only
        // counted, so a partial false-NEGATIVE (a face wrongly classified tet-bound) is not fully excluded
        // here — that gap is closed in Phase C by a face-by-face comparison against the independent t8code
        // port of t8_dpyramid_tet_boundary in the extended whole-domain oracle (substantive-critic SIG-2).
        assertTrue(counts[0] > 0, "deep tets must surface pyramid face neighbors (got 0)");
        assertTrue(counts[1] > 0, "deep tets must also have tet face neighbors (corner-walk discriminates)");
        // Exercised more than a single refinement level (multi-iteration corner-walk + type propagation).
        assertTrue(depths.size() >= 2, "must exercise deep tets at multiple levels, got depths " + depths);
    }

    /**
     * Recurse {@code remainingDepth} levels below a shallow tet, validating every pyramid-capable (type
     * 0/3) deep tet. The DFS over both pyramid types' 10 children, refined 3 levels, reliably yields deep
     * tets whose corner-walk classifies some faces pyramid-bound and others tet-bound — so the caller's
     * {@code counts[1] > 0} (tet-return) discrimination assertion is satisfied across the swept anchors.
     */
    private void checkDeepTet(Tet shallow, Tet current, int remainingDepth, int[] counts, HashSet<Integer> depths) {
        if (current.l > shallow.l && (current.type == 0 || current.type == 3)) {
            depths.add(current.l - shallow.l);
            for (var face = 0; face < 4; face++) {
                var hfn = current.faceNeighborElement(face);
                if (hfn == null) {
                    continue;
                }
                if (hfn.element() instanceof Pyramid pyr) {
                    counts[0]++;
                    // Geometric ground truth: conforming triangular face → exactly 3 shared vertices.
                    assertEquals(3, sharedVertices(current, pyr),
                                 "deep tet↔pyramid face " + face + " must share the 3 face vertices");
                    // Cross-implementation involution: the pyramid's reciprocal-face neighbor (the separate
                    // Pyramid.faceNeighbor pi1.5 path) is this deep tet (vertex set — minTetLevel is metadata).
                    var back = pyr.faceNeighbor(hfn.face());
                    assertNotNull(back, "pyramid reciprocal neighbor exists");
                    assertEquals(vertexSet(current), vertexSet(back.element()),
                                 "involution: pyramid.faceNeighbor(reciprocal) is the deep tet");
                } else {
                    counts[1]++;
                }
            }
        }
        if (remainingDepth > 0) {
            for (var k = 0; k < 8; k++) {
                checkDeepTet(shallow, current.child(k), remainingDepth - 1, counts, depths);
            }
        }
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
