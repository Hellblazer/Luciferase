/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision.physics.constraints;

import com.hellblazer.luciferase.lucien.collision.physics.InertiaTensor;
import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Quat4f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-wv1yk: {@link DistanceConstraint} stored anchors as a pure world-space translation
 * (worldAnchor - bodyPosition) and reconstructed them as position + offset, never reading the body orientation. An
 * offset anchor on a rotating body therefore stayed put instead of swinging with the body — silent drift / energy
 * injection. The fix stores anchors in the body-local frame and rotates them by the current orientation.
 *
 * @author hal.hildebrand
 */
class DistanceConstraintOrientationTest {

    private static final float SQRT29 = (float) Math.sqrt(29.0); // |(2,0,0) - (0,5,0)|

    @Test
    void offsetAnchorSwingsWithBodyRotation() {
        var inertia = InertiaTensor.sphere(1.0f, 0.5f);
        var bodyA = new RigidBody(1.0f, inertia);
        var bodyB = new RigidBody(1.0f, inertia);
        bodyA.setPosition(new Point3f(0, 0, 0));
        bodyB.setPosition(new Point3f(0, 5, 0));

        // Anchor A is offset (2,0,0) from A's centre; anchor B sits on B's centre. Target distance = sqrt(29).
        var constraint = new DistanceConstraint(bodyA, bodyB, new Point3f(2, 0, 0), new Point3f(0, 5, 0));
        assertEquals(0.0f, constraint.getError(), 1e-3f, "constraint starts satisfied at the bind configuration");

        // Rotate body A by +90 degrees about Z. The rigidly-attached anchor must swing (2,0,0) -> (0,2,0), so the
        // world anchor moves to (0,2,0) and the anchor separation becomes |(0,2,0) - (0,5,0)| = 3.
        bodyA.setOrientation(new Quat4f(0, 0, (float) Math.sin(Math.PI / 4), (float) Math.cos(Math.PI / 4)));

        float expectedError = Math.abs(3.0f - SQRT29); // ~2.385
        assertEquals(expectedError, constraint.getError(), 0.02f,
                     "rotating the body must swing the offset anchor (Luciferase-wv1yk); pre-fix error stayed 0");
        // Guard against the pre-fix behavior explicitly: the anchor did NOT move, error would be ~0.
        assertTrue(constraint.getError() > 1.0f, "offset anchor must track body rotation, not stay translationally fixed");
    }

    @Test
    void identityOrientationLeavesAnchorsUnchanged() {
        var inertia = InertiaTensor.sphere(1.0f, 0.5f);
        var bodyA = new RigidBody(1.0f, inertia);
        var bodyB = new RigidBody(1.0f, inertia);
        bodyA.setPosition(new Point3f(-1, 0, 0));
        bodyB.setPosition(new Point3f(1, 0, 0));

        var constraint = new DistanceConstraint(bodyA, bodyB, new Point3f(-1, 0, 0), new Point3f(1, 0, 0));
        assertEquals(0.0f, constraint.getError(), 1e-3f);

        // Pure translation with identity orientation behaves exactly as before.
        bodyA.setPosition(new Point3f(-2, 0, 0));
        bodyB.setPosition(new Point3f(2, 0, 0));
        assertEquals(2.0f, constraint.getError(), 0.05f, "identity-orientation translation unchanged from prior behavior");
    }
}
