/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;

import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RDR-014 Phase 1 independent geometric oracle for cross-level edge/vertex neighbors, implementing the
 * user-confirmed CONTRACT (A) ADJACENCY (see RDR-014 "Implementation-time decision"). The oracle derives
 * expected neighbor sets purely from {@link Tet#coordinates()} geometry (integer vertices → exact
 * collinearity / coincidence tests) — it does NOT re-derive from the connectivity tables the implementation
 * uses ({@code CHILDREN_AT_FACE} / {@code CHILD_VERTEX_PARENT_VERTEX}), so the fixtures cannot be satisfied
 * by a table-tautological or self-children-only implementation.
 *
 * @author hal.hildebrand
 */
final class CrossLevelNeighborOracle {

    private CrossLevelNeighborOracle() {
    }

    /** Collect a refined tet tree (levels 1..maxDepth) by descending the root via Bey children. */
    static List<Tet> refinedTets(int maxDepth) {
        var out = new ArrayList<Tet>();
        descend(new Tet(0, 0, 0, (byte) 0, (byte) 0), maxDepth, out);
        return out;
    }

    private static void descend(Tet t, int maxDepth, List<Tet> out) {
        if (t.l() >= 1) {
            out.add(t);
        }
        if (t.l() >= maxDepth) {
            return;
        }
        for (int c = 0; c < TetreeConnectivity.CHILDREN_PER_TET; c++) {
            try {
                descend(t.child(c), maxDepth, out);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // invalid / out-of-domain / max-level child — skip (do not mask programming errors)
            }
        }
    }

    /** Full edge-neighbor set of {@code t}, aggregated over all 6 edges. */
    static Set<TetreeKey<?>> fullEdgeNeighbors(TetreeNeighborFinder finder, Tet t) {
        var all = new HashSet<TetreeKey<?>>();
        var key = t.tmIndex();
        for (int e = 0; e < TetreeConnectivity.EDGES_PER_TET; e++) {
            all.addAll(finder.findEdgeNeighbors(key, e));
        }
        return all;
    }

    /** Full vertex-neighbor set of {@code t}, aggregated over all 4 vertices. */
    static Set<TetreeKey<?>> fullVertexNeighbors(TetreeNeighborFinder finder, Tet t) {
        var all = new HashSet<TetreeKey<?>>();
        var key = t.tmIndex();
        for (int v = 0; v < TetreeConnectivity.VERTICES_PER_TET; v++) {
            all.addAll(finder.findVertexNeighbors(key, v));
        }
        return all;
    }

    /**
     * Contract-(A) finer (level+1) EDGE ring, scoped to the ±1 live reach (RDR-014 F1): the children of
     * {@code t}'s two same-level bounding-face neighbors that lie along the shared edge. These are
     * adjacency neighbors of {@code t} at level L+1 — never {@code t}'s own nested children. Computed via
     * the validated same-level {@code findFaceNeighbor} plus a pure geometric edge-incidence test on the
     * neighbor's children.
     *
     * <p><b>Scope invariant (intentional, not a gap):</b> the edge-neighbor relation in this codebase is
     * defined as the (at most) two face-neighbors across the edge's bounding faces — see the same-level
     * {@code TetreeNeighborFinder.findEdgeNeighbors}, which collects exactly {@code EDGE_FACES[edge]}'s two
     * face neighbors. This is the non-conforming Bey-SFC neighbor relation (a face neighbor shares 0–3
     * vertices), NOT the full conforming geometric edge ring (which in a conforming Kuhn mesh would have
     * valence 4–6). The finer ring therefore extends exactly those two neighbors per RDR D1' (±1 via
     * {@code findDescendantsAtLevel} on the bounding-face neighbors). Pinning more (a full conforming-ring
     * walk) would contradict the locked scope and reject the intended Phase 2 implementation.
     */
    static Set<TetreeKey<?>> finerEdgeRingPlusMinus1(TetreeNeighborFinder finder, Tet t, int edge) {
        var verts = t.coordinates();
        var pa = verts[TetreeConnectivity.EDGE_VERTICES[edge][0]];
        var pb = verts[TetreeConnectivity.EDGE_VERTICES[edge][1]];
        var ring = new HashSet<TetreeKey<?>>();
        for (int f : TetreeConnectivity.EDGE_FACES[edge]) {
            var n = finder.findFaceNeighbor(t, f);
            if (n == null) {
                continue;
            }
            for (int c = 0; c < TetreeConnectivity.CHILDREN_PER_TET; c++) {
                Tet child;
                try {
                    child = n.child(c);
                } catch (RuntimeException e) {
                    continue;
                }
                if (sharesEdgeSegment(child, pa, pb)) {
                    ring.add(child.tmIndex());
                }
            }
        }
        return ring;
    }

    /**
     * Contract-(A) finer (level+1) VERTEX star: every level-(L+1) tet that carries the shared vertex,
     * EXCLUDING {@code t}'s own children (a parent is not an adjacency neighbor of its child). Enumerated
     * by a geometric box sweep around the vertex and filtered by exact vertex coincidence — independent of
     * any connectivity table.
     */
    static Set<TetreeKey<?>> finerVertexStarPlus1(Tet t, int vertex) {
        var p = t.coordinates()[vertex];
        byte lf = (byte) (t.l() + 1);
        int h = Constants.lengthAtLevel(lf);
        var star = new HashSet<TetreeKey<?>>();
        // A level-(L+1) tet with anchor a has vertices within [a, a+h], so to carry point p its anchor
        // must lie in [p-h, p] per axis. Sweep that box, all 6 types.
        for (int ax = anchorFloor(p.x - h, h); ax <= p.x; ax += h) {
            for (int ay = anchorFloor(p.y - h, h); ay <= p.y; ay += h) {
                for (int az = anchorFloor(p.z - h, h); az <= p.z; az += h) {
                    if (ax < 0 || ay < 0 || az < 0) {
                        continue;
                    }
                    for (byte type = 0; type < TetreeConnectivity.TET_TYPES; type++) {
                        Tet cand;
                        try {
                            cand = new Tet(ax, ay, az, lf, type);
                            if (!cand.isValid()) {
                                continue;
                            }
                        } catch (RuntimeException e) {
                            continue;
                        }
                        if (!hasVertex(cand, p)) {
                            continue;
                        }
                        if (cand.parent().equals(t)) {
                            continue; // exclude t's own nested children (contract A)
                        }
                        star.add(cand.tmIndex());
                    }
                }
            }
        }
        return star;
    }

    /** The level-(L+1) members of {@code result} (the finer slice of a merged neighbor set). */
    static Set<TetreeKey<?>> finerSlice(Set<TetreeKey<?>> result, byte level) {
        var out = new HashSet<TetreeKey<?>>();
        for (var k : result) {
            if (Tet.tetrahedron(k).l() == level + 1) {
                out.add(k);
            }
        }
        return out;
    }

    /** True iff at least 2 vertices of {@code cand} lie on the segment [pa, pb] (i.e. cand has an edge on it). */
    static boolean sharesEdgeSegment(Tet cand, Point3i pa, Point3i pb) {
        int onSeg = 0;
        for (var v : cand.coordinates()) {
            if (onSegment(v, pa, pb)) {
                onSeg++;
            }
        }
        return onSeg >= 2;
    }

    static boolean hasVertex(Tet cand, Point3i p) {
        for (var v : cand.coordinates()) {
            if (v.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** Exact integer test: v is collinear with [pa,pb] and lies within the inclusive segment. */
    private static boolean onSegment(Point3i v, Point3i pa, Point3i pb) {
        long dx = pb.x - pa.x, dy = pb.y - pa.y, dz = pb.z - pa.z;
        long wx = v.x - pa.x, wy = v.y - pa.y, wz = v.z - pa.z;
        // collinear: (d × w) == 0
        long cx = dy * wz - dz * wy;
        long cy = dz * wx - dx * wz;
        long cz = dx * wy - dy * wx;
        if (cx != 0 || cy != 0 || cz != 0) {
            return false;
        }
        long dot = wx * dx + wy * dy + wz * dz;
        long len2 = dx * dx + dy * dy + dz * dz;
        return dot >= 0 && dot <= len2;
    }

    private static int anchorFloor(int v, int h) {
        int a = (v / h) * h;
        return a > v ? a - h : a; // floor toward -inf (v can be slightly negative)
    }
}
