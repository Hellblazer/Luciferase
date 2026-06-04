/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.behavior;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.entity.EntityType;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-3 behavior-package remediation beads:
 * Luciferase-0frcy.1 (CompositeEntityBehavior O(n^2) per-tick scan),
 * .77 (PackHuntingBehavior 2D-XZ flank rotation wrong in 3D),
 * .78 (PredatorBehavior pursuitSpeed applied to wander velocity).
 */
class BehaviorRemediationWave3Test {

    // ---- Luciferase-0frcy.1: composite dispatch is O(N) per tick, not O(N^2) ----

    /**
     * A trivial behavior that returns zero velocity and records dispatch.
     */
    private static final class CountingBehavior implements EntityBehavior {
        @Override
        public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                        EnhancedBubble bubble, float deltaTime) {
            return new Vector3f();
        }

        @Override
        public float getAoiRadius() {
            return 10f;
        }

        @Override
        public float getMaxSpeed() {
            return 5f;
        }
    }

    @Test
    void compositeDispatchPerformsOneSnapshotScanPerTick() {
        var composite = new CompositeEntityBehavior(new CountingBehavior());
        composite.addBehavior(EntityType.PREY, new CountingBehavior());

        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);
        int n = 40;
        for (int i = 0; i < n; i++) {
            bubble.addEntity("e" + i, new Point3f(i * 1.0f, 0f, 0f), EntityType.PREY);
        }

        long scansBefore = composite.snapshotScanCount;

        // One full tick: dispatch every entity exactly once.
        for (int i = 0; i < n; i++) {
            composite.computeVelocity("e" + i, new Point3f(i * 1.0f, 0f, 0f),
                                      new Vector3f(), bubble, 0.016f);
        }

        long scans = composite.snapshotScanCount - scansBefore;
        // A correct O(N) tick rebuilds the cache exactly once. The pre-fix code
        // scanned the full record list on EVERY call (N scans => O(N^2)).
        assertEquals(1, scans,
                     "per-tick dispatch must scan the entity snapshot once (O(N)), not once-per-entity (O(N^2))");
    }

    @Test
    void compositeStillDispatchesByType() {
        var defaultBehavior = new CountingBehavior();
        var preyBehavior = new CountingBehavior();
        var composite = new CompositeEntityBehavior(defaultBehavior);
        composite.addBehavior(EntityType.PREY, preyBehavior);

        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);
        bubble.addEntity("prey", new Point3f(1f, 1f, 1f), EntityType.PREY);
        bubble.addEntity("untyped", new Point3f(2f, 2f, 2f), "plain-content");

        // Resolves without error for both typed and untyped content.
        assertNotNull(composite.computeVelocity("prey", new Point3f(1f, 1f, 1f),
                                                new Vector3f(), bubble, 0.016f));
        assertNotNull(composite.computeVelocity("untyped", new Point3f(2f, 2f, 2f),
                                                new Vector3f(), bubble, 0.016f));
    }

    // ---- Luciferase-0frcy.77: flank direction perpendicular in 3D ----

    @Test
    void flankDirectionIsPerpendicularComponentEvenForVerticalAxis() {
        // A vertical pursuit axis is the case the old XZ-only rotation got wrong
        // (it left Y unchanged, so the rotated vector was NOT perpendicular).
        var axis = new Vector3f(0f, 1f, 0f);
        float angle = (float) Math.toRadians(90.0);

        var left = PackHuntingBehavior.flankDirection(axis, true, angle);
        var right = PackHuntingBehavior.flankDirection(axis, false, angle);

        // At 90 degrees the flank direction must be fully orthogonal to the axis.
        assertEquals(0f, left.dot(axis), 1e-5f, "90-degree flank must be orthogonal to a vertical axis");
        assertEquals(0f, right.dot(axis), 1e-5f, "90-degree flank must be orthogonal to a vertical axis");
        assertEquals(1f, left.length(), 1e-5f, "flank direction must be a unit vector");

        // Left and right flankers must point to opposite sides.
        assertTrue(left.dot(right) < 0f, "left/right flankers must straddle the axis");
    }

    @Test
    void flankDirectionOrthogonalForArbitrary3dAxis() {
        var axis = new Vector3f(0.3f, 0.8f, -0.5f);
        axis.normalize();
        float angle = (float) Math.toRadians(90.0);

        var dir = PackHuntingBehavior.flankDirection(axis, true, angle);
        assertEquals(0f, dir.dot(axis), 1e-5f,
                     "90-degree flank must be perpendicular to an arbitrary 3D pursuit axis");
    }

    // ---- Luciferase-0frcy.78: no burst speed while wandering ----

    @Test
    void predatorDoesNotWanderAtPursuitSpeedWhenPreyOutsideChaseRange() {
        float aoi = 100f;
        float maxSpeed = 5f;
        float pursuitSpeed = 20f;
        var random = new Random(42L);
        var predator = new PredatorBehavior(aoi, maxSpeed, pursuitSpeed, 1.0f,
                                            WorldBounds.DEFAULT, random);

        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);
        // Predator at origin; prey present in k-NN but FAR outside chaseRange
        // (chaseRange = aoi * CHASE_RANGE_FACTOR < aoi). Place prey near the AOI
        // edge so it is returned by kNN but is not within chase distance.
        var predatorPos = new Point3f(50f, 50f, 50f);
        bubble.addEntity("pred", predatorPos, EntityType.PREDATOR);
        bubble.addEntity("prey", new Point3f(50f, 50f, 50f + (aoi * 0.95f)), EntityType.PREY);

        var result = predator.computeVelocity("pred", predatorPos, new Vector3f(1f, 0f, 0f),
                                              bubble, 0.016f);

        // With prey present-but-out-of-range the predator wanders; speed must be
        // capped at maxSpeed, NOT pursuitSpeed. Pre-fix it was capped at
        // pursuitSpeed (burst-speed wandering).
        assertTrue(result.length() <= maxSpeed + 1e-3f,
                   "wander velocity must be capped at maxSpeed (" + maxSpeed + "), got " + result.length());
    }
}
