/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for Luciferase-8ggq: {@link GhostBoundaryDetector}'s ghost-creation guard must compare a neighbor's
 * owner against the <em>local partition rank</em>, not the hardcoded literal 0.
 *
 * <p>The guard in {@code createGhostsForElement} previously read {@code if (ownerRank != 0)}, which misfires on any
 * rank &gt; 0: on rank 2, a neighbor owned by rank 0 (a genuinely remote partition) was wrongly treated as local and
 * skipped. The fix injects {@code currentRank} via {@link GhostBoundaryDetector#setCurrentRank(int)} and compares
 * {@code ownerRank != currentRank}.
 *
 * @author hal.hildebrand
 */
class GhostBoundaryDetectorRankTest {

    /**
     * Captures the placeholder {@code createGhostElement} calls so a test can observe which neighbors the guard
     * admitted, and with which owner rank.
     */
    private static final class CapturingDetector
        extends GhostBoundaryDetector<MortonKey, LongEntityID, String> {

        final List<MortonKey> ghostedKeys = new ArrayList<>();
        final List<Integer>   ghostedOwners = new ArrayList<>();

        CapturingDetector(SpatialIndex<MortonKey, LongEntityID, String> index,
                          NeighborDetector<MortonKey> detector) {
            super(index, detector, GhostType.FACES, GhostAlgorithm.MINIMAL);
        }

        @Override
        protected void createGhostElement(MortonKey neighborKey, int ownerRank) {
            ghostedKeys.add(neighborKey);
            ghostedOwners.add(ownerRank);
        }
    }

    private static Octree<LongEntityID, String> boundaryOctree() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        // A single element at the origin corner: it sits on the domain boundary, so identifyBoundaryElements marks
        // it, and its in-domain neighbours are absent from the index — the candidate ghost set.
        octree.insert(new Point3f(0, 0, 0), (byte) 5, "corner");
        return octree;
    }

    /**
     * On rank 0 (default), neighbours whose owner also defaults to 0 are local: {@code 0 != 0} is false, so no
     * ghosts are created — the original single-process behaviour is preserved.
     */
    @Test
    void rankZeroTreatsDefaultOwnedNeighborsAsLocal() {
        var octree = boundaryOctree();
        var detector = new CapturingDetector(octree, octree.getNeighborDetector());
        // currentRank defaults to 0; do not inject.

        detector.createGhostLayer();

        assertTrue(detector.ghostedKeys.isEmpty(),
                   "rank 0: default-owned (rank-0) neighbours are local, no ghosts expected");
    }

    /**
     * On rank 2, those same default-owned (rank-0) neighbours are REMOTE and must get ghosts. Pre-fix the hardcoded
     * {@code != 0} guard gave {@code 0 != 0 == false} here too, silently creating nothing — the bug.
     */
    @Test
    void rankTwoCreatesGhostsForRemoteOwnedNeighbors() {
        var octree = boundaryOctree();
        var detector = new CapturingDetector(octree, octree.getNeighborDetector());
        detector.setCurrentRank(2);
        assertEquals(2, detector.getCurrentRank(), "setCurrentRank must be observable");

        detector.createGhostLayer();

        assertFalse(detector.ghostedKeys.isEmpty(),
                    "rank 2: neighbours owned by rank 0 are remote and must get ghosts (Luciferase-8ggq)");
        // Every admitted neighbour is owned by a rank different from the local rank.
        for (int owner : detector.ghostedOwners) {
            assertEquals(0, owner, "default-owned neighbours report rank 0");
        }
        assertTrue(detector.ghostedOwners.stream().allMatch(o -> o != 2),
                   "guard must only admit neighbours whose owner != currentRank");
    }

    /**
     * A neighbour owned by the LOCAL rank must NOT get a ghost. Capture the neighbour set at rank 2, then re-run with
     * those neighbours explicitly owned by rank 2 and assert none are admitted.
     */
    @Test
    void neighborsOwnedByLocalRankAreExcluded() {
        // First pass: discover the candidate neighbour keys at rank 2.
        var octree = boundaryOctree();
        var probe = new CapturingDetector(octree, octree.getNeighborDetector());
        probe.setCurrentRank(2);
        probe.createGhostLayer();
        assertFalse(probe.ghostedKeys.isEmpty(), "probe must find candidate neighbours");

        // Second pass: same index, but mark every candidate neighbour as owned by the local rank (2).
        var octree2 = boundaryOctree();
        var detector = new CapturingDetector(octree2, octree2.getNeighborDetector());
        detector.setCurrentRank(2);
        for (var key : probe.ghostedKeys) {
            detector.setElementOwner(key, 2);
        }

        detector.createGhostLayer();

        assertTrue(detector.ghostedKeys.isEmpty(),
                   "neighbours owned by the local rank must not be ghosted (ownerRank == currentRank)");
    }

    /**
     * Explicit-owner path (not just the {@code getOrDefault(0)} fallback): neighbours explicitly registered as owned
     * by a rank different from the local rank must be ghosted. Mirrors how the distributed protocol populates owners.
     */
    @Test
    void explicitlyRemoteOwnedNeighborsAreGhosted() {
        // Discover candidate neighbours at rank 2.
        var octree = boundaryOctree();
        var probe = new CapturingDetector(octree, octree.getNeighborDetector());
        probe.setCurrentRank(2);
        probe.createGhostLayer();
        assertFalse(probe.ghostedKeys.isEmpty(), "probe must find candidate neighbours");

        // Fresh detector: explicitly own every candidate by rank 5 (remote relative to local rank 2).
        var octree2 = boundaryOctree();
        var detector = new CapturingDetector(octree2, octree2.getNeighborDetector());
        detector.setCurrentRank(2);
        for (var key : probe.ghostedKeys) {
            detector.setElementOwner(key, 5);
        }

        detector.createGhostLayer();

        assertFalse(detector.ghostedKeys.isEmpty(), "explicitly remote-owned (rank 5 != 2) neighbours must be ghosted");
        assertTrue(detector.ghostedOwners.stream().allMatch(o -> o == 5),
                   "ghosted neighbours must report their explicit remote owner rank 5");
    }

    /**
     * Luciferase-7wzml.9: after {@code createGhostLayer()} with a multi-rank fixture, {@code getNumGhostElements()}
     * must be {@code > 0}. This pins the scan → override hook → layer population → counter increment pipeline that
     * was non-functional when {@code createGhostElement} only logged.
     *
     * <p><b>Scope note:</b> the base {@code createGhostElement} is a do-nothing placeholder — it is called only
     * for keys absent from the local index (guaranteed by the {@code !containsSpatialKey} guard in
     * {@code createGhostsForElement}), so it always takes the debug-log-and-return branch and never emits a ghost.
     * Remote entity data arrives via {@link DistributedGhostManager} (the gRPC fill path), not through this hook.
     * In a single-process multi-partition test, a subclass override is required to supply synthetic ghost data.
     * This test therefore uses {@code PopulatingDetector} (which overrides the hook) to verify the
     * boundary-scan plumbing (scan → hook dispatch → ghost layer counter) rather than the gRPC fill path.
     */
    @Test
    void createGhostLayerPopulatesGhostLayerOnMultiRankBoundary() {
        var octree = boundaryOctree();
        var detector = new PopulatingDetector(octree, octree.getNeighborDetector());
        detector.setCurrentRank(2); // rank 2: default-owned (rank-0) neighbours are remote

        detector.createGhostLayer();

        // Non-vacuous: the layer must contain at least one ghost (not just "≥ 0").
        assertTrue(detector.getGhostLayer().getNumGhostElements() > 0,
                   "Luciferase-7wzml.9: getNumGhostElements() must be > 0 after createGhostLayer on a multi-rank fixture");
        // Each ghost's entityId must be the synthetic sentinel we deposited.
        var ghosts = detector.getGhostLayer().getAllGhostElements();
        assertFalse(ghosts.isEmpty(), "ghost list must not be empty");
        for (var ghost : ghosts) {
            assertNotNull(ghost.getEntityId(), "each ghost must have a non-null entity ID");
            // ownerRank is the neighbor's registered owner (0 by default), which is != local rank (2).
            // The guard createGhostsForElement only calls createGhostElement when ownerRank != currentRank.
            assertFalse(ghost.getOwnerRank() == 2,
                        "ghost owner rank must not be the local rank (2); got " + ghost.getOwnerRank());
        }
    }

    /**
     * A detector that overrides {@code createGhostElement} to deposit a synthetic {@link GhostElement} into
     * the ghost layer. This is the required pattern for single-process multi-partition tests: the base class
     * cannot populate ghosts (it is called with keys absent from the local index and always defers to the
     * gRPC fill path), so a subclass override is the only way to supply test data via this hook.
     */
    private static final class PopulatingDetector
        extends GhostBoundaryDetector<MortonKey, LongEntityID, String> {

        private long syntheticIdCounter = 1L;

        PopulatingDetector(SpatialIndex<MortonKey, LongEntityID, String> index,
                           NeighborDetector<MortonKey> detector) {
            super(index, detector, GhostType.FACES, GhostAlgorithm.MINIMAL);
        }

        @Override
        protected void createGhostElement(MortonKey neighborKey, int ownerRank) {
            var ghost = new GhostElement<>(
                neighborKey,
                new LongEntityID(syntheticIdCounter++),
                "synthetic-content",
                new Point3f(0, 0, 0),
                ownerRank,
                0L);
            getGhostLayer().addGhostElement(ghost);
        }
    }
}
