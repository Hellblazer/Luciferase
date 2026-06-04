/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.sentry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Luciferase-7wzml.14:
 * LandmarkIndex.walkToTarget hull-exit and step-cap must be treated as "inconclusive"
 * (not "definitively outside"), and all callers must fall back to deterministic locate.
 *
 * <p>Key invariants under test:
 * <ul>
 *   <li>track(Point3f, Random) never throws for a point grid.contains() accepts.</li>
 *   <li>track(Point3f, Vertex, Random) never silently drops a contained point.</li>
 *   <li>MutableGrid.locate(p, entropy) never returns null for a contained point.</li>
 * </ul>
 */
public class LandmarkIndexHullExitTest {

    private static final float BASE = 10_000f;

    private MutableGrid grid;
    private Random      entropy;

    @BeforeEach
    public void setUp() {
        grid    = new MutableGrid();
        entropy = new Random(0x7A_14L);
    }

    /**
     * Build a minimal mesh (5 vertices) so there are hull tetrahedra with null neighbors.
     * The inserted vertices are spread enough to form a non-degenerate Delaunay triangulation.
     */
    private void buildMinimalMesh() {
        // Tetrahedron base + one interior vertex; all coordinates well inside the universe.
        grid.track(new Point3f(BASE,           BASE,           BASE),           entropy);
        grid.track(new Point3f(BASE + 4000f,   BASE,           BASE),           entropy);
        grid.track(new Point3f(BASE + 2000f,   BASE + 3464f,   BASE),           entropy);
        grid.track(new Point3f(BASE + 2000f,   BASE + 1155f,   BASE + 3266f),   entropy);
        // Interior point — creates smaller tetrahedra and more hull faces
        grid.track(new Point3f(BASE + 2000f,   BASE + 1000f,   BASE + 800f),    entropy);
    }

    // -----------------------------------------------------------------------
    // track(Point3f, Random) — must never throw for a contained point
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("track(Point3f,Random): contained point must be inserted, never throw [7wzml.14]")
    public void trackByPoint_containedPoint_neverThrows() {
        buildMinimalMesh();

        Point3f p = new Point3f(BASE + 1500f, BASE + 900f, BASE + 600f);
        assertTrue(grid.contains(p), "Pre-condition: test point must be inside the mesh bounds");

        int before = grid.size();
        Vertex v = assertDoesNotThrow(
            () -> grid.track(p, entropy),
            "track(Point3f, Random) must not throw for a contained point");
        assertNotNull(v, "track(Point3f, Random) must not drop a contained point");
        assertEquals(before + 1, grid.size(), "Grid size must increase by 1");
    }

    @Test
    @DisplayName("track(Point3f,Random): multiple hull-adjacent inserts all succeed [7wzml.14]")
    public void trackByPoint_repeatedHullAdjacentInserts_allSucceed() {
        buildMinimalMesh();

        // Insert 20 random points inside the mesh bounds.  Some will be near hull faces
        // and exercise the fallback path.
        Random r = new Random(0xFACE_CAFE);
        int before = grid.size();
        int inserted = 0;
        for (int i = 0; i < 20; i++) {
            float x = BASE + 300f + r.nextFloat() * 3400f;
            float y = BASE + 300f + r.nextFloat() * 3000f;
            float z = BASE + 100f + r.nextFloat() * 2800f;
            Point3f p = new Point3f(x, y, z);
            if (!grid.contains(p)) {
                continue; // skip points outside the universe (should be rare)
            }
            Vertex v = assertDoesNotThrow(() -> grid.track(p, entropy),
                "track(Point3f, Random) must not throw: " + p);
            assertNotNull(v, "track(Point3f, Random) must not drop: " + p);
            inserted++;
        }
        assertEquals(before + inserted, grid.size(),
            "All inserted points must appear in the grid");
    }

    // -----------------------------------------------------------------------
    // track(Point3f, Vertex, Random) — must not silently drop contained points
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("track(Point3f,Vertex,Random): contained point with hull-vertex hint must be inserted [7wzml.14]")
    public void trackByPointAndNear_containedPoint_notDropped() {
        buildMinimalMesh();

        // Use the first vertex as the hint.  It is on the convex hull; its adjacent tet has
        // null-neighbor hull faces.  A walk starting from that tet for a point on the "other
        // side" of a hull face will exit the mesh and return null — the code must fall back.
        Vertex nearHint = grid.iterator().next();
        assertNotNull(nearHint, "Pre-condition: need at least one vertex");

        Point3f p = new Point3f(BASE + 1200f, BASE + 800f, BASE + 700f);
        assertTrue(grid.contains(p), "Pre-condition: test point must be inside the mesh bounds");

        int before = grid.size();
        Vertex v = assertDoesNotThrow(
            () -> grid.track(p, nearHint, entropy),
            "track(Point3f, Vertex, Random) must not throw for a contained point");
        assertNotNull(v,
            "track(Point3f, Vertex, Random) must not silently drop a contained point [7wzml.14]");
        assertEquals(before + 1, grid.size(), "Grid size must increase by 1");
    }

    @Test
    @DisplayName("track(Point3f,Vertex,Random): locate fallback used when near.locate returns null [7wzml.14]")
    public void trackByPointAndNear_locateFallback_locatesCorrectly() {
        buildMinimalMesh();

        // Insert many random points; all must be found.
        Random r = new Random(0xBEEF_D00D);
        Vertex nearHint = grid.iterator().next();
        int before = grid.size();
        int inserted = 0;
        for (int i = 0; i < 30; i++) {
            float x = BASE + 200f + r.nextFloat() * 3600f;
            float y = BASE + 200f + r.nextFloat() * 3000f;
            float z = BASE + 100f + r.nextFloat() * 2700f;
            Point3f p = new Point3f(x, y, z);
            if (!grid.contains(p)) {
                continue;
            }
            Vertex v = assertDoesNotThrow(
                () -> grid.track(p, nearHint, entropy),
                "track(Point3f, Vertex, Random) must not throw: " + p);
            assertNotNull(v,
                "track(Point3f, Vertex, Random) must not drop a contained point: " + p);
            inserted++;
        }
        assertEquals(before + inserted, grid.size());
    }

    // -----------------------------------------------------------------------
    // MutableGrid.locate — must never return null for a contained point
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("locate(Tuple3f,Random): never returns null for a contained point [7wzml.14]")
    public void locate_containedPoint_neverNull() {
        buildMinimalMesh();

        Random r = new Random(0xCAFE_BABE);
        for (int i = 0; i < 50; i++) {
            float x = BASE + 100f + r.nextFloat() * 3800f;
            float y = BASE + 100f + r.nextFloat() * 3200f;
            float z = BASE + 100f + r.nextFloat() * 3000f;
            Point3f p = new Point3f(x, y, z);
            if (!grid.contains(p)) {
                continue;
            }
            Tetrahedron t = grid.locate(p, entropy);
            assertNotNull(t,
                "locate must not return null for a contained point: " + p);
        }
    }
}
