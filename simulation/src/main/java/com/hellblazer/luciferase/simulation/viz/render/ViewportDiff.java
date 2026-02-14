/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import java.util.Set;

/**
 * Difference between two viewport visibility computations.
 * <p>
 * Represents what changed since the last viewport query for a client:
 * regions that became visible, regions that left the frustum, and regions
 * whose LOD level changed due to camera movement.
 *
 * @param added      Regions newly visible
 * @param removed    Regions no longer visible
 * @param lodChanged Regions with changed LOD level
 * @author hal.hildebrand
 */
public record ViewportDiff(
    Set<VisibleRegion> added,
    Set<RegionId> removed,
    Set<VisibleRegion> lodChanged
) {
    /**
     * Compact constructor with defensive copy.
     */
    public ViewportDiff {
        added = Set.copyOf(added);
        removed = Set.copyOf(removed);
        lodChanged = Set.copyOf(lodChanged);
    }

    /**
     * True if no regions changed.
     */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && lodChanged.isEmpty();
    }

    /**
     * Empty diff (no changes).
     */
    public static ViewportDiff empty() {
        return new ViewportDiff(Set.of(), Set.of(), Set.of());
    }
}
