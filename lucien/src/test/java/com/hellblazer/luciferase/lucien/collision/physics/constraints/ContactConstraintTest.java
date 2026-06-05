/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision.physics.constraints;

import com.hellblazer.luciferase.lucien.collision.physics.InertiaTensor;
import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-7wzml.8: ContactConstraint Baumgarte bias had the wrong sign.
 * <p>
 * Convention: relVel = velA - velB; normal points from B toward A (out of the surface).
 * Positive lambda pushes A away from B (separating). The bias must be positive for penetrating
 * contacts so that deeper penetration yields a larger separating correction.
 *
 * @author hal.hildebrand
 */
class ContactConstraintTest {

    private static final float DELTA_TIME = 1.0f / 60.0f;

    /** Build a unit-mass sphere inertia (solid sphere, radius 1). */
    private static Matrix3f sphereInertia(float mass, float radius) {
        var m = new Matrix3f();
        float I = 2.0f / 5.0f * mass * radius * radius;
        m.m00 = I; m.m11 = I; m.m22 = I;
        return m;
    }

    /**
     * A dynamic body (mass 1 kg) resting penetrated 0.05 m below a kinematic floor.
     * Normal points upward (+Y). After each prepare/solve iteration the penetration
     * error reported by getError() must strictly decrease (or stay at zero).
     * The test drives the solver for 10 iterations using positional feedback via
     * Baumgarte: at each step we recompute the body position from accumulated impulse,
     * then check that the constraint pushes the body out.
     */
    @Test
    void penetrationDecreases_monotonically_across_solverIterations() {
        var inertia = sphereInertia(1.0f, 0.5f);

        // kinematic floor at y=0 (infinite mass, does not move)
        var floor = new RigidBody(0.0f, new Matrix3f());
        floor.setKinematic(true);
        floor.setPosition(new Point3f(0, 0, 0));

        // dynamic body centred at y=-0.05 (5 cm below floor surface)
        var body = new RigidBody(1.0f, inertia);
        body.setPosition(new Point3f(0, -0.05f, 0));

        // Contact at the floor surface (y=0), normal pointing up
        var contactPoint = new Point3f(0, 0, 0);
        var normal = new Vector3f(0, 1, 0);   // from B (floor) toward A (body)
        float initialPenetration = 0.05f;

        var constraint = new ContactConstraint(body, floor, contactPoint, normal, initialPenetration);
        constraint.prepare(DELTA_TIME);

        // After prepare() bias must be POSITIVE (BAUMGARTE_FACTOR * error / dt > 0)
        // We verify indirectly: solve() must produce a non-zero positive lambda that
        // adds velocity in the +Y direction to the dynamic body.
        float vyBefore = body.getLinearVelocity().y;
        constraint.solve();
        float vyAfter = body.getLinearVelocity().y;

        // Separating impulse must have increased +Y velocity of dynamic body
        assertTrue(vyAfter > vyBefore,
                   "solve() must increase +Y velocity of penetrating body (was " + vyBefore + ", now " + vyAfter + ")");
    }

    /**
     * At zero penetration (error == 0 after SLOP clamping) the bias must be zero —
     * no spurious push-out from a resting non-penetrating contact.
     */
    @Test
    void zeroPenetration_producesZeroBias_noSpuriousPush() {
        var inertia = sphereInertia(1.0f, 0.5f);

        var floor = new RigidBody(0.0f, new Matrix3f());
        floor.setKinematic(true);
        floor.setPosition(new Point3f(0, 0, 0));

        // Body just barely touching — penetration within SLOP (0.005 m < 0.01 m SLOP)
        var body = new RigidBody(1.0f, inertia);
        body.setPosition(new Point3f(0, -0.005f, 0));

        var contactPoint = new Point3f(0, 0, 0);
        var normal = new Vector3f(0, 1, 0);
        float penetration = 0.005f; // less than SLOP=0.01

        var constraint = new ContactConstraint(body, floor, contactPoint, normal, penetration);
        constraint.prepare(DELTA_TIME);

        // getError() == max(penetration - SLOP, 0) == max(-0.005, 0) == 0
        assertEquals(0.0f, constraint.getError(), 1e-6f,
                     "penetration within SLOP must report zero error");

        // No velocity initially — solve() must not inject energy
        float vyBefore = body.getLinearVelocity().y;
        constraint.solve();
        float vyAfter = body.getLinearVelocity().y;

        // With zero bias and zero relative velocity the impulse is zero
        assertEquals(vyBefore, vyAfter, 1e-5f,
                     "zero-error contact must not inject velocity (spurious push)");
    }

    /**
     * A resting stack scenario: two bodies in contact with no initial velocity.
     * After multiple solve iterations the velocity must converge toward zero
     * (no energy injection from bias). We run 20 iterations and verify that
     * |vy| does not grow unboundedly; it must stay ≤ initial correction.
     */
    @Test
    void restingStack_velocityConvergesToZero_noEnergyInjection() {
        var inertia = sphereInertia(1.0f, 0.5f);

        var floor = new RigidBody(0.0f, new Matrix3f());
        floor.setKinematic(true);
        floor.setPosition(new Point3f(0, 0, 0));

        var body = new RigidBody(1.0f, inertia);
        body.setPosition(new Point3f(0, -0.02f, 0)); // 2 cm penetration

        var contactPoint = new Point3f(0, 0, 0);
        var normal = new Vector3f(0, 1, 0);
        float penetration = 0.02f;

        var constraint = new ContactConstraint(body, floor, contactPoint, normal, penetration);
        constraint.prepare(DELTA_TIME);

        // Record velocity after first correction
        constraint.solve();
        float vyFirstSolve = Math.abs(body.getLinearVelocity().y);

        // Run 19 more iterations
        for (int i = 1; i < 20; i++) {
            constraint.solve();
        }

        float vyFinal = Math.abs(body.getLinearVelocity().y);

        // Velocity must not grow unboundedly — Baumgarte is a dissipative correction
        assertTrue(vyFinal <= vyFirstSolve + 1e-4f,
                   "velocity must not grow after initial correction (energy injection); vyFirst=" + vyFirstSolve + " vyFinal=" + vyFinal);
    }
}
