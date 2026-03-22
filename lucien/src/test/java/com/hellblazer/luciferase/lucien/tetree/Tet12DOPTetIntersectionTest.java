// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link Tet#intersectsTet12DOP(Tet)}.
 * <p>
 * Two tets intersect iff their projections overlap on all 6 axes: the 3 AABB axes and the
 * 3 difference axes (d_xy = x-y, d_xz = x-z, d_yz = y-z).
 * <p>
 * Global slab for type with sign s (0 = [0,h], 1 = [-h,0]) at anchor a with cell size h:
 * <pre>
 *   lo = a_diff - s * h
 *   hi = a_diff + (1 - s) * h
 * </pre>
 * Two slabs overlap (closed convention ≥) iff lo1 ≤ hi2 && lo2 ≤ hi1.
 * <p>
 * Sign table per type:
 * <pre>
 *   S0: d_xy=+, d_xz=+, d_yz=+
 *   S1: d_xy=-, d_xz=+, d_yz=+
 *   S2: d_xy=+, d_xz=-, d_yz=-
 *   S3: d_xy=-, d_xz=-, d_yz=-
 *   S4: d_xy=+, d_xz=+, d_yz=-
 *   S5: d_xy=-, d_xz=-, d_yz=+
 * </pre>
 */
public class Tet12DOPTetIntersectionTest {

    // Level 10: h = 1 << (21 - 10) = 2048
    private static final byte LEVEL = 10;
    private static final int H = 1 << (21 - LEVEL); // 2048

    // ------ same cube, same type ------

    /**
     * Identical tets must intersect themselves.
     */
    @Test
    void sameCubeSameTypeSameLevel_intersects() {
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, LEVEL, type);
            assertTrue(tet.intersectsTet12DOP(tet), "S" + type + " must intersect itself");
            // also a fresh copy with same params
            var copy = new Tet(0, 0, 0, LEVEL, type);
            assertTrue(tet.intersectsTet12DOP(copy), "S" + type + " must intersect identical copy");
        }
    }

    // ------ same cube, different types ------

    /**
     * Within a cube, every pair of different S-types shares at least a face (closed-slab convention).
     * Each type's difference-axis slab is either [0,h] or [-h,0]; opposite-sign pairs meet at 0 only
     * (a single hyperplane), which still counts as overlap under ≥.
     * <p>
     * All 6×6 pairs including diagonal.
     */
    @Test
    void sameCubeAllTypePairs_intersect() {
        for (byte t1 = 0; t1 < 6; t1++) {
            for (byte t2 = 0; t2 < 6; t2++) {
                var a = new Tet(0, 0, 0, LEVEL, t1);
                var b = new Tet(0, 0, 0, LEVEL, t2);
                assertTrue(a.intersectsTet12DOP(b),
                           "S" + t1 + " vs S" + t2 + " in same cube should intersect (face-contact)");
            }
        }
    }

    // ------ adjacent cubes (face-sharing), same type ------

    /**
     * Two adjacent cubes sharing the x=H face. S0 tets in each extend from 0 to H. The AABB
     * x-axis: [0,H] vs [H, 2H] — they touch at x=H, so AABB overlap is true (closed).
     * The difference slabs: same type → same local shape, anchor shifts by H on x.
     * d_xy global: A=[0,H], B=[H,2H] — meet at H → overlap.
     * d_xz global: same argument → meet at H → overlap.
     * d_yz global: A=[0,H], B=[0,H] (y and z unchanged) → full overlap.
     * So they intersect on the shared face.
     */
    @Test
    void adjacentCubesXFace_sameType_intersects() {
        for (byte type = 0; type < 6; type++) {
            var a = new Tet(0, 0, 0, LEVEL, type);
            var b = new Tet(H, 0, 0, LEVEL, type);
            assertTrue(a.intersectsTet12DOP(b),
                       "S" + type + " adjacent on x-face should intersect");
        }
    }

    @Test
    void adjacentCubesYFace_sameType_intersects() {
        for (byte type = 0; type < 6; type++) {
            var a = new Tet(0, 0, 0, LEVEL, type);
            var b = new Tet(0, H, 0, LEVEL, type);
            assertTrue(a.intersectsTet12DOP(b),
                       "S" + type + " adjacent on y-face should intersect");
        }
    }

    @Test
    void adjacentCubesZFace_sameType_intersects() {
        for (byte type = 0; type < 6; type++) {
            var a = new Tet(0, 0, 0, LEVEL, type);
            var b = new Tet(0, 0, H, LEVEL, type);
            assertTrue(a.intersectsTet12DOP(b),
                       "S" + type + " adjacent on z-face should intersect");
        }
    }

    // ------ distant cubes ------

    /**
     * Tets separated by more than their combined extents on the x-axis have no AABB overlap.
     */
    @Test
    void distantCubes_noIntersection() {
        for (byte type = 0; type < 6; type++) {
            var a = new Tet(0, 0, 0, LEVEL, type);
            // gap of one full cell size: [0,H] vs [3H,4H], AABB x-gap = 2H > 0
            var b = new Tet(3 * H, 0, 0, LEVEL, type);
            assertFalse(a.intersectsTet12DOP(b),
                        "S" + type + " separated by gap on x should not intersect");
        }
    }

    @Test
    void distantCubesOnY_noIntersection() {
        var a = new Tet(0, 0, 0, LEVEL, (byte) 0);
        var b = new Tet(0, 3 * H, 0, LEVEL, (byte) 0);
        assertFalse(a.intersectsTet12DOP(b));
    }

    @Test
    void distantCubesOnZ_noIntersection() {
        var a = new Tet(0, 0, 0, LEVEL, (byte) 0);
        var b = new Tet(0, 0, 3 * H, LEVEL, (byte) 0);
        assertFalse(a.intersectsTet12DOP(b));
    }

    // ------ different levels (parent-child) ------

    /**
     * A level-9 tet (h=4096) contains all 6 level-10 children in the same anchor cube.
     * The child tet is fully inside the parent's AABB and difference slabs, so they overlap.
     */
    @Test
    void parentChildSameAnchor_intersects() {
        byte parentLevel = 9;
        // parent S0 at origin
        var parent = new Tet(0, 0, 0, parentLevel, (byte) 0);
        // child S0 at origin (fits inside parent's cube)
        var child = new Tet(0, 0, 0, LEVEL, (byte) 0);
        assertTrue(parent.intersectsTet12DOP(child), "Parent S0 should intersect child S0 at same anchor");
        assertTrue(child.intersectsTet12DOP(parent), "Child S0 should intersect parent S0 (symmetry)");
    }

    /**
     * A level-9 parent and a level-10 child anchored inside the parent but at different position.
     * Child anchor (H, 0, 0) is inside parent's [0, 2H] AABB.
     */
    @Test
    void parentChildOffsetChild_intersects() {
        byte parentLevel = 9; // h = 4096
        var parent = new Tet(0, 0, 0, parentLevel, (byte) 0);
        var child = new Tet(H, 0, 0, LEVEL, (byte) 0); // inside parent
        assertTrue(parent.intersectsTet12DOP(child), "Parent should intersect child offset inside it");
    }

    /**
     * A level-9 parent and a level-10 tet clearly outside the parent's AABB.
     */
    @Test
    void parentAndDistantSmallTet_noIntersection() {
        byte parentLevel = 9; // h = 4096
        var parent = new Tet(0, 0, 0, parentLevel, (byte) 0);
        // child well outside: starts at 2*parentH = 8192
        int parentH = 1 << (21 - parentLevel);
        // [0, parentH] vs [3*parentH, 4*parentH]: gap of 2*parentH
        var distant = new Tet(3 * parentH, 0, 0, LEVEL, (byte) 0);
        assertFalse(parent.intersectsTet12DOP(distant), "Distant small tet should not intersect parent");
    }

    // ------ symmetry ------

    /**
     * intersectsTet12DOP must be symmetric: A.intersects(B) == B.intersects(A).
     */
    @Test
    void symmetry_sameCube() {
        for (byte t1 = 0; t1 < 6; t1++) {
            for (byte t2 = 0; t2 < 6; t2++) {
                var a = new Tet(0, 0, 0, LEVEL, t1);
                var b = new Tet(0, 0, 0, LEVEL, t2);
                assertEquals(a.intersectsTet12DOP(b), b.intersectsTet12DOP(a),
                             "Symmetry violated: S" + t1 + " vs S" + t2);
            }
        }
    }

    @Test
    void symmetry_differentLevels() {
        var big = new Tet(0, 0, 0, (byte) 8, (byte) 3);
        var small = new Tet(H, H, H, LEVEL, (byte) 2);
        assertEquals(big.intersectsTet12DOP(small), small.intersectsTet12DOP(big),
                     "Symmetry must hold across different levels");
    }

    // ------ cross-type adjacent cubes — selected cases ------

    /**
     * S0 in cube [0,H]^3 vs S3 in adjacent cube [H,2H]x[0,H]^2.
     * S0 d_xy sign=+: global [0,H].
     * S3 d_xy sign=-: global [H-H, H] = [0, H].  → overlap [0,H]. ✓
     * S0 d_xz sign=+: [0,H]. S3 d_xz sign=-: [(H)-(H), H+0]=[0,H]. → overlap ✓
     * S0 d_yz sign=+: [0,H]. S3 d_yz sign=-: [(0)-(H), 0]= [-H,0]. → overlap at 0. ✓
     * AABB: x: [0,H] vs [H,2H] → touch at H ✓. y,z: full overlap ✓.
     */
    @Test
    void s0InOriginCube_vs_s3InAdjacentX_intersects() {
        var s0 = new Tet(0, 0, 0, LEVEL, (byte) 0);
        var s3 = new Tet(H, 0, 0, LEVEL, (byte) 3);
        assertTrue(s0.intersectsTet12DOP(s3), "S0 at origin vs S3 in adjacent-x cube should intersect");
    }

    /**
     * S0 in cube [0,H]^3 vs S0 in cube [2H, 3H] x [0,H]^2 — gap of one full H between them.
     * AABB x: [0,H] vs [2H,3H] → no overlap (H < 2H). Should be false.
     */
    @Test
    void s0OriginVsS0TwoCubesAway_noIntersection() {
        var a = new Tet(0, 0, 0, LEVEL, (byte) 0);
        var b = new Tet(2 * H, 0, 0, LEVEL, (byte) 0);
        // AABB: [0,H] vs [2H,3H] — gap of H between them, no touch
        assertFalse(a.intersectsTet12DOP(b), "S0 two cubes away should not intersect");
    }
}
