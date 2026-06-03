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
 * fix replaces the all-pairs loop with X-axis sweep-and-prune so distant pairs are never narrow-phase tested,
 * while overlapping pairs (including large AABBs overlapping across node boundaries) are still found.
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
    void crossNodeOverlappingBoundsAreDetected() {
        // Critic's counter-example to a node-membership broad-phase: two entities whose POSITION nodes are different
        // level-10 cells (cellSize 2048) but whose large AABBs overlap in the gap between those cells. A node-based
        // broad-phase would miss this; sweep-and-prune (X-axis) finds it because the AABBs overlap on X.
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var cA = new Point3f(3000.0f, 3000.0f, 3000.0f);   // position node ~ [2048,4096]
        var cB = new Point3f(7000.0f, 3000.0f, 3000.0f);   // position node ~ [6144,8192]
        // AABBs overlap around x in [5000,5500] (and on y,z) — the cell [4096,6144] between both position nodes.
        octree.insert(new LongEntityID(1), cA, (byte) 10, "a", new EntityBounds(cA, 2500.0f)); // x in [500,5500]
        octree.insert(new LongEntityID(2), cB, (byte) 10, "b", new EntityBounds(cB, 2000.0f)); // x in [5000,9000]

        var collisions = octree.findAllCollisions();

        assertEquals(1, collisions.size(),
                     "cross-node overlapping bounds must be detected by sweep-and-prune (Luciferase-ig4yi)");
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
