/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 (bead Luciferase-0utt): exhaustive cross-shape EDGE/VERTEX neighbor topology (pyramid↔tet).
 *
 * <p>pi1.5 made findEdge/VertexNeighbors a bounded superset (cross-shape faces ∪ same-shape pyramid
 * neighbors), so a tet sharing only an edge or vertex (not a face) with a pyramid was missed, and a
 * tet-leaf query got only its face set. 0utt enumerates ALL same-level SFC elements (pyramid 6/7 +
 * shallowest tet 0-5) in the 27-cube neighbourhood and classifies by shared-vertex count (≥2 edge,
 * ≥1 vertex) — the exact geometric definition for edge/vertex adjacency.
 *
 * <p>Validation is reciprocity/involution (CLAUDE.md): a cross-shape edge/vertex neighbor must list the
 * query back. Deep-tet (l &gt; minTetLevel) stays out of scope (encode rejects it, Finding #16).
 */
class PyramidCrossShapeEdgeVertexTest {

    private PyramidNeighborDetector detector;

    @BeforeEach
    void setUp() {
        detector = (PyramidNeighborDetector) new PyramidIndex<LongEntityID, String>(
            new SequentialLongIDGenerator()).getNeighborDetector();
    }

    /** SFC pyramids up to {@code maxLevel}, walked depth-first from the two roots. */
    private List<Pyramid> validPyramids(int maxLevel) {
        var out = new ArrayList<Pyramid>();
        var stack = new ArrayDeque<Pyramid>();
        stack.push(new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6));
        stack.push(new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7));
        while (!stack.isEmpty()) {
            var p = stack.pop();
            if (p.level() >= 1) {
                out.add(p);
            }
            if (p.level() < maxLevel) {
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                    if (p.child(i) instanceof Pyramid pc) {
                        stack.push(pc);
                    }
                }
            }
        }
        return out;
    }

    private static boolean isTetLeaf(PyramidKey k) {
        byte t = k.getTypeAtLevel(k.getLevel());
        return t != Pyramid.TYPE_6 && t != Pyramid.TYPE_7;
    }

    @Test
    void crossShapeTetVertexAndEdgeNeighborsAreSurfacedAndReciprocal() {
        int crossShapeVertexChecks = 0;
        int crossShapeEdgeChecks = 0;
        for (var p : validPyramids(5)) {
            var pk = PyramidKeyCodec.encode(p);
            if (pk == null) {
                continue;
            }
            var faces = detector.findFaceNeighbors(pk);
            for (var vk : detector.findVertexNeighbors(pk)) {
                if (!isTetLeaf(vk) || faces.contains(vk)) {
                    continue; // only the NEW contribution: cross-shape tets that are not face neighbors
                }
                // Reciprocity: the tet must list the pyramid back as a vertex neighbor.
                assertTrue(detector.findVertexNeighbors(vk).contains(pk),
                           "cross-shape vertex adjacency must be reciprocal: " + pk + " <-> " + vk);
                crossShapeVertexChecks++;

                // If it is an EDGE neighbor (≥2 shared verts) it must also be reciprocal in the edge set
                // and present in the vertex set (face ⊆ edge ⊆ vertex).
                if (detector.findEdgeNeighbors(pk).contains(vk)) {
                    assertTrue(detector.findEdgeNeighbors(vk).contains(pk),
                               "cross-shape edge adjacency must be reciprocal: " + pk + " <-> " + vk);
                    crossShapeEdgeChecks++;
                }
            }
        }
        assertTrue(crossShapeVertexChecks >= 5,
                   "exhaustive 0utt must surface cross-shape tet VERTEX neighbors beyond faces, got "
                   + crossShapeVertexChecks);
        assertTrue(crossShapeEdgeChecks >= 1,
                   "exhaustive 0utt must surface at least one cross-shape tet EDGE neighbor, got "
                   + crossShapeEdgeChecks);
    }

    @Test
    void vertexNeighborsAreCompleteVsWholeDomainBruteForce() {
        // Independent completeness oracle (closes the symmetric-miss blind spot reciprocity can't catch):
        // enumerate EVERY valid level-2 SFC element across the WHOLE domain (not just the 27-cube), and
        // for a query assert findVertexNeighbors contains every element sharing >=1 vertex. If the 27-cube
        // grid were under-sized, this brute force would find a vertex-sharing element the detector missed.
        int len = Constants.lengthAtLevel((byte) 2);
        var universe = new ArrayList<javax.vecmath.Point3i[]>();
        var universeKeys = new ArrayList<PyramidKey>();
        for (int ax = 0; ax <= Constants.MAX_COORD; ax += len) {
            for (int ay = 0; ay <= Constants.MAX_COORD; ay += len) {
                for (int az = 0; az <= Constants.MAX_COORD; az += len) {
                    for (byte t = Pyramid.TYPE_6; t <= Pyramid.TYPE_7; t++) {
                        var p = new Pyramid(ax, ay, az, (byte) 2, t);
                        var k = PyramidKeyCodec.encode(p);
                        if (k != null) {
                            universe.add(p.coordinates());
                            universeKeys.add(k);
                        }
                    }
                    for (byte t = 0; t < 6; t++) {
                        var tet = new Tet(ax, ay, az, (byte) 2, t, (byte) 2);
                        var k = PyramidKeyCodec.encode(tet);
                        if (k != null) {
                            universe.add(tet.coordinates());
                            universeKeys.add(k);
                        }
                    }
                }
            }
        }
        assertTrue(universe.size() > 50, "expected a populated level-2 universe, got " + universe.size());

        // Pick an interior query (a pyramid one cube off the origin corner — neighbours in all directions).
        PyramidKey queryKey = null;
        javax.vecmath.Point3i[] queryVerts = null;
        for (int i = 0; i < universeKeys.size(); i++) {
            var k = universeKeys.get(i);
            if (!isTetLeaf(k) && k.getCoordBitsAtLevel(1) == 0 && k.getCoordBitsAtLevel(2) == 7) {
                queryKey = k;
                queryVerts = universe.get(i);
                break;
            }
        }
        assertNotNull(queryKey, "expected an interior level-2 pyramid query");

        var found = new java.util.HashSet<>(detector.findVertexNeighbors(queryKey));
        int checked = 0;
        for (int i = 0; i < universeKeys.size(); i++) {
            if (universeKeys.get(i).equals(queryKey)) {
                continue;
            }
            if (sharedVertexCount(queryVerts, universe.get(i)) >= 1) {
                assertTrue(found.contains(universeKeys.get(i)),
                           "27-cube enumeration missed a whole-domain vertex-sharing neighbor: "
                           + universeKeys.get(i));
                checked++;
            }
        }
        assertTrue(checked >= 5, "expected the query to have several vertex neighbors, got " + checked);
    }

    private static int sharedVertexCount(javax.vecmath.Point3i[] a, javax.vecmath.Point3i[] b) {
        int shared = 0;
        for (var pa : a) {
            for (var pb : b) {
                if (pa.equals(pb)) {
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    @Test
    void faceSubsetOfEdgeSubsetOfVertex() {
        for (var p : validPyramids(4)) {
            var pk = PyramidKeyCodec.encode(p);
            if (pk == null) {
                continue;
            }
            var faces = new java.util.HashSet<>(detector.findFaceNeighbors(pk));
            var edges = new java.util.HashSet<>(detector.findEdgeNeighbors(pk));
            var verts = new java.util.HashSet<>(detector.findVertexNeighbors(pk));
            assertTrue(edges.containsAll(faces), "face ⊆ edge for " + pk);
            assertTrue(verts.containsAll(edges), "edge ⊆ vertex for " + pk);
        }
    }

    @Test
    void tetLeafQueryHasExhaustiveEdgeVertexNeighbors() {
        // A tet-leaf query previously got only its cross-shape face set from edge/vertex. Now it gets the
        // full all-shape edge/vertex set — including same-shape pyramid neighbors sharing an edge/vertex.
        PyramidKey tetKey = null;
        for (var p : validPyramids(5)) {
            if (p.level() < 2) {
                continue;
            }
            for (int f = 0; f < 4; f++) {
                var fn = p.faceNeighbor(f);
                if (fn != null && fn.element() instanceof Tet t) {
                    var tk = PyramidKeyCodec.encode(t);
                    if (tk != null) {
                        tetKey = tk;
                        break;
                    }
                }
            }
            if (tetKey != null) {
                break;
            }
        }
        assertNotNull(tetKey, "expected an in-domain shallow tet-leaf key");

        var verts = detector.findVertexNeighbors(tetKey);
        var faces = new java.util.HashSet<>(detector.findFaceNeighbors(tetKey));
        // The vertex set is a strict superset of the face set (the tet has neighbors sharing only a
        // vertex/edge, not a face).
        assertTrue(verts.size() > faces.size(),
                   "tet-leaf vertex neighbors must exceed its face neighbors (exhaustive edge/vertex)");
        // At least one vertex neighbor is a pyramid (cross-shape tet→pyramid vertex fan) and reciprocal.
        final var tk = tetKey;
        var pyrNeighbor = verts.stream().filter(k -> !isTetLeaf(k)).findFirst();
        assertTrue(pyrNeighbor.isPresent(), "a tet-leaf must have a pyramid vertex neighbor");
        assertTrue(detector.findVertexNeighbors(pyrNeighbor.get()).contains(tk),
                   "tet→pyramid vertex adjacency must be reciprocal");
    }
}
