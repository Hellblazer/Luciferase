/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-9m31 S3: {@code GhostAlgorithm.CONSERVATIVE} is renamed to {@link GhostAlgorithm#DEEP_COVERAGE}
 * (the depth-2 BFS option), and {@link GhostAlgorithm#MINIMAL} is the default for the forest-level detector
 * (sufficient on 2:1-balanced meshes; the old depth-2 default was over-aggressive).
 *
 * @author hal.hildebrand
 */
class GhostAlgorithmDefaultTest {

    @Test
    void deepCoverageReplacesConservativeInTheEnum() {
        // Compile-time proof the rename landed; DEEP_COVERAGE exists and the old name is gone.
        assertNotNull(GhostAlgorithm.valueOf("DEEP_COVERAGE"));
        assertThrows(IllegalArgumentException.class, () -> GhostAlgorithm.valueOf("CONSERVATIVE"),
                     "CONSERVATIVE must no longer be a GhostAlgorithm constant");
    }

    @Test
    void forestLevelDetectorDefaultsToMinimal() {
        var forest = new Forest<com.hellblazer.luciferase.lucien.octree.MortonKey, LongEntityID, String>();
        var detector = new GhostBoundaryDetector<>(forest, 1.0f);
        assertEquals(GhostAlgorithm.MINIMAL, detector.getGhostAlgorithm(),
                     "forest-level detector must default to MINIMAL (Luciferase-9m31)");
    }

    @Test
    void spatialIndexCoordinatorDefaultsToMinimal() {
        // The production path: every AbstractSpatialIndex routes ghost config through GhostCoordinator, whose
        // default ghostAlgorithm changed CONSERVATIVE -> MINIMAL. setGhostType reconstructs the detector using
        // that default, so an index that never calls setGhostCreationAlgorithm gets MINIMAL.
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        assertEquals(GhostAlgorithm.MINIMAL, octree.getGhostCreationAlgorithm(),
                     "fresh spatial index defaults to MINIMAL (Luciferase-9m31)");
        octree.setGhostType(GhostType.FACES);
        assertEquals(GhostAlgorithm.MINIMAL, octree.getGhostCreationAlgorithm(),
                     "setGhostType must reconstruct the detector with the MINIMAL default");
    }
}
