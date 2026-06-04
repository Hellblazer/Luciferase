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
 * <p>Luciferase-7wzml.94: fixed-32-step scan tunnels when the collision window is narrower than motionLength/32.
 * Adaptive step count (scaled to geometry, capped) closes this gap.
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

    /**
     * Luciferase-7wzml.94 regression: thin shape sweeping fast → collision window falls STRICTLY between two
     * adjacent fixed-32 scan samples → tunnels at 32 steps; adaptive steps (≥ ceil(L/r) = 250) must catch it.
     *
     * <p>Geometry (verified analytically):
     * <ul>
     *   <li>Sweeper radius r=0.04, sweeps 10 units from x=-0.04 to x=9.96.</li>
     *   <li>Static target radius r=0.04 at x=0.3625.</li>
     *   <li>Contact window: t ∈ [0.03225, 0.04025], width=0.008.</li>
     *   <li>Fixed-32 sample nearest to window: step 1 at t=0.03125 (below) and step 2 at t=0.0625 (above) → no
     *       sample hits the window → tunnels. Adaptive steps (250) sample at 1/250=0.004 spacing → step 9 at
     *       t=0.036 falls inside the window → collision detected.</li>
     * </ul>
     */
    @Test
    void thinShapeFastSweepTunnelsCaughtByAdaptiveSteps() {
        float r = 0.04f;
        // Sweeper: x from -r to 10.0-r (sweeps 10 units).
        // Target: at x=0.3625. Window: t in [0.03225, 0.04025] — no i/32 in that interval.
        var staticTarget = staticSphere(new Point3f(0.3625f, 0, 0), r);
        var sweeper = movingSphere(new Point3f(-r, 0, 0), new Point3f(10.0f - r, 0, 0), r);

        var result = ContinuousCollisionDetector.conservativeCCD(sweeper, staticTarget);

        assertTrue(result.collides(),
                   "thin + fast sweep (window between samples 1 and 2 of 32): adaptive steps must detect it (Luciferase-7wzml.94)");
        float toi = result.timeOfImpact();
        // First contact at t_enter=0.03225; bisection should land somewhere in [0.03225, 0.04025].
        assertTrue(toi >= 0.032f && toi <= 0.041f,
                   "time of impact should be in the contact window [0.03225,0.04025], got " + toi);
    }
}
