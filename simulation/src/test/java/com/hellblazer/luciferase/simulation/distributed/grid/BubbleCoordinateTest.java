/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BubbleCoordinate grid position record.
 * Validates coordinate creation, distance calculations, and position type detection.
 *
 * @author hal.hildebrand
 */
class BubbleCoordinateTest {

    @Test
    void testValidCoordinateCreation() {
        var coord = new BubbleCoordinate(0, 0);
        assertEquals(0, coord.row());
        assertEquals(0, coord.column());

        var coord2 = new BubbleCoordinate(5, 10);
        assertEquals(5, coord2.row());
        assertEquals(10, coord2.column());
    }

    @Test
    void testNegativeRowRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BubbleCoordinate(-1, 0));
    }

    @Test
    void testNegativeColumnRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BubbleCoordinate(0, -1));
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0, 0, 0, 0",      // Same position
        "0, 0, 0, 1, 1",      // Adjacent column
        "0, 0, 1, 0, 1",      // Adjacent row
        "0, 0, 2, 3, 5",      // Diagonal distance
        "5, 5, 8, 9, 7"       // Larger distance
    })
    void testManhattanDistance(int r1, int c1, int r2, int c2, int expected) {
        var coord1 = new BubbleCoordinate(r1, c1);
        var coord2 = new BubbleCoordinate(r2, c2);
        assertEquals(expected, coord1.manhattanDistance(coord2));
        assertEquals(expected, coord2.manhattanDistance(coord1)); // Symmetric
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0, 0, 0, 0",      // Same position
        "0, 0, 0, 1, 1",      // Adjacent column
        "0, 0, 1, 0, 1",      // Adjacent row
        "0, 0, 1, 1, 1",      // Diagonal
        "0, 0, 2, 3, 3",      // Max(2,3) = 3
        "5, 5, 8, 9, 4"       // Max(3,4) = 4
    })
    void testChebyshevDistance(int r1, int c1, int r2, int c2, int expected) {
        var coord1 = new BubbleCoordinate(r1, c1);
        var coord2 = new BubbleCoordinate(r2, c2);
        assertEquals(expected, coord1.chebyshevDistance(coord2));
        assertEquals(expected, coord2.chebyshevDistance(coord1)); // Symmetric
    }

    @Test
    void testIsAdjacentTo() {
        var center = new BubbleCoordinate(1, 1);

        // 8 adjacent positions (including diagonals)
        assertTrue(center.isAdjacentTo(new BubbleCoordinate(0, 0)));  // Top-left
        assertTrue(center.isAdjacentTo(new BubbleCoordinate(0, 1)));  // Top
        assertTrue(center.isAdjacentTo(new BubbleCoordinate(0, 2)));  // Top-right
        assertTrue(center.isAdjacentTo(new BubbleCoordinate(1, 0)));  // Left
        assertTrue(center.isAdjacentTo(new BubbleCoordinate(1, 2)));  // Right
        assertTrue(center.isAdjacentTo(new BubbleCoordinate(2, 0)));  // Bottom-left
        assertTrue(center.isAdjacentTo(new BubbleCoordinate(2, 1)));  // Bottom
        assertTrue(center.isAdjacentTo(new BubbleCoordinate(2, 2)));  // Bottom-right

        // Not adjacent
        assertFalse(center.isAdjacentTo(center));                     // Self
        assertFalse(center.isAdjacentTo(new BubbleCoordinate(0, 3))); // Too far
        assertFalse(center.isAdjacentTo(new BubbleCoordinate(3, 3))); // Too far
    }

    @Test
    void testCornerPositionType() {
        // 3x3 grid corners
        assertEquals(BubbleCoordinate.PositionType.CORNER, new BubbleCoordinate(0, 0).getPositionType(3, 3));
        assertEquals(BubbleCoordinate.PositionType.CORNER, new BubbleCoordinate(0, 2).getPositionType(3, 3));
        assertEquals(BubbleCoordinate.PositionType.CORNER, new BubbleCoordinate(2, 0).getPositionType(3, 3));
        assertEquals(BubbleCoordinate.PositionType.CORNER, new BubbleCoordinate(2, 2).getPositionType(3, 3));
    }

    @Test
    void testEdgePositionType() {
        // 3x3 grid edges (not corners)
        assertEquals(BubbleCoordinate.PositionType.EDGE, new BubbleCoordinate(0, 1).getPositionType(3, 3)); // Top edge
        assertEquals(BubbleCoordinate.PositionType.EDGE, new BubbleCoordinate(2, 1).getPositionType(3, 3)); // Bottom edge
        assertEquals(BubbleCoordinate.PositionType.EDGE, new BubbleCoordinate(1, 0).getPositionType(3, 3)); // Left edge
        assertEquals(BubbleCoordinate.PositionType.EDGE, new BubbleCoordinate(1, 2).getPositionType(3, 3)); // Right edge
    }

    @Test
    void testInteriorPositionType() {
        // 3x3 grid center
        assertEquals(BubbleCoordinate.PositionType.INTERIOR, new BubbleCoordinate(1, 1).getPositionType(3, 3));

        // 4x4 grid interior positions
        assertEquals(BubbleCoordinate.PositionType.INTERIOR, new BubbleCoordinate(1, 1).getPositionType(4, 4));
        assertEquals(BubbleCoordinate.PositionType.INTERIOR, new BubbleCoordinate(1, 2).getPositionType(4, 4));
        assertEquals(BubbleCoordinate.PositionType.INTERIOR, new BubbleCoordinate(2, 1).getPositionType(4, 4));
        assertEquals(BubbleCoordinate.PositionType.INTERIOR, new BubbleCoordinate(2, 2).getPositionType(4, 4));
    }

    @Test
    void testExpectedNeighborCountCorner() {
        // Corners always have 3 neighbors
        assertEquals(3, new BubbleCoordinate(0, 0).expectedNeighborCount(3, 3));
        assertEquals(3, new BubbleCoordinate(0, 2).expectedNeighborCount(3, 3));
        assertEquals(3, new BubbleCoordinate(2, 0).expectedNeighborCount(3, 3));
        assertEquals(3, new BubbleCoordinate(2, 2).expectedNeighborCount(3, 3));
    }

    @Test
    void testExpectedNeighborCountEdge() {
        // Edges have 5 neighbors
        assertEquals(5, new BubbleCoordinate(0, 1).expectedNeighborCount(3, 3));
        assertEquals(5, new BubbleCoordinate(1, 0).expectedNeighborCount(3, 3));
        assertEquals(5, new BubbleCoordinate(1, 2).expectedNeighborCount(3, 3));
        assertEquals(5, new BubbleCoordinate(2, 1).expectedNeighborCount(3, 3));
    }

    @Test
    void testExpectedNeighborCountInterior() {
        // Interior has 8 neighbors
        assertEquals(8, new BubbleCoordinate(1, 1).expectedNeighborCount(3, 3));
        assertEquals(8, new BubbleCoordinate(1, 1).expectedNeighborCount(4, 4));
        assertEquals(8, new BubbleCoordinate(2, 2).expectedNeighborCount(4, 4));
    }

    @Test
    void testToString() {
        assertEquals("(0,0)", new BubbleCoordinate(0, 0).toString());
        assertEquals("(5,10)", new BubbleCoordinate(5, 10).toString());
    }

    @Test
    void testEquality() {
        var coord1 = new BubbleCoordinate(1, 2);
        var coord2 = new BubbleCoordinate(1, 2);
        var coord3 = new BubbleCoordinate(2, 1);

        assertEquals(coord1, coord2);
        assertNotEquals(coord1, coord3);
        assertEquals(coord1.hashCode(), coord2.hashCode());
    }
}
