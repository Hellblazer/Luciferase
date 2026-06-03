/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision.ccd;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-j8iqw: {@link SweptSphere#sweptSphereVsLineSegment} collapsed the segment to a zero-radius sphere at
 * its midpoint, so a sphere grazing the segment away from the midpoint was missed (false negative). The corrected
 * version solves the perpendicular-distance quadratic clamped to the segment interval (mirrors the cylinder part of
 * {@code sweptSphereVsCapsule}).
 *
 * @author hal.hildebrand
 */
class SweptSphereSegmentTest {

    // Segment along x from origin to (10,0,0); midpoint is (5,0,0).
    private static final Point3f P1 = new Point3f(0, 0, 0);
    private static final Point3f P2 = new Point3f(10, 0, 0);

    @Test
    void detectsGrazeFarFromMidpoint() {
        // Sphere descends straight down at x=9 (far from the midpoint x=5) toward the segment. The old midpoint
        // reduction misses this entirely; the corrected geometry detects it.
        var start = new Point3f(9, 5, 0);
        var velocity = new Vector3f(0, -6, 0); // center y: 5 -> -1 over t in [0,1]
        float radius = 0.5f;

        var result = SweptSphere.sweptSphereVsLineSegment(start, velocity, radius, P1, P2);

        assertTrue(result.collides(), "graze near the segment end must be detected (Luciferase-j8iqw)");
        // Contact when the sphere bottom touches the segment: center y = radius = 0.5 -> t = (5-0.5)/6.
        assertEquals((5.0f - 0.5f) / 6.0f, result.timeOfImpact(), 1e-3f, "time of impact at the grazing contact");
    }

    @Test
    void missesWhenPassingOutsideTheSegmentSpan() {
        // Same descent but at x = 13, beyond the segment end (10) by more than the radius: no contact with the
        // finite segment. The old midpoint reduction would also miss, but for the wrong reason; assert the correct
        // negative so the clamped-interval logic is pinned.
        var start = new Point3f(13, 5, 0);
        var velocity = new Vector3f(0, -6, 0);
        float radius = 0.5f;

        var result = SweptSphere.sweptSphereVsLineSegment(start, velocity, radius, P1, P2);

        assertTrue(!result.collides(), "a descent beyond the segment span must not collide with the finite segment");
    }
}
