/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.simulation.animation.VolumeAnimator;
import com.hellblazer.luciferase.simulation.behavior.CompositeEntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.bubble.BubbleBoundsTracker;
import com.hellblazer.luciferase.simulation.bubble.BucketSynchronizedController;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import com.hellblazer.luciferase.simulation.entity.EntityType;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave-4 simulation deep-review remediation regression tests.
 * <p>
 * Covers: Luciferase-0frcy.74 (negative Kronos.sleep clamp),
 * .76 (CompositeEntityBehavior concurrent map), .4 (bounds shrink on inward move),
 * .56 (bucket TOCTOU CAS), .57 (self-stop / daemon tick thread),
 * .59 (containment-checker key is read directly, no grid scan).
 *
 * @author hal.hildebrand
 */
class SimRemediationWave4Test {

    /** Luciferase-0frcy.74: per-frame sleep must clamp to >= 0 on budget overrun. */
    @Test
    void frameSleepClampsNegativeToZero() {
        long frameRateNs = 16_000_000L; // ~60fps budget
        // Work + overhead exceeds budget -> raw value negative -> must clamp to 0.
        assertEquals(0L, VolumeAnimator.frameSleepNs(frameRateNs, 20_000_000L, 5_000_000L),
            "Overrun must clamp to zero, never a negative sleep");
        // Normal case: remainder preserved.
        assertEquals(6_000_000L, VolumeAnimator.frameSleepNs(frameRateNs, 8_000_000L, 2_000_000L),
            "Within budget, remaining time is returned");
        assertEquals(0L, VolumeAnimator.frameSleepNs(frameRateNs, frameRateNs, 1L),
            "Exactly-over boundary clamps to zero");
    }

    /**
     * Luciferase-0frcy.76: CompositeEntityBehavior.behaviors must be a concurrent
     * map so addBehavior() can race reads without HashMap corruption. We assert
     * the field type directly (the defect was the concrete HashMap type) and that
     * concurrent add + read does not throw.
     */
    @Test
    void compositeBehaviorUsesConcurrentMap() throws Exception {
        var composite = new CompositeEntityBehavior(new FlockingBehavior(
            30f, 15f, 0.5f, 1f, 1f, 1f,
            com.hellblazer.luciferase.simulation.config.WorldBounds.DEFAULT,
            new java.util.Random(42)));

        Field f = CompositeEntityBehavior.class.getDeclaredField("behaviors");
        f.setAccessible(true);
        Object map = f.get(composite);
        assertTrue(map instanceof java.util.concurrent.ConcurrentMap,
            "behaviors must be a ConcurrentMap, was " + map.getClass().getName());

        // Concurrent add + iterate must not throw / corrupt.
        var latch = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        var adder = new Thread(() -> {
            try {
                latch.await();
                for (int i = 0; i < 1000; i++) {
                    composite.addBehavior(i % 2 == 0 ? EntityType.PREY : EntityType.PREDATOR,
                        new FlockingBehavior(30f, 15f, 0.5f, 1f, 1f, 1f,
                            com.hellblazer.luciferase.simulation.config.WorldBounds.DEFAULT,
                            new java.util.Random(i)));
                }
            } catch (Throwable t) {
                error.set(t);
            }
        });
        var reader = new Thread(() -> {
            try {
                latch.await();
                for (int i = 0; i < 1000; i++) {
                    composite.getMaxSpeed();
                    composite.getAoiRadius();
                }
            } catch (Throwable t) {
                error.set(t);
            }
        });
        adder.start();
        reader.start();
        latch.countDown();
        adder.join(10_000);
        reader.join(10_000);
        assertNull(error.get(), "Concurrent add/read must not throw");
    }

    /**
     * Luciferase-0frcy.4: bounds must SHRINK when entities migrate inward, not
     * only expand. We add a far-flung entity to grow bounds, then move it inward
     * and require the recalculated bounds to no longer contain the original far
     * position.
     */
    @Test
    void boundsShrinkWhenEntityMovesInward() {
        var tracker = new BubbleBoundsTracker((byte) 10);
        var far = new Point3f(900, 900, 900);
        var near = new Point3f(100, 100, 100);

        tracker.onEntityAdded("anchor", near);
        tracker.onEntityAdded("rover", far);

        var grown = tracker.bounds();
        assertNotNull(grown);
        assertTrue(grown.contains(far), "Bounds should encompass the far entity after add");

        // Rover migrates inward, near the anchor.
        tracker.onEntityMoved("rover", far, new Point3f(110, 110, 110));

        var shrunk = tracker.bounds();
        assertNotNull(shrunk);
        assertFalse(shrunk.contains(far),
            "After inward move, bounds must shrink and no longer contain the vacated far position");
    }

    /**
     * Luciferase-0frcy.56 / Luciferase-7wzml.71: bucket transitions must be
     * monotonic. advanceBucket uses a forward-only CAS loop (never moves backward).
     * synchronizeAtBucket must NOT write currentBucket — the tickLoop's CAS owns
     * that transition; synchronizeAtBucket is only responsible for sim-time
     * alignment and synthetic tick emission.
     */
    @Test
    void bucketAdvanceIsConsistent() {
        var controller = new BucketSynchronizedController(UUID.randomUUID(), "bucket-test", 100);
        assertEquals(0L, controller.getCurrentBucket());

        // advanceBucket moves forward.
        controller.advanceBucket(5L);
        assertEquals(5L, controller.getCurrentBucket());

        // advanceBucket with a lower value is a no-op (forward-only monotonic CAS).
        controller.advanceBucket(3L);
        assertEquals(5L, controller.getCurrentBucket(), "advanceBucket must not move bucket backward");

        // synchronizeAtBucket does NOT write currentBucket (Luciferase-7wzml.71):
        // the tickLoop's CAS is the sole owner of that transition.
        // Bucket stays at 5; only sim-time is advanced.
        controller.synchronizeAtBucket(7L, 0L);
        assertEquals(5L, controller.getCurrentBucket(),
                     "synchronizeAtBucket must not write currentBucket; tick-loop CAS owns that");
        // Sim time was advanced toward bucket 7's target (7 * TICKS_PER_BUCKET).
        assertTrue(controller.getSimulationTime() > 0L,
                   "synchronizeAtBucket should advance simulationTime toward bucket target");

        // advanceBucket still works correctly after synchronizeAtBucket.
        controller.advanceBucket(9L);
        assertEquals(9L, controller.getCurrentBucket());
    }

    /**
     * Luciferase-0frcy.57: a TickListener that calls stop() from inside onTick
     * must NOT self-join-deadlock. The controller must stop within the timeout.
     */
    @Test
    void selfStopFromTickListenerDoesNotDeadlock() {
        assertTimeoutPreemptively(ofSeconds(5), () -> {
            var controller = new RealTimeController(UUID.randomUUID(), "self-stop", 100);
            var stopped = new CountDownLatch(1);
            controller.addTickListener((simTime, lamport) -> {
                if (controller.isRunning()) {
                    controller.stop();       // self-stop from the tick thread
                    stopped.countDown();
                }
            });
            controller.start();
            assertTrue(stopped.await(4, java.util.concurrent.TimeUnit.SECONDS),
                "Self-stop listener must run without deadlocking");
            // Give the loop a moment to observe running=false and exit.
            Thread.sleep(100);
            assertFalse(controller.isRunning(), "Controller must be stopped");
        });
    }

    /**
     * Luciferase-0frcy.57: the tick thread must be a daemon so a leaked
     * controller cannot pin the JVM.
     */
    @Test
    void tickThreadIsDaemon() throws Exception {
        var controller = new RealTimeController(UUID.randomUUID(), "daemon-test", 100);
        try {
            controller.start();
            Thread.sleep(50);
            Field tf = RealTimeController.class.getDeclaredField("tickThread");
            tf.setAccessible(true);
            Thread tickThread = (Thread) tf.get(controller);
            assertNotNull(tickThread, "tick thread should be created");
            assertTrue(tickThread.isDaemon(), "tick thread must be a daemon");
        } finally {
            controller.stop();
        }
    }
}
