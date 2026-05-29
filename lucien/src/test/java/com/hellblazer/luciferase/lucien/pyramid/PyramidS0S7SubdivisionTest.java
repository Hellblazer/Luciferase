/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that a cube is exactly tiled by two pyramids (types 6 and 7) plus two tetrahedra, with
 * no gaps and no overlaps (RDR-010, Knapp 2026 Fig 3.1c). This is the foundational correctness proof
 * for {@link Pyramid#coordinates()} and the pyramid/tet hybrid partition.
 *
 * <p>Geometry derived from t8code {@code t8_dpyramid_compute_coords}: at height {@code z} the type-6
 * pyramid occupies {@code [z,L]x[z,L]} and the type-7 pyramid occupies {@code [0,z]x[0,z]}, leaving
 * two tetrahedral gaps {@code conv{V0,V1,V5,V7}} and {@code conv{V0,V2,V6,V7}}. Volume identity:
 * {@code 2*(L^3/3) + 2*(L^3/6) = L^3}.
 *
 * <p>A high level (small edge length L=8) keeps the {@code orient3d} determinants well inside double
 * precision. Monte Carlo sampling is seeded for determinism.
 *
 * @author hal.hildebrand
 */
class PyramidS0S7SubdivisionTest {

    private static final double EPS = 1e-9;

    // Cube under test: anchor (0,0,0), level 18 => edge length L = 2^(21-18) = 8.
    private static final byte LEVEL = 18;
    private static final int  L     = 8;

    @Test
    void type6VertexCoordinatesMatchT8code() {
        var p = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        var expected = new Point3i[] { new Point3i(0, 0, 0), new Point3i(L, 0, 0), new Point3i(0, L, 0),
                                       new Point3i(L, L, 0), new Point3i(L, L, L) };
        assertArrayEquals(expected, p.coordinates(),
                          "Type-6 base on bottom face, apex at far (+x,+y,+z) corner");
    }

    @Test
    void type7VertexCoordinatesMatchT8code() {
        var p = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_7);
        var expected = new Point3i[] { new Point3i(0, 0, L), new Point3i(L, 0, L), new Point3i(0, L, L),
                                       new Point3i(L, L, L), new Point3i(0, 0, 0) };
        assertArrayEquals(expected, p.coordinates(),
                          "Type-7 base on top face, apex at anchor corner (180-deg rotation of type 6)");
    }

    @Test
    void typesAreDistinctAndAnchorShared() {
        var p6 = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        var p7 = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_7);
        // Both share the anchor corner as a vertex (apex of 7 == base corner of 6, and vice-versa).
        assertEquals(new Point3i(0, 0, 0), p6.coordinates()[0]);
        assertEquals(new Point3i(0, 0, 0), p7.coordinates()[4]);
    }

    @Test
    void cubeIsTiledWithNoGapsNoOverlaps() {
        double[][] p6 = toDoubles(new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6).coordinates());
        double[][] p7 = toDoubles(new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_7).coordinates());
        // Two tetrahedral gaps. These corner sets are derived purely from the geometry: at height z
        // the pyramids occupy [z,L]^2 (type 6) and [0,z]^2 (type 7), leaving conv{V0,V1,V5,V7} and
        // conv{V0,V2,V6,V7}. They happen to coincide with Luciferase tet types S4 and S5. NOTE: these
        // are NOT the tet *child* types of a refined pyramid -- Knapp Table 3.2 / t8code
        // parenttype_Iloc_to_type row 6 gives child tet types 0 and 3. That is a different partition
        // (pyramid refinement, not the root cube tiling); this test validates coordinates() and the
        // cube tiling only. Child-type correctness is exercised in a later phase (pi1.2/pi1.3).
        double[][] gapA = { v(0, 0, 0), v(L, 0, 0), v(L, 0, L), v(L, L, L) }; // conv{V0,V1,V5,V7}
        double[][] gapB = { v(0, 0, 0), v(0, L, 0), v(0, L, L), v(L, L, L) }; // conv{V0,V2,V6,V7}

        // Volume conservation: 2 pyramids + 2 tets == cube.
        double cubeVol = (double) L * L * L;
        double sum = pyramidVolume(p6) + pyramidVolume(p7) + tetVolume(gapA) + tetVolume(gapB);
        assertEquals(cubeVol, sum, EPS * cubeVol, "Element volumes must sum to the cube volume");
        assertEquals(cubeVol / 3.0, pyramidVolume(p6), EPS * cubeVol, "Each pyramid is L^3/3");
        assertEquals(cubeVol / 6.0, tetVolume(gapA), EPS * cubeVol, "Each gap tet is L^3/6");

        // Monte Carlo coverage (no gaps) and disjointness (no overlaps).
        var rng = new Random(0xC0FFEEL);
        int samples = 200_000;
        for (int i = 0; i < samples; i++) {
            double[] q = { rng.nextDouble() * L, rng.nextDouble() * L, rng.nextDouble() * L };
            int strictly = 0;
            if (strictInsidePyramid(p6, q)) {
                strictly++;
            }
            if (strictInsidePyramid(p7, q)) {
                strictly++;
            }
            if (strictInsideTet(gapA, q)) {
                strictly++;
            }
            if (strictInsideTet(gapB, q)) {
                strictly++;
            }
            final int overlap = strictly;
            final double[] pt = q;
            assertTrue(overlap <= 1, () -> "Overlap: point " + java.util.Arrays.toString(pt) + " in " + overlap
            + " elements");

            boolean covered = insideOrOnPyramid(p6, q) || insideOrOnPyramid(p7, q) || insideOrOnTet(gapA, q)
            || insideOrOnTet(gapB, q);
            assertTrue(covered, () -> "Gap: point " + java.util.Arrays.toString(pt) + " covered by no element");
        }
    }

    // ===== geometry helpers =====

    private static double[] v(double x, double y, double z) {
        return new double[] { x, y, z };
    }

    private static double[][] toDoubles(Point3i[] pts) {
        var out = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            out[i] = v(pts[i].x, pts[i].y, pts[i].z);
        }
        return out;
    }

    /** Six times the signed volume of tetrahedron (a,b,c,d). */
    private static double orient3d(double[] a, double[] b, double[] c, double[] d) {
        double[] ad = { a[0] - d[0], a[1] - d[1], a[2] - d[2] };
        double[] bd = { b[0] - d[0], b[1] - d[1], b[2] - d[2] };
        double[] cd = { c[0] - d[0], c[1] - d[1], c[2] - d[2] };
        double cx = bd[1] * cd[2] - bd[2] * cd[1];
        double cy = bd[2] * cd[0] - bd[0] * cd[2];
        double cz = bd[0] * cd[1] - bd[1] * cd[0];
        return ad[0] * cx + ad[1] * cy + ad[2] * cz;
    }

    private static double tetVolume(double[][] t) {
        return Math.abs(orient3d(t[0], t[1], t[2], t[3])) / 6.0;
    }

    private static double pyramidVolume(double[][] p) {
        // p = [c0,c1,c2,c3,apex]; base square loop is c0,c1,c3,c2.
        double[][] a = { p[0], p[1], p[3], p[4] };
        double[][] b = { p[0], p[3], p[2], p[4] };
        return tetVolume(a) + tetVolume(b);
    }

    /** Barycentric coordinates of q w.r.t. tet t (relative to the tet's signed volume). */
    private static double[] bary(double[][] t, double[] q) {
        double vol = orient3d(t[0], t[1], t[2], t[3]);
        double b0 = orient3d(q, t[1], t[2], t[3]) / vol;
        double b1 = orient3d(t[0], q, t[2], t[3]) / vol;
        double b2 = orient3d(t[0], t[1], q, t[3]) / vol;
        double b3 = orient3d(t[0], t[1], t[2], q) / vol;
        return new double[] { b0, b1, b2, b3 };
    }

    private static boolean insideOrOnTet(double[][] t, double[] q) {
        for (double b : bary(t, q)) {
            if (b < -EPS) {
                return false;
            }
        }
        return true;
    }

    private static boolean strictInsideTet(double[][] t, double[] q) {
        for (double b : bary(t, q)) {
            if (b <= EPS) {
                return false;
            }
        }
        return true;
    }

    private static boolean insideOrOnPyramid(double[][] p, double[] q) {
        double[][] a = { p[0], p[1], p[3], p[4] };
        double[][] b = { p[0], p[3], p[2], p[4] };
        return insideOrOnTet(a, q) || insideOrOnTet(b, q);
    }

    private static boolean strictInsidePyramid(double[][] p, double[] q) {
        // Strictly inside the pyramid: strictly inside either half-tet, OR on their shared internal
        // diagonal face but inside the pyramid body. Approximate by "inside-or-on either half AND not
        // on the pyramid's outer boundary"; for disjointness testing it suffices to treat a point as
        // strictly inside if it is strictly inside either half-tet.
        double[][] a = { p[0], p[1], p[3], p[4] };
        double[][] b = { p[0], p[3], p[2], p[4] };
        return strictInsideTet(a, q) || strictInsideTet(b, q);
    }
}
