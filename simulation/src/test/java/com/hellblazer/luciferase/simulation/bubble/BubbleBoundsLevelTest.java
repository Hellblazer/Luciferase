/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@link BubbleBounds#fromEntityPositions(java.util.List, byte)} honors the
 * caller-provided spatial level, and that the no-arg overload uses
 * {@link SpatialLevelHeuristic#DEFAULT_SPATIAL_LEVEL}.
 * <p>
 * RDR-003 Phase 0 Step 0: callers using the no-arg form previously got hardcoded level 10
 * (cell-edge 2048 in the default 200-unit world — every entity collapsed into one cell).
 * They now get level 18 (cell-edge 8 — ~25 cells across the default world), matching the
 * r &approx; 8&middot;cell-edge target.
 */
class BubbleBoundsLevelTest {

    private static final List<Point3f> POSITIONS = List.of(
        new Point3f(50f, 50f, 50f),
        new Point3f(60f, 50f, 50f),
        new Point3f(55f, 55f, 55f)
    );

    @Test
    void fromEntityPositions_explicitLevel_isHonored() {
        for (byte level : new byte[]{8, 10, 15, 18, 21}) {
            var bounds = BubbleBounds.fromEntityPositions(POSITIONS, level);
            assertEquals(level, bounds.level(),
                "Bounds should be at the explicit level requested");
        }
    }

    @Test
    void fromEntityPositions_noArgOverload_usesDefaultSpatialLevel() {
        var bounds = BubbleBounds.fromEntityPositions(POSITIONS);
        assertEquals(SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, bounds.level(),
            "No-arg overload must use SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL (= 18 for default r=50)");
    }

    @Test
    void recalculate_preservesInstanceLevel() {
        var initial = BubbleBounds.fromEntityPositions(POSITIONS, (byte) 15);
        var recalculated = initial.recalculate(POSITIONS);
        assertEquals((byte) 15, recalculated.level(),
            "recalculate() must reuse the instance's existing level, not the default");
    }
}
