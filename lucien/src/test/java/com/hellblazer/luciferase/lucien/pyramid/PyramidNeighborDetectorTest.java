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
 * Same-shape (pyramid↔pyramid) topology for {@link PyramidNeighborDetector} (RDR-010 pi1.4 Phase B,
 * bead Luciferase-mu9). A pyramid's only same-shape face is the quadrilateral base (f4, type 6↔7);
 * the four triangular faces neighbor tetrahedra (cross-shape, deferred to pi1.5). Validation follows
 * the CLAUDE.md face-neighbor caveat: reciprocity/involution, plus the shared-vertex-count assertion
 * which is valid ONLY for the conforming pyramid quad base (invariant #5).
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
        assertEquals(1, faces.size(), "a pyramid has exactly one same-shape (quad-base) face neighbor");
        assertEquals(neighborKey, faces.get(0));
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
        assertEquals(List.of(expected), detector.findFaceNeighbors(selfKey),
                     "unified-enum face result must agree with Pyramid.faceNeighbor(4)");
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

    @Test
    void triangularFacesNeighborTetsAndAreAbsentFromSameShapeResult() {
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var self = pair[0];
        var selfKey = PyramidKeyCodec.encode(self);
        // The four triangular faces neighbor tetrahedra (cross-shape, pi1.5) — documented here so the
        // single-element same-shape face result is a deliberate deferral, not silent scope reduction.
        for (int f = 0; f < 4; f++) {
            var fn = self.faceNeighbor(f);
            if (fn != null) {
                assertInstanceOf(Tet.class, fn.element(),
                                 "triangular face " + f + " must neighbor a tetrahedron");
            }
        }
        assertEquals(1, detector.findFaceNeighbors(selfKey).size(),
                     "same-shape face result must exclude all four triangular (tet) faces");
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
        // Non-vacuous: each returned vertex/edge/face neighbor genuinely shares ≥1/≥2/≥4 vertices
        // with self, and is a pyramid (same-shape), and decodes back to the level-matched element.
        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var self = pair[0];
        var selfKey = PyramidKeyCodec.encode(self);

        assertThresholds(self, detector.findVertexNeighbors(selfKey), 1);
        assertThresholds(self, detector.findEdgeNeighbors(selfKey), 2);
        assertThresholds(self, detector.findFaceNeighbors(selfKey), 4);
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

        assertEquals(expectedNeighborKeys(self, universe, 4), setOf(detector.findFaceNeighbors(selfKey)));
        assertEquals(expectedNeighborKeys(self, universe, 2), setOf(detector.findEdgeNeighbors(selfKey)));
        assertEquals(expectedNeighborKeys(self, universe, 1), setOf(detector.findVertexNeighbors(selfKey)));
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
    void tetLeafKeyDefersToEmpty() {
        // root6 child index 1 is a tetrahedron (cid 1, type 3). A tet-leaf PyramidKey is cross-shape
        // territory (pi1.5): the same-shape detector returns empty, never throws.
        var tetLeafKey = PyramidKey.fromLevels((byte) 1, new int[] { 0, 1 }, new int[] { 0, 3 });
        assertEquals((byte) 3, tetLeafKey.getTypeAtLevel(1), "precondition: leaf type is a tet");
        assertTrue(detector.findFaceNeighbors(tetLeafKey).isEmpty());
        assertTrue(detector.findEdgeNeighbors(tetLeafKey).isEmpty());
        assertTrue(detector.findVertexNeighbors(tetLeafKey).isEmpty());
        assertTrue(detector.findNeighborsWithOwners(tetLeafKey, GhostType.FACES).isEmpty());
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
        var owners = detector.findNeighborsWithOwners(selfKey, GhostType.FACES);
        assertEquals(1, owners.size());
        var info = owners.get(0);
        assertEquals(neighborKey, info.neighborKey());
        assertEquals(0, info.ownerRank());
        assertTrue(info.isLocal());
    }
}
