/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import java.util.Queue;
import java.util.Set;

/**
 * Shared declaration of the bounds-query / neighbor-expansion subclass hooks used by the collision and k-NN
 * geometry seams (Luciferase-rk8hv). Declared once here and extended by both, so a signature drift between them is a
 * compile error rather than a silent divergence (the RDR-008 failure mode the extraction targets).
 *
 * @param <Key> the spatial key type
 * @author hal.hildebrand
 */
public interface NodeBoundsQueryGeometry<Key extends SpatialKey<Key>> {

    /** Spatial keys of nodes potentially intersecting the given volume bounds (subclass-specific spatial query). */
    Set<Key> findNodesIntersectingBounds(VolumeBounds bounds);

    /**
     * Expand the neighbor frontier from the given node into {@code toVisit}, tracking visited keys in
     * {@code visitedNodes} (subclass-specific topology).
     */
    void addNeighboringNodes(Key nodeIndex, Queue<Key> toVisit, Set<Key> visitedNodes);
}
