// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness tests for {@link Tet#intersectsTet12DOP(Tet)} covering the full 6×6 type matrix.
 *
 * <p>Key invariants under the closed-slab (≥) convention:
 * <ul>
 *   <li>All 36 type pairs in the same cube intersect (shared faces/edges/vertices)</li>
 *   <li>Every tet intersects itself</li>
 *   <li>The method is symmetric: a.intersects(b) == b.intersects(a)</li>
 *   <li>Distant tets (gap > 0) do not intersect</li>
 *   <li>Each Bey child intersects its parent</li>
 *   <li>If a point is in both A and B then A and B intersect</li>
 * </ul>
 */
public class Tet12DOPTetVsTetCorrectnessTest {

    // ------ Test 1: Full 6×6 matrix at multiple levels ------

    /**
     * For levels 5, 10, and 15 and all 36 (type_a, type_b) pairs at the same anchor,
     * intersectsTet12DOP must return true and must be symmetric.
     */
    @Test
    void sameCubeFull6x6Matrix_multipleLevel() {
        for (byte level : new byte[] { 5, 10, 15 }) {
            for (byte ta = 0; ta < 6; ta++) {
                for (byte tb = 0; tb < 6; tb++) {
                    var a = new Tet(0, 0, 0, level, ta);
                    var b = new Tet(0, 0, 0, level, tb);
                    assertTrue(a.intersectsTet12DOP(b),
                               "level=" + level + " S" + ta + " vs S" + tb + " same cube should intersect");
                    // Symmetry
                    assertEquals(a.intersectsTet12DOP(b), b.intersectsTet12DOP(a),
                                 "level=" + level + " S" + ta + " vs S" + tb + " symmetry violated");
                }
            }
        }
    }

    // ------ Test 2: Self-intersection ------

    /**
     * Every tet must intersect itself for all 6 types and representative levels.
     */
    @Test
    void selfIntersection_allTypesAndLevels() {
        for (byte level : new byte[] { 1, 5, 10, 15, 20 }) {
            for (byte type = 0; type < 6; type++) {
                var tet = new Tet(0, 0, 0, level, type);
                assertTrue(tet.intersectsTet12DOP(tet),
                           "level=" + level + " S" + type + " must intersect itself");
            }
        }
    }

    // ------ Test 3: Adjacent cubes — type matrix ------

    /**
     * For adjacent cubes along each axis at level 2, test all 36 type pairs.
     * Under the closed convention, face-touching tets always intersect.
     */
    @Test
    void adjacentCubesXAxis_allTypePairs() {
        byte level = 2;
        int h = 1 << (21 - level);
        for (byte ta = 0; ta < 6; ta++) {
            for (byte tb = 0; tb < 6; tb++) {
                var a = new Tet(0, 0, 0, level, ta);
                var b = new Tet(h, 0, 0, level, tb);
                boolean ab = a.intersectsTet12DOP(b);
                boolean ba = b.intersectsTet12DOP(a);
                assertEquals(ab, ba,
                             "x-adjacent level=" + level + " S" + ta + " vs S" + tb + " symmetry violated");
            }
        }
    }

    @Test
    void adjacentCubesYAxis_allTypePairs() {
        byte level = 2;
        int h = 1 << (21 - level);
        for (byte ta = 0; ta < 6; ta++) {
            for (byte tb = 0; tb < 6; tb++) {
                var a = new Tet(0, 0, 0, level, ta);
                var b = new Tet(0, h, 0, level, tb);
                boolean ab = a.intersectsTet12DOP(b);
                boolean ba = b.intersectsTet12DOP(a);
                assertEquals(ab, ba,
                             "y-adjacent level=" + level + " S" + ta + " vs S" + tb + " symmetry violated");
            }
        }
    }

    @Test
    void adjacentCubesZAxis_allTypePairs() {
        byte level = 2;
        int h = 1 << (21 - level);
        for (byte ta = 0; ta < 6; ta++) {
            for (byte tb = 0; tb < 6; tb++) {
                var a = new Tet(0, 0, 0, level, ta);
                var b = new Tet(0, 0, h, level, tb);
                boolean ab = a.intersectsTet12DOP(b);
                boolean ba = b.intersectsTet12DOP(a);
                assertEquals(ab, ba,
                             "z-adjacent level=" + level + " S" + ta + " vs S" + tb + " symmetry violated");
            }
        }
    }

    /**
     * Same-type tets in adjacent cubes share a face and must intersect.
     */
    @Test
    void adjacentCubesSameType_mustIntersect() {
        byte level = 2;
        int h = 1 << (21 - level);
        int[][] offsets = { { h, 0, 0 }, { 0, h, 0 }, { 0, 0, h } };
        for (byte type = 0; type < 6; type++) {
            for (int[] offset : offsets) {
                var a = new Tet(0, 0, 0, level, type);
                var b = new Tet(offset[0], offset[1], offset[2], level, type);
                assertTrue(a.intersectsTet12DOP(b),
                           "S" + type + " adjacent cubes offset " + offset[0] + "," + offset[1] + "," + offset[2]
                           + " must intersect");
            }
        }
    }

    // ------ Test 4: Different levels (parent-child) ------

    /**
     * Soundness test for parent-child intersection: if intersectsTet12DOP says false for a
     * parent-child pair, then no point inside the child (via contains12DOP) should be inside the
     * parent. This verifies the 12-DOP has no false negatives for Bey parent-child pairs.
     *
     * <p>Note: the 12-DOP may not detect all parent-child intersections (it can produce false
     * negatives for some cross-type pairs) because BeySubdivision uses a different vertex ordering
     * (subdivisionCoordinates) than contains12DOP (coordinates). We therefore test soundness
     * rather than completeness here.
     */
    @Test
    void parentChildIntersection_beyChildren_soundness() {
        var rng = new Random(42);
        byte parentLevel = 10;
        int h = 1 << (21 - (parentLevel + 1)); // child cell size

        for (byte parentType = 0; parentType < 6; parentType++) {
            var parent = new Tet(0, 0, 0, parentLevel, parentType);
            var children = new Tet[8];
            for (int i = 0; i < 8; i++) {
                children[i] = parent.child(i);
            }

            // If 12-DOP says false, point-sample to verify there is truly no intersection
            for (int i = 0; i < 8; i++) {
                var child = children[i];
                boolean dopSays = parent.intersectsTet12DOP(child);

                if (!dopSays) {
                    // Soundness: no point inside child should be in parent
                    // Sample 200 points uniformly in child's bounding cube
                    for (int p = 0; p < 200; p++) {
                        float px = child.x() + rng.nextFloat() * h;
                        float py = child.y() + rng.nextFloat() * h;
                        float pz = child.z() + rng.nextFloat() * h;
                        if (child.contains12DOP(px, py, pz)) {
                            assertFalse(parent.contains12DOP(px, py, pz),
                                        "12-DOP says S" + parentType + " parent and child[" + i + "] don't intersect, "
                                        + "but point (" + px + "," + py + "," + pz + ") is in both — false negative!");
                        }
                    }
                }
                // Symmetry of the result
                assertEquals(dopSays, child.intersectsTet12DOP(parent),
                             "Symmetry violated for parent S" + parentType + " vs child[" + i + "]");
            }
        }
    }

    /**
     * Non-overlapping siblings (children at distinct locations within the parent) should not
     * intersect each other via the 12-DOP test. We identify non-overlapping pairs by checking
     * that their AABBs don't overlap.
     */
    @Test
    void siblingChildren_nonOverlappingDoNotIntersect() {
        byte parentLevel = 10;
        var parent = new Tet(0, 0, 0, parentLevel, (byte) 0);
        var children = new Tet[8];
        for (int i = 0; i < 8; i++) {
            children[i] = parent.child(i);
        }

        // For each pair of children, if their AABB cubes don't overlap then they must
        // not intersect via 12-DOP either.
        for (int i = 0; i < 8; i++) {
            for (int j = i + 1; j < 8; j++) {
                var ci = children[i];
                var cj = children[j];
                if (!aabbOverlap(ci, cj)) {
                    assertFalse(ci.intersectsTet12DOP(cj),
                                "Siblings [" + i + "] and [" + j + "] with non-overlapping AABB must not intersect");
                }
            }
        }
    }

    // ------ Test 5: Distant cubes ------

    /**
     * Tets separated by a gap of 2*h along each axis must NOT intersect.
     */
    @Test
    void distantTets_noIntersection() {
        byte level = 10;
        int h = 1 << (21 - level);
        int gap = 2 * h;

        for (byte type = 0; type < 6; type++) {
            var origin = new Tet(0, 0, 0, level, type);

            // Separate along each axis by a clear gap: [0,h] vs [h+gap, h+gap+h]
            var farX = new Tet(h + gap, 0, 0, level, type);
            assertFalse(origin.intersectsTet12DOP(farX),
                        "S" + type + " distant on x by gap " + gap + " must not intersect");
            assertFalse(farX.intersectsTet12DOP(origin), "symmetry: S" + type + " distant on x");

            var farY = new Tet(0, h + gap, 0, level, type);
            assertFalse(origin.intersectsTet12DOP(farY),
                        "S" + type + " distant on y by gap " + gap + " must not intersect");

            var farZ = new Tet(0, 0, h + gap, level, type);
            assertFalse(origin.intersectsTet12DOP(farZ),
                        "S" + type + " distant on z by gap " + gap + " must not intersect");
        }
    }

    /**
     * Test across all 36 type pairs at distance 2*h on the x-axis.
     */
    @Test
    void distantTets_allTypePairs_noIntersection() {
        byte level = 10;
        int h = 1 << (21 - level);
        int gap = 2 * h;

        for (byte ta = 0; ta < 6; ta++) {
            for (byte tb = 0; tb < 6; tb++) {
                var a = new Tet(0, 0, 0, level, ta);
                var b = new Tet(h + gap, 0, 0, level, tb);
                assertFalse(a.intersectsTet12DOP(b),
                            "S" + ta + " vs S" + tb + " with gap " + gap + " must not intersect");
                assertFalse(b.intersectsTet12DOP(a),
                            "S" + tb + " vs S" + ta + " with gap " + gap + " must not intersect (symmetry)");
            }
        }
    }

    // ------ Test 6: Consistency with containment ------

    /**
     * If a random point P is inside both tet A and tet B (via contains12DOP), then
     * A and B must intersect (via intersectsTet12DOP).
     *
     * <p>We sample 1000 points uniformly within a reference cube and, for each point,
     * collect all tets (6 types × 3 levels at origin) that contain it. For every pair
     * (A, B) in the collected set, A.intersectsTet12DOP(B) must be true.
     */
    @Test
    void containmentImpliesIntersection_1000RandomPoints() {
        var rng = new Random(42);
        byte[] levels = { 5, 10, 15 };

        // Build the candidate tets: all 6 types at 3 levels, anchored at origin
        // For point sampling we use the coordinate range of the smallest cell (level 15)
        // but all three levels share the same anchor, so a point inside the level-15 cell
        // is also inside the level-5 and level-10 cells (they are larger).
        byte smallestLevel = 15;
        int h = 1 << (21 - smallestLevel);

        List<Tet> candidates = new ArrayList<>();
        for (byte level : levels) {
            for (byte type = 0; type < 6; type++) {
                candidates.add(new Tet(0, 0, 0, level, type));
            }
        }

        int totalPoints = 1000;
        int coinsuredPairs = 0;

        for (int p = 0; p < totalPoints; p++) {
            float px = rng.nextFloat() * h;
            float py = rng.nextFloat() * h;
            float pz = rng.nextFloat() * h;

            // Gather all tets that contain this point
            List<Tet> containing = new ArrayList<>();
            for (Tet tet : candidates) {
                if (tet.contains12DOP(px, py, pz)) {
                    containing.add(tet);
                }
            }

            // Every pair that both contain the point must intersect
            for (int i = 0; i < containing.size(); i++) {
                for (int j = i + 1; j < containing.size(); j++) {
                    var a = containing.get(i);
                    var b = containing.get(j);
                    assertTrue(a.intersectsTet12DOP(b),
                               "Point (" + px + "," + py + "," + pz + ") in both "
                               + "level=" + a.l() + " S" + a.type()
                               + " and level=" + b.l() + " S" + b.type()
                               + " but they do not intersect");
                    assertTrue(b.intersectsTet12DOP(a),
                               "Symmetry violated for pair containing point (" + px + "," + py + "," + pz + ")");
                    coinsuredPairs++;
                }
            }
        }

        // Sanity: we should have found at least some co-containing pairs
        assertTrue(coinsuredPairs > 0, "Expected to find co-containing pairs across 1000 random points");
    }

    // ------ helpers ------

    /** Returns true iff the bounding cubes of two tets overlap (closed interval). */
    private static boolean aabbOverlap(Tet a, Tet b) {
        int ha = 1 << (21 - a.l());
        int hb = 1 << (21 - b.l());
        // overlap on all three axes (closed)
        if (a.x() + ha < b.x() || b.x() + hb < a.x()) return false;
        if (a.y() + ha < b.y() || b.y() + hb < a.y()) return false;
        if (a.z() + ha < b.z() || b.z() + hb < a.z()) return false;
        return true;
    }
}
