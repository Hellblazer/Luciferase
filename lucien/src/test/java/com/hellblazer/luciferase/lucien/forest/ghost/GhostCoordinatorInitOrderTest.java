/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Luciferase-smaik: the GhostBoundaryDetector is built lazily by whichever of setNeighborDetector / setGhostType
 * completes the pair, so detector construction is order-independent and the lazily-built detector inherits the
 * configured ghost type (and the persisted rank, now propagated in setNeighborDetector). The index constructor sets
 * the neighbor detector, so a subsequent setGhostType must yield a working ghost layer of that type.
 *
 * @author hal.hildebrand
 */
class GhostCoordinatorInitOrderTest {

    @Test
    void setGhostTypeAfterDetectorYieldsWorkingLayerOfThatType() {
        // Octree's constructor calls setNeighborDetector; the user then sets the ghost type.
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.setGhostType(GhostType.FACES);

        var layer = octree.getGhostLayer();
        assertNotNull(layer, "a detector must exist after setGhostType (Luciferase-smaik)");
        assertEquals(GhostType.FACES, layer.getGhostType(),
                     "the lazily-(re)built detector must carry the configured ghost type");
    }

    @Test
    void reconfiguringGhostTypeRebuildsDetectorWithNewType() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.setGhostType(GhostType.FACES);
        octree.setGhostType(GhostType.EDGES); // detector present -> rebuilt with the new type
        assertEquals(GhostType.EDGES, octree.getGhostLayer().getGhostType(),
                     "type reconfiguration must rebuild the detector with the new type");
    }
}
