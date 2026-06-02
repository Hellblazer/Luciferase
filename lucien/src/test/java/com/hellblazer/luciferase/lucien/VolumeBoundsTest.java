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
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VolumeBounds#from(Spatial)} (Luciferase-2jf5).
 *
 * @author hal.hildebrand
 */
class VolumeBoundsTest {

    @Test
    void fromTetrahedron_allNegativeCoordinates_correctMaxBounds() {
        // Luciferase-2jf5: the Tetrahedron branch initialised max accumulators to Float.MIN_VALUE (the smallest
        // POSITIVE float ~1.4e-45). For a tetrahedron entirely in negative space the max would stay pinned at
        // that tiny positive value instead of the actual (negative) maximum vertex, producing wrong bounds.
        var tet = new Spatial.Tetrahedron(new Point3f(-10, -10, -10), new Point3f(-4, -8, -6),
                                          new Point3f(-7, -3, -9), new Point3f(-5, -6, -2));
        var b = VolumeBounds.from(tet);
        assertNotNull(b);
        assertEquals(-10f, b.minX());
        assertEquals(-10f, b.minY());
        assertEquals(-10f, b.minZ());
        assertEquals(-4f, b.maxX(), "maxX must be the largest vertex x, not Float.MIN_VALUE");
        assertEquals(-3f, b.maxY(), "maxY must be the largest vertex y, not Float.MIN_VALUE");
        assertEquals(-2f, b.maxZ(), "maxZ must be the largest vertex z, not Float.MIN_VALUE");
        // Sanity: a well-formed AABB has min <= max on every axis.
        assertTrue(b.minX() <= b.maxX() && b.minY() <= b.maxY() && b.minZ() <= b.maxZ());
    }

    @Test
    void fromTetrahedron_mixedCoordinates_tightAabb() {
        var tet = new Spatial.Tetrahedron(new Point3f(0, 0, 0), new Point3f(8, 2, 1), new Point3f(3, 9, 4),
                                          new Point3f(1, 5, 12));
        var b = VolumeBounds.from(tet);
        assertNotNull(b);
        assertEquals(0f, b.minX());
        assertEquals(0f, b.minY());
        assertEquals(0f, b.minZ());
        assertEquals(8f, b.maxX());
        assertEquals(9f, b.maxY());
        assertEquals(12f, b.maxZ());
    }
}
