/**
 * Copyright (C) 2010 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi system
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

public class TetrahedronTest {
    @Test
    public void testCreateUniverse() {
        Tetrahedron idaho = MutableGrid.myOwnPrivateIdaho(new MutableGrid());
        Assertions.assertNotNull(idaho);
        Vertex[] vertices = idaho.getVertices();
        Assertions.assertNotNull(vertices);
        Assertions.assertEquals(4, vertices.length);
        Assertions.assertNotSame(vertices[0], vertices[1]);
        Assertions.assertNotSame(vertices[0], vertices[2]);
        Assertions.assertNotSame(vertices[0], vertices[3]);
        Assertions.assertNotSame(vertices[1], vertices[2]);
        Assertions.assertNotSame(vertices[1], vertices[3]);
        Assertions.assertNotSame(vertices[2], vertices[3]);

        // There can only be one
        Assertions.assertNull(idaho.getNeighbor(A));
        Assertions.assertNull(idaho.getNeighbor(B));
        Assertions.assertNull(idaho.getNeighbor(C));
        Assertions.assertNull(idaho.getNeighbor(D));

        // Check our faces
        for (OrientedFace face : idaho) {
            Assertions.assertNotNull(face);
            Assertions.assertSame(idaho, face.getIncident());
            Assertions.assertNull(face.getAdjacent());
        }
    }

    @Test
    public void testFlip14() {
        Tetrahedron U = MutableGrid.myOwnPrivateIdaho(new MutableGrid());
        Vertex a = U.getVertex(A);
        Vertex b = U.getVertex(B);
        Vertex c = U.getVertex(C);
        Vertex d = U.getVertex(D);

        Vertex N = new Vertex(100, 100, 100);

        List<OrientedFace> unlinkedFacets = new ArrayList<>();

        Tetrahedron tIV = U.flip1to4(N, unlinkedFacets);
        Assertions.assertNotNull(tIV);
        Assertions.assertEquals(0, unlinkedFacets.size());

        Tetrahedron tI = tIV.getNeighbor(C);
        Tetrahedron tII = tIV.getNeighbor(B);
        Tetrahedron tIII = tIV.getNeighbor(A);

        Assertions.assertSame(tIII, tI.getNeighbor(A));
        Assertions.assertSame(tIV, tI.getNeighbor(B));
        Assertions.assertSame(tII, tI.getNeighbor(C));
        Assertions.assertSame(null, tI.getNeighbor(D));

        Assertions.assertSame(tIII, tII.getNeighbor(A));
        Assertions.assertSame(tI, tII.getNeighbor(B));
        Assertions.assertSame(tIV, tII.getNeighbor(C));
        Assertions.assertSame(null, tII.getNeighbor(D));

        Assertions.assertSame(tI, tIII.getNeighbor(A));
        Assertions.assertSame(tII, tIII.getNeighbor(B));
        Assertions.assertSame(tIV, tIII.getNeighbor(C));
        Assertions.assertSame(null, tIII.getNeighbor(D));

        Assertions.assertSame(tIII, tIV.getNeighbor(A));
        Assertions.assertSame(tII, tIV.getNeighbor(B));
        Assertions.assertSame(tI, tIV.getNeighbor(C));
        Assertions.assertSame(null, tIV.getNeighbor(D));

        assertEquals(a, tI.getVertex(A));
        assertEquals(c, tI.getVertex(B));
        assertEquals(d, tI.getVertex(C));
        assertEquals(N, tI.getVertex(D));

        assertEquals(a, tII.getVertex(A));
        assertEquals(b, tII.getVertex(B));
        assertEquals(c, tII.getVertex(C));
        assertEquals(N, tII.getVertex(D));

        assertEquals(b, tIII.getVertex(A));
        assertEquals(d, tIII.getVertex(B));
        assertEquals(c, tIII.getVertex(C));
        assertEquals(N, tIII.getVertex(D));

        assertEquals(a, tIV.getVertex(A));
        assertEquals(d, tIV.getVertex(B));
        assertEquals(b, tIV.getVertex(C));
        assertEquals(N, tIV.getVertex(D));

        OrientedFace face = tI.getFace(A);
        assertEquals(d, face.getVertex(0));
        assertEquals(c, face.getVertex(1));
        assertEquals(N, face.getVertex(2));

        face = tI.getFace(B);
        assertEquals(N, face.getVertex(0));
        assertEquals(a, face.getVertex(1));
        assertEquals(d, face.getVertex(2));

        face = tI.getFace(C);
        assertEquals(a, face.getVertex(0));
        assertEquals(N, face.getVertex(1));
        assertEquals(c, face.getVertex(2));

        face = tI.getFace(D);
        assertEquals(c, face.getVertex(0));
        assertEquals(d, face.getVertex(1));
        assertEquals(a, face.getVertex(2));

        face = tII.getFace(A);
        assertEquals(c, face.getVertex(0));
        assertEquals(b, face.getVertex(1));
        assertEquals(N, face.getVertex(2));

        face = tII.getFace(B);
        assertEquals(N, face.getVertex(0));
        assertEquals(a, face.getVertex(1));
        assertEquals(c, face.getVertex(2));

        face = tII.getFace(C);
        assertEquals(a, face.getVertex(0));
        assertEquals(N, face.getVertex(1));
        assertEquals(b, face.getVertex(2));

        face = tII.getFace(D);
        assertEquals(b, face.getVertex(0));
        assertEquals(c, face.getVertex(1));
        assertEquals(a, face.getVertex(2));

        face = tIII.getFace(A);
        assertEquals(c, face.getVertex(0));
        assertEquals(d, face.getVertex(1));
        assertEquals(N, face.getVertex(2));

        face = tIII.getFace(B);
        assertEquals(N, face.getVertex(0));
        assertEquals(b, face.getVertex(1));
        assertEquals(c, face.getVertex(2));

        face = tIII.getFace(C);
        assertEquals(b, face.getVertex(0));
        assertEquals(N, face.getVertex(1));
        assertEquals(d, face.getVertex(2));

        face = tIII.getFace(D);
        assertEquals(d, face.getVertex(0));
        assertEquals(c, face.getVertex(1));
        assertEquals(b, face.getVertex(2));

        face = tIV.getFace(A);
        assertEquals(b, face.getVertex(0));
        assertEquals(d, face.getVertex(1));
        assertEquals(N, face.getVertex(2));

        face = tIV.getFace(B);
        assertEquals(N, face.getVertex(0));
        assertEquals(a, face.getVertex(1));
        assertEquals(b, face.getVertex(2));

        face = tIV.getFace(C);
        assertEquals(a, face.getVertex(0));
        assertEquals(N, face.getVertex(1));
        assertEquals(d, face.getVertex(2));

        face = tIV.getFace(D);
        assertEquals(d, face.getVertex(0));
        assertEquals(b, face.getVertex(1));
        assertEquals(a, face.getVertex(2));
    }
}
