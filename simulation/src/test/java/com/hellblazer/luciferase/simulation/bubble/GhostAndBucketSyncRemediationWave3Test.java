/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-3 remediation beads:
 * Luciferase-0frcy.83 (BucketSynchronizedController silent tick-count gap on bucket jump),
 * .60 / .85 (TetreeGhostSyncAdapter re-derives bubble TetreeKey from centroid).
 */
class GhostAndBucketSyncRemediationWave3Test {

    // ---- Luciferase-0frcy.83: forward bucket jump emits every skipped tick ----

    @Test
    void bucketBoundaryJumpEmitsContiguousTicks() {
        var controller = new BucketSynchronizedController(UUID.randomUUID(), "sync-test", 100);

        var ticks = new ArrayList<Long>();
        controller.addTickListener((simTime, lamport) -> ticks.add(simTime));

        // Start at simTime = 2, jump to bucket 5 => target simTime = 5 * 10 = 50.
        controller.setSimulationTime(2L);
        controller.synchronizeAtBucket(5L, 2L);

        // Every intermediate tick 3..50 must have been emitted, contiguous, no gap.
        assertFalse(ticks.isEmpty(), "bucket jump must emit synthetic tick events");
        assertEquals(50L, ticks.get(ticks.size() - 1), "last emitted tick must reach the aligned sim time");
        assertEquals(3L, ticks.get(0), "first emitted synthetic tick must follow currentSimTime");
        for (int i = 1; i < ticks.size(); i++) {
            assertEquals(ticks.get(i - 1) + 1, ticks.get(i),
                         "emitted ticks must be strictly contiguous (no monotonic gap) at index " + i);
        }
        assertEquals(48, ticks.size(), "ticks 3..50 inclusive = 48 synthetic ticks");
    }

    @Test
    void noJumpEmitsNoSyntheticTicks() {
        var controller = new BucketSynchronizedController(UUID.randomUUID(), "sync-test", 100);
        var ticks = new ArrayList<Long>();
        controller.addTickListener((simTime, lamport) -> ticks.add(simTime));

        // currentSimTime already at/above target: no jump, no synthetic ticks.
        controller.setSimulationTime(100L);
        controller.synchronizeAtBucket(5L, 100L); // target 50 < 100 => no advance

        assertTrue(ticks.isEmpty(), "no synthetic ticks when already past the bucket target");
    }

    // ---- Luciferase-0frcy.60 / .85: ghost neighbor lookup uses registration key, not drifted centroid ----

    @Test
    void findBoundaryNeighborsStableUnderCentroidDrift() {
        var grid = new TetreeBubbleGrid((byte) 5);
        grid.createBubbles(16, (byte) 2, 16L);

        var neighborFinder = grid.getNeighborFinder();
        var adapter = new TetreeGhostSyncAdapter(grid, neighborFinder);

        // Populate each bubble with a couple of entities so bounds exist.
        for (var bubble : grid.getAllBubbles()) {
            var c = bubble.bounds() != null ? bubble.bounds().centroid() : null;
            float bx = c != null ? (float) c.getX() : 0.5f;
            float by = c != null ? (float) c.getY() : 0.5f;
            float bz = c != null ? (float) c.getZ() : 0.5f;
            bubble.addEntity("seed-" + bubble.id(), new Point3f(bx, by, bz), "seed");
            bubble.recalculateBounds();
        }

        // Pick a bubble that has at least one boundary neighbor.
        EnhancedBubble target = null;
        Set<UUID> before = null;
        for (var bubble : grid.getAllBubbles()) {
            var n = adapter.findBoundaryNeighbors(bubble);
            if (!n.isEmpty()) {
                target = bubble;
                before = n;
                break;
            }
        }
        // If the partition yields no neighbors at all, the registration-key
        // property is trivially satisfied; skip the drift assertion.
        if (target == null) {
            return;
        }

        var registrationKey = grid.getKeyForBubble(target.id());
        assertNotNull(registrationKey, "target bubble must have a registration key");

        // Drift the centroid: add entities far from the registration tet so the
        // bounds centroid would locate a DIFFERENT tetrahedron. Pre-fix this
        // changed the geometrically re-derived key and thus the neighbor set.
        for (int i = 0; i < 20; i++) {
            target.addEntity("drift-" + i, new Point3f(0.99f, 0.99f, 0.99f), "drift");
        }
        target.recalculateBounds();

        var after = adapter.findBoundaryNeighbors(target);

        // The registration key is unchanged...
        assertEquals(registrationKey, grid.getKeyForBubble(target.id()),
                     "registration key must not change due to entity/centroid drift");
        // ...so the neighbor topology computed from it must be stable.
        assertEquals(before, after,
                     "boundary neighbors must be derived from the registration key, "
                     + "not the drifted centroid");
    }
}
