/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegionBounds record.
 *
 * @author hal.hildebrand
 */
class RegionBoundsTest {

    @Test
    void testCenterCalculation() {
        var bounds = new RegionBounds(0.0f, 0.0f, 0.0f, 64.0f, 64.0f, 64.0f);

        assertEquals(32.0f, bounds.centerX(), 0.0001f);
        assertEquals(32.0f, bounds.centerY(), 0.0001f);
        assertEquals(32.0f, bounds.centerZ(), 0.0001f);
    }

    @Test
    void testSizeCalculation() {
        var bounds = new RegionBounds(100.0f, 200.0f, 300.0f, 164.0f, 264.0f, 364.0f);

        assertEquals(64.0f, bounds.size(), 0.0001f);
    }

    @Test
    void testContains() {
        var bounds = new RegionBounds(0.0f, 0.0f, 0.0f, 64.0f, 64.0f, 64.0f);

        // Inside
        assertTrue(bounds.contains(32.0f, 32.0f, 32.0f));
        assertTrue(bounds.contains(0.0f, 0.0f, 0.0f));  // Min boundary (inclusive)
        assertTrue(bounds.contains(63.9f, 63.9f, 63.9f));

        // On max boundary (exclusive)
        assertFalse(bounds.contains(64.0f, 32.0f, 32.0f));
        assertFalse(bounds.contains(32.0f, 64.0f, 32.0f));
        assertFalse(bounds.contains(32.0f, 32.0f, 64.0f));

        // Outside
        assertFalse(bounds.contains(-1.0f, 32.0f, 32.0f));
        assertFalse(bounds.contains(65.0f, 32.0f, 32.0f));
    }

    @Test
    void testContainsWithPrecisionNearBoundary() {
        var bounds = new RegionBounds(0.0f, 0.0f, 0.0f, 64.0f, 64.0f, 64.0f);

        // Float precision near boundary
        float nearMax = 63.99999f;
        assertTrue(bounds.contains(nearMax, 32.0f, 32.0f));

        float justOver = 64.00001f;
        assertFalse(bounds.contains(justOver, 32.0f, 32.0f));
    }

    @Test
    void testEquality() {
        var b1 = new RegionBounds(0.0f, 0.0f, 0.0f, 64.0f, 64.0f, 64.0f);
        var b2 = new RegionBounds(0.0f, 0.0f, 0.0f, 64.0f, 64.0f, 64.0f);
        var b3 = new RegionBounds(0.0f, 0.0f, 0.0f, 128.0f, 128.0f, 128.0f);

        assertEquals(b1, b2);
        assertNotEquals(b1, b3);
        assertEquals(b1.hashCode(), b2.hashCode());
    }

    @Test
    void testNonUniformBounds() {
        // Non-cubic bounds (though size() assumes cubic)
        var bounds = new RegionBounds(0.0f, 0.0f, 0.0f, 64.0f, 128.0f, 32.0f);

        assertTrue(bounds.contains(32.0f, 64.0f, 16.0f));
        assertFalse(bounds.contains(32.0f, 64.0f, 33.0f));
    }
}
