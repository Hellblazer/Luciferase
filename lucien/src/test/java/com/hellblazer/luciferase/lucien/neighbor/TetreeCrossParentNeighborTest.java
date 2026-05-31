/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.neighbor;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation for Luciferase-v9vm: {@code TetreeNeighborDetector.findNonSiblingNeighborsSharing}, the
 * cross-parent edge/vertex neighbor walk that used to be an unimplemented stub.
 *
 * <p>Three independent checks, none circular w.r.t. the SUT's own descent:
 * <ul>
 *   <li><b>Brute-force geometric ground truth</b> — over every level-{@code L} tet in the domain, the
 *       detector's edge/vertex-neighbor set must EXACTLY equal the set of OTHER level-{@code L} tets whose
 *       vertex set contains the element's endpoint(s). This is the definition of edge/vertex sharing
 *       (legitimate for the sharing relation; distinct from the forbidden "&ge;N shared vertices" check on
 *       non-conforming FACE neighbors). The oracle is independent of the SUT's <em>search strategy</em> (it
 *       enumerates exhaustively rather than via the {@code cubeEncloses} prune), so it catches descent/prune
 *       errors. It does share the {@code Tet.child()} / {@code Tet.coordinates()} primitives with the SUT —
 *       a systematic fault there would be invisible to BOTH, so those primitives are validated separately by
 *       {@code T8codeDtetOracleTest} / {@code T8codeDtetFaceNeighborOracleTest}.</li>
 *   <li><b>Reciprocity / involution</b> — A is an edge(/vertex) neighbor of B iff B is one of A.</li>
 *   <li><b>Non-vacuous growth</b> — for a tet whose edge/vertex lies on a parent boundary, the full
 *       edge/vertex neighbor set is strictly larger than the same-parent sibling face neighbors, proving the
 *       cross-parent walk actually contributes (the stub returned no cross-parent neighbors).</li>
 * </ul>
 */
class TetreeCrossParentNeighborTest {

    private static final byte LEVEL = 3;

    private Tetree<LongEntityID, String> tetree;
    private TetreeNeighborDetector detector;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        detector = new TetreeNeighborDetector(tetree);
    }

    /** Every level-LEVEL tet reachable from root via Bey refinement, deduplicated. */
    private List<Tet> allTetsAtLevel(byte level) {
        var out = new ArrayList<Tet>();
        var seen = new HashSet<Tet>();
        var work = new ArrayDeque<Tet>();
        work.push(new Tet(0, 0, 0, (byte) 0, (byte) 0));
        while (!work.isEmpty()) {
            var t = work.pop();
            if (t.l() == level) {
                if (seen.add(t)) {
                    out.add(t);
                }
                continue;
            }
            for (int c = 0; c < 8; c++) {
                work.push(t.child(c));
            }
        }
        return out;
    }

    private static boolean inDomain(Tet t) {
        int max = Constants.lengthAtLevel((byte) 0);
        return t.x() >= 0 && t.x() < max && t.y() >= 0 && t.y() < max && t.z() >= 0 && t.z() < max;
    }

    private static boolean hasVertex(Tet t, Point3i p) {
        for (var c : t.coordinates()) {
            if (c.x == p.x && c.y == p.y && c.z == p.z) {
                return true;
            }
        }
        return false;
    }

    /** Independent brute-force oracle: all OTHER level-L tets sharing every element endpoint with `tet`. */
    private Set<TetreeKey<?>> bruteForceSharers(Tet tet, List<Tet> universe, Point3i[] target) {
        var result = new HashSet<TetreeKey<?>>();
        for (var other : universe) {
            if (other.equals(tet)) {
                continue;
            }
            boolean all = true;
            for (var p : target) {
                if (!hasVertex(other, p)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                result.add(other.tmIndex());
            }
        }
        return result;
    }

    @Test
    void edgeNeighborsMatchBruteForceGeometricGroundTruth() {
        var universe = allTetsAtLevel(LEVEL);
        assertTrue(universe.size() >= 512, "expected full level-" + LEVEL + " coverage, got " + universe.size());
        int checked = 0;
        int withCrossParent = 0;
        for (var tet : universe) {
            for (int edge = 0; edge < 6; edge++) {
                var coords = tet.coordinates();
                int[] ev = edgeVertices(edge);
                var target = new Point3i[] { coords[ev[0]], coords[ev[1]] };

                var expected = bruteForceSharers(tet, universe, target);
                var actual = new HashSet<>(detector.findNeighborsSharingEdge(tet, edge));

                assertEquals(expected, actual,
                             "edge-neighbor set must equal brute-force geometric sharers for " + tet + " edge " + edge);
                checked++;
                if (!expected.isEmpty()) {
                    withCrossParent++;
                }
            }
        }
        assertTrue(checked > 3000, "expected broad edge coverage, got " + checked);
        assertTrue(withCrossParent > 0, "expected some edges to have neighbors, got " + withCrossParent);
    }

    @Test
    void vertexNeighborsMatchBruteForceGeometricGroundTruth() {
        var universe = allTetsAtLevel(LEVEL);
        int checked = 0;
        for (var tet : universe) {
            for (int vertex = 0; vertex < 4; vertex++) {
                var coords = tet.coordinates();
                var target = new Point3i[] { coords[vertex] };

                var expected = bruteForceSharers(tet, universe, target);
                var actual = new HashSet<>(detector.findNeighborsSharingVertex(tet, vertex));

                assertEquals(expected, actual,
                             "vertex-neighbor set must equal brute-force geometric sharers for " + tet + " vertex "
                             + vertex);
                checked++;
            }
        }
        assertTrue(checked > 2000, "expected broad vertex coverage, got " + checked);
    }

    @Test
    void edgeAndVertexNeighborsAreReciprocal() {
        var universe = allTetsAtLevel(LEVEL);
        // Build keyed edge-neighbor relation over the full uniform leaf set and assert symmetry.
        var keyToTet = new java.util.HashMap<TetreeKey<?>, Tet>();
        for (var t : universe) {
            keyToTet.put(t.tmIndex(), t);
        }
        for (var tet : universe) {
            var key = tet.tmIndex();
            var edgeNbrs = new HashSet<>(detector.findEdgeNeighbors(key));
            for (var n : edgeNbrs) {
                // Only reciprocity-check neighbors that are themselves level-L domain leaves.
                var nt = keyToTet.get(n);
                if (nt == null) {
                    continue;
                }
                var back = new HashSet<>(detector.findEdgeNeighbors(n));
                assertTrue(back.contains(key),
                           "edge-neighbor relation must be reciprocal: " + tet + " -> " + nt + " but not back");
            }
            var vertexNbrs = new HashSet<>(detector.findVertexNeighbors(key));
            for (var n : vertexNbrs) {
                var nt = keyToTet.get(n);
                if (nt == null) {
                    continue;
                }
                var back = new HashSet<>(detector.findVertexNeighbors(n));
                assertTrue(back.contains(key),
                           "vertex-neighbor relation must be reciprocal: " + tet + " -> " + nt + " but not back");
            }
        }
    }

    @Test
    void crossParentWalkStrictlyGrowsBeyondSiblingFaceNeighbors() {
        // Find a tet whose edge has neighbors that are NOT same-parent siblings, proving cross-parent
        // discovery. The stub produced only same-parent siblings; this would have failed against it.
        var universe = allTetsAtLevel(LEVEL);
        boolean demonstrated = false;
        for (var tet : universe) {
            var parent = tet.parent();
            var siblings = new HashSet<TetreeKey<?>>();
            for (int c = 0; c < 8; c++) {
                siblings.add(parent.child(c).tmIndex());
            }
            for (int edge = 0; edge < 6 && !demonstrated; edge++) {
                var nbrs = detector.findNeighborsSharingEdge(tet, edge);
                for (var n : nbrs) {
                    if (!siblings.contains(n)) {
                        demonstrated = true; // a genuine cross-parent edge neighbor
                        break;
                    }
                }
            }
            if (demonstrated) {
                break;
            }
        }
        assertTrue(demonstrated,
                   "expected at least one tet with a cross-parent (non-sibling) edge neighbor — the stub never produced any");
    }

    private static int[] edgeVertices(int edge) {
        return switch (edge) {
            case 0 -> new int[] { 0, 1 };
            case 1 -> new int[] { 0, 2 };
            case 2 -> new int[] { 0, 3 };
            case 3 -> new int[] { 1, 2 };
            case 4 -> new int[] { 1, 3 };
            default -> new int[] { 2, 3 };
        };
    }
}
