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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

public class VertexTest {

    @Test
    public void testFlip4to1() {
        Sentinel tetrahedralization = new Sentinel();
        Tetrahedron U = Sentinel.myOwnPrivateIdaho(tetrahedralization);
        Vertex N = new Vertex(100, 100, 100);

        List<OrientedFace> unlinkedFacets = new ArrayList<>();

        U.flip1to4(N, unlinkedFacets);

        tetrahedralization.flip4to1(N);
    }

    @Test
    public void testOrientation() {
        Vertex[] fourCorners = Sentinel.getFourCorners();
        assertEquals(1, fourCorners[3].orientation(fourCorners[0], fourCorners[1], fourCorners[2]));
        assertEquals(-1, fourCorners[3].orientation(fourCorners[1], fourCorners[0], fourCorners[2]));
        assertEquals(0, new Vertex(100, 100, 0).orientation(new Vertex(1000, 100000, 0), new Vertex(0, -1456, 0),
                                                            new Vertex(-2567, 0, 0)));
        assertEquals(1, fourCorners[0].orientation(fourCorners[2], fourCorners[1], fourCorners[3]));

        assertEquals(1, fourCorners[1].orientation(fourCorners[3], fourCorners[0], fourCorners[2]));

        assertEquals(1, fourCorners[2].orientation(fourCorners[0], fourCorners[3], fourCorners[1]));

        assertEquals(1, fourCorners[3].orientation(fourCorners[1], fourCorners[2], fourCorners[0]));
    }

    @Test
    public void testOrientation2() {
        Vertex[] fourCorners = Sentinel.getFourCorners();
        Vertex N = new Vertex(0, 0, 0);
        Tetrahedron t = new Tetrahedron(fourCorners);
        for (OrientedFace face : t) {
            assertEquals(1, face.orientationOf(N));
        }
        Vertex query = new Vertex(3949, 3002, 8573);
        for (OrientedFace face : t) {
            assertEquals(1, face.orientationOf(query));
        }
    }

}
