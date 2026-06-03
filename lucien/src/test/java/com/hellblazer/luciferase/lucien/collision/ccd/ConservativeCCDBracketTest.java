/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision.ccd;

import com.hellblazer.luciferase.lucien.collision.SphereShape;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-fglgp: the old {@code conservativeCCD} blindly bisected [0,1], so a through-collision whose window
 * excluded the first midpoint (t=0.5) was never found, and iteration exhaustion returned {@code noCollision()} even
 * when a hit had been bracketed. The corrected version scans for the first colliding sample, then bisects the
 * bracket and always returns the bracketed collision.
 *
 * @author hal.hildebrand
 */
class ConservativeCCDBracketTest {

    private static MovingShape staticSphere(Point3f at, float radius) {
        return new MovingShape(new SphereShape(at, radius), at, at, 0.0f, 1.0f);
    }

    private static MovingShape movingSphere(Point3f from, Point3f to, float radius) {
        return new MovingShape(new SphereShape(from, radius), from, to, 0.0f, 1.0f);
    }

    @Test
    void detectsEarlyThroughCollisionWhoseWindowExcludesMidpoint() {
        // Static unit sphere at the origin; a sphere sweeps fast along +x, overlapping only early (t ~ 0.018..0.196).
        // The window excludes t=0.5, so the old half-interval bisection (which only narrows the half containing 0.5)
        // never looked there and reported no collision.
        var s1 = staticSphere(new Point3f(0, 0, 0), 0.5f);
        var s2 = movingSphere(new Point3f(-1.2f, 0, 0), new Point3f(10, 0, 0), 0.5f);

        var result = ContinuousCollisionDetector.conservativeCCD(s1, s2);

        assertTrue(result.collides(), "an early through-collision must be detected (Luciferase-fglgp)");
        assertTrue(result.timeOfImpact() >= 0.0f && result.timeOfImpact() < 0.25f,
                   "time of impact should land in the early overlap window, got " + result.timeOfImpact());
    }

    @Test
    void reportsNoCollisionWhenPathsNeverOverlap() {
        // Sweep offset in +y by more than the combined radius: genuinely no contact.
        var s1 = staticSphere(new Point3f(0, 0, 0), 0.5f);
        var s2 = movingSphere(new Point3f(-1.2f, 5, 0), new Point3f(10, 5, 0), 0.5f);

        var result = ContinuousCollisionDetector.conservativeCCD(s1, s2);

        assertFalse(result.collides(), "non-overlapping sweeps must not report a collision");
    }
}
