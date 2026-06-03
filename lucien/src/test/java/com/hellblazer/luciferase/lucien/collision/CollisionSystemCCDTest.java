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
}
