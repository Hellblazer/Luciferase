/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.NavigableSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code SpatialIndex.spatialKeysInRange} (Luciferase-3uwx S3) — the navigable SFC-subrange primitive
 * backing t8code-style owner-range ghost pruning. The contract: return exactly the occupied keys whose SFC
 * order falls in {@code [from, to]} (inclusivity per the flags), in ascending SFC order.
 *
 * @author hal.hildebrand
 */
class OctreeSpatialKeysInRangeTest {

    private static Octree<LongEntityID, String> populated() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        // Spread entities across the domain so several distinct Morton cells are occupied.
        byte level = 20;
        for (int x = 100; x <= 900; x += 100) {
            for (int y = 100; y <= 900; y += 100) {
                octree.insert(new Point3f(x, y, 50), level, "e" + x + "_" + y);
            }
        }
        return octree;
    }

    @Test
    void returnsExactlyTheOccupiedKeysInTheSubrange() {
        var octree = populated();
        var all = new TreeSet<>(octree.nodes().map(n -> n.sfcIndex()).toList());
        assertTrue(all.size() >= 4, "fixture must occupy several cells, got " + all.size());

        // Pick an interior subrange [from, to] from the occupied keys.
        var asList = all.stream().toList();
        var from = asList.get(1);
        var to = asList.get(asList.size() - 2);

        NavigableSet<MortonKey> expected = new TreeSet<>(all.subSet(from, true, to, true));
        NavigableSet<MortonKey> actual = octree.spatialKeysInRange(from, true, to, true);
        assertEquals(expected, actual, "inclusive subrange must match a manual navigable filter");
        assertEquals(from, actual.first(), "ascending SFC order, fromKey included");
        assertEquals(to, actual.last(), "toKey included");
    }

    @Test
    void exclusiveBoundsDropTheEndpoints() {
        var octree = populated();
        var all = new TreeSet<>(octree.nodes().map(n -> n.sfcIndex()).toList());
        var asList = all.stream().toList();
        var from = asList.get(0);
        var to = asList.get(asList.size() - 1);

        var inclusive = octree.spatialKeysInRange(from, true, to, true);
        var exclusive = octree.spatialKeysInRange(from, false, to, false);
        assertTrue(inclusive.contains(from) && inclusive.contains(to));
        assertFalse(exclusive.contains(from), "fromInclusive=false drops the lower endpoint");
        assertFalse(exclusive.contains(to), "toInclusive=false drops the upper endpoint");
        assertEquals(inclusive.size() - 2, exclusive.size());
    }

    @Test
    void emptyWhenRangeStraddlesNoOccupiedKey() {
        var octree = populated();
        var all = new TreeSet<>(octree.nodes().map(n -> n.sfcIndex()).toList());
        var first = all.first();
        // A half-open range just below the smallest occupied key contains nothing.
        var actual = octree.spatialKeysInRange(first, false, first, false);
        assertTrue(actual.isEmpty(), "degenerate exclusive range is empty");
    }
}
