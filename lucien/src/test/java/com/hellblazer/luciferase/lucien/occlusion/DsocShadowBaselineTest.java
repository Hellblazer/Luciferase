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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic coverage for the DSOC shadow-baseline auto-disable methodology (Luciferase-vdv4p).
 *
 * <p>Before this fix the {@code >20%} auto-disable ratio compared the average DSOC frame against the average
 * non-DSOC frame, but {@code shouldSkipDSOC} routes cheap frames (entities {@code <} MIN_ENTITIES_FOR_DSOC,
 * inactive Z-buffer) into the standard bucket and only the expensive active frames into the DSOC bucket — different
 * query populations, a structurally biased baseline. The fix re-runs the standard cull on the SAME query on a
 * sampled fraction of DSOC frames and computes the ratio from those paired same-query measurements.
 *
 * @author hal.hildebrand
 */
class DsocShadowBaselineTest {

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

    /**
     * The shadow sample runs the STANDARD path on the SAME query as the DSOC frame, so the baseline is drawn from
     * a comparable population — not the cheap skip frames. The first DSOC frame (index 0) is sampled.
     */
    @Test
    void shadowSampleTimesStandardPathOnTheSameQuery() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        populate(octree, 60); // >= MIN_ENTITIES_FOR_DSOC so DSOC runs
        var controller = controllerOf(octree);
        controller.forceZBufferActivation(); // activate the Z-buffer so the DSOC path (not skip) runs

        final long delta = 321L;
        var clock = new AtomicLong(0);
        controller.setNanoTimeSource(() -> clock.getAndAdd(delta)); // each measured span reads twice -> exactly delta

        octree.frustumCullVisible(frustum(), new Point3f(0.5f, 0.5f, -1));

        assertEquals(1, counter(controller, "dsocFrameCount"), "the DSOC path ran");
        assertEquals(1, counter(controller, "shadowSampleCount"), "the first DSOC frame was shadow-sampled");
        assertEquals(delta, counter(controller, "shadowStandardTotalTime"),
                     "the standard path was timed on the SAME query as the DSOC frame (Luciferase-vdv4p)");
        assertEquals(delta, counter(controller, "shadowDsocTotalTime"),
                     "the DSOC duration was paired with the shadow standard duration under one denominator");
    }

    /** A 3x-cost DSOC measured against its same-query standard baseline DOES auto-disable. */
    @Test
    void threeXOverheadAgainstShadowBaselineAutoDisables() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        var controller = controllerOf(octree);
        assertTrue(octree.isDSOCEnabled(), "DSOC starts enabled");

        // Old-style counters at 1.0x (NOT over threshold): the legacy formula would NOT disable here, so a passing
        // test isolates that the SHADOW ratio drives the decision (would catch a formula reversion). Total 50 still
        // triggers evaluation on the next cull.
        controller.setCountersForTest(25, 25 * 100L, 25, 25 * 100L);
        controller.setShadowCountersForTest(10, 10 * 300L, 10 * 100L); // 3x same-query overhead, 10 samples

        octree.frustumCullVisible(frustum(), new Point3f(0.5f, 0.5f, -1));

        assertFalse(octree.isDSOCEnabled(),
                    "a 3x DSOC measured against the same-query baseline must auto-disable (Luciferase-vdv4p)");
    }

    /**
     * A warmup spike (a few very expensive early frames) must NOT permanently disable: the sample floor requires
     * enough comparable measurements before the safety valve can engage.
     */
    @Test
    void warmupSpikeBelowSampleFloorDoesNotDisable() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        var controller = controllerOf(octree);

        controller.setCountersForTest(25, 25 * 1000L, 25, 25 * 100L); // trigger evaluation
        // 10x overhead but only 3 samples — below MIN_FRAMES_FOR_EVALUATION (10): a transient spike, not a trend.
        controller.setShadowCountersForTest(3, 3 * 1000L, 3 * 100L);

        octree.frustumCullVisible(frustum(), new Point3f(0.5f, 0.5f, -1));

        assertTrue(octree.isDSOCEnabled(),
                   "a warmup spike below the sample floor must not permanently disable DSOC (Luciferase-vdv4p)");
    }

    /**
     * The crux: a biased standard bucket (cheap skip frames) that would have tripped the OLD ratio must NOT disable
     * when the same-query shadow baseline shows DSOC is actually within tolerance. This is the regression the bias
     * fix targets — the decision now ignores the structurally cheaper skip-frame bucket.
     */
    @Test
    void biasedCheapSkipBucketDoesNotDisableWhenShadowBaselineIsFair() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.enableDSOC(config(), 256, 256);
        var controller = controllerOf(octree);

        // OLD biased inputs: dsoc avg 1000ns vs cheap skip-frame standard avg 100ns = 10x. The pre-fix code would
        // auto-disable here. trigger evaluation as well (total 50).
        controller.setCountersForTest(25, 25 * 1000L, 25, 25 * 100L);
        // FAIR same-query baseline: DSOC marginal cost is actually only 1.0x the standard cost of the SAME query.
        controller.setShadowCountersForTest(10, 10 * 100L, 10 * 100L);

        octree.frustumCullVisible(frustum(), new Point3f(0.5f, 0.5f, -1));

        assertTrue(octree.isDSOCEnabled(),
                   "the biased cheap-skip baseline must not drive auto-disable; the fair same-query baseline governs "
                   + "(Luciferase-vdv4p)");
    }
}
