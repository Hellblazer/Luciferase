/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Luciferase-s62fr: {@link CollisionSystem#processCCDForEntity} ignored any registered {@link CollisionShape}
 * (always derived a sphere from the AABB) and used a hardcoded {@code +10.0f} broad-phase buffer, and was
 * untested. These tests pin the registered-shape path and the configurable buffer.
 *
 * @author hal.hildebrand
 */
class CollisionSystemCCDTest {

    private static Octree<LongEntityID, String> octreeWith(Point3f a, Point3f b) {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.insert(new LongEntityID(1), a, (byte) 10, "a");
        octree.insert(new LongEntityID(2), b, (byte) 10, "b");
        return octree;
    }

    @Test
    void processCCDForEntityUsesRegisteredShape() {
        // Mover sweeps along the x-axis; target sits 0.4 off-axis. Point entities (zero-extent AABB) would derive
        // radius-0 spheres and miss the 0.4 gap; registered radius-0.5 spheres (combined 1.0 > 0.4) collide.
        var mover = new Point3f(0, 0, 0);
        var target = new Point3f(2, 0.4f, 0);
        var octree = octreeWith(mover, target);
        var system = new CollisionSystem<>(octree);

        system.getPhysicsProperties(new LongEntityID(1)).setVelocity(new Vector3f(4, 0, 0));

        // Without a registered shape, point entities have no bounds, so getEntityShape returns null and CCD is a
        // no-op for the pair — there is no narrow-phase collision.
        assertTrue(system.processCCDForEntity(new LongEntityID(1), 1.0f).isEmpty(),
                   "no registered shape (and no bounds) => CCD finds nothing");

        // Register real shapes: now the swept radius-0.5 sphere grazes the radius-0.5 target.
        system.registerCollisionShape(new LongEntityID(1), new SphereShape(mover, 0.5f));
        system.registerCollisionShape(new LongEntityID(2), new SphereShape(target, 0.5f));

        assertFalse(system.processCCDForEntity(new LongEntityID(1), 1.0f).isEmpty(),
                    "registered collision shapes must be used by CCD (Luciferase-s62fr)");
    }

    @Test
    void ccdSearchRadiusBufferIsConfigurable() {
        var system = new CollisionSystem<>(octreeWith(new Point3f(0, 0, 0), new Point3f(2, 0, 0)));

        assertEquals(CollisionSystem.DEFAULT_CCD_SEARCH_RADIUS_BUFFER, system.getCcdSearchRadiusBuffer(), 0.0f);

        system.setCcdSearchRadiusBuffer(2.5f);
        assertEquals(2.5f, system.getCcdSearchRadiusBuffer(), 0.0f, "buffer must be configurable (Luciferase-s62fr)");

        assertThrows(IllegalArgumentException.class, () -> system.setCcdSearchRadiusBuffer(-1.0f),
                     "negative buffer must be rejected");
    }

    /**
     * Regression for Luciferase-7wzml.93: the old start-only kNN with k=100 missed a target near the
     * ENDPOINT when ≥100 other entities were clustered closer to the start position (the target was the
     * 101st-nearest from startPos and was truncated by the k-cap). The swept-AABB range query covers
     * the full path, so the endpoint target is always included in the candidate set.
     * <p>
     * Setup: mover sweeps from origin to (1000, 0, 0). 110 filler entities sit 1–50 units from the origin
     * but offset 200 units on the y-axis, so they are close in Euclidean distance to startPos but far off the
     * sweep axis → no narrow-phase CCD collision with the mover. Target at (950, 0, 0) is on the sweep axis,
     * well within reach at the endpoint. The old kNN(startPos, k=100) returns the 100 nearest fillers (all
     * at distance ≈200 from start) and truncates before reaching the target at distance 950.
     */
    @Test
    void ccdBroadPhaseDetectsCollisionNearEndpoint() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        // IDs 1..110: fillers near origin in x but far on y-axis (≈200 units from start),
        // placed so all 110 are closer to startPos (distance ≈200) than the target (distance 950).
        for (int i = 1; i <= 110; i++) {
            octree.insert(new LongEntityID(i), new Point3f(i * 0.4f, 200, 0), (byte) 10, "filler");
        }
        // ID 111: target on the sweep axis near the endpoint.
        var targetPos = new Point3f(950, 0, 0);
        octree.insert(new LongEntityID(111), targetPos, (byte) 10, "target");

        var system = new CollisionSystem<>(octree);
        // Mover is entity 1; reposition it to origin, velocity 1000 along x.
        var moverPos = new Point3f(0, 0, 0);
        octree.updateEntity(new LongEntityID(1), moverPos, (byte) 10);
        system.getPhysicsProperties(new LongEntityID(1)).setVelocity(new Vector3f(1000, 0, 0));

        // Zero buffer: the swept-AABB is the sole coverage mechanism (no over-reach from the buffer).
        system.setCcdSearchRadiusBuffer(0.0f);
        // Register shapes: mover and target get radius-1 spheres; fillers get radius-0.01 so they
        // don't accidentally produce CCD hits with the mover (they're 200 units off-axis).
        system.registerCollisionShape(new LongEntityID(1), new SphereShape(moverPos, 1.0f));
        for (int i = 2; i <= 110; i++) {
            system.registerCollisionShape(new LongEntityID(i),
                                          new SphereShape(new Point3f(i * 0.4f, 200, 0), 0.01f));
        }
        system.registerCollisionShape(new LongEntityID(111), new SphereShape(targetPos, 1.0f));

        var results = system.processCCDForEntity(new LongEntityID(1), 1.0f);
        assertFalse(results.isEmpty(),
                    "Swept-AABB broad phase must detect the collision near the sweep endpoint (Luciferase-7wzml.93)");
    }
}
