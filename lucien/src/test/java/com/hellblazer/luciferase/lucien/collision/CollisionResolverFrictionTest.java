/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.SpatialIndex.CollisionPair;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Luciferase-fyb22: {@link CollisionResolver#applyFriction} reused the already-scaled friction vector for the
 * second body ({@code scale(mass2/mass1)} does not undo {@code scale(-1/mass1)}), producing
 * {@code -frictionImpulse/mass1^2} on body 2 instead of {@code frictionImpulse/mass2}. The error is invisible when
 * {@code mass1 == mass2} and grows with the mass ratio.
 *
 * <p>Internal collision impulses must conserve momentum: {@code mass1*impulse1 + mass2*impulse2 == 0}. The normal
 * impulse already satisfies this; the friction bug breaks it for unequal masses with tangential relative velocity.
 *
 * @author hal.hildebrand
 */
class CollisionResolverFrictionTest {

    private static CollisionPair<LongEntityID, String> pairWithNormal(Vector3f normal) {
        var b1 = new EntityBounds(new Point3f(0, 0, 0), 0.5f);
        var b2 = new EntityBounds(new Point3f(0, 1, 0), 0.5f);
        return new CollisionPair<>(new LongEntityID(1), "a", b1, new LongEntityID(2), "b", b2,
                                   new Point3f(0, 0.5f, 0), normal, 0.0f, java.util.List.of());
    }

    private static float momentumResidual(float mass1, float mass2, Vector3f impulse1, Vector3f impulse2) {
        var p = new Vector3f();
        p.scaleAdd(mass1, impulse1, p); // p = mass1*impulse1
        p.scaleAdd(mass2, impulse2, p); // p = mass1*impulse1 + mass2*impulse2
        return p.length();
    }

    @Test
    void frictionConservesMomentumForUnequalMasses() {
        var resolver = new CollisionResolver();
        var pair = pairWithNormal(new Vector3f(0, 1, 0));

        float mass1 = 1.0f, mass2 = 10.0f; // 10:1 ratio amplifies the double-scale error
        var v1 = new Vector3f(0, 0, 0);
        var v2 = new Vector3f(5, -1, 0);   // tangential (x) component drives friction; -y approaches the contact

        var resp = resolver.resolveCollision(pair, v1, v2, mass1, mass2);

        assertEquals(0.0f, momentumResidual(mass1, mass2, resp.impulse1(), resp.impulse2()), 1e-3f,
                     "friction impulses must conserve momentum for unequal masses (Luciferase-fyb22)");
    }

    @Test
    void frictionConservesMomentumForEqualMasses() {
        // The double-scale also broke equal-mass momentum (body 2 got -fI/m^2, not +fI/m); both cases must hold.
        var resolver = new CollisionResolver();
        var pair = pairWithNormal(new Vector3f(0, 1, 0));

        var v1 = new Vector3f(0, 0, 0);
        var v2 = new Vector3f(5, -1, 0);

        var resp = resolver.resolveCollision(pair, v1, v2, 2.0f, 2.0f);

        assertEquals(0.0f, momentumResidual(2.0f, 2.0f, resp.impulse1(), resp.impulse2()), 1e-3f,
                     "momentum conserved for equal masses");
    }
}
