/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.prism.Prism;
import com.hellblazer.luciferase.lucien.pyramid.PyramidIndex;
import com.hellblazer.luciferase.lucien.sfc.SFCArrayIndex;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 §4c (bead Luciferase-uzyd): {@link RegionShape} gains PYRAMID (and PRISM) so forest events can
 * represent a pyramid tree's true shape — previously the enum had only {CUBIC, TETRAHEDRAL}, forcing a
 * PyramidIndex tree to be mislabelled (d3z3 substantive-critic SIG-4). {@link RegionShape#of} classifies
 * a spatial index to its shape.
 */
class RegionShapeTest {

    @Test
    void pyramidAndPrismVariantsExist() {
        // Enum carries all four peer shapes (the model gap fix).
        assertNotNull(RegionShape.valueOf("PYRAMID"));
        assertNotNull(RegionShape.valueOf("PRISM"));
        assertEquals(4, RegionShape.values().length, "CUBIC, TETRAHEDRAL, PYRAMID, PRISM");
    }

    @Test
    void classifierMapsEachIndexToItsShape() {
        var gen = new SequentialLongIDGenerator();
        assertEquals(RegionShape.CUBIC, RegionShape.of(new Octree<LongEntityID, String>(gen)));
        assertEquals(RegionShape.CUBIC, RegionShape.of(new SFCArrayIndex<LongEntityID, String>(gen)));
        assertEquals(RegionShape.TETRAHEDRAL, RegionShape.of(new Tetree<LongEntityID, String>(gen)));
        assertEquals(RegionShape.PYRAMID, RegionShape.of(new PyramidIndex<LongEntityID, String>(gen)),
                     "a PyramidIndex tree must classify as PYRAMID, not TETRAHEDRAL");
        assertEquals(RegionShape.PRISM, RegionShape.of(new Prism<LongEntityID, String>(gen)));
    }

    @Test
    void treeAddedEventCanCarryPyramidShape() {
        // The concrete SIG-4 fix: a ForestEvent.TreeAdded can now carry PYRAMID end-to-end.
        var event = new ForestEvent.TreeAdded(1L, "forest", "pyrTree", null, RegionShape.PYRAMID, null);
        assertEquals(RegionShape.PYRAMID, event.regionShape());
    }
}
