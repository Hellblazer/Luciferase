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

package com.hellblazer.sentry;

import static com.hellblazer.sentry.V.A;
import static com.hellblazer.sentry.V.B;
import static com.hellblazer.sentry.V.C;
import static com.hellblazer.sentry.V.D;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

public class OrientedFaceTest {

    @Test
    public void testFlip2to3() {
        Vertex a = new Vertex(0, 100, 0);
        Vertex b = new Vertex(100, 0, 0);
        Vertex c = new Vertex(50, 50, 0);
        Vertex d = new Vertex(0, -50, -100);
        Vertex e = new Vertex(0, -50, 100);

        Tetrahedron tA = new Tetrahedron(a, b, c, d);
        Tetrahedron tB = new Tetrahedron(e, b, c, a);
        tA.setNeighbor(D, tB);
        tB.setNeighbor(A, tA);

        OrientedFace face = tA.getFace(D);
        assertTrue(face.hasAdjacent());
        assertEquals(A, face.getAdjacentVertexOrdinal());
        Tetrahedron[] created = face.flip2to3();
        Assertions.assertNotNull(created);
        Tetrahedron tI = created[1];
        Assertions.assertNotNull(tI);
        Tetrahedron tII = created[0];
        Assertions.assertNotNull(tII);
        Tetrahedron tIII = created[2];
        Assertions.assertNotNull(tIII);

        Assertions.assertSame(c, tI.getVertex(A));
        Assertions.assertSame(d, tI.getVertex(B));
        Assertions.assertSame(a, tI.getVertex(C));
        Assertions.assertSame(e, tI.getVertex(D));

        Assertions.assertSame(b, tII.getVertex(A));
        Assertions.assertSame(d, tII.getVertex(B));
        Assertions.assertSame(c, tII.getVertex(C));
        Assertions.assertSame(e, tII.getVertex(D));

        Assertions.assertSame(b, tIII.getVertex(A));
        Assertions.assertSame(a, tIII.getVertex(B));
        Assertions.assertSame(d, tIII.getVertex(C));
        Assertions.assertSame(e, tIII.getVertex(D));

        Assertions.assertSame(tII, tI.getNeighbor(C));
        Assertions.assertSame(tIII, tI.getNeighbor(A));

        Assertions.assertSame(tI, tII.getNeighbor(A));
        Assertions.assertSame(tIII, tII.getNeighbor(C));
    }

    @Test
    public void testIsConvex() {
        Vertex a = new Vertex(-1, -1, 1);
        Vertex b = new Vertex(1, 1, 1);
        Vertex c = new Vertex(-1, 1, -1);
        Vertex d = new Vertex(1, -1, -1);
        Vertex e = new Vertex(-1, 1, 1);

        Tetrahedron tA = new Tetrahedron(a, b, c, d);
        Tetrahedron tB = new Tetrahedron(b, c, a, e);
        tA.setNeighbor(D, tB);
        tB.setNeighbor(D, tA);

        OrientedFace faceAB = tA.getFace(D);

        for (int i = 0; i < 2; i++) {
            assertTrue(faceAB.isConvex(i));
            assertFalse(faceAB.isReflex(i));
        }
    }

    @Test
    public void testIsReflex() {
        Vertex a = new Vertex(-1, -1, 1);
        Vertex b = new Vertex(1, 1, 1);
        Vertex c = new Vertex(-1, 1, -1);
        Vertex d = new Vertex(1, -1, 100);
        Vertex e = new Vertex(-1, 1, 100);

        Tetrahedron tA = new Tetrahedron(a, b, c, d);
        Tetrahedron tB = new Tetrahedron(b, c, a, e);
        tA.setNeighbor(D, tB);
        tB.setNeighbor(D, tA);

        OrientedFace faceAB = tA.getFace(D);
        assertFalse(faceAB.isReflex(0));
        assertTrue(faceAB.isConvex(0));
        assertTrue(faceAB.isReflex(1));
        assertFalse(faceAB.isConvex(1));
        assertFalse(faceAB.isReflex(2));
        assertTrue(faceAB.isConvex(2));
    }
}
