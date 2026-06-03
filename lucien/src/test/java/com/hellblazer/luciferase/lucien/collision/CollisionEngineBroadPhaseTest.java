/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-ig4yi: the bounded-vs-bounded phase of {@link CollisionEngine#findAllCollisions} tested every pair of
 * bounded entities (O(n^2)) regardless of proximity, ignoring the spatial index used by the single-entity path. The
 * fix routes the broad-phase through {@code findNodesIntersectingBounds} so distant pairs are never narrow-phase
 * tested, while overlapping pairs are still found.
 *
 * @author hal.hildebrand
 */
class CollisionEngineBroadPhaseTest {

    private static CollisionEngine<?, ?, ?> engineOf(AbstractSpatialIndex<?, ?, ?> index) throws Exception {
        Field f = AbstractSpatialIndex.class.getDeclaredField("collisions");
        f.setAccessible(true);
        return (CollisionEngine<?, ?, ?>) f.get(index);
    }

    @Test
    void distantBoundedPairsAreNotNarrowPhaseTested() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        int n = 24;
        // Bounded entities each in their own level-10 node (cell size 2048): spacing > one node width so the
        // broad-phase candidate set for each is just itself. radius-1 bounds stay within a single cell.
        for (int i = 0; i < n; i++) {
            var center = new Point3f(i * 4096.0f + 1024.0f, 1024.0f, 1024.0f);
            octree.insert(new LongEntityID(i), center, (byte) 10, "e" + i, new EntityBounds(center, 1.0f));
        }

        var collisions = octree.findAllCollisions();
        int narrowChecks = ((CollisionEngine<?, ?, ?>) engineOf(octree)).lastNarrowPhaseChecks();

        assertTrue(collisions.isEmpty(), "well-separated bounded entities must not collide");
        // O(n^2) would be n*(n-1)/2 = 276 narrow-phase checks; the broad-phase keeps it far below that.
        assertTrue(narrowChecks < n, "distant bounded pairs must be excluded by the broad-phase, got " + narrowChecks
                                     + " (n^2/2 = " + (n * (n - 1) / 2) + ", Luciferase-ig4yi)");
    }

    @Test
    void overlappingBoundedPairIsStillDetected() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var c1 = new Point3f(50.0f, 50.0f, 50.0f);
        var c2 = new Point3f(50.5f, 50.0f, 50.0f); // 0.5 apart, radius-1 bounds overlap
        octree.insert(new LongEntityID(1), c1, (byte) 10, "a", new EntityBounds(c1, 1.0f));
        octree.insert(new LongEntityID(2), c2, (byte) 10, "b", new EntityBounds(c2, 1.0f));

        var collisions = octree.findAllCollisions();

        assertEquals(1, collisions.size(), "overlapping bounded entities must still be detected (Luciferase-ig4yi)");
    }
}
