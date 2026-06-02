/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-c1ka5: {@link GhostCoordinator} held an inert {@code this.ghostLayer} that was never
 * populated, while real ghosts (delivered via gRPC into {@link GhostBoundaryDetector}'s own layer) lived in the
 * detector. {@code getGhostLayer()}, {@code findEntitiesIncludingGhosts}, and {@code findNeighborsIncludingGhosts}
 * read the inert field, so every ghost query returned zero ghosts.
 *
 * <p>The detector's layer IS the gRPC reception target ({@code createGhostElement} is a placeholder; actual data
 * arrives asynchronously). These tests inject a ghost directly into that reception target and assert the façade
 * read paths observe it, and that the coordinator and detector share one {@link GhostLayer} instance.
 *
 * @author hal.hildebrand
 */
class GhostCoordinatorLayerUnificationTest {

    /** White-box handle on the layer the real ghost-reception path writes to (the coordinator's detector layer). */
    @SuppressWarnings("unchecked")
    private static GhostLayer<MortonKey, LongEntityID, String> detectorLayer(
            AbstractSpatialIndex<MortonKey, LongEntityID, String> index) throws Exception {
        Field ghostField = AbstractSpatialIndex.class.getDeclaredField("ghost");
        ghostField.setAccessible(true);
        Object coordinator = ghostField.get(index);
        Field detectorField = coordinator.getClass().getDeclaredField("ghostBoundaryDetector");
        detectorField.setAccessible(true);
        var detector = (GhostBoundaryDetector<MortonKey, LongEntityID, String>) detectorField.get(coordinator);
        return detector.getGhostLayer();
    }

    @Test
    void coordinatorAndDetectorShareOneGhostLayerInstance() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.setGhostType(GhostType.FACES);

        assertSame(detectorLayer(octree), octree.getGhostLayer(),
                   "façade getGhostLayer() must return the detector's live layer, not an inert split copy");
    }

    @Test
    void findEntitiesIncludingGhostsReturnsReceivedGhosts() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.setGhostType(GhostType.FACES);

        var key = new MortonKey(42L, (byte) 5);
        var ghostId = new LongEntityID(999L);
        var ghost = new GhostElement<>(key, ghostId, "ghost-content", new Point3f(1, 1, 1), 1, 0L);
        // Simulate gRPC reception: write into the detector's layer (the real reception target).
        detectorLayer(octree).addGhostElement(ghost);

        assertTrue(octree.findEntitiesIncludingGhosts(key).contains(ghostId),
                   "ghost query must read the populated (detector) layer, not the inert split copy");
    }

    @Test
    void findNeighborsIncludingGhostsReturnsReceivedGhosts() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.setGhostType(GhostType.FACES);

        var key = new MortonKey(42L, (byte) 5);
        var ghostPos = new Point3f(10, 10, 10);
        var ghost = new GhostElement<>(key, new LongEntityID(999L), "ghost-content", ghostPos, 1, 0L);
        detectorLayer(octree).addGhostElement(ghost);

        var neighbors = octree.findNeighborsIncludingGhosts(ghostPos, 5.0f);
        assertFalse(neighbors.isEmpty(), "ghost neighbor query must read the populated (detector) layer");
    }
}
