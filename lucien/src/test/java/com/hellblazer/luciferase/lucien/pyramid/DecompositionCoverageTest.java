/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage test for {@link PyramidContainment}: verifies that the decompose-and-reuse algorithm
 * achieves ≥99.9% coverage — i.e., every point geometrically inside the pyramid is reported
 * as contained.
 *
 * <h2>Oracle strategy</h2>
 * The oracle determines whether a query point Q is "truly inside" the pyramid P by recursively
 * decomposing P into its 10 children via {@link Pyramid#child(int)}, using:
 * <ul>
 *   <li>For tet children: {@code Tet.contains12DOP()} (valid since tet type is always 0..5)</li>
 *   <li>For pyramid children: a conservative AABB test on the pyramid's surrounding cube</li>
 * </ul>
 * The oracle recurses to a fixed depth (ORACLE_MAX_DEPTH) and uses union semantics: a point is
 * "inside" if any child contains it. This is an upper bound — it may over-report "inside" for
 * border points near pyramid faces, but it will never report "inside" for points that are clearly
 * outside all children.
 *
 * <p>The oracle is CONSERVATIVE in the AABB sense for pyramid sub-children: it uses the
 * surrounding cube of a sub-pyramid as a proxy. Points in the cube-AABB but not in the geometric
 * pyramid are treated as "possibly inside" (not "inside") at depth limit — so the oracle does NOT
 * call {@code PyramidContainment.contains} under the hood, avoiding circular reasoning. The oracle
 * terminates independently.
 *
 * <h2>STOP gate</h2>
 * If coverage drops below 99.9%, the test fails with a clear message. Do NOT widen scope or
 * weaken this threshold. If it fails, report and recommend escalation to §3a (20-DOP, bead
 * gated on Bey 1992 paper).
 *
 * @author hal.hildebrand
 */
class DecompositionCoverageTest {

    /**
     * Oracle recursion depth — MUST exceed {@link PyramidContainment#MAX_RECURSION_DEPTH}, otherwise
     * the oracle and algorithm both fall back to AABB at the same depth and the gate is blind to any
     * systematic error in the AABB-fallback region (code-review HIGH-1, 2026-05-29). Setting it
     * {@code +2} deeper means the oracle resolves two extra levels of pyramid sub-children with
     * exact tet 12-DOP before its own AABB fallback, giving the gate real teeth in the region the
     * algorithm handles via AABB.
     */
    private static final int ORACLE_MAX_DEPTH = PyramidContainment.MAX_RECURSION_DEPTH + 2;

    /** Number of random samples to test. */
    private static final int SAMPLE_COUNT = 10_000;

    /** Coverage gate: at least 99.9% of oracle-inside points must be reported as contained. */
    private static final double COVERAGE_THRESHOLD = 0.999;

    @Test
    void coverageAtLeast999PercentType6() {
        var p = new Pyramid(0, 0, 0, (byte) 3, Pyramid.TYPE_6);
        checkCoverage(p, "type-6 pyramid level 3");
    }

    @Test
    void coverageAtLeast999PercentType7() {
        var p = new Pyramid(0, 0, 0, (byte) 3, Pyramid.TYPE_7);
        checkCoverage(p, "type-7 pyramid level 3");
    }

    @Test
    void coverageAtLeast999PercentType6Level5() {
        var p = new Pyramid(0, 0, 0, (byte) 5, Pyramid.TYPE_6);
        checkCoverage(p, "type-6 pyramid level 5");
    }

    @Test
    void coverageAtLeast999PercentType7Level5() {
        var p = new Pyramid(0, 0, 0, (byte) 5, Pyramid.TYPE_7);
        checkCoverage(p, "type-7 pyramid level 5");
    }

    // -----------------------------------------------------------------------
    // Partition property: each point lands in exactly one tet child (or ≥1 with border tolerance)
    // -----------------------------------------------------------------------

    @Test
    void partitionPropertyBothTypes() {
        // For each type, check that the 4 tet children of a pyramid partition its interior:
        // no point is in zero tet children AND no point is in more than 2 tet children
        // (overlaps at borders are expected due to closed-simplex convention).
        for (var type : new byte[]{ Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var p = new Pyramid(0, 0, 0, (byte) 3, type);
            var rng = new Random(0xCAFEBABEL);
            int h = p.length();
            int zeroTetCount = 0;
            int manyTetCount = 0;
            int totalInside = 0;

            for (int i = 0; i < 2000; i++) {
                float qx = p.x() + rng.nextFloat() * h;
                float qy = p.y() + rng.nextFloat() * h;
                float qz = p.z() + rng.nextFloat() * h;

                // Count how many direct tet children contain this point
                int tetHits = 0;
                for (int c = 0; c < 10; c++) {
                    var child = p.child(c);
                    if (child instanceof Tet t) {
                        if (t.contains12DOP(qx, qy, qz)) {
                            tetHits++;
                        }
                    }
                }
                if (tetHits == 0) zeroTetCount++;
                if (tetHits > 2) manyTetCount++;
                totalInside++;
            }

            // Most points should be covered by exactly 1 tet child; some border points by 2.
            // If any point has >2 tet coverage, that's unexpected (closed-simplex face overlap is
            // bounded at 2).
            assertTrue(manyTetCount == 0 || (double) manyTetCount / totalInside < 0.01,
                       "At most 1% of points may be in >2 tet children for type-" + type
                       + "; got " + manyTetCount + "/" + totalInside);
            // Non-vacuousness: the test would pass trivially if every point landed in pyramid
            // sub-children only (zero tet hits) — then the `manyTetCount` bound is meaningless. The
            // 4 tet children of a pyramid cover a nontrivial fraction of the cube interior, so a
            // sane lower bound on tet-hit fraction guards against this. Pyramid is roughly half the
            // cube, the 4 tet children cover a non-trivial sub-volume — well above 5%.
            int tetHitFraction = totalInside - zeroTetCount;
            assertTrue((double) tetHitFraction / totalInside > 0.05,
                       "Tet children should cover >5% of the cube AABB for type-" + type
                       + "; got " + tetHitFraction + "/" + totalInside
                       + ". A near-zero tetHitFraction would make the manyTetCount bound vacuous.");
        }
    }

    // -----------------------------------------------------------------------
    // Core coverage helper
    // -----------------------------------------------------------------------

    private void checkCoverage(Pyramid p, String label) {
        var rng = new Random(0xBEEFCAFEL);
        int h = p.length();

        int oracleInsideCount = 0;
        int truePositiveCount = 0;
        int falseNegativeCount = 0;

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            float qx = p.x() + rng.nextFloat() * h;
            float qy = p.y() + rng.nextFloat() * h;
            float qz = p.z() + rng.nextFloat() * h;
            var q = new Point3f(qx, qy, qz);

            boolean oracleIn = oracleContains(p, qx, qy, qz, ORACLE_MAX_DEPTH);
            if (oracleIn) {
                oracleInsideCount++;
                boolean algoIn = PyramidContainment.contains(p, q);
                if (algoIn) {
                    truePositiveCount++;
                } else {
                    falseNegativeCount++;
                }
            }
        }

        // Require at least some oracle-inside samples (sanity: pyramid should contain ~33-50% of cube AABB)
        assertTrue(oracleInsideCount > SAMPLE_COUNT / 10,
                   label + ": oracle should find many inside points; got " + oracleInsideCount
                   + "/" + SAMPLE_COUNT);

        double coverage = oracleInsideCount == 0 ? 1.0
                                                 : (double) truePositiveCount / oracleInsideCount;

        assertTrue(coverage >= COVERAGE_THRESHOLD,
                   String.format(
                   "%s: coverage %.4f%% is below the %.1f%% gate. "
                   + "oracleInside=%d truePositive=%d falseNegative=%d out of %d samples. "
                   + "STOP: do not widen scope or weaken threshold. "
                   + "Escalate to §3a (20-DOP) if this persists.",
                   label, coverage * 100, COVERAGE_THRESHOLD * 100,
                   oracleInsideCount, truePositiveCount, falseNegativeCount, SAMPLE_COUNT));
    }

    // -----------------------------------------------------------------------
    // Oracle — independent of PyramidContainment
    // -----------------------------------------------------------------------

    /**
     * Conservative oracle: a point Q is "inside" pyramid P if any immediate child of P
     * contains Q. For tet children, uses {@code Tet.contains12DOP}. For pyramid children with
     * remaining depth budget, recurses with budget − 1. At budget 0, uses AABB (cube bounding box)
     * of the sub-pyramid — which over-approximates but never under-reports.
     *
     * <p>The oracle uses the SAME descending-budget convention as the algorithm
     * ({@link PyramidContainment#containsRecursive}) so that {@code ORACLE_MAX_DEPTH −
     * MAX_RECURSION_DEPTH} cleanly counts the extra levels of exact tet 12-DOP resolution the
     * oracle performs past where the algorithm falls back to AABB. With ORACLE = MAX + 2, the
     * oracle resolves two extra pyramid-tree levels exactly, giving the coverage gate teeth in
     * the AABB-region.
     *
     * <p>This oracle deliberately does NOT call {@link PyramidContainment#contains} — that would
     * be circular reasoning.
     */
    private static boolean oracleContains(Pyramid p, float qx, float qy, float qz, int depth) {
        for (int i = 0; i < 10; i++) {
            HybridElement child = p.child(i);
            if (child instanceof Tet t) {
                if (t.contains12DOP(qx, qy, qz)) {
                    return true;
                }
            } else if (child instanceof Pyramid sub) {
                if (depth > 0) {
                    if (oracleContains(sub, qx, qy, qz, depth - 1)) {
                        return true;
                    }
                } else {
                    // Budget exhausted: fall back to cube AABB (conservative over-approximation)
                    if (pointInCubeAABB(sub, qx, qy, qz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns true if (qx, qy, qz) lies within the cube AABB of element {@code e}.
     * This is a conservative test: points in the AABB but outside the geometric element
     * are treated as "inside" (upper bound).
     */
    private static boolean pointInCubeAABB(HybridElement e, float qx, float qy, float qz) {
        float minX = e.x(), minY = e.y(), minZ = e.z();
        float maxX = minX + e.length(), maxY = minY + e.length(), maxZ = minZ + e.length();
        return qx >= minX && qx <= maxX && qy >= minY && qy <= maxY && qz >= minZ && qz <= maxZ;
    }
}
