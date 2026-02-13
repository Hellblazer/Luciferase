/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegionId record.
 *
 * @author hal.hildebrand
 */
class RegionIdTest {

    @Test
    void testEquality() {
        var r1 = new RegionId(1234L, 4);
        var r2 = new RegionId(1234L, 4);
        var r3 = new RegionId(5678L, 4);
        var r4 = new RegionId(1234L, 5);

        assertEquals(r1, r2);
        assertNotEquals(r1, r3);
        assertNotEquals(r1, r4);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void testComparison() {
        var r1 = new RegionId(100L, 3);
        var r2 = new RegionId(200L, 3);
        var r3 = new RegionId(100L, 4);

        // Same level, different morton code
        assertTrue(r1.compareTo(r2) < 0);
        assertTrue(r2.compareTo(r1) > 0);

        // Different level, same morton code (level takes precedence)
        assertTrue(r1.compareTo(r3) < 0);
        assertTrue(r3.compareTo(r1) > 0);

        // Self comparison
        assertEquals(0, r1.compareTo(r1));
    }

    @Test
    void testSorting() {
        var regions = new ArrayList<>(List.of(
            new RegionId(300L, 5),
            new RegionId(100L, 3),
            new RegionId(200L, 3),
            new RegionId(100L, 4)
        ));

        Collections.sort(regions);

        // Should be sorted by level first, then morton code
        assertEquals(new RegionId(100L, 3), regions.get(0));
        assertEquals(new RegionId(200L, 3), regions.get(1));
        assertEquals(new RegionId(100L, 4), regions.get(2));
        assertEquals(new RegionId(300L, 5), regions.get(3));
    }

    @Test
    void testMortonCodeEncoding() {
        // Encode 3D coordinates to Morton code
        int x = 5, y = 3, z = 7;
        long morton = MortonCurve.encode(x, y, z);

        var region = new RegionId(morton, 4);

        // Verify round-trip
        int[] decoded = MortonCurve.decode(region.mortonCode());
        assertEquals(x, decoded[0]);
        assertEquals(y, decoded[1]);
        assertEquals(z, decoded[2]);
    }

    @Test
    void testUseInHashMap() {
        var map = new HashMap<RegionId, String>();
        var r1 = new RegionId(123L, 4);
        var r2 = new RegionId(123L, 4);

        map.put(r1, "value1");

        // r2 should retrieve same value (equality)
        assertEquals("value1", map.get(r2));
    }

    @Test
    void testUseInTreeSet() {
        var set = new TreeSet<RegionId>();
        set.add(new RegionId(300L, 5));
        set.add(new RegionId(100L, 3));
        set.add(new RegionId(200L, 3));

        // Should be sorted
        var iterator = set.iterator();
        assertEquals(new RegionId(100L, 3), iterator.next());
        assertEquals(new RegionId(200L, 3), iterator.next());
        assertEquals(new RegionId(300L, 5), iterator.next());
    }
}
