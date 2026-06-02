/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the load-bearing geometric fact behind PyramidIndex's <em>conservative</em> (cube-AABB) query and
 * containment for pyramid leaves (Luciferase-yye5 / Luciferase-2lo3).
 *
 * <p><b>Why this matters.</b> The two root pyramids (t8code dpyramid {@code FIRST_TYPE}=6 and
 * {@code SECOND_TYPE}=7) are the entire pyramid root cover. A cube cannot be tiled by two square pyramids:
 * the classic Yangma decomposition needs <em>three</em> pyramids, and the t8code dpyramid scheme defines
 * only two types. The two root pyramids therefore cover exactly <b>2/3</b> of the cube and are disjoint; the
 * remaining 1/3 has <em>no</em> exact pyramid owner. Consequently an "exact" pyramid query/containment over a
 * full-cube domain would silently drop entities located in the uncovered third — so PyramidIndex deliberately
 * keeps the conservative surrounding-cube result for pyramid leaves (never a false negative). This test makes
 * that 2/3 coverage an explicit, regression-guarded invariant: it is the reason the "exact 14-DOP / 2-tet
 * pyramid query" (Luciferase-2lo3) is infeasible without first completing the partition (a construction-level
 * change beyond the type-6/7 scheme).
 *
 * @author hal.hildebrand
 */
class PyramidDomainCoverageTest {

    @Test
    void twoRootPyramidsCoverExactlyTwoThirdsOfTheCubeAndAreDisjoint() {
        var r6 = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var r7 = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7);
        long extent = 1L << 21; // root cube edge (MAX_EXTENT)

        var rng = new Random(20260602L);
        int n = 200_000, covered = 0, both = 0;
        for (int i = 0; i < n; i++) {
            double x = rng.nextDouble() * extent, y = rng.nextDouble() * extent, z = rng.nextDouble() * extent;
            boolean in6 = insidePyramid(r6, x, y, z);
            boolean in7 = insidePyramid(r7, x, y, z);
            if (in6 || in7) {
                covered++;
            }
            if (in6 && in7) {
                both++;
            }
        }
        double coverage = (double) covered / n;

        // The two root pyramids are disjoint (overlap only on a measure-zero shared face).
        assertEquals(0, both, "the two root pyramids must be interior-disjoint");
        // ...and together cover 2/3 of the cube — NOT the whole cube. A cube needs three Yangma pyramids.
        assertTrue(coverage > 0.64 && coverage < 0.69,
                   "two root pyramids cover ~2/3 of the cube (got " + String.format("%.3f", coverage)
                   + "); if this changes, the conservative-cube contract for pyramid leaves must be revisited "
                   + "(Luciferase-2lo3 / yye5)");
        // The uncovered third is real: a clear sample of it exists. (30468, 5757, 29884) is the integer-rounded
        // entity-460 witness point from PyramidRangeQueryScenarioTest (float (30468.6,5756.7,29884.5)); the gap
        // is a large region, not a boundary sliver, so the rounding is immaterial.
        assertTrue(!insidePyramid(r6, 30468, 5757, 29884) && !insidePyramid(r7, 30468, 5757, 29884),
                   "the entity-460 witness point lies in the uncovered third (no exact pyramid owner)");
    }

    /** Independent exact point-in-pyramid via the two Kuhn sub-tets (barycentric), level-agnostic. */
    private static boolean insidePyramid(Pyramid p, double x, double y, double z) {
        var c = p.coordinates(); // c0..c3 base (c0–c3 diagonal), c4 apex
        return inTet(c[0], c[1], c[3], c[4], x, y, z) || inTet(c[0], c[2], c[3], c[4], x, y, z);
    }

    private static boolean inTet(Point3i a, Point3i b, Point3i c, Point3i d, double x, double y, double z) {
        double vt = vol(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z);
        if (vt == 0) {
            return false;
        }
        double s = Math.signum(vt), tol = 1e-9 * Math.abs(vt);
        double v0 = vol(x, y, z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z) * s;
        double v1 = vol(a.x, a.y, a.z, x, y, z, c.x, c.y, c.z, d.x, d.y, d.z) * s;
        double v2 = vol(a.x, a.y, a.z, b.x, b.y, b.z, x, y, z, d.x, d.y, d.z) * s;
        double v3 = vol(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, x, y, z) * s;
        return v0 >= -tol && v1 >= -tol && v2 >= -tol && v3 >= -tol;
    }

    private static double vol(double ax, double ay, double az, double bx, double by, double bz,
                              double cx, double cy, double cz, double dx, double dy, double dz) {
        double bax = bx - ax, bay = by - ay, baz = bz - az;
        double cax = cx - ax, cay = cy - ay, caz = cz - az;
        double dax = dx - ax, day = dy - ay, daz = dz - az;
        return bax * (cay * daz - caz * day) - bay * (cax * daz - caz * dax) + baz * (cax * day - cay * dax);
    }
}
