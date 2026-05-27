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

import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-009 Phase 2 (Luciferase-4ky): pins {@link Triangle#consecutiveIndex()} to the real
 * t8code tetrahedral-Morton <em>consecutive</em> index I(T) — the base-{@code 2^d}=4 string of
 * per-level local indices ({@code I_loc}) that respects the TM-order (Burstedde &amp; Holke,
 * §4.5, eq. 55). This replaces the literature-rejected positional packing
 * ({@code x + y·2^L + n·2^{2L} + type·2^{3L}}) which broke ancestor-grouping and sign-flipped
 * at {@code type=1}.
 *
 * <p>Under the consecutive index the children of any triangle occupy a <b>contiguous</b> block:
 * {@code I(child_i) = I(parent)·4 + i}. This is the ancestor-grouping / locality property
 * (Theorem 16). The triangle anchors follow the t8 model ({@code y ≤ x}, the root simplex S0);
 * a single Triangle index therefore tiles the lower-right half-cube (the upper-left S1 root is
 * RDR-009 Phase 3). 2 bits/level × 21 = 42 bits at MAX_LEVEL — fits a signed long, ordered.
 *
 * @author hal.hildebrand
 */
class TriangleTmSfcTest {

    @Test
    @DisplayName("root consecutive index is 0")
    void rootIndexZero() {
        assertEquals(0L, new Triangle(0, 0, 0, 0).consecutiveIndex());
    }

    @Test
    @DisplayName("children occupy the contiguous block I(parent)*4 + {0,1,2,3} (ancestor grouping)")
    void childrenAreContiguous() {
        // S0 anchors (y <= x) at a few levels and both types.
        int[][] seeds = { { 1, 0, 0, 0 }, { 2, 0, 2, 1 }, { 2, 1, 3, 1 }, { 3, 0, 5, 2 }, { 4, 1, 9, 4 } };
        for (int[] s : seeds) {
            var parent = new Triangle(s[0], s[1], s[2], s[3]);
            long base = parent.consecutiveIndex();
            var seen = new HashSet<Long>();
            for (int i = 0; i < Triangle.CHILDREN; i++) {
                long ci = parent.child(i).consecutiveIndex();
                assertEquals(base * 4 + i, ci, String.format(
                    "child %d of %s must have index parent*4+%d", i, parent, i));
                seen.add(ci);
            }
            assertEquals(4, seen.size(), "the 4 children must have 4 distinct indices");
        }
    }

    @Test
    @DisplayName("child(i).parent() round-trips (level,type,x,y) and getChildIndex()==i")
    void childParentRoundTrip() {
        int[][] seeds = { { 0, 0, 0, 0 }, { 1, 0, 1, 0 }, { 2, 0, 2, 1 }, { 2, 1, 3, 2 }, { 3, 1, 6, 3 } };
        for (int[] s : seeds) {
            if (s[0] >= Triangle.MAX_LEVEL) {
                continue;
            }
            var parent = new Triangle(s[0], s[1], s[2], s[3]);
            for (int i = 0; i < Triangle.CHILDREN; i++) {
                var child = parent.child(i);
                assertEquals(i, child.getChildIndex(), "TM child index must round-trip");
                var back = child.parent();
                assertEquals(parent.getLevel(), back.getLevel(), "parent level");
                assertEquals(parent.getType(), back.getType(), "parent type round-trip");
                assertEquals(parent.getX(), back.getX(), "parent x round-trip");
                assertEquals(parent.getY(), back.getY(), "parent y round-trip");
            }
        }
    }

    @Test
    @DisplayName("all level-k descendants of a triangle form a contiguous index range")
    void descendantsFormContiguousRange() {
        var t = new Triangle(1, 0, 0, 0);
        // Two levels down: 16 descendants occupying [I(t)*16, I(t)*16 + 16).
        long base = t.consecutiveIndex();
        var indices = new ArrayList<Long>();
        for (int a = 0; a < Triangle.CHILDREN; a++) {
            var c = t.child(a);
            for (int b = 0; b < Triangle.CHILDREN; b++) {
                indices.add(c.child(b).consecutiveIndex());
            }
        }
        indices.sort(Long::compare);
        assertEquals(16, new HashSet<>(indices).size(), "16 distinct grandchildren");
        for (int k = 0; k < 16; k++) {
            assertEquals(base * 16 + k, indices.get(k), "grandchildren must be the contiguous block base*16+k");
        }
    }

    @Test
    @DisplayName("anchors stay in the S0 region y <= x under refinement")
    void anchorsStayInS0() {
        var roots = new Triangle[] { new Triangle(0, 0, 0, 0) };
        var frontier = new ArrayList<Triangle>();
        frontier.add(roots[0]);
        for (int depth = 0; depth < 6; depth++) {
            var next = new ArrayList<Triangle>();
            for (var t : frontier) {
                for (int i = 0; i < Triangle.CHILDREN; i++) {
                    var c = t.child(i);
                    assertTrue(c.getY() <= c.getX(), "S0 invariant y <= x violated by " + c);
                    next.add(c);
                }
            }
            frontier = next;
        }
    }

    @Test
    @DisplayName("MAX_LEVEL indices are non-negative, distinct, and TM-monotonic (no sign-flip/overflow)")
    void maxLevelOrdering() {
        // Build a handful of level-21 triangles by descending a fixed path, plus their siblings,
        // and assert indices are non-negative and strictly ordered by the consecutive index.
        var t = new Triangle(0, 0, 0, 0);
        int[] path = { 0, 2, 1, 3, 0, 1, 2, 3, 1, 0, 2, 3, 1, 2, 0, 3, 1, 0, 2, 1, 3 }; // 21 steps
        for (int step : path) {
            t = t.child(step);
        }
        assertEquals(Triangle.MAX_LEVEL, t.getLevel());
        long idx = t.consecutiveIndex();
        assertTrue(idx >= 0, "level-21 index must be non-negative (no sign-flip), got " + idx);

        // Siblings at MAX_LEVEL: distinct, and ordered by child index.
        var parent = t.parent();
        long prev = -1;
        var seen = new HashSet<Long>();
        for (int i = 0; i < Triangle.CHILDREN; i++) {
            long ci = parent.child(i).consecutiveIndex();
            assertTrue(ci >= 0, "MAX_LEVEL sibling index must be non-negative");
            assertTrue(seen.add(ci), "MAX_LEVEL siblings must be distinct");
            assertTrue(ci > prev, "MAX_LEVEL sibling indices must be strictly increasing with child index");
            prev = ci;
        }
    }

    @Test
    @DisplayName("fromWorldCoordinates locates S0 (y<=x) points and the result contains them")
    void pointLocationInS0() {
        for (int level = 1; level <= 8; level++) {
            for (int i = 1; i < 8; i++) {
                for (int j = 1; j <= i; j++) { // wy <= wx region (S0)
                    float wx = i / 8.0f * 0.999f;
                    float wy = j / 8.0f * 0.999f;
                    if (wy > wx) {
                        continue;
                    }
                    var tri = Triangle.fromWorldCoordinates(wx, wy, level);
                    assertEquals(level, tri.getLevel());
                    assertTrue(tri.getY() <= tri.getX(), "located anchor must satisfy S0 y<=x");
                    assertTrue(tri.contains(wx, wy), String.format(
                        "located triangle must contain its point (%.4f,%.4f) at level %d", wx, wy, level));
                }
            }
        }
    }

    @Test
    @DisplayName("fromWorldCoordinates routes upper-left (y>x) points to the S1 root (RDR-009 P3)")
    void pointLocationRoutesToS1() {
        // RDR-009 P3 added the S1 root: an upper-left point (y > x) now lands in S1 (half 1) and is
        // contained, rather than throwing (P2) or being silently relocated (pre-P1).
        var s1 = Triangle.fromWorldCoordinates(0.2f, 0.8f, 4);
        assertEquals(1, s1.getHalf(), "upper-left point must land in the S1 half");
        assertTrue(s1.contains(0.2f, 0.8f), "the S1 triangle must contain its point");
        // The lower-right mirror stays in S0.
        assertEquals(0, Triangle.fromWorldCoordinates(0.8f, 0.2f, 4).getHalf());
    }

    @Test
    @DisplayName("neighbors() only return S0 (y<=x) triangles — no cross-diagonal compareTo collision")
    void neighborsStayInS0() {
        // Regression guard (review finding): a y>x neighbor (e.g. the top neighbor of a diagonal
        // cell) is an S1 triangle that would collide with a coordinate-swapped S0 key under the
        // consecutive index, breaking compareTo's consistency with equals in ConcurrentSkipListMap.
        // neighbors()/neighbor() must suppress it (S0-only until the S1 root lands in Phase 3).
        for (int level = 1; level <= 6; level++) {
            int max = 1 << level;
            for (int x = 0; x < max; x++) {
                for (int y = 0; y <= x; y++) { // all S0 anchors
                    for (int type = 0; type < Triangle.TYPES; type++) {
                        var t = new Triangle(level, type, x, y);
                        for (var nb : t.neighbors()) {
                            if (nb != null) {
                                assertTrue(nb.getY() <= nb.getX(),
                                    "neighbors() must not return a y>x triangle: " + nb + " from " + t);
                            }
                        }
                        for (int e = 0; e < Triangle.EDGES; e++) {
                            var nb = t.neighbor(e);
                            if (nb != null) {
                                assertTrue(nb.getY() <= nb.getX(),
                                    "neighbor(" + e + ") must not return a y>x triangle: " + nb + " from " + t);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("consecutiveIndex is collision-free across a uniform refinement level")
    void uniformLevelCollisionFree() {
        // Enumerate all level-5 descendants of the root via refinement; indices must be the
        // dense contiguous range [0, 4^5).
        var frontier = new ArrayList<Triangle>();
        frontier.add(new Triangle(0, 0, 0, 0));
        for (int depth = 0; depth < 5; depth++) {
            var next = new ArrayList<Triangle>();
            for (var t : frontier) {
                for (int i = 0; i < Triangle.CHILDREN; i++) {
                    next.add(t.child(i));
                }
            }
            frontier = next;
        }
        var seen = new HashSet<Long>();
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (var t : frontier) {
            long ci = t.consecutiveIndex();
            assertTrue(seen.add(ci), "level-5 index collision at " + t);
            min = Math.min(min, ci);
            max = Math.max(max, ci);
        }
        assertEquals(1024, frontier.size(), "4^5 level-5 triangles");
        assertEquals(0L, min, "dense range starts at 0");
        assertEquals(1023L, max, "dense range ends at 4^5 - 1");
        assertEquals(1024, seen.size(), "all 4^5 indices distinct and dense");
    }
}
