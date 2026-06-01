/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-9m31 S1: {@link DistributedGhostManager} owner-map unification. Setting element ownership on the
 * manager must feed the local boundary scan (the {@link GhostBoundaryDetector}'s owner map is the single
 * source of truth), not a separate manager-private map that the scan never reads.
 *
 * @author hal.hildebrand
 */
class DistributedGhostManagerOwnerUnificationTest {

    /** Minimal in-memory channel double (no transport). */
    private static final class StubChannel implements GhostChannel<MortonKey, LongEntityID, String> {
        final int rank;
        final AtomicInteger queued = new AtomicInteger();

        StubChannel(int rank) {
            this.rank = rank;
        }

        @Override public void queueGhost(int targetRank, GhostElement<MortonKey, LongEntityID, String> e) {
            queued.incrementAndGet();
        }
        @Override public CompletableFuture<Void> flushToTarget(int targetRank) { return CompletableFuture.completedFuture(null); }
        @Override public int getTotalPendingCount() { return queued.get(); }
        @Override public void clear() { queued.set(0); }
        @Override public int getCurrentRank() { return rank; }
        @Override public long getTreeId() { return 0L; }
        @Override public GhostType getGhostType() { return GhostType.FACES; }
    }

    private static Octree<LongEntityID, String> cornerOctree() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.insert(new Point3f(0, 0, 0), (byte) 5, "corner"); // absent in-domain face neighbors = ghost candidates
        return octree;
    }

    @Test
    void managerSetElementOwnerFeedsTheLocalBoundaryScan() {
        var octree = cornerOctree();
        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        var channel = new StubChannel(0); // local rank 0
        var manager = new DistributedGhostManager<>(octree, channel, detector);

        // An absent face neighbor of the corner — mark it remote via the MANAGER.
        MortonKey absentNeighbor = null;
        for (var key : octree.getSpatialKeys()) {
            for (var fn : octree.getNeighborDetector().findFaceNeighbors(key)) {
                if (!octree.containsSpatialKey(fn)) {
                    absentNeighbor = fn;
                    break;
                }
            }
            if (absentNeighbor != null) {
                break;
            }
        }
        assertNotNull(absentNeighbor, "fixture must have an absent face neighbor");

        manager.setElementOwner(absentNeighbor, 1); // remote relative to local rank 0

        // Read-through: the manager and the detector agree (single source of truth).
        assertEquals(1, manager.getElementOwner(absentNeighbor), "manager reads the unified owner map");
        assertEquals(detector.getElementOwner(absentNeighbor), manager.getElementOwner(absentNeighbor),
                     "manager and detector must report the same owner");

        // The local scan (driven by the manager) must now see the remote neighbor and flag the corner.
        manager.createDistributedGhostLayer();
        assertFalse(detector.getBoundaryElements().isEmpty(),
                    "owner set on the manager must drive the local boundary scan (unified map)");
    }

    @Test
    void getElementOwnerDefaultsAreUnifiedWithTheDetector() {
        var octree = cornerOctree();
        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        var manager = new DistributedGhostManager<>(octree, new StubChannel(0), detector);

        var unknown = new MortonKey(123456L, (byte) 5);
        assertEquals(detector.getElementOwner(unknown), manager.getElementOwner(unknown),
                     "an unregistered key reports the same default through both APIs");
    }
}
