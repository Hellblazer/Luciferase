/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision.ccd;

import com.hellblazer.luciferase.lucien.collision.BoxShape;
import com.hellblazer.luciferase.lucien.collision.ConvexHullShape;
import com.hellblazer.luciferase.lucien.collision.SphereShape;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions for two P1-CRITICAL CCD bugs.
 *
 * <p>Luciferase-v2na8: conservativeCCD translated the LIVE shapes owned by MovingShape and restored them afterward,
 * but the early-return on convergence skipped the restore — leaving the simulation's geometry permanently displaced
 * (and racing under concurrent CCD). The fix tests independent copies positioned at the sampled time.
 *
 * <p>Luciferase-gojpy: rayVsMovingSphereCCD sampled 10 discrete times; a sphere smaller than motion_length/10 passed
 * through the ray between samples. The fix solves the ray-vs-swept-sphere distance quadratic in closed form.
 *
 * @author hal.hildebrand
 */
class CcdCriticalFixesTest {

    @Test
    void conservativeCcdDoesNotMutateLiveShapes() {
        // Box vs box routes through conservativeCCD. The boxes overlap mid-sweep, so the convergence early-return
        // (the path that skipped the restore pre-fix) fires.
        var box1 = new BoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1));
        var box2 = new BoxShape(new Point3f(2.5f, 0, 0), new Vector3f(1, 1, 1));

        var moving1 = new MovingShape(box1, new Point3f(0, 0, 0), new Point3f(5, 0, 0), 0, 1);
        var moving2 = new MovingShape(box2, new Point3f(2.5f, 0, 0), new Point3f(2.5f, 0, 0), 0, 1);

        var before1 = new Point3f(box1.getPosition());
        var before2 = new Point3f(box2.getPosition());

        var result = ContinuousCollisionDetector.detectCollision(moving1, moving2);
        assertTrue(result.collides(), "sweeping box must collide with the static box in its path");

        assertEquals(before1, box1.getPosition(), "conservativeCCD must not mutate the live shape (Luciferase-v2na8)");
        assertEquals(before2, box2.getPosition(), "conservativeCCD must not mutate the live shape (Luciferase-v2na8)");
    }

    @Test
    void rayVsMovingSphereDetectsHitThatDiscreteSamplingTunnels() {
        // Ray along +x at the origin. A small sphere (r=0.2) sweeps across the ray in y, crossing within the radius
        // window only for t in ~[0.53, 0.57] — strictly between the 10-sample grid points (0.5 -> y=-0.5,
        // 0.6 -> y=+0.5), so the old sampler tunnelled.
        var sphere = new SphereShape(new Point3f(10, -5.5f, 0), 0.2f);
        var moving = new MovingShape(sphere, new Point3f(10, -5.5f, 0), new Point3f(10, 4.5f, 0), 0, 1);

        var result = ContinuousCollisionDetector.rayVsMovingSphereCCD(
            new Point3f(0, 0, 0), new Vector3f(1, 0, 0), 20.0f, moving);

        assertTrue(result.collides(), "closed-form CCD must detect the hit the discrete sampler tunnels (Luciferase-gojpy)");
        assertTrue(result.timeOfImpact() > 0.5f && result.timeOfImpact() < 0.6f,
                   "time of impact must fall between the sampling grid points");
    }

    @Test
    void convexHullCopyDoesNotDoubleOffsetPositionAndIsIndependent() {
        // The ConvexHullShape constructor adds position to its input vertices; copy() must back-transform to local
        // first or it double-applies the offset (review CRITICAL). Verify the copy is geometrically identical and
        // that mutating the copy does not perturb the original (the property conservativeCCD relies on).
        var cube = List.of(
            new Point3f(-1, -1, -1), new Point3f(1, -1, -1), new Point3f(1, 1, -1), new Point3f(-1, 1, -1),
            new Point3f(-1, -1, 1), new Point3f(1, -1, 1), new Point3f(1, 1, 1), new Point3f(-1, 1, 1));
        var hull = new ConvexHullShape(new Point3f(5, 0, 0), cube);
        var copy = hull.copy();

        var origAabb = hull.getAABB();
        var copyAabb = copy.getAABB();
        assertEquals(origAabb.getMinX(), copyAabb.getMinX(), 1e-4f, "copy must not double-apply the position offset");
        assertEquals(origAabb.getMaxX(), copyAabb.getMaxX(), 1e-4f, "copy must not double-apply the position offset");

        // Mutate the copy; the original must be untouched.
        float beforeMax = hull.getAABB().getMaxX();
        copy.translate(new Vector3f(100, 0, 0));
        assertEquals(beforeMax, hull.getAABB().getMaxX(), 1e-4f, "translating the copy must not move the original");
    }

    @Test
    void conservativeCcdIsConcurrencySafeOnSharedShapes() throws InterruptedException {
        // Many threads run CCD on MovingShapes wrapping the SAME shared shapes; the copy-based fix means no thread
        // mutates the live geometry, so every thread sees a consistent verdict and the shapes are untouched after.
        var box1 = new BoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1));
        var box2 = new BoxShape(new Point3f(2.5f, 0, 0), new Vector3f(1, 1, 1));
        var before1 = new Point3f(box1.getPosition());
        var before2 = new Point3f(box2.getPosition());

        int threads = 8, iterations = 200;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var verdicts = new ConcurrentLinkedQueue<Boolean>();
        var start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < iterations; k++) {
                        var m1 = new MovingShape(box1, new Point3f(0, 0, 0), new Point3f(5, 0, 0), 0, 1);
                        var m2 = new MovingShape(box2, new Point3f(2.5f, 0, 0), new Point3f(2.5f, 0, 0), 0, 1);
                        verdicts.add(ContinuousCollisionDetector.detectCollision(m1, m2).collides());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS), "CCD threads must finish");

        assertEquals(threads * iterations, verdicts.size(), "every CCD call produced a verdict");
        assertTrue(verdicts.stream().allMatch(Boolean::booleanValue),
                   "all concurrent CCD calls agree (no torn shared-shape state)");
        assertEquals(before1, box1.getPosition(), "shared shape must be untouched after concurrent CCD");
        assertEquals(before2, box2.getPosition(), "shared shape must be untouched after concurrent CCD");
    }

    @Test
    void rayVsMovingSphereMissesWhenSphereNeverReachesRay() {
        // Sphere stays well off the ray — no false positive from the closed form.
        var sphere = new SphereShape(new Point3f(10, -5, 0), 0.2f);
        var moving = new MovingShape(sphere, new Point3f(10, -5, 0), new Point3f(10, -3, 0), 0, 1);

        var result = ContinuousCollisionDetector.rayVsMovingSphereCCD(
            new Point3f(0, 0, 0), new Vector3f(1, 0, 0), 20.0f, moving);

        assertFalse(result.collides(), "no hit when the swept sphere never comes within the radius of the ray");
    }
}
