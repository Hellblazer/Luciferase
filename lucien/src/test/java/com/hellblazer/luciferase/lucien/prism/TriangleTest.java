/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.prism;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anchor tests for {@link Triangle#consecutiveIndex()}.
 *
 * <p>RDR-009 Phase 2 replaced the prior positional packing
 * ({@code x + y·2^L + n·2^{2L} + type·2^{3L}}, the literature-rejected "semiquadcode") with the
 * real t8code tetrahedral-Morton <em>consecutive</em> index I(T). The collision-freeness tests
 * that pinned the old packing over arbitrary {@code (x, y, n, type)} tuples are obsolete — the
 * index no longer depends on {@code n}, and only valid S0 anchors ({@code y ≤ x}) occur. The new
 * contract (ancestor-grouping / contiguous children, dense per-level range, MAX_LEVEL ordering)
 * is pinned by {@link TriangleTmSfcTest}.
 */
class TriangleTest {

    @Test
    @DisplayName("consecutiveIndex root (level=0) is 0")
    void testRootIndexZero() {
        // Anchor for the SFC: the root triangle always indexes to 0. Used by PrismKey.createRoot
        // and asserted by PrismKeyTest.testCompositeSFC / PrismKeyTmSfcTest.
        assertEquals(0L, new Triangle(0, 0, 0, 0).consecutiveIndex());
    }

    /**
     * Luciferase-7wzml.139: contains() must apply the same y==x → S0 tie-break as
     * fromWorldCoordinates so that insert-key == contains-half for diagonal and near-diagonal
     * points.
     *
     * <p>The defect: contains() on an S1 triangle (half=1) previously returned true for points
     * exactly on the y==x diagonal because the inclusive barycentric test (a≥0,b≥0,c≥0) claims
     * boundary edges for both triangles. But fromWorldCoordinates assigns y==x to S0 exclusively
     * ({@code (worldY > worldX) ? 1 : 0}). The mismatch means a point inserted at y==x lands in an
     * S0 key yet a range/containment query via contains() on the S1 triangle also fires → silent
     * double-match or, if only S1 is checked, a miss.
     *
     * <p>Fix: contains() must treat "S1 with y≤x" as an automatic false (mirrors fromWorldCoordinates
     * strict-greater test) so the diagonal belongs to S0 in both paths.
     */
    @Test
    @DisplayName("contains() diagonal tie-break: y==x belongs to S0 only (Luciferase-7wzml.139)")
    void testDiagonalTieBreakConsistentWithFromWorldCoordinates() {
        // --- exact diagonal points ---
        float[] diagonalPts = { 0.0f, 0.25f, 0.5f, 0.75f };
        for (float v : diagonalPts) {
            // fromWorldCoordinates assigns y==x to S0 (half=0)
            Triangle loc = Triangle.fromWorldCoordinates(v, v, 1);
            assertEquals(0, loc.getHalf(),
                         "fromWorldCoordinates(" + v + "," + v + ") must return half=0 (S0)");

            // S0 root contains the diagonal point
            assertTrue(Triangle.fromWorldCoordinates(v, v, 0).contains(v, v),
                       "S0 root must contain diagonal point (" + v + "," + v + ")");

            // S1 root must NOT contain the diagonal point (tie-break to S0)
            assertFalse(Triangle.rootS1().contains(v, v),
                        "S1 root must NOT contain diagonal point (" + v + "," + v + ") — belongs to S0");
        }

        // --- epsilon-above diagonal (y > x): belongs to S1 ---
        float eps = 1e-5f;
        Triangle above = Triangle.fromWorldCoordinates(0.4f, 0.4f + eps, 1);
        assertEquals(1, above.getHalf(), "y > x point must be in S1");
        assertTrue(Triangle.rootS1().contains(0.4f, 0.4f + eps),
                   "S1 root must contain epsilon-above-diagonal point");
        // S0 root = new Triangle(0,0,0,0,half=0) — construct directly since fromWorldCoordinates
        // on a y>x point returns S1, not S0
        Triangle s0Root = new Triangle(0, 0, 0, 0, 0);
        assertFalse(s0Root.contains(0.4f, 0.4f + eps),
                    "S0 root must NOT contain epsilon-above-diagonal point (y > x belongs to S1)");

        // --- epsilon-below diagonal (y < x): belongs to S0 ---
        Triangle below = Triangle.fromWorldCoordinates(0.4f + eps, 0.4f, 1);
        assertEquals(0, below.getHalf(), "y < x point must be in S0");
        assertTrue(below.contains(0.4f + eps, 0.4f),
                   "S0 triangle must contain epsilon-below-diagonal point");
        assertFalse(Triangle.rootS1().contains(0.4f + eps, 0.4f),
                    "S1 root must NOT contain epsilon-below-diagonal point");
    }

    /**
     * insert-key == contains-match invariant: the triangle returned by fromWorldCoordinates
     * must be the unique triangle whose contains() returns true, and no triangle of the opposite
     * half should claim the same point.
     */
    @Test
    @DisplayName("fromWorldCoordinates and contains() agree on ownership for diagonal sweep")
    void testInsertKeyEqualsContainsMatch() {
        int level = 2;
        // Sweep a grid including several exactly-diagonal points
        for (int i = 0; i < (1 << level); i++) {
            float v = i / (float) (1 << level);
            // Exact diagonal point: y == x
            Triangle owner = Triangle.fromWorldCoordinates(v, v, level);
            assertEquals(0, owner.getHalf(), "Diagonal owner must be S0 at level " + level);
            assertTrue(owner.contains(v, v),
                       "Owner triangle must contain its own point (" + v + "," + v + ")");

            // The opposite-half root must not claim it
            assertFalse(Triangle.rootS1().contains(v, v),
                        "S1 root must not claim diagonal point (" + v + "," + v + ")");
        }
    }
}
