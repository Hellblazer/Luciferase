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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import java.util.ArrayList;

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
                    // No double-count: off the diagonal, the OPPOSITE half's triangle (same S0-frame
                    // anchor, reflected geometry) must NOT contain the point. (On the diagonal the
                    // two closed half-triangles share the edge, so both contain it — fromWorldCoordinates
                    // assigns it to S0 by convention; see Triangle.contains javadoc.)
                    if (Math.abs(wx - wy) > 1e-4f) {
                        var opposite = new Triangle(tri.getLevel(), tri.getType(), tri.getX(), tri.getY(), 1 - tri.getHalf());
                        assertFalse(opposite.contains(wx, wy), String.format(
                            "opposite half must NOT contain (%.4f,%.4f) — no double count", wx, wy));
                        var prism = PrismKey.fromWorldCoordinates(wx, wy, 0.5f, level);
                        assertTrue(prism.contains(wx, wy, 0.5f), "prism must contain its point");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Prism index inserts and retrieves entities in both halves (insert + insertBatch)")
    void prismInsertAndRetrieveBothHalves() {
        // Per-entity insert places each key at its correct half (calculateSpatialIndex -> S0/S1),
        // so the default insert and insertBatch paths cover the full cube. (The opt-in
        // StackBasedTreeBuilder, default off, seeds from a single root and does not yet support
        // the two-prism cover — documented on PrismKey.createRoot, a P4 prerequisite.)
        var prism = new Prism<LongEntityID, String>(new SequentialLongIDGenerator(), 1.0f, 21);

        var s0 = new Point3f(0.8f, 0.3f, 0.5f); // y < x -> S0
        var s1 = new Point3f(0.3f, 0.8f, 0.5f); // y > x -> S1
        var s0Id = prism.insert(s0, (byte) 8, "S0");
        var s1Id = prism.insert(s1, (byte) 8, "S1");
        assertEquals(2, prism.entityCount());
        assertTrue(prism.containsEntity(s0Id));
        assertTrue(prism.containsEntity(s1Id));
        assertTrue(prism.lookup(s0, (byte) 8).contains(s0Id), "S0 entity must be retrievable");
        assertTrue(prism.lookup(s1, (byte) 8).contains(s1Id), "S1 entity must be retrievable");

        // insertBatch (default per-entity path) with a mix of both halves.
        var positions = new ArrayList<Point3f>();
        var contents = new ArrayList<String>();
        for (int i = 1; i < 20; i++) {
            float a = i / 20.0f * 0.9f;
            float b = (i % 7) / 20.0f * 0.9f;
            positions.add(new Point3f(Math.max(a, b), Math.min(a, b), 0.4f)); // S0 (y<=x)
            contents.add("S0-" + i);
            positions.add(new Point3f(Math.min(a, b), Math.max(a, b) + 0.001f, 0.6f)); // S1 (y>x)
            contents.add("S1-" + i);
        }
        var ids = prism.insertBatch(positions, contents, (byte) 8);
        assertEquals(positions.size(), ids.size(), "all S0+S1 batch entities inserted");
        assertEquals(2 + positions.size(), prism.entityCount());
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
        var manual = new Triangle(tri.getLevel(), tri.getType(), tri.getX(), tri.getY());
        assertEquals(0, manual.getHalf());
        assertEquals(manual, tri, "legacy 5-arg ctor must equal the S0 located triangle");
        assertEquals(manual.consecutiveIndex(), tri.consecutiveIndex());
    }

    @Test
    @DisplayName("level-0 roots are per-half: S0 root contains only y<=x, S1 root only y>=x")
    void levelZeroRootsArePerHalf() {
        var s0Root = new Triangle(0, 0, 0, 0);          // S0 (half 0)
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
