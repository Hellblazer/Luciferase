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

/**
 * Neighbor finding algorithms for tetrahedral trees. Implements t8code's face neighbor finding using connectivity
 * tables.
 *
 * @author hal.hildebrand
 */
public class TetreeNeighborFinder {

    /**
     * Check if two tetrahedra are face neighbors.
     *
     * @param tet1 First tetrahedron
     * @param tet2 Second tetrahedron
     * @return true if they share a face
     */
    public boolean areNeighbors(Tet tet1, Tet tet2) {
        // Check if tet2 is a neighbor of any face of tet1
        for (var face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            var neighbor = findFaceNeighbor(tet1, face);
            if (neighbor != null && neighbor.equals(tet2)) {
                return true;
            }
        }

        // Also check the reverse (in case of level differences)
        for (var face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            var neighbor = findFaceNeighbor(tet2, face);
            if (neighbor != null && neighbor.equals(tet1)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Find all face-adjacent neighbors of a tetrahedron.
     *
     * @param tet The tetrahedron to find neighbors of
     * @return List of neighboring tetrahedra (may be less than 4 at boundaries)
     */
    public List<Tet> findAllNeighbors(Tet tet) {
        var neighbors = new ArrayList<Tet>();

        // Check all 4 faces
        for (var face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            var neighbor = findFaceNeighbor(tet, face);
            if (neighbor != null) {
                neighbors.add(neighbor);
            }
        }

        return neighbors;
    }

    /**
     * Find all neighbors that share a specific edge with the given tetrahedron. Each tetrahedron has 6 edges
     * (connecting pairs of its 4 vertices).
     *
     * @param tetIndex  The SFC index of the tetrahedron
     * @param edgeIndex The edge index (0-5)
     * @return List of neighbor tetrahedron indices sharing the specified edge
     */
    public List<TetreeKey<?>> findEdgeNeighbors(TetreeKey<? extends TetreeKey<?>> tetIndex, int edgeIndex) {
        if (edgeIndex < 0 || edgeIndex > 5) {
            throw new IllegalArgumentException("Edge index must be between 0 and 5, got: " + edgeIndex);
        }

        var tet = Tet.tetrahedron(tetIndex);
        var edgeNeighbors = new ArrayList<TetreeKey<?>>();

        // Each edge is bounded by exactly two faces; use the single canonical edge->face table
        // (RDR-014 F4/AC2). The previous inline table here was geometrically WRONG (e.g. edge 0
        // listed faces {0,2} instead of {2,3}); it is removed in favor of TetreeConnectivity.EDGE_FACES.
        var edgeToFaces = TetreeConnectivity.EDGE_FACES;

        // Check neighbors across both faces that share this edge
        var uniqueNeighbors = new HashSet<TetreeKey<?>>();
        for (var faceIndex : edgeToFaces[edgeIndex]) {
            var neighbor = findFaceNeighbor(tet, faceIndex);
            if (neighbor != null) {
                uniqueNeighbors.add(neighbor.tmIndex());
            }
        }

        // Also need to check for neighbors at different levels that share the edge
        var level = tet.l();

        // Check coarser level. CONTRACT (A) coarser neighbors are the EXACT inverse of the finer ring: a
        // level-(L-1) tet m is a coarser edge-neighbor of tet ACROSS THIS edge iff tet lies in m's finer
        // adjacency ring for the m-edge that geometrically coincides with tet's queried edge [pa,pb]. m must be
        // a face-neighbor of tet's parent (so tet is a child of one of m's face-neighbors). Scoping to the
        // coincident m-edge (not the union over all of m's edges) is required: tet may share a DIFFERENT edge
        // with m, which must not be reported as a neighbor across THIS edge. This makes the relation reciprocal
        // by construction per-edge (RDR-014 AC3): tet→m across [pa,pb] ⟹ m's finer ring on that edge ∋ tet ⟹ m→tet.
        if (level > 0) {
            var parent = tet.parent();
            var pa = tet.coordinates()[TetreeConnectivity.EDGE_VERTICES[edgeIndex][0]];
            var pb = tet.coordinates()[TetreeConnectivity.EDGE_VERTICES[edgeIndex][1]];
            for (var face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
                var m = findFaceNeighbor(parent, face);
                if (m == null || m.l() != level - 1) {
                    continue;
                }
                if (isCoarserEdgeNeighbor(m, tet.tmIndex(), pa, pb)) {
                    uniqueNeighbors.add(m.tmIndex());
                }
            }
        }

        // Check finer level
        if (level < Constants.getMaxRefinementLevel()) {
            var childEdgeNeighbors = findEdgeNeighborsAtLevel(tet, edgeIndex, (byte) (level + 1));
            uniqueNeighbors.addAll(childEdgeNeighbors);
        }

        edgeNeighbors.addAll(uniqueNeighbors);
        return edgeNeighbors;
    }

    /**
     * Find the neighbor across a specific face of a tetrahedron.
     *
     * @param tet       The tetrahedron to find the neighbor of
     * @param faceIndex The face index (0-3)
     * @return The neighbor tetrahedron, or null if at boundary
     */
    public Tet findFaceNeighbor(Tet tet, int faceIndex) {
        if (faceIndex < 0 || faceIndex >= TetreeConnectivity.FACES_PER_TET) {
            throw new IllegalArgumentException("Face index must be 0-3: " + faceIndex);
        }

        // Use t8code's face neighbor algorithm
        var neighbor = tet.faceNeighbor(faceIndex);

        // Check if neighbor exists (null when at boundary of positive octant)
        if (neighbor == null) {
            return null; // At boundary
        }

        // Check if neighbor is within domain bounds
        if (isWithinDomain(neighbor.tet())) {
            return neighbor.tet();
        }

        return null; // At boundary
    }

    /**
     * Find neighbors at a different refinement level. This handles the case where neighbors may be coarser or finer.
     *
     * @param tet         The tetrahedron to find neighbors of
     * @param targetLevel The desired neighbor level
     * @return List of neighbors at the target level
     */
    public List<Tet> findNeighborsAtLevel(Tet tet, byte targetLevel) {
        if (targetLevel < 0 || targetLevel > Constants.getMaxRefinementLevel()) {
            throw new IllegalArgumentException("Invalid target level: " + targetLevel);
        }

        var neighbors = new ArrayList<Tet>();

        // For each face, find neighbor at appropriate level
        for (var face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            var immediateNeighbor = findFaceNeighbor(tet, face);
            if (immediateNeighbor == null) {
                continue; // Boundary face
            }

            // Adjust neighbor to target level
            if (immediateNeighbor.l() == targetLevel) {
                // Already at target level
                neighbors.add(immediateNeighbor);
            } else if (immediateNeighbor.l() < targetLevel) {
                // Neighbor is coarser, find descendants at target level
                var descendants = findDescendantsAtLevel(immediateNeighbor, targetLevel, face);
                neighbors.addAll(descendants);
            } else {
                // Neighbor is finer, find ancestor at target level
                var ancestor = findAncestorAtLevel(immediateNeighbor, targetLevel);
                if (ancestor != null && !neighbors.contains(ancestor)) {
                    neighbors.add(ancestor);
                }
            }
        }

        return neighbors;
    }

    /**
     * Find all neighbors within a certain distance (in terms of face crossings).
     *
     * @param tet      The starting tetrahedron
     * @param distance The maximum distance in face crossings
     * @return List of tetrahedra within the distance
     */
    public List<Tet> findNeighborsWithinDistance(Tet tet, int distance) {
        if (distance < 0) {
            throw new IllegalArgumentException("Distance must be non-negative: " + distance);
        }

        var result = new ArrayList<Tet>();
        var currentLayer = new ArrayList<Tet>();
        var nextLayer = new ArrayList<Tet>();

        // Start with the given tetrahedron
        currentLayer.add(tet);
        result.add(tet);

        // Expand layer by layer
        for (var d = 0; d < distance; d++) {
            for (var current : currentLayer) {
                var neighbors = findAllNeighbors(current);
                for (var neighbor : neighbors) {
                    if (!result.contains(neighbor)) {
                        nextLayer.add(neighbor);
                        result.add(neighbor);
                    }
                }
            }

            // Swap layers
            currentLayer = nextLayer;
            nextLayer = new ArrayList<>();
        }

        return result;
    }

    /**
     * Find the shared face between two neighboring tetrahedra.
     *
     * @param tet1 First tetrahedron
     * @param tet2 Second tetrahedron
     * @return The face index on tet1 that is shared with tet2, or -1 if not neighbors
     */
    public int findSharedFace(Tet tet1, Tet tet2) {
        for (var face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            var neighbor = findFaceNeighbor(tet1, face);
            if (neighbor != null && neighbor.equals(tet2)) {
                return face;
            }
        }
        return -1;
    }

    /**
     * Find all neighbors that share a specific vertex with the given tetrahedron. Each tetrahedron has 4 vertices.
     *
     * @param tetIndex    The SFC index of the tetrahedron
     * @param vertexIndex The vertex index (0-3)
     * @return List of neighbor tetrahedron indices sharing the specified vertex
     */
    public List<TetreeKey<?>> findVertexNeighbors(TetreeKey<? extends TetreeKey<?>> tetIndex, int vertexIndex) {
        if (vertexIndex < 0 || vertexIndex > 3) {
            throw new IllegalArgumentException("Vertex index must be between 0 and 3, got: " + vertexIndex);
        }

        var tet = Tet.tetrahedron(tetIndex);
        var vertexNeighbors = new HashSet<TetreeKey<?>>();

        // Vertex-to-face mapping for tetrahedron:
        // Vertex 0: faces 1, 2, 3
        // Vertex 1: faces 0, 2, 3
        // Vertex 2: faces 0, 1, 3
        // Vertex 3: faces 0, 1, 2
        var vertexToFaces = new int[][] { { 1, 2, 3 },  // Vertex 0
                                          { 0, 2, 3 },  // Vertex 1
                                          { 0, 1, 3 },  // Vertex 2
                                          { 0, 1, 2 }   // Vertex 3
        };

        // First, find all face neighbors
        for (var faceIndex : vertexToFaces[vertexIndex]) {
            var neighbor = findFaceNeighbor(tet, faceIndex);
            if (neighbor != null) {
                vertexNeighbors.add(neighbor.tmIndex());
            }
        }

        // Also find edge neighbors for edges containing this vertex
        // Vertex-to-edge mapping:
        // Vertex 0: edges 0, 1, 2
        // Vertex 1: edges 0, 3, 4
        // Vertex 2: edges 1, 3, 5
        // Vertex 3: edges 2, 4, 5
        var vertexToEdges = new int[][] { { 0, 1, 2 },  // Vertex 0
                                          { 0, 3, 4 },  // Vertex 1
                                          { 1, 3, 5 },  // Vertex 2
                                          { 2, 4, 5 }   // Vertex 3
        };

        for (var edgeIndex : vertexToEdges[vertexIndex]) {
            var edgeNeighborsList = findEdgeNeighbors(tetIndex, edgeIndex);
            vertexNeighbors.addAll(edgeNeighborsList);
        }

        // Check different levels for vertex neighbors
        var level = tet.l();

        // Check the coarser level. ±1 live reach only (RDR-014 F1), symmetric to the finer star so the
        // cross-level relation is reciprocal (AC3). Pass the ORIGINAL tet (not its ancestor) so the helper
        // resolves the shared vertex POINT from tet.coordinates()[vertexIndex]; re-deriving it from an
        // ancestor's same index would pick a different geometric vertex (the anchor/index correspondence is not
        // preserved up the parent chain).
        if (level > 0) {
            vertexNeighbors.addAll(findVertexNeighborsAtLevel(tet, vertexIndex, (byte) (level - 1)));
        }

        // Check finer levels
        if (level < Constants.getMaxRefinementLevel()) {
            var finerNeighbors = findVertexNeighborsAtFinerLevels(tet, vertexIndex, (byte) (level + 1));
            vertexNeighbors.addAll(finerNeighbors);
        }

        // A cross-level (level != L) vertex neighbor must genuinely carry the shared vertex (CONTRACT A). The
        // edge-neighbor aggregation above pulls in finer/coarser edge rings whose tets touch an edge THROUGH
        // the vertex but need not be coincident with it; drop those so the cross-level slices equal exactly the
        // adjacency star (RDR-014 AC4). Same-level adjacency (face/edge ring) is left untouched.
        var vp = tet.coordinates()[vertexIndex];
        vertexNeighbors.removeIf(k -> {
            var other = Tet.tetrahedron(k);
            return other.l() != level && !hasVertex(other, vp);
        });

        // Remove self
        vertexNeighbors.remove(tetIndex);

        return new ArrayList<>(vertexNeighbors);
    }

    // Find ancestor at a specific level
    private Tet findAncestorAtLevel(Tet descendant, byte targetLevel) {
        if (descendant.l() <= targetLevel) {
            return descendant;
        }

        var current = descendant;
        while (current.l() > targetLevel && current.l() > 0) {
            current = current.parent();
        }

        return current.l() == targetLevel ? current : null;
    }

    // Find descendants at a specific level touching a given face
    private List<Tet> findDescendantsAtLevel(Tet ancestor, byte targetLevel, int ancestorFace) {
        var descendants = new ArrayList<Tet>();

        if (ancestor.l() >= targetLevel) {
            return descendants; // No descendants at this level
        }

        // Get children that touch the ancestor face
        var childrenAtFace = TetreeConnectivity.getChildrenAtFace(ancestor.type(), ancestorFace);

        for (var childIndex : childrenAtFace) {
            try {
                var child = ancestor.child(childIndex);

                if (child.l() == targetLevel) {
                    descendants.add(child);
                } else if (child.l() < targetLevel) {
                    // Recursively find descendants
                    // Determine which face of the child corresponds to ancestor's face
                    var childFace = TetreeConnectivity.getChildFace(ancestor.type(), childIndex, ancestorFace);
                    if (childFace != -1) {
                        descendants.addAll(findDescendantsAtLevel(child, targetLevel, childFace));
                    }
                }
            } catch (IllegalStateException e) {
                // Max level reached
            }
        }

        return descendants;
    }

    /**
     * Cross-level EDGE neighbors under CONTRACT (A) ADJACENCY (RDR-014). Returns the level-{@code targetLevel}
     * tets that share {@code tet}'s edge {@code edgeIndex} and are adjacent to {@code tet} via its (at most two)
     * bounding-face neighbors — never {@code tet}'s own nested children. Scope is ±1 (RDR-014 F1): the public
     * caller invokes this with {@code targetLevel == tet.level()} (coarser-side, passing the parent) or
     * {@code targetLevel == tet.level()+1} (finer-side, passing the tet itself).
     *
     * <ul>
     *   <li><b>Same level</b> ({@code targetLevel == tet.level()}): the bounding-face neighbors of {@code tet}
     *       across {@code EDGE_FACES[edgeIndex]} that actually share the edge segment.</li>
     *   <li><b>Finer</b> ({@code targetLevel > tet.level()}): the children of those bounding-face neighbors that
     *       lie along the shared edge — the contract-(A) adjacency ring, matching the independent geometric
     *       oracle {@code CrossLevelNeighborOracle.finerEdgeRingPlusMinus1}.</li>
     * </ul>
     */
    private List<TetreeKey<?>> findEdgeNeighborsAtLevel(Tet tet, int edgeIndex, byte targetLevel) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        if (targetLevel < 0 || targetLevel > Constants.getMaxRefinementLevel() || targetLevel < tet.l()) {
            return neighbors; // ±1 scope: callers only ask same-level or finer (RDR-014 F1)
        }
        var verts = tet.coordinates();
        var pa = verts[TetreeConnectivity.EDGE_VERTICES[edgeIndex][0]];
        var pb = verts[TetreeConnectivity.EDGE_VERTICES[edgeIndex][1]];
        for (var faceIndex : TetreeConnectivity.EDGE_FACES[edgeIndex]) {
            var neighbor = findFaceNeighbor(tet, faceIndex);
            if (neighbor == null) {
                continue; // boundary face
            }
            if (targetLevel == tet.l()) {
                if (neighbor.l() == targetLevel && sharesEdgeSegment(neighbor, pa, pb)) {
                    neighbors.add(neighbor.tmIndex());
                }
            } else { // targetLevel > tet.l(): finer adjacency ring = neighbor's children along the edge
                for (var c = 0; c < TetreeConnectivity.CHILDREN_PER_TET; c++) {
                    Tet child;
                    try {
                        child = neighbor.child(c);
                    } catch (RuntimeException e) {
                        continue; // invalid / max-level child
                    }
                    if (child.l() == targetLevel && sharesEdgeSegment(child, pa, pb)) {
                        neighbors.add(child.tmIndex());
                    }
                }
            }
        }
        return neighbors;
    }

    /**
     * True iff coarser tet {@code m} is an edge-neighbor of the tet whose queried edge is the segment
     * [{@code pa}, {@code pb}] (identified by {@code tetKey}). Scoped to the single m-edge that geometrically
     * coincides with [pa, pb] (both query endpoints lie on it): for that m-edge, {@code tetKey} must appear in
     * {@code m}'s finer adjacency ring. Per-edge scoping prevents reporting {@code m} across an edge the query
     * tet does not actually share with {@code m} (RDR-014 AC3 reciprocity is then per-edge, not all-edges).
     */
    private boolean isCoarserEdgeNeighbor(Tet m, TetreeKey<?> tetKey, Point3i pa, Point3i pb) {
        var finer = (byte) (m.l() + 1);
        var mv = m.coordinates();
        for (var em = 0; em < TetreeConnectivity.EDGES_PER_TET; em++) {
            var ma = mv[TetreeConnectivity.EDGE_VERTICES[em][0]];
            var mb = mv[TetreeConnectivity.EDGE_VERTICES[em][1]];
            if (!onSegment(pa, ma, mb) || !onSegment(pb, ma, mb)) {
                continue; // the queried edge does not lie on this m-edge
            }
            if (findEdgeNeighborsAtLevel(m, em, finer).contains(tetKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cross-level (finer) VERTEX star under CONTRACT (A) ADJACENCY (RDR-014). Returns every level-{@code
     * startLevel} tet that carries {@code tet}'s vertex {@code vertexIndex}, EXCLUDING {@code tet}'s own nested
     * children (a parent is not an adjacency neighbor of its child). Enumerated geometrically — independent of
     * the BEY-indexed {@code CHILD_VERTEX_PARENT_VERTEX} table (which a naive Morton-index inversion would
     * mis-read, RDR-014 Phase 3 hazard) — so it matches the table-independent oracle
     * {@code CrossLevelNeighborOracle.finerVertexStarPlus1}. Vertex neighbors are a read-only, test-scope query
     * (RDR-014 F1).
     */
    private List<TetreeKey<?>> findVertexNeighborsAtFinerLevels(Tet tet, int vertexIndex, byte startLevel) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        if (startLevel < 0 || startLevel > Constants.getMaxRefinementLevel()) {
            return neighbors;
        }
        // ±1 live reach only (RDR-014 F1; matches the oracle finerVertexStarPlus1). The public caller invokes
        // this with startLevel == level+1, so this enumerates exactly the level-(L+1) star. Full-depth descent
        // is intentionally NOT done: it is unbounded work (to maxRefinementLevel=21) for no tested benefit and
        // would make the finer/coarser depths asymmetric unless the coarser loop also ran full-depth.
        collectVertexStarAtLevel(tet, tet.coordinates()[vertexIndex], startLevel, neighbors);
        return neighbors;
    }

    /**
     * Cross-level (coarser) VERTEX star under CONTRACT (A) ADJACENCY (RDR-014). Returns every level-{@code
     * targetLevel} tet (coarser than {@code tet}) that carries {@code tet}'s vertex {@code vertexIndex},
     * EXCLUDING {@code tet}'s own ancestor at that level (the container, not an adjacency neighbor). Symmetric
     * to {@link #findVertexNeighborsAtFinerLevels}: whenever {@code tet} lists a finer neighbor {@code n},
     * {@code n} lists {@code tet} here, satisfying AC3 reciprocity.
     */
    private List<TetreeKey<?>> findVertexNeighborsAtLevel(Tet tet, int vertexIndex, byte targetLevel) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        if (targetLevel < 0 || targetLevel >= tet.l()) {
            return neighbors;
        }
        collectVertexStarAtLevel(tet, tet.coordinates()[vertexIndex], targetLevel, neighbors);
        return neighbors;
    }

    /**
     * Enumerate all level-{@code targetLevel} tets carrying point {@code p}, excluding {@code self}'s own
     * relative at that level (its child when {@code targetLevel} is finer, its ancestor when coarser). A
     * level-{@code targetLevel} tet with anchor {@code a} spans {@code [a, a+h]} per axis, so to carry {@code p}
     * its anchor must lie in {@code [p-h, p]} — a 2×2×2 anchor box, all 6 types. Pure {@code Tet.coordinates()}
     * geometry; no connectivity-table dependence.
     */
    private void collectVertexStarAtLevel(Tet self, Point3i p, byte targetLevel, List<TetreeKey<?>> out) {
        int h = Constants.lengthAtLevel(targetLevel);
        for (var ax = anchorFloor(p.x - h, h); ax <= p.x; ax += h) {
            for (var ay = anchorFloor(p.y - h, h); ay <= p.y; ay += h) {
                for (var az = anchorFloor(p.z - h, h); az <= p.z; az += h) {
                    if (ax < 0 || ay < 0 || az < 0) {
                        continue;
                    }
                    for (byte type = 0; type < TetreeConnectivity.TET_TYPES; type++) {
                        Tet cand;
                        try {
                            cand = new Tet(ax, ay, az, targetLevel, type);
                            if (!cand.isValid()) {
                                continue;
                            }
                        } catch (RuntimeException e) {
                            continue;
                        }
                        if (!hasVertex(cand, p)) {
                            continue;
                        }
                        // Exclude self's own containment line (CONTRACT A: a container is not an adjacency
                        // neighbor of what it contains). Finer: skip cand if it is a descendant of self (self is
                        // its ancestor). Coarser: skip cand if it is self's ancestor. Both reduce to the same
                        // ancestor test on the deeper of the two, making finer/coarser exclusions symmetric so
                        // the cross-level relation is reciprocal (RDR-014 AC3).
                        if (targetLevel > self.l()) {
                            if (self.equals(findAncestorAtLevel(cand, self.l()))) {
                                continue; // finer: skip self's own descendant
                            }
                        } else {
                            if (cand.equals(findAncestorAtLevel(self, targetLevel))) {
                                continue; // coarser: skip self's own ancestor
                            }
                        }
                        out.add(cand.tmIndex());
                    }
                }
            }
        }
    }

    /** True iff at least two vertices of {@code cand} lie on the inclusive segment [pa, pb]. */
    private static boolean sharesEdgeSegment(Tet cand, Point3i pa, Point3i pb) {
        var onSeg = 0;
        for (var v : cand.coordinates()) {
            if (onSegment(v, pa, pb)) {
                onSeg++;
            }
        }
        return onSeg >= 2;
    }

    /** True iff {@code cand} has a vertex coincident with {@code p}. */
    private static boolean hasVertex(Tet cand, Point3i p) {
        for (var v : cand.coordinates()) {
            if (v.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** Exact integer test: {@code v} is collinear with [pa,pb] and lies within the inclusive segment. */
    private static boolean onSegment(Point3i v, Point3i pa, Point3i pb) {
        long dx = pb.x - pa.x, dy = pb.y - pa.y, dz = pb.z - pa.z;
        long wx = v.x - pa.x, wy = v.y - pa.y, wz = v.z - pa.z;
        long cx = dy * wz - dz * wy;
        long cy = dz * wx - dx * wz;
        long cz = dx * wy - dy * wx;
        if (cx != 0 || cy != 0 || cz != 0) {
            return false; // not collinear
        }
        long dot = wx * dx + wy * dy + wz * dz;
        long len2 = dx * dx + dy * dy + dz * dz;
        return dot >= 0 && dot <= len2;
    }

    /** Floor {@code v} to the nearest multiple of {@code h} toward -inf (v may be slightly negative). */
    private static int anchorFloor(int v, int h) {
        var a = (v / h) * h;
        return a > v ? a - h : a;
    }

    // Helper method to check if tetrahedron is within domain bounds
    private boolean isWithinDomain(Tet tet) {
        var maxCoord = Constants.lengthAtLevel((byte) 0);
        return tet.x() >= 0 && tet.x() < maxCoord && tet.y() >= 0 && tet.y() < maxCoord && tet.z() >= 0
        && tet.z() < maxCoord;
    }
}
