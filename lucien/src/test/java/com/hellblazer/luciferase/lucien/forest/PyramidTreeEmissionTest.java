/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.pyramid.PyramidIndex;
import com.hellblazer.luciferase.lucien.pyramid.PyramidKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 §4c operational wiring (bead Luciferase-7poh): {@link AdaptiveForest#addTree} now emits a root
 * {@link ForestEvent.TreeAdded} carrying the tree's true {@link RegionShape} (via {@link RegionShape#of}),
 * so a {@code PyramidIndex} tree is announced as {@link RegionShape#PYRAMID} in a running forest — closing
 * the d3z3/uzyd SIG "pyramid is representable but never emitted" gap operationally, not just at the enum.
 */
class PyramidTreeEmissionTest {

    @Test
    void addingPyramidTreeEmitsTreeAddedWithPyramidShape() {
        var forest = new AdaptiveForest<PyramidKey, LongEntityID, String>(new SequentialLongIDGenerator());
        var events = new ArrayList<ForestEvent.TreeAdded>();
        forest.addEventListener(e -> {
            if (e instanceof ForestEvent.TreeAdded added) {
                events.add(added);
            }
        });

        var treeId = forest.addTree(new PyramidIndex<>(new SequentialLongIDGenerator()), null);

        assertEquals(1, events.size(), "addTree must emit exactly one root TreeAdded (no double emit)");
        var event = events.get(0);
        assertEquals(treeId, event.treeId());
        assertNull(event.parentId(), "a root tree's TreeAdded has no parent");
        assertEquals(RegionShape.PYRAMID, event.regionShape(),
                     "a PyramidIndex tree must be announced as PYRAMID, not mislabelled");
    }

    @Test
    void addingOctreeTreeEmitsCubicShape() {
        var forest = new AdaptiveForest<MortonKey, LongEntityID, String>(new SequentialLongIDGenerator());
        var shapes = new ArrayList<RegionShape>();
        forest.addEventListener(e -> {
            if (e instanceof ForestEvent.TreeAdded added) {
                shapes.add(added.regionShape());
            }
        });

        forest.addTree(new Octree<>(new SequentialLongIDGenerator()), null);

        assertEquals(List.of(RegionShape.CUBIC), shapes, "an Octree root tree is announced as CUBIC");
    }
}
