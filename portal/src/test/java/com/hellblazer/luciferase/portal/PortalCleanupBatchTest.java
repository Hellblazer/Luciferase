/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.portal;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the portal-rdfcc-quality cleanup batch
 * (Luciferase-2py, etb, xnf, 7jk, f2z).
 *
 * @author hal.hildebrand
 */
public class PortalCleanupBatchTest {

    private static final float EPS = 1e-5f;

    private final RDG         rdg         = new RDG();
    private final Tetrahedral tetrahedral = new Tetrahedral();

    // ============================================================
    // Luciferase-2py: RDG.faceConnectedNeighbors[2] z=1 typo
    // ============================================================

    @Test
    void faceConnectedNeighborsAreAllDistinctAndProperlyShifted() {
        var origin = new Point3i(5, 7, 11);
        var neighbors = rdg.faceConnectedNeighbors(origin);

        assertEquals(12, neighbors.length, "FCC face-connected shell must have 12 neighbors");

        var distinct = new HashSet<Point3i>(Arrays.asList(neighbors));
        assertEquals(12, distinct.size(),
                     "all 12 face-connected neighbors must be distinct (Luciferase-2py: index 2 "
                     + "previously aliased (0,+1,+1) with index 8 due to z=1 hardcode)");

        // Index 2 was the bug — must now be (x, y+1, z), not (x, y+1, 1)
        assertEquals(new Point3i(5, 8, 11), neighbors[2],
                     "neighbors[2] must be (x, y+1, z), regression-locked at non-(z=1) cell");
    }

    @Test
    void faceConnectedNeighborsAtOriginAlsoDistinct() {
        // Sanity check against the original bug's "only works when z=1" symptom.
        var neighbors = rdg.faceConnectedNeighbors(new Point3i(0, 0, 0));
        assertEquals(12, new HashSet<>(Arrays.asList(neighbors)).size(),
                     "the bug masqueraded as correct only at z=1; verify origin too");
    }

    // ============================================================
    // Luciferase-etb: RDG.cross/dot/rotateVectorCC stubs → UOE
    // ============================================================

    @Test
    void rdgCrossThrowsUOE() {
        assertThrows(UnsupportedOperationException.class,
                     () -> rdg.cross(new Point3f(1, 0, 0), new Point3f(0, 1, 0)));
    }

    @Test
    void rdgDotThrowsUOE() {
        assertThrows(UnsupportedOperationException.class,
                     () -> rdg.dot(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0)));
    }

    @Test
    void rdgRotateVectorCCThrowsUOE() {
        assertThrows(UnsupportedOperationException.class,
                     () -> rdg.rotateVectorCC(new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), Math.PI / 2));
    }

    // ============================================================
    // Luciferase-xnf: Tetrahedral.vertexConnectedNeighbors — 6 at distance sqrt(2)
    // ============================================================

    @Test
    void vertexConnectedNeighborsAreSixDistinctAtSqrt2() {
        var origin = new Point3i(0, 0, 0);
        var neighbors = tetrahedral.vertexConnectedNeighbors(origin);

        assertEquals(6, neighbors.length, "FCC second shell must have 6 neighbors");
        assertEquals(6, new HashSet<>(Arrays.asList(neighbors)).size(),
                     "all 6 vertex-connected neighbors must be distinct");

        double sqrt2 = Math.sqrt(2.0);
        for (var n : neighbors) {
            var cart = tetrahedral.toCartesian(n);
            double dist = Math.sqrt(cart.getX() * cart.getX() + cart.getY() * cart.getY()
                                    + cart.getZ() * cart.getZ());
            assertEquals(sqrt2, dist, 1e-6,
                         "vertex-connected neighbor " + n + " must be at Cartesian distance √2 "
                         + "(actual=" + dist + ")");
        }
    }

    @Test
    void vertexConnectedNeighborsMapToCartesianAxes() {
        // The 6 FCC second-shell neighbors are the 6 axis-aligned ±√2 unit
        // points (±√2, 0, 0), (0, ±√2, 0), (0, 0, ±√2).
        var neighbors = tetrahedral.vertexConnectedNeighbors(new Point3i(0, 0, 0));
        var cartesianSet = new HashSet<String>();
        double sqrt2 = Math.sqrt(2.0);
        for (var n : neighbors) {
            var c = tetrahedral.toCartesian(n);
            cartesianSet.add(String.format("%.3f,%.3f,%.3f", c.getX(), c.getY(), c.getZ()));
        }
        var expected = Set.of(
            String.format("%.3f,%.3f,%.3f", sqrt2, 0d, 0d),
            String.format("%.3f,%.3f,%.3f", -sqrt2, 0d, 0d),
            String.format("%.3f,%.3f,%.3f", 0d, sqrt2, 0d),
            String.format("%.3f,%.3f,%.3f", 0d, -sqrt2, 0d),
            String.format("%.3f,%.3f,%.3f", 0d, 0d, sqrt2),
            String.format("%.3f,%.3f,%.3f", 0d, 0d, -sqrt2)
        );
        assertEquals(expected, cartesianSet,
                     "vertex-connected neighbors' Cartesian images must be the 6 axis-aligned √2 points");
    }

    // ============================================================
    // Luciferase-7jk: Tetrahedral.dot() metric tensor
    // ============================================================

    @Test
    void dotMatchesMetricTensorForBasisVectors() {
        // Metric: G_ii = 1, G_ij = 1/2 (i ≠ j).
        var ex = new Vector3f(1, 0, 0);
        var ey = new Vector3f(0, 1, 0);
        var ez = new Vector3f(0, 0, 1);

        assertAll("metric tensor G_ii=1, G_ij=1/2",
                  () -> assertEquals(1.0f, tetrahedral.dot(ex, ex), EPS, "G_xx"),
                  () -> assertEquals(1.0f, tetrahedral.dot(ey, ey), EPS, "G_yy"),
                  () -> assertEquals(1.0f, tetrahedral.dot(ez, ez), EPS, "G_zz"),
                  () -> assertEquals(0.5f, tetrahedral.dot(ex, ey), EPS, "G_xy"),
                  () -> assertEquals(0.5f, tetrahedral.dot(ey, ex), EPS, "G_yx (symmetric)"),
                  () -> assertEquals(0.5f, tetrahedral.dot(ex, ez), EPS, "G_xz"),
                  () -> assertEquals(0.5f, tetrahedral.dot(ez, ex), EPS, "G_zx (symmetric)"),
                  () -> assertEquals(0.5f, tetrahedral.dot(ey, ez), EPS, "G_yz"),
                  () -> assertEquals(0.5f, tetrahedral.dot(ez, ey), EPS, "G_zy (symmetric)"));
    }

    @Test
    void dotIsBilinearAndSymmetric() {
        var u = new Vector3f(1, 2, 3);
        var v = new Vector3f(4, -1, 2);
        var w = new Vector3f(0, 3, -2);
        var sum = new Vector3f(u);
        sum.add(v);

        // Symmetry: dot(u, v) == dot(v, u)
        assertEquals(tetrahedral.dot(u, v), tetrahedral.dot(v, u), EPS, "dot must be symmetric");

        // Linearity in first argument: dot(u+v, w) == dot(u, w) + dot(v, w)
        assertEquals(tetrahedral.dot(u, w) + tetrahedral.dot(v, w),
                     tetrahedral.dot(sum, w),
                     EPS,
                     "dot must be linear in left argument");
    }

    // ============================================================
    // Luciferase-f2z: RDG.symmetry vs symmetryOrtho — group closure + correspondence
    // ============================================================

    @Test
    void symmetryOrthoIsGroupClosed() {
        // Generic Cartesian point with all distinct, all non-zero components —
        // its Oh orbit is the full 48 elements (no axis/plane stabilisers).
        var p = new Point3i(1, 2, 3);
        var orbit = new HashSet<String>();
        for (int g = 0; g < 48; g++) {
            var q = rdg.symmetryOrtho(g, p);
            orbit.add(q.x + "," + q.y + "," + q.z);
        }
        assertEquals(48, orbit.size(),
                     "symmetryOrtho applied to a generic Cartesian point must give 48 distinct images "
                     + "(verified Oh group structure)");
    }

    @Test
    void symmetryRdgIsGroupClosedOnGenericPoint() {
        // CHOICE OF TEST POINT (Luciferase-f2z investigation): use an RDG point
        // whose Cartesian image has all distinct, all-non-zero components. RDG
        // (1, 3, 3) maps to Cartesian (1+3-3, -1+3, 3) = (1, 2, 3), which has
        // the full 48-element Oh orbit. The naïve choice RDG (1, 2, 3) maps to
        // Cartesian (0, 1, 3) whose 0 component creates a reflection
        // stabiliser, yielding only 24 orbit elements — that does not test
        // group closure, it tests stabiliser arithmetic.
        //
        // An earlier draft of this test used (1, 2, 3) directly and claimed a
        // 24-element bug in RDG.symmetry; the root-cause investigation
        // (Luciferase-yai) showed the table is in fact correct (0 mismatches
        // vs the table derived from symmetryOrtho through toCartesian/toRDG),
        // and the 24-vs-48 gap was a test-point artefact.
        var p = new Point3i(1, 3, 3);
        var orbit = new HashSet<String>();
        for (int g = 0; g < 48; g++) {
            var q = rdg.symmetry(g, p);
            orbit.add(q.x + "," + q.y + "," + q.z);
        }
        assertEquals(48, orbit.size(),
                     "RDG.symmetry applied to a generic-Cartesian-image RDG point must give "
                     + "48 distinct images (parallel to symmetryOrtho)");
    }

    @Test
    void symmetryRdgMatchesSymmetryOrthoViaCoordTransform() {
        // Cross-system correspondence: for every group element g and every RDG
        // point p, symmetry(g, p) must equal toRDG(symmetryOrtho(g, toCartesian(p))).
        // The toCartesian/toRDG transforms preserve integrality only when the
        // intermediate division by 2 produces integers — every Oh image of an
        // integer Cartesian point in this lattice satisfies that, so the test
        // can compare integer-to-integer.
        var rdgPoints = new Point3i[] {
            new Point3i(0, 0, 0), new Point3i(1, 3, 3),
            new Point3i(2, 4, 5), new Point3i(3, 5, 7), new Point3i(5, 7, 11)
        };
        for (var p : rdgPoints) {
            var cart = rdg.toCartesian(p);
            for (int g = 0; g < 48; g++) {
                var via = rdg.symmetry(g, p);
                var roundtrip = rdg.symmetryOrtho(g, new Point3i((int) cart.getX(), (int) cart.getY(),
                                                                  (int) cart.getZ()));
                var expected = rdg.toRDG(new javax.vecmath.Point3f(roundtrip.x, roundtrip.y, roundtrip.z));
                assertEquals(expected, via,
                             "symmetry(g=" + g + ", " + p + ") via direct table must match "
                             + "toRDG(symmetryOrtho(g, toCartesian(p)))");
            }
        }
    }
}
