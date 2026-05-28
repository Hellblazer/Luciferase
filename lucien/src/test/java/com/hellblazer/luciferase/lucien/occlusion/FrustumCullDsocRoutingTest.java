/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for RDR-008 P4 Critical finding: the {@code frustumCullInside}, {@code frustumCullIntersecting},
 * and {@code frustumCullWithinDistance} façade derivatives must compose on top of {@code frustumCullVisible} so they
 * inherit the DSOC routing decision. An earlier P4 draft delegated each directly to {@code Culler}, silently bypassing
 * DSOC — caught by the code-review-expert pass before merge.
 *
 * <p>Each test enables DSOC, calls the derivative once, and asserts that the DSOC-controller frame counter advanced
 * (which only happens when the call reaches {@code DsocController.frustumCullVisible.measureAndExecute}, never on the
 * direct {@code Culler.frustumCullVisibleStandard} bypass path).
 *
 * @author hal.hildebrand
 */
public class FrustumCullDsocRoutingTest {

    private Octree<LongEntityID, String> octree;
    private Frustum3D                    frustum;
    private Point3f                      cameraPos;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        // Populate a handful of entities so the cull path actually touches the visibility machinery.
        for (int i = 0; i < 50; i++) {
            octree.insert(new Point3f((i % 10) * 10, ((i / 10) % 5) * 10, (i / 50) * 10), (byte) 10, "entity-" + i);
        }
        var config = DSOCConfiguration.defaultConfig()
                                      .withEnabled(true)
                                      .withEnableHierarchicalOcclusion(true);
        octree.enableDSOC(config, 256, 256);

        // Camera looking down +Z from origin, simple perspective frustum.
        var eye    = new Point3f(50, 25, -50);
        var center = new Point3f(50, 25, 50);
        var up     = new Vector3f(0, 1, 0);
        frustum   = Frustum3D.createPerspective(eye, center, up, (float) Math.toRadians(60.0), 1.0f, 1.0f, 200.0f);
        cameraPos = eye;
    }

    private long totalDsocCullCalls() {
        var stats             = octree.getDSOCStatistics();
        var dsocFrameCount    = (long) stats.getOrDefault("dsocFrameCount", 0L);
        var standardFrameCount = (long) stats.getOrDefault("standardFrameCount", 0L);
        return dsocFrameCount + standardFrameCount;
    }

    @Test
    @DisplayName("frustumCullVisible (canonical) routes through DSOC — baseline assertion")
    void frustumCullVisibleRoutesThroughDsoc() {
        assertTrue(totalDsocCullCalls() == 0, "baseline DSOC counter should be 0");
        var result = octree.frustumCullVisible(frustum, cameraPos);
        assertNotNull(result);
        assertTrue(totalDsocCullCalls() == 1, "frustumCullVisible must increment a DSOC frame counter");
    }

    @Test
    @DisplayName("frustumCullInside routes through DSOC (regression: P4 Critical)")
    void frustumCullInsideRoutesThroughDsoc() {
        assertTrue(totalDsocCullCalls() == 0, "baseline DSOC counter should be 0");
        var result = octree.frustumCullInside(frustum, cameraPos);
        assertNotNull(result);
        assertTrue(totalDsocCullCalls() == 1,
                   "frustumCullInside must compose through frustumCullVisible (DSOC counter expected 1, was "
                   + totalDsocCullCalls() + ")");
    }

    @Test
    @DisplayName("frustumCullIntersecting routes through DSOC (regression: P4 Critical)")
    void frustumCullIntersectingRoutesThroughDsoc() {
        assertTrue(totalDsocCullCalls() == 0, "baseline DSOC counter should be 0");
        var result = octree.frustumCullIntersecting(frustum, cameraPos);
        assertNotNull(result);
        assertTrue(totalDsocCullCalls() == 1,
                   "frustumCullIntersecting must compose through frustumCullVisible (DSOC counter expected 1, was "
                   + totalDsocCullCalls() + ")");
    }

    @Test
    @DisplayName("frustumCullWithinDistance routes through DSOC (regression: P4 Critical)")
    void frustumCullWithinDistanceRoutesThroughDsoc() {
        assertTrue(totalDsocCullCalls() == 0, "baseline DSOC counter should be 0");
        var result = octree.frustumCullWithinDistance(frustum, cameraPos, 1000.0f);
        assertNotNull(result);
        assertTrue(totalDsocCullCalls() == 1,
                   "frustumCullWithinDistance must compose through frustumCullVisible (DSOC counter expected 1, was "
                   + totalDsocCullCalls() + ")");
    }

    @Test
    @DisplayName("DSOC counter does NOT advance when DSOC is disabled (sanity check on instrumentation)")
    void counterAbsentWhenDsocDisabled() {
        var fresh = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        fresh.insert(new Point3f(0, 0, 0), (byte) 10, "e0");
        // No enableDSOC call.
        fresh.frustumCullVisible(frustum, cameraPos);
        var stats = fresh.getDSOCStatistics();
        assertTrue(Boolean.FALSE.equals(stats.get("dsocEnabled")), "DSOC must be disabled");
    }
}
