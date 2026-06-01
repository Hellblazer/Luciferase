/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-3uwx S5: the {@link GhostBoundaryDetector} boundary set is exactly the <em>partition seam</em> —
 * the local elements with at least one face neighbor owned by a different rank — NOT the domain edge.
 *
 * <p>This is the user-approved correctness contract (a deliberate semantic change). The old implementation
 * seeded ghosts from domain-boundary elements ({@code getBoundaryDirections} vs {@code MAX_COORD}), which
 * never seeded ghosts for domain-interior partition-seam elements. There is intentionally no old==new
 * equivalence assertion; these two sets differ.
 *
 * @author hal.hildebrand
 */
class GhostBoundaryDetectorPartitionSeamTest {

    private static final byte LEVEL = 18; // fine level so adjacent insert points occupy distinct Morton cells

    private static Octree<LongEntityID, String> lineOfThree() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        int h = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel(LEVEL);
        // Three cells adjacent along x: A, B, C with B between A and C.
        octree.insert(new Point3f(100, 100, 100), LEVEL, "A");
        octree.insert(new Point3f(100 + h, 100, 100), LEVEL, "B");
        octree.insert(new Point3f(100 + 2 * h, 100, 100), LEVEL, "C");
        return octree;
    }

    /** With no remote owners, a single-rank index has no partition seam → empty boundary set. */
    @Test
    void singleRankHasNoPartitionSeam() {
        var octree = lineOfThree();
        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        detector.createGhostLayer();
        assertTrue(detector.getBoundaryElements().isEmpty(),
                   "rank 0 with all-local owners: no face neighbor is remote, so no boundary elements");
    }

    /**
     * Inject a single remote-owned face neighbor of one occupied element and assert the boundary set is
     * exactly that element — a proper, non-empty subset of the occupied elements (non-vacuous).
     */
    @Test
    void boundarySetIsExactlyThePartitionSeam() {
        var octree = lineOfThree();
        NeighborDetector<MortonKey> nd = octree.getNeighborDetector();
        var detector = new GhostBoundaryDetector<>(octree, nd, GhostType.FACES, GhostAlgorithm.MINIMAL);

        var occupied = octree.getSpatialKeys();
        assertEquals(3, occupied.size(), "fixture occupies three distinct cells");

        // Find element 'A' (lowest Morton code along the line) and one of its absent face neighbors that is
        // NOT a face neighbor of any other occupied element, so marking it remote affects only A.
        var sorted = new java.util.TreeSet<>(occupied);
        var a = sorted.first();
        var others = new HashSet<>(occupied);
        others.remove(a);
        var othersNeighbors = new HashSet<MortonKey>();
        for (var o : others) {
            othersNeighbors.addAll(nd.findFaceNeighbors(o));
        }

        MortonKey seamNeighbor = null;
        for (var fn : nd.findFaceNeighbors(a)) {
            if (!octree.containsSpatialKey(fn) && !othersNeighbors.contains(fn)) {
                seamNeighbor = fn;
                break;
            }
        }
        assertNotNull(seamNeighbor, "expected an absent face neighbor of A exclusive to A");

        detector.setElementOwner(seamNeighbor, 1); // owned by remote rank 1 (local rank is 0)
        detector.createGhostLayer();

        // Independent restatement of the contract: e is boundary iff some face neighbor's owner != currentRank.
        Set<MortonKey> expected = new HashSet<>();
        for (var e : occupied) {
            for (var fn : nd.findFaceNeighbors(e)) {
                if (detector.getElementOwner(fn) != detector.getCurrentRank()) {
                    expected.add(e);
                    break;
                }
            }
        }

        assertEquals(Set.of(a), expected, "model: only A has a remote face neighbor");
        assertEquals(expected, detector.getBoundaryElements(),
                     "boundary set must equal exactly the partition-seam elements");
        assertFalse(detector.getBoundaryElements().isEmpty(), "non-vacuous: the seam is non-empty");
        assertTrue(detector.getBoundaryElements().size() < occupied.size(),
                   "non-vacuous: interior elements are excluded");
    }
}
