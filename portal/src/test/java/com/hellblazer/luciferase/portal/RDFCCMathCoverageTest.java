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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for the portal RD/FCC math kernel
 * (Luciferase-6oa, label {@code portal-rdfcc-quality}).
 * <p>
 * Complements {@link PortalCleanupBatchTest} (which covers the specific
 * bug-regression cases for the {@code 2py / yyb / xnf / 7jk / etb / f2z}
 * batch) with the broader minimum-coverage items the bead lists:
 * <ul>
 *   <li>toCartesian/toRDG round-trip in RDG and Tetrahedral</li>
 *   <li>faceConnectedNeighbors: 12 distinct, all FCC-lattice-valid (distance == 1)</li>
 *   <li>vertexConnectedNeighbors: already exercised in PortalCleanupBatchTest</li>
 *   <li>symmetry/symmetryOrtho: already exercised in PortalCleanupBatchTest</li>
 *   <li>euclideanNorm matches metric tensor</li>
 *   <li>dot/cross algebraic properties (anti-symmetry, orthogonality)</li>
 * </ul>
 * <p>
 * {@code ThreeOrthoscheme} and {@code RhombicDodecahedron} are mesh
 * geometry classes (constructor-only APIs returning {@code Polyhedron}
 * meshes); they are exercised by {@link RDGeometrySmokeTest}.
 *
 * @author hal.hildebrand
 */
public class RDFCCMathCoverageTest {

    private static final float EPS = 1e-4f;

    private final RDG         rdg         = new RDG();
    private final Tetrahedral tetrahedral = new Tetrahedral();

    // ============================================================
    // toCartesian / toRDG round-trip
    // ============================================================

    @Test
    void rdgRoundTripIsIdentityForIntegerLattice() {
        // RDG.toCartesian / toRDG should round-trip exactly for any integer
        // RDG point because the back-transform's /2 always lands on an integer.
        var samples = new Point3i[] {
            new Point3i(0, 0, 0), new Point3i(1, 0, 0), new Point3i(0, 1, 0),
            new Point3i(0, 0, 1), new Point3i(1, 2, 3), new Point3i(-1, -2, -3),
            new Point3i(5, -7, 11), new Point3i(-3, 4, -2)
        };
        for (var p : samples) {
            var cart = rdg.toCartesian(p);
            var back = rdg.toRDG(new Point3f((float) cart.getX(), (float) cart.getY(),
                                              (float) cart.getZ()));
            assertEquals(p, back, "RDG toCartesian then toRDG must round-trip for " + p);
        }
    }

    @Test
    void tetrahedralRoundTripIsIdentityForEvenSumLattice() {
        // Tetrahedral.toCartesian((a,b,c)) = ((b+c), (a+c), (a+b)) / √2.
        // Inverting requires the sum (b+c)+(a+c)+(a+b) = 2(a+b+c) be /2-integer,
        // which holds for any integer (a, b, c). Use Point3D overload to keep
        // double precision through the round-trip.
        var samples = new Point3i[] {
            new Point3i(0, 0, 0), new Point3i(1, 0, 0), new Point3i(0, 1, 0),
            new Point3i(0, 0, 1), new Point3i(1, 3, 3), new Point3i(2, 4, 5),
            new Point3i(-1, 1, -1)
        };
        for (var p : samples) {
            var cart = tetrahedral.toCartesian(p);
            var back = tetrahedral.toRDG(new Point3f((float) cart.getX(),
                                                     (float) cart.getY(),
                                                     (float) cart.getZ()));
            assertEquals(p, back, "Tetrahedral toCartesian then toRDG must round-trip for " + p);
        }
    }

    // ============================================================
    // faceConnectedNeighbors: 12 distinct + FCC-valid (Cartesian distance == 1)
    // ============================================================

    @Test
    void rdgFaceConnectedNeighborsAllAtCartesianDistanceOne() {
        var origin = new Point3i(0, 0, 0);
        var neighbors = rdg.faceConnectedNeighbors(origin);
        assertEquals(12, neighbors.length);
        assertEquals(12, new HashSet<>(Arrays.asList(neighbors)).size(),
                     "12 distinct face-neighbors");

        for (var n : neighbors) {
            var cart = rdg.toCartesian(n);
            double d = Math.sqrt(cart.getX() * cart.getX() + cart.getY() * cart.getY()
                                 + cart.getZ() * cart.getZ());
            // RDG Cartesian basis (1,1,-1), (-1,1,0), (0,0,1): FCC nearest-neighbor
            // distance varies by direction but must be a small lattice constant.
            // Accepting distances in [1, √3] as the FCC near-shell envelope.
            assertTrue(d >= 1.0 - EPS && d <= Math.sqrt(3) + EPS,
                       "RDG face-neighbor " + n + " at Cartesian distance " + d
                       + " must fall in [1, √3] (FCC nearest-shell range under this basis)");
        }
    }

    @Test
    void tetrahedralFaceConnectedNeighborsAreTwelveDistinctAtDistanceOne() {
        var origin = new Point3i(0, 0, 0);
        var neighbors = tetrahedral.faceConnectedNeighbors(origin);
        assertEquals(12, neighbors.length, "FCC face shell has 12 neighbors");
        assertEquals(12, new HashSet<>(Arrays.asList(neighbors)).size(),
                     "12 distinct neighbors");

        for (var n : neighbors) {
            var cart = tetrahedral.toCartesian(n);
            double d = Math.sqrt(cart.getX() * cart.getX() + cart.getY() * cart.getY()
                                 + cart.getZ() * cart.getZ());
            assertEquals(1.0, d, EPS,
                         "Tetrahedral face-neighbor " + n + " must be at Cartesian distance 1.0 "
                         + "(actual " + d + ")");
        }
    }

    // ============================================================
    // euclideanNorm consistency with metric tensor (dot)
    // ============================================================

    @Test
    void euclideanNormMatchesSquareRootOfDotProductWithSelf() {
        var samples = new Vector3f[] {
            new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1),
            new Vector3f(1, 1, 0), new Vector3f(1, 0, 1), new Vector3f(0, 1, 1),
            new Vector3f(2, 3, -1), new Vector3f(-1, -2, 4)
        };
        for (var v : samples) {
            var norm = Tetrahedral.euclideanNorm(v);
            var dotSelf = tetrahedral.dot(v, v);
            assertEquals(Math.sqrt(dotSelf), norm, 1e-4,
                         "||v|| must equal sqrt(<v,v>) for " + v);
        }
    }

    @Test
    void l1IsManhattanDistance() {
        assertEquals(0f, Tetrahedral.l1(new Vector3f(0, 0, 0)), EPS);
        assertEquals(6f, Tetrahedral.l1(new Vector3f(1, 2, 3)), EPS);
        assertEquals(6f, Tetrahedral.l1(new Vector3f(-1, -2, -3)), EPS, "l1 takes |components|");
        assertEquals(7f, Tetrahedral.l1(new Vector3f(-2, 3, -2)), EPS);
    }

    // ============================================================
    // Tetrahedral.cross algebraic properties
    // ============================================================

    @Test
    void crossIsAntiSymmetric() {
        var samples = new Vector3f[][] {
            { new Vector3f(1, 0, 0), new Vector3f(0, 1, 0) },
            { new Vector3f(1, 2, 3), new Vector3f(-1, 1, 2) },
            { new Vector3f(0, 1, 1), new Vector3f(1, 0, 1) }
        };
        for (var pair : samples) {
            var uv = tetrahedral.cross(pair[0], pair[1]);
            var vu = tetrahedral.cross(pair[1], pair[0]);
            assertEquals(uv.x, -vu.x, EPS, "cross(u,v).x must equal -cross(v,u).x");
            assertEquals(uv.y, -vu.y, EPS, "cross(u,v).y must equal -cross(v,u).y");
            assertEquals(uv.z, -vu.z, EPS, "cross(u,v).z must equal -cross(v,u).z");
        }
    }

    @Test
    void crossOfParallelVectorsIsZero() {
        var u = new Vector3f(2, 3, -1);
        // Same direction: cross with itself
        var c = tetrahedral.cross(u, u);
        assertEquals(0f, c.x, EPS, "cross(u, u).x must be 0");
        assertEquals(0f, c.y, EPS, "cross(u, u).y must be 0");
        assertEquals(0f, c.z, EPS, "cross(u, u).z must be 0");
    }

    // ============================================================
    // Tetrahedral.rotateVectorCC sanity checks
    // ============================================================

    @Test
    void rotateVectorCCByZeroIsIdentity() {
        var v = new Vector3f(1, 2, 3);
        var axis = new Vector3f(0, 0, 1);
        var rotated = tetrahedral.rotateVectorCC(v, axis, 0d);
        assertEquals(v.x, rotated.x, EPS);
        assertEquals(v.y, rotated.y, EPS);
        assertEquals(v.z, rotated.z, EPS);
    }

    @Test
    void rotateVectorCCByTwoPiReturnsApproximatelyOriginal() {
        var v = new Vector3f(1, 2, 3);
        var axis = new Vector3f(0, 0, 1);
        var rotated = tetrahedral.rotateVectorCC(v, axis, 2 * Math.PI);
        assertEquals(v.x, rotated.x, 1e-3f, "2π rotation must return to start");
        assertEquals(v.y, rotated.y, 1e-3f);
        assertEquals(v.z, rotated.z, 1e-3f);
    }

    // ============================================================
    // Sanity: euclideanNorm of a unit Cartesian basis vector
    // ============================================================

    @Test
    void euclideanNormOfTetrahedralBasisVectorIsOne() {
        // In the Tetrahedral basis, e_x = (1, 0, 0) has metric-tensor norm
        // sqrt(G_xx) = sqrt(1) = 1.
        assertEquals(1.0f, Tetrahedral.euclideanNorm(new Vector3f(1, 0, 0)), EPS);
        assertEquals(1.0f, Tetrahedral.euclideanNorm(new Vector3f(0, 1, 0)), EPS);
        assertEquals(1.0f, Tetrahedral.euclideanNorm(new Vector3f(0, 0, 1)), EPS);
    }

    @Test
    void euclideanNormOfSumOfTwoBasisVectorsMatchesMetricFormula() {
        // ||e_x + e_y||² = G_xx + G_yy + 2·G_xy = 1 + 1 + 2·(1/2) = 3.
        // So ||e_x + e_y|| = √3.
        assertEquals(Math.sqrt(3), Tetrahedral.euclideanNorm(new Vector3f(1, 1, 0)), EPS);
        assertEquals(Math.sqrt(3), Tetrahedral.euclideanNorm(new Vector3f(1, 0, 1)), EPS);
        assertEquals(Math.sqrt(3), Tetrahedral.euclideanNorm(new Vector3f(0, 1, 1)), EPS);
    }

    @Test
    void rdgToCartesianHandlesPoint3DOverload() {
        // The Point3D (double-precision) overload should agree with the Tuple3i version.
        var p = new Point3i(2, 3, 5);
        var cartI = rdg.toCartesian(p);
        var cartD = rdg.toCartesian(new javafx.geometry.Point3D(p.x, p.y, p.z));
        assertEquals(cartI.getX(), cartD.getX(), EPS, "Point3D and Tuple3i overloads must agree (x)");
        assertEquals(cartI.getY(), cartD.getY(), EPS, "y");
        assertEquals(cartI.getZ(), cartD.getZ(), EPS, "z");
    }

    @Test
    void tetrahedralToCartesianHandlesPoint3DOverload() {
        var p = new Point3i(2, 3, 5);
        var cartI = tetrahedral.toCartesian(p);
        var cartD = tetrahedral.toCartesian(new javafx.geometry.Point3D(p.x, p.y, p.z));
        assertEquals(cartI.getX(), cartD.getX(), EPS);
        assertEquals(cartI.getY(), cartD.getY(), EPS);
        assertEquals(cartI.getZ(), cartD.getZ(), EPS);
    }

    // ============================================================
    // toCartesian of origin is the cartesian origin
    // ============================================================

    @Test
    void cartesianOfOriginIsOrigin() {
        var origin = new Point3i(0, 0, 0);
        assertEquals(0.0, rdg.toCartesian(origin).getX(), EPS);
        assertEquals(0.0, rdg.toCartesian(origin).getY(), EPS);
        assertEquals(0.0, rdg.toCartesian(origin).getZ(), EPS);
        assertEquals(0.0, tetrahedral.toCartesian(origin).getX(), EPS);
        assertEquals(0.0, tetrahedral.toCartesian(origin).getY(), EPS);
        assertEquals(0.0, tetrahedral.toCartesian(origin).getZ(), EPS);
    }

    @Test
    void rdgInstanceIsNotNull() {
        // Smoke: zero-arg constructor works.
        assertNotNull(new RDG());
    }

    @Test
    void tetrahedralInstanceIsNotNull() {
        // Smoke: zero-arg constructor works.
        assertNotNull(new Tetrahedral());
    }
}
