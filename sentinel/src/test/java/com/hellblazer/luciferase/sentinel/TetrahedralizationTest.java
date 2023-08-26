/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi GUI
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

package com.hellblazer.luciferase.sentinel;

import static com.hellblazer.luciferase.sentinel.Vertex.getRandomPoints;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import java.util.Set;

import javax.vecmath.Point3f;

import org.junit.jupiter.api.Test;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

public class TetrahedralizationTest {

    @Test
    public void testCubic() {
        Sentinel T = new Sentinel(new Random(0));
        for (var v : TestCases.getCubicCrystalStructure()) {
            T.track(v);
        }

        Set<Tetrahedron> L = T.tetrahedrons();
        assertEquals(184, L.size());
    }

    @Test
    public void testFlip4to1() {
        Sentinel T = new Sentinel(new Random(0));
        Point3f N = new Point3f(100, 100, 100);
        var v = T.track(N);
        T.flip4to1(v);
        assertEquals(1, T.tetrahedrons().size());
    }

    @Test
    public void testGrid() {
        Sentinel T = new Sentinel(new Random(0));
        for (var v : TestCases.getGrid()) {
            T.track(v);
        }

        Set<Tetrahedron> L = T.tetrahedrons();
        assertEquals(378, L.size());
    }

    @Test
    public void testLargeRandom() {
        Random random = new Random(666);
        Point3f ourPoints[] = getRandomPoints(random, 60000, 100.0f, false);

        Sentinel T = new Sentinel(random);

        for (var v : ourPoints) {
            T.track(v);
        }

        Set<Tetrahedron> L = T.tetrahedrons();
        assertEquals(402808, L.size());
    }

    @Test
    public void testWorstCase() {
        Sentinel T = new Sentinel(new Random(0));
        for (var v : TestCases.getWorstCase()) {
            T.track(v);
        }

        Set<Tetrahedron> L = T.tetrahedrons();
        assertEquals(611, L.size());
    }
}
