/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.BeySubdivision;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-7wzml.171: cross-check ESVTChildOrder.CHILD_CENTROIDS (all 6 types × 8 Morton children)
 * against the authoritative Bey/t8code child geometry.
 *
 * <p>For each parent type (0-5) and Morton child index (0-7), this test independently derives the
 * expected centroid from {@link Constants#SIMPLEX_STANDARD} + {@link TetreeConnectivity#INDEX_TO_BEY_NUMBER}
 * and asserts it matches {@link ESVTChildOrder#getChildCentroid(int, int)}.
 *
 * <p>The Bey child vertex sets (matching t8code / BeySubdivision.getBeyChild):
 * <ul>
 *   <li>Bey 0: {v0, m01, m02, m03}</li>
 *   <li>Bey 1: {m01, v1, m12, m13}</li>
 *   <li>Bey 2: {m02, m12, v2, m23}</li>
 *   <li>Bey 3: {m03, m13, m23, v3}</li>
 *   <li>Bey 4: {m01, m02, m03, m13}</li>
 *   <li>Bey 5: {m01, m02, m12, m13}</li>
 *   <li>Bey 6: {m02, m03, m13, m23}</li>
 *   <li>Bey 7: {m02, m12, m13, m23}</li>
 * </ul>
 *
 * <p>The Morton→Bey mapping is {@code INDEX_TO_BEY_NUMBER[type][mortonChild]}, which is type-dependent
 * (this was the bug: the old code treated Morton 0-7 as Bey 0-7).
 *
 * @see ESVTChildOrder#getChildCentroid(int, int)
 * @see TetreeConnectivity#INDEX_TO_BEY_NUMBER
 */
class ChildCentroidsVsBeyTablesTest {

    private static final float TOLERANCE = 1e-5f;

    /**
     * For every tet type (0-5) and every Morton child index (0-7), verify that
     * {@code ESVTChildOrder.CHILD_CENTROIDS[type][mortonChild]} equals the centroid derived from
     * {@code SIMPLEX_STANDARD[type]} vertices mapped through {@code INDEX_TO_BEY_NUMBER[type][mortonChild]}.
     *
     * <p>Children 4-7 (octahedral Bey children) are the primary concern in bead .171, but this test
     * covers all 48 cases uniformly to guard against any future table divergence.
     */
    @Test
    void childCentroidsMatchBeyTablesForAllTypesAndChildren() {
        for (int type = 0; type < 6; type++) {
            Point3i[] pv = Constants.SIMPLEX_STANDARD[type];
            float[] v0 = { pv[0].x, pv[0].y, pv[0].z };
            float[] v1 = { pv[1].x, pv[1].y, pv[1].z };
            float[] v2 = { pv[2].x, pv[2].y, pv[2].z };
            float[] v3 = { pv[3].x, pv[3].y, pv[3].z };

            float[] m01 = mid(v0, v1);
            float[] m02 = mid(v0, v2);
            float[] m03 = mid(v0, v3);
            float[] m12 = mid(v1, v2);
            float[] m13 = mid(v1, v3);
            float[] m23 = mid(v2, v3);

            // Centroids of each Bey child (index 0-7), matching t8code / BeySubdivision.getBeyChild
            float[][] beyChildCentroid = {
                avg(v0,  m01, m02, m03), // Bey 0
                avg(m01, v1,  m12, m13), // Bey 1
                avg(m02, m12, v2,  m23), // Bey 2
                avg(m03, m13, m23, v3),  // Bey 3
                avg(m01, m02, m03, m13), // Bey 4
                avg(m01, m02, m12, m13), // Bey 5
                avg(m02, m03, m13, m23), // Bey 6
                avg(m02, m12, m13, m23), // Bey 7
            };

            for (int mortonChild = 0; mortonChild < 8; mortonChild++) {
                int beyChild = TetreeConnectivity.INDEX_TO_BEY_NUMBER[type][mortonChild];
                float[] expected = beyChildCentroid[beyChild];
                float[] actual   = ESVTChildOrder.getChildCentroid(type, mortonChild);

                assertEquals(expected[0], actual[0], TOLERANCE,
                             "CHILD_CENTROIDS[" + type + "][" + mortonChild
                             + "] (Bey " + beyChild + ").x");
                assertEquals(expected[1], actual[1], TOLERANCE,
                             "CHILD_CENTROIDS[" + type + "][" + mortonChild
                             + "] (Bey " + beyChild + ").y");
                assertEquals(expected[2], actual[2], TOLERANCE,
                             "CHILD_CENTROIDS[" + type + "][" + mortonChild
                             + "] (Bey " + beyChild + ").z");
            }
        }
    }

    /**
     * Independent oracle (addresses the "partially tautological" critique of the test above, which
     * re-derives centroids from the same SIMPLEX_STANDARD + midpoint arithmetic the production code uses).
     * Here the expected centroids come from the REAL Bey subdivision code path: a level-1 {@link Tet} of
     * each type is subdivided via {@link BeySubdivision#getBeyChild(Tet, int)} and each child's actual
     * {@link Tet#coordinates()} (the t8code Kuhn vertex formula) are averaged.
     *
     * <p>The comparison is by <em>set</em>, not by index: {@code getBeyChild} derives vertices from
     * {@code parent.coordinates()} whose ordering follows the t8code Kuhn formula, which differs from the
     * hand-listed {@code SIMPLEX_STANDARD} vertex ordering for types 1-5 — so a per-index match would
     * compare two different vertex labelings. The 8 Bey children tile the same geometric parent region
     * regardless of labeling, so the multiset of 8 child centroids is invariant. Asserting that
     * production's 8 centroids (for a type) equal the 8 centroids from the real subdivision code path —
     * as a set — is a genuinely independent geometric cross-check that cannot be satisfied by a shared
     * transcription error in the hand-written Bey vertex sets.
     */
    @Test
    void childCentroidSetMatchesBeySubdivisionCodePath() {
        // Level-1 parent of each type anchored at origin (a level-0 tet must be type 0). Its coordinates()
        // are SIMPLEX_STANDARD[type] scaled by the level-1 edge length — the same unit-simplex frame
        // CHILD_CENTROIDS uses — so normalizing child centroids by that edge length yields the [0,1] frame.
        final float edge = Constants.lengthAtLevel((byte) 1);
        for (int type = 0; type < 6; type++) {
            Tet parent = new Tet(0, 0, 0, (byte) 1, (byte) type);

            float[][] oracle = new float[8][];
            for (int beyChild = 0; beyChild < 8; beyChild++) {
                Point3i[] cv = BeySubdivision.getBeyChild(parent, beyChild).coordinates();
                oracle[beyChild] = new float[] {
                    (cv[0].x + cv[1].x + cv[2].x + cv[3].x) * 0.25f / edge,
                    (cv[0].y + cv[1].y + cv[2].y + cv[3].y) * 0.25f / edge,
                    (cv[0].z + cv[1].z + cv[2].z + cv[3].z) * 0.25f / edge
                };
            }

            // Every production centroid must match exactly one oracle centroid (and vice versa).
            boolean[] oracleUsed = new boolean[8];
            for (int mortonChild = 0; mortonChild < 8; mortonChild++) {
                float[] actual = ESVTChildOrder.getChildCentroid(type, mortonChild);
                int match = -1;
                for (int b = 0; b < 8; b++) {
                    if (!oracleUsed[b] && close(actual, oracle[b])) {
                        match = b;
                        break;
                    }
                }
                assertTrue(match >= 0,
                           "type " + type + " Morton child " + mortonChild + " centroid "
                           + java.util.Arrays.toString(actual)
                           + " has no match among the real Bey-subdivision child centroids");
                oracleUsed[match] = true;
            }
        }
    }

    private static boolean close(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) < TOLERANCE && Math.abs(a[1] - b[1]) < TOLERANCE
               && Math.abs(a[2] - b[2]) < TOLERANCE;
    }

    private static float[] mid(float[] a, float[] b) {
        return new float[] { (a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f, (a[2] + b[2]) * 0.5f };
    }

    private static float[] avg(float[] a, float[] b, float[] c, float[] d) {
        return new float[] {
            (a[0] + b[0] + c[0] + d[0]) * 0.25f,
            (a[1] + b[1] + c[1] + d[1]) * 0.25f,
            (a[2] + b[2] + c[2] + d[2]) * 0.25f
        };
    }
}
