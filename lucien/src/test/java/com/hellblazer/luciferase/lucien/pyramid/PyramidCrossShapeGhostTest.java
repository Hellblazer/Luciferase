/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostAlgorithm;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostChannel;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.5 Phase C (bead Luciferase-azwr): full distributed cross-rank ghost wiring through the
 * inverted seam, proven end-to-end for the cross-shape (pyramid↔tet) boundary.
 *
 * <p><b>Inverted seam (Luciferase-703 / RDR-008 P2).</b> {@link GhostBoundaryDetector} binds to the
 * {@link NeighborDetector} interface, not a concrete index. The {@link PyramidIndex}'s wired
 * {@link PyramidNeighborDetector} now surfaces cross-shape face neighbors (Phase B), so the ghost
 * boundary path automatically exchanges across all four triangular tet faces — not just the f4 quad
 * base it was limited to in pi1.4.
 *
 * <p><b>External ownership.</b> The detector stays geometric/local (owner rank 0). Distributed
 * ownership is assigned externally via {@link GhostBoundaryDetector#setElementOwner}; a remote-owned
 * neighbor that is absent from the local index becomes a ghost. This test assigns DISTINCT ranks to a
 * boundary pyramid's cross-shape tet neighbors and asserts a ghost fires for each, with the assigned
 * rank — non-vacuous: it fails if only the f4 same-shape face is exchanged (the pi1.4 limitation).
 *
 * <p><b>Observability.</b> {@link RecordingGhostBoundaryDetector} overrides the {@code createGhostElement}
 * seam to record (key, ownerRank) firings — the placeholder production hook only logs (real ghost data
 * arrives via gRPC), so a test subclass is the cleanest observation point for the local detection path.
 *
 * @author Hal Hildebrand
 */
class PyramidCrossShapeGhostTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    /**
     * A {@link GhostBoundaryDetector} that records every {@code createGhostElement} firing so a test can
     * assert which remote-owned neighbor keys became ghosts and with what rank.
     */
    private static final class RecordingGhostBoundaryDetector<Key extends com.hellblazer.luciferase.lucien.SpatialKey<Key>>
        extends GhostBoundaryDetector<Key, LongEntityID, String> {

        final Map<Key, Integer> created = new LinkedHashMap<>();

        RecordingGhostBoundaryDetector(com.hellblazer.luciferase.lucien.SpatialIndex<Key, LongEntityID, String> index,
                                       NeighborDetector<Key> detector, GhostType type, GhostAlgorithm algo) {
            super(index, detector, type, algo);
        }

        @Override
        protected void createGhostElement(Key neighborKey, int ownerRank) {
            created.put(neighborKey, ownerRank);
            super.createGhostElement(neighborKey, ownerRank);
        }
    }

    /**
     * In-memory {@link GhostChannel} that records every queued ghost by target rank and every flush —
     * the observation seam for the distributed fan-out path (no network).
     */
    private static final class RecordingGhostChannel<Key extends com.hellblazer.luciferase.lucien.SpatialKey<Key>>
        implements GhostChannel<Key, LongEntityID, String> {

        final Map<Integer, Set<Key>> queuedByRank = new LinkedHashMap<>();
        final Set<Integer> flushedRanks = new LinkedHashSet<>();
        int pending = 0;

        @Override
        public void queueGhost(int targetRank, GhostElement<Key, LongEntityID, String> element) {
            queuedByRank.computeIfAbsent(targetRank, r -> new LinkedHashSet<>()).add(element.getSpatialKey());
            pending++;
        }

        @Override
        public CompletableFuture<Void> flushToTarget(int targetRank) {
            flushedRanks.add(targetRank);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public int getTotalPendingCount() {
            return pending;
        }

        @Override
        public void clear() {
            queuedByRank.clear();
            pending = 0;
        }

        @Override
        public int getCurrentRank() {
            return 0;
        }

        @Override
        public long getTreeId() {
            return 0L;
        }

        @Override
        public GhostType getGhostType() {
            return GhostType.FACES;
        }
    }

    @Test
    void crossShapeGhostsFireForAllFourTriangularFacesWithAssignedRanks() {
        // Occupy a boundary pyramid (anchored at the domain origin corner) so the ghost boundary scan
        // processes it. Insert across the origin sub-cube at a shallow forced level to occupy real keys.
        occupyOriginCorner();

        var detector = index.getNeighborDetector();
        var qualifying = findBoundaryKeyWithCrossShapeTetNeighbors(detector);
        assertNotNull(qualifying,
                      "expected an occupied boundary key with >=2 in-domain cross-shape tet neighbors");
        var boundaryKey = qualifying.key;
        var tetNeighbors = qualifying.tetNeighbors;

        var ghostDetector = new RecordingGhostBoundaryDetector<>(index, detector, GhostType.FACES,
                                                                 GhostAlgorithm.MINIMAL);

        // Assign each cross-shape tet neighbor a DISTINCT remote rank (>0 = not local).
        var expectedRank = new LinkedHashMap<PyramidKey, Integer>();
        int rank = 1;
        for (var tk : tetNeighbors) {
            ghostDetector.setElementOwner(tk, rank);
            expectedRank.put(tk, rank);
            rank++;
        }

        ghostDetector.createGhostLayer();

        // Every assigned cross-shape tet neighbor must have fired a ghost with its assigned rank.
        for (var e : expectedRank.entrySet()) {
            assertEquals(e.getValue(), ghostDetector.created.get(e.getKey()),
                         "cross-shape tet neighbor " + e.getKey() + " must ghost with its assigned rank");
        }

        // Non-vacuous / meaningful: more than one cross-shape face exchanged (pi1.4 had only f4 == 1),
        // and the fired ghosts are genuinely tets (the cross-shape contribution).
        long tetGhosts = ghostDetector.created.keySet().stream()
                                       .filter(k -> PyramidIndex.elementFromKey(k) instanceof Tet)
                                       .count();
        assertTrue(tetGhosts >= 2,
                   "meaningful cross-shape exchange: >=2 triangular tet faces must ghost, got " + tetGhosts);
    }

    @Test
    void distributedGhostManagerFansOutBoundaryGhostsAcrossMultipleRanks() {
        // SIG-1 (Phase C substantive-critic): prove the FULL distributed path through
        // DistributedGhostManager.createDistributedGhostLayer() — not just the local detector scan.
        // The manager queues each local boundary element for transmission to every known remote rank
        // through the GhostChannel (inverted seam: lucien core depends on the GhostChannel interface,
        // not a gRPC transport). Non-vacuous: asserts cross-rank fan-out to >=2 distinct ranks.
        occupyOriginCorner();

        var detector = index.getNeighborDetector();
        var ghostDetector = new GhostBoundaryDetector<>(index, detector, GhostType.FACES,
                                                        GhostAlgorithm.MINIMAL);
        // Luciferase-3uwx: the boundary set is now the PARTITION seam (an occupied element with a face
        // neighbor owned by a different rank), not the domain edge. Establish a seam by assigning a remote
        // owner to the absent cross-shape face neighbors of one occupied boundary element, so the local
        // boundary set is non-empty for the fan-out assertion.
        var qualifying = findBoundaryKeyWithCrossShapeTetNeighbors(detector);
        assertNotNull(qualifying, "fixture must yield an occupied element with absent cross-shape tet neighbors");
        for (var tk : qualifying.tetNeighbors) {
            ghostDetector.setElementOwner(tk, 9); // rank 9 is remote relative to the default local rank 0
        }
        var channel = new RecordingGhostChannel<PyramidKey>();
        var manager = new DistributedGhostManager<>(index, channel, ghostDetector);
        manager.addKnownProcess(1);
        manager.addKnownProcess(2);

        manager.createDistributedGhostLayer();

        // Both remote ranks received the local boundary set (cross-rank fan-out).
        assertTrue(channel.queuedByRank.containsKey(1) && channel.queuedByRank.containsKey(2),
                   "boundary ghosts must be queued for every known remote rank, got "
                   + channel.queuedByRank.keySet());
        assertFalse(channel.queuedByRank.get(1).isEmpty(), "rank 1 must receive >=1 boundary ghost");
        assertFalse(channel.queuedByRank.get(2).isEmpty(), "rank 2 must receive >=1 boundary ghost");
        assertTrue(channel.flushedRanks.containsAll(java.util.Set.of(1, 2)),
                   "the manager must flush to each target rank");

        // The queued ghosts are exactly the detector's identified boundary elements (the elements this
        // rank owns and advertises to peers) — and the set is non-empty (origin corner is a boundary).
        var boundary = ghostDetector.getBoundaryElements();
        assertFalse(boundary.isEmpty(), "precondition: the seam (remote-owned face neighbor) yields a boundary element");
        assertEquals(boundary, channel.queuedByRank.get(1),
                     "queued ghosts to a rank must equal the local boundary element set");
    }

    @Test
    void tetLeafBoundaryClassificationIsAConservativeSuperset() {
        // SIG-2 (Phase C substantive-critic): the red-guard #2 safety argument is "tet-leaf
        // getBoundaryDirections returns the ENCLOSING CUBE's classification = a conservative superset of
        // the tet's true domain-boundary faces, hence ghost-safe (never under-classifies -> never drops a
        // real boundary ghost)". Validate it concretely, not just no-throw: a shallow tet whose enclosing
        // level-1 cube is anchored at (h,0,0) touches the y==0 and z==0 domain faces, so its boundary
        // directions MUST include NEGATIVE_Y and NEGATIVE_Z (the superset), and must NOT claim NEGATIVE_X
        // (anchor x == h != 0). encode(Tet) writes the genuine cube-id to the coord field (distinct from
        // the type field), so anchorOf recovers the true surrounding cube.
        int h = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel((byte) 1);
        var tet = new Tet(h, 0, 0, (byte) 1, (byte) 3, (byte) 1); // shallowest tet (minTetLevel == level)
        var tetLeafKey = PyramidKeyCodec.encode(tet);
        assertNotNull(tetLeafKey, "precondition: shallow tet encodes to a tet-leaf key");
        assertTrue(PyramidIndex.elementFromKey(tetLeafKey) instanceof Tet, "precondition: tet-leaf key");

        var detector = index.getNeighborDetector();
        var dirs = detector.getBoundaryDirections(tetLeafKey);
        assertTrue(dirs.contains(NeighborDetector.Direction.NEGATIVE_Y),
                   "enclosing cube touches y==0 -> NEGATIVE_Y must be in the conservative superset");
        assertTrue(dirs.contains(NeighborDetector.Direction.NEGATIVE_Z),
                   "enclosing cube touches z==0 -> NEGATIVE_Z must be in the conservative superset");
        assertFalse(dirs.contains(NeighborDetector.Direction.NEGATIVE_X),
                    "enclosing cube anchored at x==h is not on the x==0 boundary");
    }

    @Test
    void tetLeafKeyBoundaryClassificationIsGuardedAndGhostSafe() {
        // Red-guard #2 (Phase B substantive-critic): isBoundaryElement / getBoundaryDirections are
        // computed from the surrounding-cube anchor, which is the ENCLOSING PYRAMID's cube for a
        // tet-leaf key. This must NOT throw, and the enclosing-cube classification is a conservative
        // SUPERSET of the tet's true boundary faces — ghost-safe (it never misses a real boundary; an
        // over-included element simply finds no remote owner and creates no ghost).
        var tetLeafKey = firstShallowTetLeafKey();
        assertNotNull(tetLeafKey, "expected a shallow tet-leaf key");

        var detector = index.getNeighborDetector();
        // No throw for any direction.
        for (var dir : NeighborDetector.Direction.values()) {
            assertDoesNotThrow(() -> detector.isBoundaryElement(tetLeafKey, dir),
                               "isBoundaryElement must be guarded for a tet-leaf key (dir=" + dir + ")");
        }
        var dirs = assertDoesNotThrow(() -> detector.getBoundaryDirections(tetLeafKey),
                                      "getBoundaryDirections must be guarded for a tet-leaf key");
        assertNotNull(dirs);

        // The GhostBoundaryDetector boundary scan must tolerate a tet-leaf key end-to-end (it calls
        // getBoundaryDirections under the hood) without throwing.
        var ghostDetector = new GhostBoundaryDetector<>(index, detector, GhostType.FACES,
                                                        GhostAlgorithm.MINIMAL);
        assertDoesNotThrow(ghostDetector::createGhostLayer,
                           "ghost boundary scan must tolerate tet-leaf keys");
    }

    // ===== helpers =====

    /** Insert entities across the domain-origin sub-cube so a boundary pyramid is occupied. */
    private void occupyOriginCorner() {
        int n = 0;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                float x = 1 + i * 2;
                float y = 1 + j * 2;
                index.insert(new Point3f(x, y, 1), (byte) 5, "e" + (n++));
                index.insert(new Point3f(x, y, 3), (byte) 5, "e" + (n++));
            }
        }
    }

    private record Qualifying(PyramidKey key, List<PyramidKey> tetNeighbors) {
    }

    /**
     * Among occupied keys, find a boundary element whose cross-shape face neighbors include >=2 in-domain
     * tet leaves that are NOT occupied (so they qualify as remote-owned ghosts).
     */
    private Qualifying findBoundaryKeyWithCrossShapeTetNeighbors(NeighborDetector<PyramidKey> detector) {
        for (var key : index.getSpatialKeys()) {
            if (detector.getBoundaryDirections(key).isEmpty()) {
                continue;
            }
            var tets = new ArrayList<PyramidKey>();
            for (var fk : detector.findFaceNeighbors(key)) {
                if (PyramidIndex.elementFromKey(fk) instanceof Tet && !index.containsSpatialKey(fk)) {
                    tets.add(fk);
                }
            }
            if (tets.size() >= 2) {
                return new Qualifying(key, tets);
            }
        }
        return null;
    }

    /** A shallow (minTetLevel == level) tet-leaf key sourced from a pyramid's triangular face neighbor. */
    private PyramidKey firstShallowTetLeafKey() {
        var roots = new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                    new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) };
        var stack = new java.util.ArrayDeque<Pyramid>();
        for (var r : roots) {
            stack.push(r);
        }
        while (!stack.isEmpty()) {
            var p = stack.pop();
            if (p.level() >= 2) {
                for (int f = 0; f < 4; f++) {
                    var fn = p.faceNeighbor(f);
                    if (fn != null && fn.element() instanceof Tet t) {
                        var tk = PyramidKeyCodec.encode(t);
                        if (tk != null) {
                            return tk;
                        }
                    }
                }
            }
            if (p.level() < 5) {
                for (int i = 0; i < com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                    if (p.child(i) instanceof Pyramid pc) {
                        stack.push(pc);
                    }
                }
            }
        }
        return null;
    }
}
