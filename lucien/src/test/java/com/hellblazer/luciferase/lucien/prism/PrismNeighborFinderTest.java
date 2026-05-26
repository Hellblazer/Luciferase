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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PrismNeighborFinder} — prism face-neighbor finding over the two-prism cover
 * (RDR-009 P4). The three quadrilateral side faces (0-2) use the triangle face-neighbor (which
 * crosses the shared S0/S1 diagonal); the two triangular faces (3 bottom, 4 top) move along the
 * line component.
 *
 * @author hal.hildebrand
 */
class PrismNeighborFinderTest {

    // An interior prism: x in (0, max-1), 0 < y < x, line z in (0, max-1) — all 5 faces in-domain.
    private static PrismKey interiorPrism() {
        return new PrismKey(new Triangle(4, 0, 8, 5, 5, 0), new Line(4, 7));
    }

    @Test
    @DisplayName("quad faces (0-2) keep the line component and change the triangle")
    void quadFacesChangeTriangleKeepLine() {
        var prism = interiorPrism();
        for (int face = 0; face < 3; face++) {
            var neighbor = PrismNeighborFinder.findFaceNeighbor(prism, face);
            assertNotNull(neighbor, "interior quad face " + face + " must have a neighbor");
            assertEquals(prism.getLine(), neighbor.getLine(), "quad face keeps the line");
            assertEquals(prism.getLevel(), neighbor.getLevel());
            assertNotEqualsTriangle(prism, neighbor);
        }
    }

    @Test
    @DisplayName("triangular faces (bottom 3, top 4) keep the triangle and move the line")
    void triangularFacesChangeLineKeepTriangle() {
        var prism = interiorPrism();
        var bottom = PrismNeighborFinder.findFaceNeighbor(prism, PrismNeighborFinder.FACE_TRIANGLE_BOTTOM);
        var top = PrismNeighborFinder.findFaceNeighbor(prism, PrismNeighborFinder.FACE_TRIANGLE_TOP);
        assertNotNull(bottom);
        assertNotNull(top);
        assertEquals(prism.getTriangle(), bottom.getTriangle(), "bottom face keeps the triangle");
        assertEquals(prism.getTriangle(), top.getTriangle(), "top face keeps the triangle");
        assertEquals(prism.getLine().getZ() - 1, bottom.getLine().getZ(), "bottom moves down one");
        assertEquals(prism.getLine().getZ() + 1, top.getLine().getZ(), "top moves up one");
    }

    @Test
    @DisplayName("the diagonal quad face of a diagonal-cell prism reaches the S1 family")
    void diagonalQuadFaceReachesS1() {
        var prism = new PrismKey(new Triangle(4, 0, 6, 6, 6, 0), new Line(4, 7)); // diagonal cell, S0
        var neighbor = PrismNeighborFinder.findFaceNeighbor(prism, 1); // hypotenuse
        assertNotNull(neighbor, "interior diagonal face must reach the S1 neighbor, not null");
        assertEquals(1, neighbor.getTriangle().getHalf(), "neighbor across the diagonal is in S1");
        assertEquals(prism.getLine(), neighbor.getLine());
    }

    @Test
    @DisplayName("getNeighborFace is an involution (reciprocal face)")
    void getNeighborFaceReciprocity() {
        for (int face = 0; face < PrismNeighborFinder.NUM_FACES; face++) {
            int reciprocal = PrismNeighborFinder.getNeighborFace(face);
            assertEquals(face, PrismNeighborFinder.getNeighborFace(reciprocal),
                "getNeighborFace must be its own inverse for face " + face);
        }
    }

    @Test
    @DisplayName("findAllFaceNeighbors returns all five for an interior prism, each reciprocally adjacent")
    void findAllFaceNeighborsInterior() {
        var prism = interiorPrism();
        var neighbors = PrismNeighborFinder.findAllFaceNeighbors(prism);
        assertEquals(5, neighbors.size(), "an interior prism has 5 face neighbors");
        // Each face's neighbor, viewed from its reciprocal face, returns the original prism.
        for (int face = 0; face < PrismNeighborFinder.NUM_FACES; face++) {
            var neighbor = PrismNeighborFinder.findFaceNeighbor(prism, face);
            assertNotNull(neighbor);
            var back = PrismNeighborFinder.findFaceNeighbor(neighbor, PrismNeighborFinder.getNeighborFace(face));
            assertEquals(prism, back, "face " + face + " neighbor must be reciprocally adjacent");
        }
    }

    private static void assertNotEqualsTriangle(PrismKey a, PrismKey b) {
        assertTrue(!a.getTriangle().equals(b.getTriangle()) || !a.equals(b),
                   "neighbor must differ from the original");
    }
}
