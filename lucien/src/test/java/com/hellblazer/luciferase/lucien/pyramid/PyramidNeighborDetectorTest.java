/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-shape (pyramid↔tet) topology for {@link PyramidNeighborDetector} (RDR-010 pi1.4 same-shape /
 * pi1.5 cross-shape, beads Luciferase-mu9 / Luciferase-9e3a). A pyramid's only same-shape face is the
 * quadrilateral base (f4, type 6↔7); the four triangular faces neighbor tetrahedra (cross-shape, now
 * surfaced as tet-leaf keys). Validation follows the CLAUDE.md face-neighbor caveat: reciprocity/
 * involution for cross-shape tet neighbors, and the shared-vertex-count assertion ONLY for the
 * conforming pyramid quad base (invariant #5).
 *
 * @author hal.hildebrand
 */
class PyramidNeighborDetectorTest {

    private PyramidNeighborDetector detector;

    @BeforeEach
    void setUp() {
        var index = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        detector = new PyramidNeighborDetector(index);
    }

    // ===== helpers =====

    private static List<Pyramid> validPyramids(int maxLevel) {
        var out = new ArrayList<Pyramid>();
        for (var root : new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                        new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) }) {
            descend(root, maxLevel, out);
        }
        return out;
    }

    private static void descend(Pyramid p, int maxLevel, List<Pyramid> out) {
        if (p.level() >= 1) {
            out.add(p);
        }
        if (p.level() >= maxLevel) {
            return;
        }
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            HybridElement child = p.child(i);
            if (child instanceof Pyramid pc) {
                descend(pc, maxLevel, out);
            }
        }
    }

    /** The pyramid-typed (same-shape) subset of a neighbor-key list. */
    private static List<PyramidKey> pyramidSubset(List<PyramidKey> keys) {
        var out = new ArrayList<PyramidKey>();
        for (var k : keys) {
            if (PyramidIndex.elementFromKey(k) instanceof Pyramid) {
                out.add(k);
            }
        }
        return out;
    }

    /** The tet-typed (cross-shape) subset of a neighbor-key list. */
    private static List<PyramidKey> tetSubset(List<PyramidKey> keys) {
        var out = new ArrayList<PyramidKey>();
        for (var k : keys) {
            if (PyramidIndex.elementFromKey(k) instanceof Tet) {
                out.add(k);
            }
        }
        return out;
    }

    private static int sharedVertexCount(Pyramid a, Pyramid b) {
        Point3i[] va = a.coordinates();
        Point3i[] vb = b.coordinates();
        int shared = 0;
        for (Point3i pa : va) {
            for (Point3i pb : vb) {
                if (pa.equals(pb)) {
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    /** A valid SFC pyramid whose quad-base (f4) neighbor is in-domain AND is itself an SFC element. */
    private static Pyramid[] selfAndQuadBaseNeighbor() {
        for (var p : validPyramids(5)) {
            var fn = p.faceNeighbor(4);
            if (fn == null || !(fn.element() instanceof Pyramid pn)) {
                continue;
            }
            if (PyramidKeyCodec.encode(p) != null && PyramidKeyCodec.encode(pn) != null) {
                return new Pyramid[] { p, pn };
            }
        }
        return null;
    }

    // ===== tests =====

    @Test
    void quadBaseFaceNeighborIsTheSingleSameShapeFaceNeighbor() {
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair, "expected an interior SFC pyramid with an SFC quad-base neighbor");
        var self = pair[0];
        var neighbor = pair[1];
        var selfKey = PyramidKeyCodec.encode(self);
        var neighborKey = PyramidKeyCodec.encode(neighbor);

        var faces = detector.findFaceNeighbors(selfKey);
        // pi1.5: a pyramid has exactly ONE same-shape (quad-base) face neighbor; the rest of the face
        // result is cross-shape tets. Filter to the pyramid subset to assert the same-shape contract.
        var pyramidFaces = pyramidSubset(faces);
        assertEquals(1, pyramidFaces.size(), "a pyramid has exactly one same-shape (quad-base) face neighbor");
        assertEquals(neighborKey, pyramidFaces.get(0));
        // The quad base is a conforming face shared by exactly two pyramids → exactly 4 shared vertices
        // (invariant #5; this assertion is valid ONLY for the conforming pyramid quad base).
        assertEquals(4, sharedVertexCount(self, neighbor));
    }

    @Test
    void faceNeighborMatchesPyramidFaceNeighbor4() {
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var self = pair[0];
        var selfKey = PyramidKeyCodec.encode(self);
        var expected = PyramidKeyCodec.encode((Pyramid) self.faceNeighbor(4).element());
        // The single pyramid-typed face neighbor must agree with Pyramid.faceNeighbor(4).
        assertEquals(List.of(expected), pyramidSubset(detector.findFaceNeighbors(selfKey)),
                     "the same-shape face result must agree with Pyramid.faceNeighbor(4)");
    }

    @Test
    void faceNeighborIsReciprocal() {
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var selfKey = PyramidKeyCodec.encode(pair[0]);
        var neighborKey = PyramidKeyCodec.encode(pair[1]);
        // Involution: the f4-neighbor's same-shape face neighbor is self again.
        assertTrue(detector.findFaceNeighbors(neighborKey).contains(selfKey),
                   "face-neighbor relation must be reciprocal (neighbor(neighbor)==self)");
    }

    /**
     * pi1.5 cross-shape (honesty-trap pin rewrite): the four triangular faces neighbor tetrahedra, and
     * those in-domain, in-SFC tet neighbors are now PRESENT in the face result as tet-leaf keys (no
     * longer absent). The same-shape f4 pyramid neighbor is also present. This is RED against the pi1.4
     * same-shape-only detector (which returned only the f4 pyramid).
     */
    @Test
    void triangularFacesNeighborTetsAndArePresentInTheFaceResult() {
        // A pyramid all four of whose triangular-face tet neighbors are in-domain SFC elements.
        Pyramid self = null;
        for (var p : validPyramids(5)) {
            if (PyramidKeyCodec.encode(p) == null) {
                continue;
            }
            int encodableTets = 0;
            for (int f = 0; f < 4; f++) {
                var fn = p.faceNeighbor(f);
                if (fn != null && fn.element() instanceof Tet t && PyramidKeyCodec.encode(t) != null) {
                    encodableTets++;
                }
            }
            if (encodableTets >= 1) {
                self = p;
                break;
            }
        }
        assertNotNull(self, "expected an SFC pyramid with at least one in-SFC triangular tet neighbor");
        var selfKey = PyramidKeyCodec.encode(self);
        var faces = detector.findFaceNeighbors(selfKey);

        // Every in-domain, in-SFC triangular-face tet neighbor must appear as a tet-leaf key.
        int expectedTets = 0;
        for (int f = 0; f < 4; f++) {
            var fn = self.faceNeighbor(f);
            if (fn != null && fn.element() instanceof Tet t) {
                assertInstanceOf(Tet.class, fn.element(), "triangular face " + f + " neighbors a tet");
                var tk = PyramidKeyCodec.encode(t);
                if (tk != null) {
                    expectedTets++;
                    assertTrue(faces.contains(tk),
                               "cross-shape tet face neighbor (face " + f + ") must be present: " + t);
                    assertInstanceOf(Tet.class, PyramidIndex.elementFromKey(tk), "tet-leaf key decodes to a Tet");
                }
            }
        }
        assertTrue(expectedTets >= 1, "fixture must contribute >= 1 cross-shape tet face neighbor");
        // Completeness (not just presence): the tet-typed subset of the face result equals exactly the
        // count of encodable triangular-face tets — no legitimate neighbor dropped, no duplicate added.
        assertEquals(expectedTets, tetSubset(faces).size(),
                     "tet-leaf subset of face result must equal the count of encodable triangular-face tets");
    }

    /**
     * Completeness count-oracle (substantive-critic Phase B finding): for a pyramid whose ALL FOUR
     * triangular faces neighbor in-domain SFC tets, the detector must surface exactly four tet-leaf
     * keys — the encode-filter must not silently drop a legitimate cross-shape neighbor (ghost hole).
     * Reciprocity sweeps prove soundness of survivors; this proves coverage.
     */
    @Test
    void allFourTriangularFaceTetsArePresentWhenInDomain() {
        Pyramid self = null;
        for (var p : validPyramids(5)) {
            if (PyramidKeyCodec.encode(p) == null) {
                continue;
            }
            int encodableTets = 0;
            for (int f = 0; f < 4; f++) {
                var fn = p.faceNeighbor(f);
                if (fn != null && fn.element() instanceof Tet t && PyramidKeyCodec.encode(t) != null) {
                    encodableTets++;
                }
            }
            if (encodableTets == 4) {
                self = p;
                break;
            }
        }
        assertNotNull(self, "expected an interior pyramid whose four triangular faces are all in-SFC tets");
        var faces = detector.findFaceNeighbors(PyramidKeyCodec.encode(self));
        assertEquals(4, tetSubset(faces).size(),
                     "all four in-domain triangular-face tets must be surfaced (no encode-filter drop)");
    }

    @Test
    void neighborBucketsAreCumulativeSupersets() {
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var selfKey = PyramidKeyCodec.encode(pair[0]);
        var neighborKey = PyramidKeyCodec.encode(pair[1]);

        var faces = detector.findFaceNeighbors(selfKey);
        var edges = detector.findEdgeNeighbors(selfKey);
        var verts = detector.findVertexNeighbors(selfKey);

        // Per the NeighborDetector contract: vertex ⊇ edge ⊇ face. The quad-base (f4, shared 4)
        // neighbor must appear in ALL THREE buckets.
        assertTrue(faces.contains(neighborKey));
        assertTrue(edges.contains(neighborKey));
        assertTrue(verts.contains(neighborKey));
        assertTrue(edges.containsAll(faces), "edge set ⊇ face set");
        assertTrue(verts.containsAll(edges), "vertex set ⊇ edge set");
        assertTrue(verts.size() >= edges.size() && edges.size() >= faces.size());
    }

    @Test
    void everyEnumeratedNeighborSharesAtLeastTheBucketThreshold() {
        // Non-vacuous: each returned SAME-SHAPE (pyramid) vertex/edge/face neighbor genuinely shares
        // ≥1/≥2/≥4 vertices with self, and decodes back to the level-matched pyramid. (Cross-shape tet
        // neighbors are validated by reciprocity, not shared-vertex — CLAUDE.md caveat.)
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var self = pair[0];
        var selfKey = PyramidKeyCodec.encode(self);

        assertThresholds(self, pyramidSubset(detector.findVertexNeighbors(selfKey)), 1);
        assertThresholds(self, pyramidSubset(detector.findEdgeNeighbors(selfKey)), 2);
        assertThresholds(self, pyramidSubset(detector.findFaceNeighbors(selfKey)), 4);
    }

    private static void assertThresholds(Pyramid self, List<PyramidKey> neighbors, int min) {
        assertFalse(neighbors.isEmpty(), "expected at least one neighbor at threshold " + min);
        for (var key : neighbors) {
            var n = PyramidIndex.pyramidFromKey(key);
            assertNotNull(n, "neighbor key must decode to a pyramid");
            assertEquals(self.level(), n.level(), "neighbor must be at the same level");
            assertTrue(sharedVertexCount(self, n) >= min,
                       "neighbor " + n + " must share >= " + min + " vertices with " + self);
        }
    }

    @Test
    void enumerationMatchesBruteForceOverTheWholeSfcUniverse() {
        // Independent completeness/soundness oracle: instead of the detector's ±len cube-offset trick,
        // brute-force EVERY SFC pyramid in the level-≤5 universe and select those at self's level
        // sharing ≥ threshold vertices. A missed cube (under-report) or a spurious candidate
        // (over-report) makes the sets differ. This is the test that would catch ghost-holes in pi1.5.
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var self = pair[0];
        var selfKey = PyramidKeyCodec.encode(self);
        var universe = validPyramids(5);

        // The SAME-SHAPE (pyramid) subset of each bucket must match the brute-force same-shape oracle.
        // Cross-shape tet neighbors are validated separately (navigation + reciprocity); they are not
        // part of this shared-vertex pyramid oracle.
        assertEquals(expectedNeighborKeys(self, universe, 4),
                     setOf(pyramidSubset(detector.findFaceNeighbors(selfKey))));
        assertEquals(expectedNeighborKeys(self, universe, 2),
                     setOf(pyramidSubset(detector.findEdgeNeighbors(selfKey))));
        assertEquals(expectedNeighborKeys(self, universe, 1),
                     setOf(pyramidSubset(detector.findVertexNeighbors(selfKey))));
        // And the universe genuinely contains MORE than the single face neighbor at the edge/vertex
        // thresholds, so the oracle is not trivially equal to the 1-element face set.
        assertTrue(expectedNeighborKeys(self, universe, 2).size() > 1,
                   "fixture must have >1 edge neighbor for the oracle to be non-trivial");
    }

    private static java.util.Set<PyramidKey> setOf(List<PyramidKey> keys) {
        return new java.util.HashSet<>(keys);
    }

    private static java.util.Set<PyramidKey> expectedNeighborKeys(Pyramid self, List<Pyramid> universe,
                                                                  int minShared) {
        var out = new java.util.HashSet<PyramidKey>();
        for (var q : universe) {
            if (q.level() != self.level() || q.equals(self)) {
                continue;
            }
            if (sharedVertexCount(self, q) >= minShared) {
                var key = PyramidKeyCodec.encode(q);
                if (key != null) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    @Test
    void positiveBoundaryDirectionsNearMaxCoord() {
        // root6.child(9) = TYPE_6 at cube-id 7 (anchor (h,h,h), level 1, h = lengthAtLevel(1) = 2^20).
        // anchor + len = 2^21 > MAX_COORD, so the +X/+Y/+Z faces are all domain boundaries — this is
        // the positive-direction (anchor+len > MAX_COORD) branch the origin test cannot reach.
        int h = Constants.lengthAtLevel((byte) 1);
        var farCorner = new Pyramid(h, h, h, (byte) 1, Pyramid.TYPE_6);
        var key = PyramidKeyCodec.encode(farCorner);
        assertNotNull(key, "far-corner pyramid must be a valid SFC element");
        var dirs = detector.getBoundaryDirections(key);
        assertTrue(dirs.contains(NeighborDetector.Direction.POSITIVE_X));
        assertTrue(dirs.contains(NeighborDetector.Direction.POSITIVE_Y));
        assertTrue(dirs.contains(NeighborDetector.Direction.POSITIVE_Z));
        assertTrue(detector.isBoundaryElement(key, NeighborDetector.Direction.POSITIVE_X));
    }

    @Test
    void tetLeafKeyReportsCrossShapeNeighbors() {
        // pi1.5 (was tetLeafKeyDefersToEmpty): root6.child(1) is a shallowest tet (cid 1, type 3). A
        // tet-leaf PyramidKey now reports its cross-shape face neighbors (its bounding pyramid + tet
        // neighbors at the shallow boundary) instead of deferring to empty.
        var tetLeafKey = PyramidKey.fromLevels((byte) 1, new int[] { 0, 1 }, new int[] { 0, 3 });
        assertEquals((byte) 3, tetLeafKey.getTypeAtLevel(1), "precondition: leaf type is a tet");
        assertInstanceOf(Tet.class, PyramidIndex.elementFromKey(tetLeafKey), "precondition: decodes to a Tet");

        var faces = detector.findFaceNeighbors(tetLeafKey);
        assertFalse(faces.isEmpty(), "a shallow tet leaf must report cross-shape face neighbors");
        // Reciprocity: the tet leaf appears in each of its neighbors' face results.
        for (var nk : faces) {
            assertTrue(detector.findFaceNeighbors(nk).contains(tetLeafKey),
                       "cross-shape face-neighbor relation must be reciprocal: " + tetLeafKey + " <-> " + nk);
        }
        // Edge/vertex are bounded supersets of the face set (face ⊆ edge ⊆ vertex).
        var edges = detector.findEdgeNeighbors(tetLeafKey);
        var verts = detector.findVertexNeighbors(tetLeafKey);
        assertTrue(edges.containsAll(faces), "edge set ⊇ face set");
        assertTrue(verts.containsAll(edges), "vertex set ⊇ edge set");
    }

    @Test
    void detectorNeverThrowsForAnyTetLeafKey() {
        // The detector must never propagate an exception (a throw would break KnnSearcher/CollisionEngine
        // BFS). A level-2 shallow tet leaf exercises the tet-leaf navigation path. Note: a DEEP tet
        // (l > minTetLevel) is unreachable as a key by construction — encode(Tet) returns null for it
        // and elementFromKey cannot reconstruct it (until cjwr Phase B). Tet.faceNeighborElement no
        // longer fails loud for deep tets either (RDR-010 Luciferase-cjwr), so there is no throw to catch.
        var p1 = new Pyramid(0, 0, 0, (byte) 1, Pyramid.TYPE_6);
        var tet2 = (Tet) p1.child(1);               // shallow level-2 tet (minTetLevel == level == 2)
        var tetKey = PyramidKeyCodec.encode(tet2);
        assertNotNull(tetKey);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> detector.findFaceNeighbors(tetKey));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> detector.findVertexNeighbors(tetKey));
    }

    @Test
    void boundaryDirectionsAtOrigin() {
        // type-6 pyramid at the origin cube, level 2: anchor (0,0,0) → on the −X/−Y/−Z domain faces;
        // its surrounding cube (len = lengthAtLevel(2)) is far from the +faces.
        var origin = new Pyramid(0, 0, 0, (byte) 2, Pyramid.TYPE_6);
        var key = PyramidKeyCodec.encode(origin);
        assertNotNull(key);
        var dirs = detector.getBoundaryDirections(key);
        assertTrue(dirs.contains(NeighborDetector.Direction.NEGATIVE_X));
        assertTrue(dirs.contains(NeighborDetector.Direction.NEGATIVE_Y));
        assertTrue(dirs.contains(NeighborDetector.Direction.NEGATIVE_Z));
        assertFalse(dirs.contains(NeighborDetector.Direction.POSITIVE_X));
        assertFalse(dirs.contains(NeighborDetector.Direction.POSITIVE_Y));
        assertFalse(dirs.contains(NeighborDetector.Direction.POSITIVE_Z));
        assertTrue(detector.isBoundaryElement(key, NeighborDetector.Direction.NEGATIVE_X));
        assertFalse(detector.isBoundaryElement(key, NeighborDetector.Direction.POSITIVE_X));
    }

    @Test
    void findNeighborsWithOwnersWrapsLocalFaceNeighbors() {
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var selfKey = PyramidKeyCodec.encode(pair[0]);
        var neighborKey = PyramidKeyCodec.encode(pair[1]);
        var faces = detector.findFaceNeighbors(selfKey);
        var owners = detector.findNeighborsWithOwners(selfKey, GhostType.FACES);
        // One NeighborInfo per face neighbor (the f4 pyramid + the cross-shape tets), all wrapped local.
        assertEquals(faces.size(), owners.size());
        var ownerKeys = owners.stream().map(NeighborDetector.NeighborInfo::neighborKey).toList();
        assertTrue(ownerKeys.contains(neighborKey), "the f4 pyramid neighbor must be wrapped");
        for (var info : owners) {
            assertEquals(0, info.ownerRank(), "Phase B: ownership is local-only (distributed → Phase C)");
            assertTrue(info.isLocal());
        }
    }
}
