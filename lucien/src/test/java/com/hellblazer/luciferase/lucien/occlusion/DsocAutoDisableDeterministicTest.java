/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic coverage for the DSOC auto-disable safety valve.
 *
 * <p>Luciferase-cvtaa: every prior {@code DSOCAutoDisableTest} asserted {@code isDSOCEnabled()==true}; the
 * auto-disable true-branch had ZERO coverage and {@code measureAndExecute} used {@code System.nanoTime()} directly so
 * the path was not deterministically testable. The fix injects a {@code LongSupplier} time source and exposes
 * package-private test seams ({@code setNanoTimeSource}, {@code setCountersForTest}, {@code forceZBufferActivation}).
 *
 * <p>Luciferase-z3gvs: the performance counters were {@code volatile long} with {@code ++}/{@code +=} (visibility,
 * not atomicity), so concurrent culls lost counts and biased the averages. They are now {@link AtomicLong}.
 *
 * @author hal.hildebrand
 */
class DsocAutoDisableDeterministicTest {

    private static DSOCConfiguration config() {
        return DSOCConfiguration.defaultConfig()
                                .withEnabled(true)
                                .withAutoDynamicsEnabled(true)
                                .withEnableHierarchicalOcclusion(true);
    }

    private static Frustum3D frustum() {
        return Frustum3D.createPerspective(new Point3f(0.5f, 0.5f, -1), new Point3f(0.5f, 0.5f, 0.5f),
                                           new Vector3f(0, 1, 0), (float) Math.toRadians(60.0), 1.0f, 0.1f, 10.0f);
    }

    /** The only reflection used: reach the index's private DSOC controller (the public façade hides it). */
    private static DsocController<?, ?, ?> controllerOf(AbstractSpatialIndex<?, ?, ?> index) throws Exception {
        Field f = AbstractSpatialIndex.class.getDeclaredField("dsoc");
        f.setAccessible(true);
        return (DsocController<?, ?, ?>) f.get(index);
    }

    private static long counter(DsocController<?, ?, ?> controller, String name) throws Exception {
        Field f = DsocController.class.getDeclaredField(name);
        f.setAccessible(true);
        return ((AtomicLong) f.get(controller)).get();
    }

    private static void populate(Octree<LongEntityID, String> octree, int count) {
        for (int i = 0; i < count; i++) {
            octree.insert(new LongEntityID(i), new Point3f(0.1f + (i % 8) * 0.1f, 0.1f, 0.1f), (byte) 10, "e" + i);
        }
    }

    @Test
    void injectedTimeSourceFeedsTheStandardMeasuredPath() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        var controller = controllerOf(octree);

        final long delta = 777L;
        var clock = new AtomicLong(0);
        controller.setNanoTimeSource(() -> clock.getAndAdd(delta)); // each frame reads twice -> records exactly delta

        octree.frustumCullVisible(frustum(), new Point3f(0.5f, 0.5f, -1)); // 0 entities -> standard path

        assertEquals(1, counter(controller, "standardFrameCount"), "one standard frame measured");
        assertEquals(delta, counter(controller, "standardTotalTime"),
                     "standard-path duration came from the injected time source (Luciferase-cvtaa)");
    }

    @Test
    void injectedTimeSourceFeedsTheDsocMeasuredPath() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        populate(octree, 60); // >= MIN_ENTITIES_FOR_DSOC
        var controller = controllerOf(octree);
        controller.forceZBufferActivation(); // activate the Z-buffer so the DSOC (isDSOC=true) path runs

        final long delta = 555L;
        var clock = new AtomicLong(0);
        controller.setNanoTimeSource(() -> clock.getAndAdd(delta));

        octree.frustumCullVisible(frustum(), new Point3f(0.5f, 0.5f, -1));

        assertEquals(1, counter(controller, "dsocFrameCount"), "the DSOC path ran (isDSOC=true branch)");
        assertEquals(delta, counter(controller, "dsocTotalTime"),
                     "DSOC-path duration came from the injected time source (Luciferase-cvtaa)");
    }

    @Test
    void autoDisableFiresWhenDsocOverheadExceedsThreshold() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        var controller = controllerOf(octree);
        assertTrue(octree.isDSOCEnabled(), "DSOC starts enabled");

        // 25 DSOC frames averaging 1000ns vs 25 standard averaging 100ns -> 10x overhead, far above the 1.2x (20%)
        // tolerance. Total = 50 so shouldEvaluatePerformance (every 50 frames) fires on the next cull.
        controller.setCountersForTest(25, 25 * 1000L, 25, 25 * 100L);

        octree.frustumCullVisible(frustum(), new Point3f(0.5f, 0.5f, -1));

        assertFalse(octree.isDSOCEnabled(),
                    "DSOC must auto-disable when measured overhead exceeds the 20% threshold (Luciferase-cvtaa)");
    }

    @Test
    void autoDisableDoesNotFireWithinThreshold() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        var controller = controllerOf(octree);

        // 1.1x overhead, within the 1.2x tolerance: must NOT auto-disable.
        controller.setCountersForTest(25, 25 * 110L, 25, 25 * 100L);

        octree.frustumCullVisible(frustum(), new Point3f(0.5f, 0.5f, -1));

        assertTrue(octree.isDSOCEnabled(), "DSOC must stay enabled when overhead is within tolerance");
    }

    @Test
    void concurrentDsocCullsLoseNoCounts() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        populate(octree, 60);
        var controller = controllerOf(octree);
        controller.forceZBufferActivation(); // route culls through the DSOC counters (the original bug site)

        final int threads = 4, perThread = 500;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var errors = new AtomicInteger();
        var f = frustum();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < perThread; k++) {
                        octree.frustumCullVisible(f, new Point3f(0.5f, 0.5f, -1));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "cull threads must finish");
        pool.shutdown();

        assertEquals(0, errors.get(), "concurrent culls must not error");
        long dsoc = counter(controller, "dsocFrameCount");
        long total = dsoc + counter(controller, "standardFrameCount");
        assertEquals((long) threads * perThread, total,
                     "every concurrent cull counted exactly once — no lost increments (Luciferase-z3gvs)");
        assertTrue(dsoc > 0, "the DSOC-path AtomicLong counters were exercised under concurrency");
    }
}
