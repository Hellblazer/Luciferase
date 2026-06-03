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

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-zz8xp: the spatial-index collision path (findAllCollisions -> checkShapeCollision) previously mapped
 * a {@code CollisionShape.CollisionResult} into a {@code CollisionPair} that had no manifold field, silently
 * dropping the narrow-phase contact manifold. The manifold is now a {@code CollisionPair} component and
 * checkShapeCollision propagates it. This test drives two face-to-face oriented boxes through the index-level
 * findAllCollisions and asserts the resulting pair carries the multi-point manifold.
 *
 * @author hal.hildebrand
 */
class ManifoldPropagationTest {

    private static OrientedBoxShape obb(Point3f center, float h) {
        var id = new Matrix3f();
        id.setIdentity();
        return new OrientedBoxShape(center, new Vector3f(h, h, h), id);
    }

    @Test
    void findAllCollisionsCarriesTheContactManifold() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator(), 10, (byte) 10);

        var p1 = new Point3f(100, 100, 100);
        var p2 = new Point3f(101.5f, 100, 100);   // face-to-face overlap on X
        var id1 = octree.insert(p1, (byte) 10, "a");
        var id2 = octree.insert(p2, (byte) 10, "b");
        octree.setCollisionShape(id1, obb(p1, 1));
        octree.setCollisionShape(id2, obb(p2, 1));

        // Sanity: the shapes themselves produce a 4-point face-face manifold.
        var direct = octree.getCollisionShape(id1).collidesWith(octree.getCollisionShape(id2));
        assertTrue(direct.collides, "the two boxes must overlap");
        assertEquals(4, direct.contactManifold.size(), "face-face contact should yield a 4-point manifold");

        var pairs = octree.findAllCollisions();
        var pair = pairs.stream().filter(p -> p.involves(id1) && p.involves(id2)).findFirst().orElseThrow(
            () -> new AssertionError("expected a collision pair between the two overlapping boxes"));

        assertFalse(pair.contactManifold().isEmpty(),
                    "CollisionPair must carry the narrow-phase contact manifold, not drop it (Luciferase-zz8xp)");
        assertEquals(direct.contactManifold.size(), pair.contactManifold().size(),
                     "propagated manifold must match the narrow-phase result");
    }
}
