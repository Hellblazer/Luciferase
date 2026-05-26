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
 * RDR-009 Phase 4 (Luciferase-ner): cross-diagonal neighbor traversal. With the two-prism cover
 * (P3), the main diagonal {@code y = x} is an <em>interior</em> face between the S0 and S1 prism
 * families, not a domain edge. {@link Triangle#faceNeighbor(int)} implements the t8code constant-
 * time face-neighbor (Burstedde &amp; Holke, Table 3) and, when the neighbor across the hypotenuse
 * (face 1) falls outside the S0 root, reflects it into the S1 root (and vice versa) rather than
 * returning {@code null}. Face neighbors round-trip: {@code faceNeighbor(faceNeighbor(t, f), f̃) == t}.
 *
 * @author hal.hildebrand
 */
class PrismCrossDiagonalNeighborTest {

    /** The hypotenuse / diagonal face (opposite the right-angle vertex) is face 1 for both types. */
    private static final int DIAGONAL_FACE = 1;

    @Test
    @DisplayName("a diagonal-cell S0 triangle's hypotenuse neighbor is its S1 mirror (not null)")
    void crossDiagonalReturnsS1() {
        // Diagonal cell (x == y) at level 3, type 0 (lower-right), S0.
        var s0 = new Triangle(3, 0, 3, 3, 3, 0);
        var neighbor = s0.faceNeighbor(DIAGONAL_FACE);
        assertNotNull(neighbor, "interior diagonal face must have a neighbor (S1), not null");
        assertEquals(1, neighbor.getHalf(), "cross-diagonal neighbor must be in the S1 half");
        assertEquals(s0.getLevel(), neighbor.getLevel());
        // It is the mirror: same S0-frame anchor, S0 orientation.
        assertEquals(3, neighbor.getX());
        assertEquals(3, neighbor.getY());
    }

    @Test
    @DisplayName("cross-diagonal face neighbor round-trips: faceNeighbor(faceNeighbor(t)) == t")
    void crossDiagonalRoundTrip() {
        for (int level = 1; level <= 6; level++) {
            int max = 1 << level;
            for (int d = 0; d < max; d++) {
                for (int half = 0; half < 2; half++) {
                    // A diagonal cell (x == y == d), type 0: its hypotenuse lies on the global
                    // diagonal, so its face-1 neighbor crosses into the other half.
                    var t = new Triangle(level, 0, d, d, d, half);
                    var n = t.faceNeighbor(DIAGONAL_FACE);
                    assertNotNull(n, "diagonal-cell hypotenuse neighbor must not be null at " + t);
                    assertEquals(1 - half, n.getHalf(), "must cross to the other half");
                    var back = n.faceNeighbor(DIAGONAL_FACE);
                    assertEquals(t, back, "faceNeighbor must round-trip across the diagonal: " + t);
                }
            }
        }
    }

    @Test
    @DisplayName("an interior (non-diagonal) cell's hypotenuse neighbor stays in the same half")
    void interiorHypotenuseStaysSameHalf() {
        // Cell strictly below the diagonal (x > y): the hypotenuse neighbor is the same-cell
        // type-1 sub-triangle, still in S0.
        var t = new Triangle(3, 0, 5, 2, 2, 0);
        var n = t.faceNeighbor(DIAGONAL_FACE);
        assertNotNull(n);
        assertEquals(0, n.getHalf(), "interior hypotenuse neighbor stays in S0");
        assertEquals(1, n.getType(), "it is the same-cell type-1 sub-triangle");
        assertEquals(5, n.getX());
        assertEquals(2, n.getY());
        assertEquals(t, n.faceNeighbor(DIAGONAL_FACE), "round-trips within S0");
    }

    @Test
    @DisplayName("all in-domain face neighbors round-trip via the reciprocal face")
    void allFaceNeighborsRoundTrip() {
        // For every face with a non-null neighbor, faceNeighbor(neighbor, 2-f) returns the original
        // (reciprocal face f̃ = 2 - f in 2D).
        for (int level = 2; level <= 5; level++) {
            int max = 1 << level;
            for (int x = 0; x < max; x++) {
                for (int y = 0; y <= x; y++) { // S0-frame anchors
                    for (int type = 0; type < 2; type++) {
                        // A type-1 triangle on a diagonal cell (y == x) is not a valid S0 leaf — it
                        // is the upper-left sub-triangle, which lives in S1 (stored as type-0/half-1).
                        if (y == x && type == 1) {
                            continue;
                        }
                        for (int half = 0; half < 2; half++) {
                            var t = new Triangle(level, type, x, y, Math.min(x, y), half);
                            for (int f = 0; f < 3; f++) {
                                var n = t.faceNeighbor(f);
                                if (n == null) {
                                    continue; // outer domain boundary
                                }
                                var back = n.faceNeighbor(2 - f); // reciprocal face f̃ = 2 - f
                                assertEquals(t, back, String.format(
                                    "round-trip failed: %s face %d -> %s reciprocal %d -> %s", t, f, n, 2 - f, back));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("PrismNeighborFinder crosses the diagonal: the quad face neighbor is an S1 prism")
    void prismNeighborFinderCrossesDiagonal() {
        var s0Prism = new PrismKey(new Triangle(3, 0, 3, 3, 3, 0), new Line(3, 4));
        var neighbor = PrismNeighborFinder.findFaceNeighbor(s0Prism, DIAGONAL_FACE);
        assertNotNull(neighbor, "diagonal quad face must have an interior (S1) neighbor, not null");
        assertEquals(1, neighbor.getTriangle().getHalf(), "the cross-diagonal prism neighbor is in S1");
    }
}
