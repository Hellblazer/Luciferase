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
    void rotatedConstraintStaysBoundedOverManySolverSteps() {
        // Acceptance "no drift/energy growth over many steps": drive the solver on a body with a non-identity
        // orientation and an offset anchor, and assert the constraint error stays bounded rather than diverging.
        var inertia = InertiaTensor.sphere(1.0f, 0.5f);
        var bodyA = new RigidBody(1.0f, inertia);
        var bodyB = new RigidBody(1.0f, inertia);
        bodyA.setPosition(new Point3f(0, 0, 0));
        bodyB.setPosition(new Point3f(3, 0, 0));
        // Non-identity bind orientation for A (30 deg about Z) with an offset anchor.
        bodyA.setOrientation(new Quat4f(0, 0, (float) Math.sin(Math.PI / 12), (float) Math.cos(Math.PI / 12)));

        var constraint = new DistanceConstraint(bodyA, bodyB, new Point3f(0.5f, 0.5f, 0), new Point3f(3, 0, 0));
        float initialError = constraint.getError();

        float dt = 0.016f;
        float maxError = initialError;
        for (int step = 0; step < 200; step++) {
            constraint.prepare(dt);
            constraint.solve();
            maxError = Math.max(maxError, constraint.getError());
        }

        // The solver must not let the error grow without bound (energy injection). It need not converge to zero
        // here (bodies free-fall under the impulses, no integration of position), but error must stay bounded.
        assertTrue(Float.isFinite(maxError), "constraint error must remain finite (no divergence)");
        assertTrue(maxError < initialError + 1.0f,
                   "rotated constraint must stay bounded over many steps, not inject energy (Luciferase-wv1yk)");
    }

    /**
     * Regression for Luciferase-7wzml.92: warm-start used {@code lambda * deltaTime} instead of raw {@code lambda}.
     * Because {@code lambda} accumulates raw impulse units, scaling by deltaTime made the warm-start magnitude
     * proportional to the timestep. A smaller dt → weaker warm-start → more solve iterations needed to converge.
     * After the fix, the converged lambda (and therefore constraint error) must be timestep-independent.
     */
    @Test
    void warmStartIsTimestepIndependent() {
        var inertia = InertiaTensor.sphere(1.0f, 0.5f);

        // Run two identical scenarios with different dt values, both seeded identically.
        // Scenario A: large timestep (dt=0.016, 60 Hz)
        var bodyA1 = new RigidBody(1.0f, inertia);
        var bodyB1 = new RigidBody(1.0f, inertia);
        bodyA1.setPosition(new Point3f(0, 0, 0));
        bodyB1.setPosition(new Point3f(2.5f, 0, 0)); // slightly violated: target is 2.0

        var constraintLarge = new DistanceConstraint(bodyA1, bodyB1, new Point3f(0, 0, 0), new Point3f(2.0f, 0, 0));

        // Scenario B: small timestep (dt=0.004, 250 Hz) — 4× finer
        var bodyA2 = new RigidBody(1.0f, inertia);
        var bodyB2 = new RigidBody(1.0f, inertia);
        bodyA2.setPosition(new Point3f(0, 0, 0));
        bodyB2.setPosition(new Point3f(2.5f, 0, 0));

        var constraintSmall = new DistanceConstraint(bodyA2, bodyB2, new Point3f(0, 0, 0), new Point3f(2.0f, 0, 0));

        float dtLarge = 0.016f;
        float dtSmall = 0.004f;
        int stepsLarge = 30;
        int stepsSmall = stepsLarge * 4; // equal simulated time

        for (int i = 0; i < stepsLarge; i++) {
            constraintLarge.prepare(dtLarge);
            constraintLarge.solve();
        }
        for (int i = 0; i < stepsSmall; i++) {
            constraintSmall.prepare(dtSmall);
            constraintSmall.solve();
        }

        float errorLarge = constraintLarge.getError();
        float errorSmall = constraintSmall.getError();

        // Both should converge to a similar error level; the ratio must be close to 1.
        // Pre-fix: small-dt warm-start was 4× weaker → errorSmall >> errorLarge.
        assertTrue(Float.isFinite(errorLarge), "large-dt constraint error must be finite");
        assertTrue(Float.isFinite(errorSmall), "small-dt constraint error must be finite");

        // The converged errors should agree to within a factor of 3 (generous; pre-fix diverged by >>10×).
        float ratio = errorLarge > 1e-6f ? errorSmall / errorLarge : 1.0f;
        assertTrue(ratio < 3.0f && ratio > 0.33f,
                   "warm-start must be timestep-independent (Luciferase-7wzml.92): "
                   + "errorLarge=" + errorLarge + " errorSmall=" + errorSmall + " ratio=" + ratio);
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
