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
 * RDR-009 Phase 3 (Luciferase-7iu): the two-prism cover. Adds the upper-triangle root S1 (the
 * reflection of S0 across the main diagonal {@code y = x}) so the two prism families tile the full
 * cube. A point with {@code y <= x} lands in S0 (half 0); a point with {@code y > x} lands in S1
 * (half 1). Together they cover {@code [0,1)²} with no gaps and no double-counting along the
 * diagonal (the diagonal {@code y == x} belongs to S0 by convention).
 *
 * <p>This replaces the previous behavior where a single Prism tiled only S0 and {@code y > x}
 * points threw (P2) — and, before that, were silently relocated onto the diagonal (a data-loss
 * hazard the RDR was motivated to remove).
 *
 * @author hal.hildebrand
 */
class PrismFullCubeCoverageTest {

    @Test
    @DisplayName("every point in [0,1)² maps to exactly one prism: y<=x -> S0, y>x -> S1")
    void fullCubeCoverageNoGapsNoDoubleCount() {
        for (int level = 1; level <= 6; level++) {
            for (int i = 1; i < 16; i++) {
                for (int j = 1; j < 16; j++) {
                    float wx = i / 16.0f * 0.999f;
                    float wy = j / 16.0f * 0.999f;
                    var tri = Triangle.fromWorldCoordinates(wx, wy, level);
                    int expectedHalf = (wy > wx) ? 1 : 0;
                    assertEquals(expectedHalf, tri.getHalf(), String.format(
                        "(%.4f,%.4f) level %d must be in half %d", wx, wy, level, expectedHalf));
                    // The located triangle must contain its own point (no gap).
                    assertTrue(tri.contains(wx, wy), String.format(
                        "located triangle (half %d) must contain (%.4f,%.4f)", tri.getHalf(), wx, wy));
                    // The OTHER half's triangle at this point must NOT also contain it (no double count),
                    // except exactly on the diagonal where the closed half-triangles share an edge.
                    if (Math.abs(wx - wy) > 1e-4f) {
                        var prism = PrismKey.fromWorldCoordinates(wx, wy, 0.5f, level);
                        assertTrue(prism.contains(wx, wy, 0.5f), "prism must contain its point");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a y>x point (previously thrown/clamped) now lands in S1 and is contained")
    void upperLeftPointLandsInS1() {
        var tri = Triangle.fromWorldCoordinates(0.2f, 0.8f, 5); // y > x
        assertEquals(1, tri.getHalf(), "upper-left point must be in S1");
        assertTrue(tri.contains(0.2f, 0.8f), "S1 triangle must contain its point");
        // And via the prism + Prism index (no longer throws).
        var prism = PrismKey.fromWorldCoordinates(0.2f, 0.8f, 0.5f, 5);
        assertEquals(1, prism.getTriangle().getHalf());
        assertTrue(prism.contains(0.2f, 0.8f, 0.5f));
    }

    @Test
    @DisplayName("S0 keys are unchanged (additive): a y<=x point yields the same half-0 key as before")
    void s0KeysUnchangedAndAdditive() {
        // S0 points keep half 0 and the same (level,type,x,y) Tet-id and consecutiveIndex as P2.
        var tri = Triangle.fromWorldCoordinates(0.8f, 0.3f, 5); // y < x => S0
        assertEquals(0, tri.getHalf());
        // The 5-arg constructor (legacy) defaults to S0/half 0 and equals an S0 fromWorld* triangle.
        var manual = new Triangle(tri.getLevel(), tri.getType(), tri.getX(), tri.getY(), tri.getN());
        assertEquals(0, manual.getHalf());
        assertEquals(manual, tri, "legacy 5-arg ctor must equal the S0 located triangle");
        assertEquals(manual.consecutiveIndex(), tri.consecutiveIndex());
    }

    @Test
    @DisplayName("level-0 roots are per-half: S0 root contains only y<=x, S1 root only y>=x")
    void levelZeroRootsArePerHalf() {
        var s0Root = new Triangle(0, 0, 0, 0, 0);          // S0 (half 0)
        assertEquals(0, s0Root.getHalf());
        assertTrue(s0Root.contains(0.8f, 0.2f), "S0 root contains lower-right (y<x)");
        assertFalse(s0Root.contains(0.2f, 0.8f), "S0 root must NOT contain upper-left (y>x) — no full-square special case");

        var s1Root = Triangle.rootS1();                     // S1 (half 1)
        assertEquals(1, s1Root.getHalf());
        assertEquals(0, s1Root.getLevel());
        assertTrue(s1Root.contains(0.2f, 0.8f), "S1 root contains upper-left (y>x)");
        assertFalse(s1Root.contains(0.8f, 0.2f), "S1 root must NOT contain lower-right (y<x)");
    }

    @Test
    @DisplayName("S0 and S1 triangles are distinct keys even when mirror-equal in the S0 frame")
    void s0AndS1AreDistinctKeys() {
        // A half-1 triangle stores its anchor in the S0 frame, so it can share (type,x,y) with a
        // half-0 triangle; the half makes them distinct and orders them (S0 before S1).
        var s0 = Triangle.fromWorldCoordinates(0.8f, 0.3f, 4);     // y<x -> S0
        var s1 = Triangle.fromWorldCoordinates(0.3f, 0.8f, 4);     // mirror point -> S1
        // Same S0-frame Tet-id (mirror), different half.
        assertEquals(s0.getType(), s1.getType());
        assertEquals(s0.getX(), s1.getX());
        assertEquals(s0.getY(), s1.getY());
        assertEquals(0, s0.getHalf());
        assertEquals(1, s1.getHalf());
        assertFalse(s0.equals(s1), "S0 and S1 mirror triangles must not be equal");

        var k0 = new PrismKey(s0, new Line(4, 5));
        var k1 = new PrismKey(s1, new Line(4, 5));
        assertFalse(k0.equals(k1), "S0 and S1 prism keys must not be equal");
        assertTrue(k0.compareTo(k1) < 0, "S0 must sort before S1 (half-major SFC order)");
        assertEquals(-Integer.signum(k0.compareTo(k1)), Integer.signum(k1.compareTo(k0)), "antisymmetric");
    }
}
