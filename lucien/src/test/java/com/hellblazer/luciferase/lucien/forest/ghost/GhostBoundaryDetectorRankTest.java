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
}
