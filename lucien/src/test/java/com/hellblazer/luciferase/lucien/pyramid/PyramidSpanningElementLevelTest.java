/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Element-level spanning coverage for PyramidIndex (Luciferase-401t). The pre-401t spanning registered only
 * the one element containing each cube cell's centre (cube-granular under-coverage). After 401t,
 * {@code insertWithSpanning} descends the pyramid SFC and registers the entity in EVERY leaf whose cube
 * intersects the bounds.
 *
 * @author hal.hildebrand
 */
class PyramidSpanningElementLevelTest {

    private static PyramidIndex<LongEntityID, String> spanningIndex() {
        return new PyramidIndex<>(new SequentialLongIDGenerator(), 8, PyramidKey.MAX_PYRAMID_LEVEL,
                                  EntitySpanningPolicy.withSpanning());
    }

    /**
     * The isolating regression test: bounds that fit entirely within a SINGLE cube cell still span multiple
     * pyramid-SFC elements of that cube (the two root pyramids + tets share the cube). The old centre-only
     * code registered exactly ONE element per cube, so a span &gt; 1 here can only come from element-level
     * coverage — this is the precise gap 401t fixes.
     */
    @Test
    void boundsWithinSingleCubeSpanMultipleElements() {
        var index = spanningIndex();
        var id = new LongEntityID(1L);
        byte level = 1; // cellSize = 2^20 = 1048576; the (0,0,0) cube is [0,1048576)
        // Bounds entirely inside the single (0,0,0) cube (do not cross the 1048576 grid boundary).
        var bounds = new EntityBounds(new Point3f(100_000, 100_000, 100_000),
                                      new Point3f(900_000, 900_000, 900_000));
        index.insert(id, new Point3f(500_000, 500_000, 500_000), level, "intra-cube", bounds);

        int spanCount = index.getEntitySpanCount(id);
        assertTrue(spanCount > 1, "bounds within a single cube must still span >1 element (element-level); got "
                                  + spanCount + " — old centre-only code would give exactly 1");
    }

    @Test
    void spanningBoundsRegisteredInMultipleElements() {
        var index = spanningIndex();
        var id = new LongEntityID(2L);
        byte level = 4;
        var min = new Point3f(50_000, 50_000, 2_000);
        var max = new Point3f(140_000, 140_000, 60_000);
        var bounds = new EntityBounds(min, max);
        var center = new Point3f((min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2);
        index.insert(id, center, level, "spanner", bounds);

        assertTrue(index.getEntitySpanCount(id) > 1, "a multi-element span must register in >1 element");
    }

    @Test
    void rangeQueryFindsSpanningEntityAtBothBoundEnds() {
        var index = spanningIndex();
        var id = new LongEntityID(7L);
        byte level = 4;
        var min = new Point3f(50_000, 50_000, 2_000);
        var max = new Point3f(140_000, 140_000, 60_000);
        var bounds = new EntityBounds(min, max);
        var center = new Point3f((min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2);
        index.insert(id, center, level, "spanner", bounds);

        var lowEnd = new Spatial.Cube(min.x + 100, min.y + 100, min.z + 100, 2_000);
        var highEnd = new Spatial.Cube(max.x - 2_100, max.y - 2_100, max.z - 2_100, 2_000);

        assertTrue(index.entitiesInRegion(lowEnd).contains(id), "spanning entity must be found at its low-corner");
        assertTrue(index.entitiesInRegion(highEnd).contains(id), "spanning entity must be found at its high-corner");
    }

    @Test
    void uncoveredThirdEntityIsStillRegisteredAndFindable() {
        // A point in the uncovered third (no root pyramid SHAPE owns it — the entity-460 witness region from
        // PyramidDomainCoverageTest) is still registered: the descent is cube-conservative and the leaf CUBES
        // tile the full domain, so the entity lands in the cube-touching leaf(s) (never zero nodes), and a
        // range query at its position finds it.
        var index = spanningIndex();
        var id = new LongEntityID(9L);
        byte level = 4;
        var pos = new Point3f(30_500, 5_500, 29_500);
        var bounds = new EntityBounds(new Point3f(30_000, 5_000, 29_000), new Point3f(31_000, 6_000, 30_000));
        index.insert(id, pos, level, "uncovered", bounds);

        assertTrue(index.getEntitySpanCount(id) >= 1, "uncovered-third entity must be registered (never zero nodes)");
        assertTrue(index.entitiesInRegion(new Spatial.Cube(pos.x - 200, pos.y - 200, pos.z - 200, 400)).contains(id),
                   "uncovered-third entity must be findable at its position");
    }

    @Test
    void level0BoundsDoNotCrash() {
        // Regression guard: level 0 (whole domain = one cell) must not drive the descent (coordBits[1] would
        // be out of range). It falls back to single-node insertion.
        var index = spanningIndex();
        var id = new LongEntityID(11L);
        var bounds = new EntityBounds(new Point3f(10_000, 10_000, 10_000), new Point3f(900_000, 900_000, 900_000));
        index.insert(id, new Point3f(400_000, 400_000, 400_000), (byte) 0, "level0", bounds);
        assertTrue(index.getEntitySpanCount(id) >= 1, "level-0 spanning insert must still register the entity");
    }
}
