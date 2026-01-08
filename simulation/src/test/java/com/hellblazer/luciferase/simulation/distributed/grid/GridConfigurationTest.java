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

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GridConfiguration validation and spatial calculations.
 * Validates configuration creation, bounds checking, and coordinate transformations.
 *
 * @author hal.hildebrand
 */
class GridConfigurationTest {

    @Test
    void testValidConfigurationCreation() {
        var config = new GridConfiguration(2, 3, 100f, 50f, 0f, 0f);
        assertEquals(2, config.rows());
        assertEquals(3, config.columns());
        assertEquals(100f, config.cellWidth());
        assertEquals(50f, config.cellHeight());
        assertEquals(0f, config.originX());
        assertEquals(0f, config.originY());
    }

    @Test
    void testInvalidRowsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new GridConfiguration(0, 2, 100f, 100f, 0f, 0f));
        assertThrows(IllegalArgumentException.class,
            () -> new GridConfiguration(-1, 2, 100f, 100f, 0f, 0f));
    }

    @Test
    void testInvalidColumnsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new GridConfiguration(2, 0, 100f, 100f, 0f, 0f));
        assertThrows(IllegalArgumentException.class,
            () -> new GridConfiguration(2, -1, 100f, 100f, 0f, 0f));
    }

    @Test
    void testInvalidCellWidthRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new GridConfiguration(2, 2, 0f, 100f, 0f, 0f));
        assertThrows(IllegalArgumentException.class,
            () -> new GridConfiguration(2, 2, -1f, 100f, 0f, 0f));
    }

    @Test
    void testInvalidCellHeightRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new GridConfiguration(2, 2, 100f, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class,
            () -> new GridConfiguration(2, 2, 100f, -1f, 0f, 0f));
    }

    @Test
    void testSquareConfigurationFactory() {
        var config = GridConfiguration.square(3, 50f);
        assertEquals(3, config.rows());
        assertEquals(3, config.columns());
        assertEquals(50f, config.cellWidth());
        assertEquals(50f, config.cellHeight());
        assertEquals(0f, config.originX());
        assertEquals(0f, config.originY());
    }

    @Test
    void testOfFactory() {
        var config = GridConfiguration.of(4, 5, 120f, 80f);
        assertEquals(4, config.rows());
        assertEquals(5, config.columns());
        assertEquals(120f, config.cellWidth());
        assertEquals(80f, config.cellHeight());
        assertEquals(0f, config.originX());
        assertEquals(0f, config.originY());
    }

    @Test
    void testBubbleCount() {
        assertEquals(4, GridConfiguration.DEFAULT_2X2.bubbleCount());
        assertEquals(9, GridConfiguration.DEFAULT_3X3.bubbleCount());
        assertEquals(12, GridConfiguration.of(3, 4, 100f, 100f).bubbleCount());
    }

    @Test
    void testTotalWidth() {
        assertEquals(200f, GridConfiguration.DEFAULT_2X2.totalWidth());
        assertEquals(300f, GridConfiguration.DEFAULT_3X3.totalWidth());
        assertEquals(480f, GridConfiguration.of(3, 4, 120f, 80f).totalWidth());
    }

    @Test
    void testTotalHeight() {
        assertEquals(200f, GridConfiguration.DEFAULT_2X2.totalHeight());
        assertEquals(300f, GridConfiguration.DEFAULT_3X3.totalHeight());
        assertEquals(240f, GridConfiguration.of(3, 4, 120f, 80f).totalHeight());
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0, 0, 0",      // Bottom-left corner
        "99, 99, 0, 0",    // Still in (0,0)
        "100, 100, 1, 1",  // Next cell
        "150, 150, 1, 1",  // Middle of (1,1)
        "199, 199, 1, 1"   // Edge of (1,1)
    })
    void testCoordinateFor2x2Grid(float x, float y, int expectedRow, int expectedCol) {
        var config = GridConfiguration.DEFAULT_2X2;
        var coord = config.coordinateFor(x, y);
        assertNotNull(coord);
        assertEquals(expectedRow, coord.row());
        assertEquals(expectedCol, coord.column());
    }

    @Test
    void testCoordinateForOutOfBounds() {
        var config = GridConfiguration.DEFAULT_2X2;
        assertNull(config.coordinateFor(-1f, 0f));      // Negative X
        assertNull(config.coordinateFor(0f, -1f));      // Negative Y
        assertNull(config.coordinateFor(200f, 100f));   // X too large
        assertNull(config.coordinateFor(100f, 200f));   // Y too large
        assertNull(config.coordinateFor(201f, 201f));   // Both too large
    }

    @Test
    void testCoordinateForWithNonZeroOrigin() {
        var config = new GridConfiguration(2, 2, 100f, 100f, 50f, 25f);

        // Position (50, 25) is at grid origin -> (0,0)
        var coord = config.coordinateFor(50f, 25f);
        assertNotNull(coord);
        assertEquals(0, coord.row());
        assertEquals(0, coord.column());

        // Position (150, 125) is at (1,1)
        var coord2 = config.coordinateFor(150f, 125f);
        assertNotNull(coord2);
        assertEquals(1, coord2.row());
        assertEquals(1, coord2.column());
    }

    @Test
    void testCoordinateForPoint3f() {
        var config = GridConfiguration.DEFAULT_2X2;
        var point = new Point3f(150f, 150f, 999f);  // Z should be ignored
        var coord = config.coordinateFor(point);
        assertNotNull(coord);
        assertEquals(1, coord.row());
        assertEquals(1, coord.column());
    }

    @Test
    void testCellCenter() {
        var config = GridConfiguration.DEFAULT_2X2;

        var center00 = config.cellCenter(new BubbleCoordinate(0, 0));
        assertEquals(50f, center00.x, 0.001f);
        assertEquals(50f, center00.y, 0.001f);
        assertEquals(0f, center00.z);

        var center11 = config.cellCenter(new BubbleCoordinate(1, 1));
        assertEquals(150f, center11.x, 0.001f);
        assertEquals(150f, center11.y, 0.001f);
        assertEquals(0f, center11.z);
    }

    @Test
    void testCellMin() {
        var config = GridConfiguration.DEFAULT_2X2;

        var min00 = config.cellMin(new BubbleCoordinate(0, 0));
        assertEquals(0f, min00.x);
        assertEquals(0f, min00.y);
        assertEquals(0f, min00.z);

        var min11 = config.cellMin(new BubbleCoordinate(1, 1));
        assertEquals(100f, min11.x);
        assertEquals(100f, min11.y);
        assertEquals(0f, min11.z);
    }

    @Test
    void testCellMax() {
        var config = GridConfiguration.DEFAULT_2X2;

        var max00 = config.cellMax(new BubbleCoordinate(0, 0));
        assertEquals(100f, max00.x);
        assertEquals(100f, max00.y);
        assertEquals(0f, max00.z);

        var max11 = config.cellMax(new BubbleCoordinate(1, 1));
        assertEquals(200f, max11.x);
        assertEquals(200f, max11.y);
        assertEquals(0f, max11.z);
    }

    @Test
    void testIsValid() {
        var config = GridConfiguration.DEFAULT_3X3;

        assertTrue(config.isValid(new BubbleCoordinate(0, 0)));
        assertTrue(config.isValid(new BubbleCoordinate(1, 1)));
        assertTrue(config.isValid(new BubbleCoordinate(2, 2)));

        assertFalse(config.isValid(new BubbleCoordinate(3, 0)));  // Row out of bounds
        assertFalse(config.isValid(new BubbleCoordinate(0, 3)));  // Column out of bounds
        // Note: Negative coordinates throw in BubbleCoordinate constructor,
        // tested in BubbleCoordinateTest
    }

    @Test
    void testToIndexAndFromIndex() {
        var config = GridConfiguration.DEFAULT_3X3;

        // Row-major ordering
        assertEquals(0, config.toIndex(new BubbleCoordinate(0, 0)));
        assertEquals(1, config.toIndex(new BubbleCoordinate(0, 1)));
        assertEquals(2, config.toIndex(new BubbleCoordinate(0, 2)));
        assertEquals(3, config.toIndex(new BubbleCoordinate(1, 0)));
        assertEquals(4, config.toIndex(new BubbleCoordinate(1, 1)));
        assertEquals(8, config.toIndex(new BubbleCoordinate(2, 2)));

        // Round-trip
        for (int i = 0; i < config.bubbleCount(); i++) {
            var coord = config.fromIndex(i);
            assertEquals(i, config.toIndex(coord));
        }
    }

    @Test
    void testFromIndexOutOfBounds() {
        var config = GridConfiguration.DEFAULT_2X2;
        assertThrows(IndexOutOfBoundsException.class, () -> config.fromIndex(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> config.fromIndex(4));
        assertThrows(IndexOutOfBoundsException.class, () -> config.fromIndex(100));
    }

    @Test
    void testToString() {
        var config = GridConfiguration.DEFAULT_2X2;
        assertTrue(config.toString().contains("2x2"));
        assertTrue(config.toString().contains("100"));
    }
}
