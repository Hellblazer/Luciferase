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

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;

/**
 * Neighbor detector implementation for tetrahedral trees.
 * 
 * This class provides neighbor detection using the t8code connectivity
 * tables and algorithms to find face, edge, and vertex neighbors in
 * a tetrahedral subdivision.
 * 
 * @author Hal Hildebrand
 */
public class TetreeNeighborDetector implements NeighborDetector<TetreeKey<? extends TetreeKey<?>>> {
    
    private static final Logger log = LoggerFactory.getLogger(TetreeNeighborDetector.class);
    
    private final Tetree<?, ?> tetree;
    
    // Canonical edge/face connectivity now lives in TetreeConnectivity (RDR-014 AC2); consume it
    // directly so both neighbor classes share one authoritative source.
    private static final int[][] EDGE_VERTICES = TetreeConnectivity.EDGE_VERTICES;

    // Which edges touch each vertex
    private static final int[][] VERTEX_EDGES = {
        {0, 1, 2},    // Vertex 0: edges 0, 1, 2
        {0, 3, 4},    // Vertex 1: edges 0, 3, 4
        {1, 3, 5},    // Vertex 2: edges 1, 3, 5
        {2, 4, 5}     // Vertex 3: edges 2, 4, 5
    };

    // Which faces share each edge (canonical: TetreeConnectivity.EDGE_FACES)
    private static final int[][] EDGE_FACES = TetreeConnectivity.EDGE_FACES;

    // Which faces contain each vertex
    private static final int[][] VERTEX_FACES = {
        {1, 2, 3},    // Vertex 0: in faces 1, 2, 3 (opposite face 0)
        {0, 2, 3},    // Vertex 1: in faces 0, 2, 3 (opposite face 1)
        {0, 1, 3},    // Vertex 2: in faces 0, 1, 3 (opposite face 2)
        {0, 1, 2}     // Vertex 3: in faces 0, 1, 2 (opposite face 3)
    };
    
    public TetreeNeighborDetector(Tetree<?, ?> tetree) {
        this.tetree = Objects.requireNonNull(tetree, "Tetree cannot be null");
    }
    
    @Override
    public List<TetreeKey<?>> findFaceNeighbors(TetreeKey<?> element) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        var tet = keyToTet(element);
        
        // A tetrahedron has 4 faces
        for (int face = 0; face < 4; face++) {
            var neighbor = findFaceNeighbor(tet, face);
            if (neighbor != null && !neighbor.equals(element)) {
                neighbors.add(neighbor);
            }
        }
        
        return neighbors;
    }
    
    @Override
    public List<TetreeKey<?>> findEdgeNeighbors(TetreeKey<?> element) {
        var neighbors = new HashSet<TetreeKey<?>>();
        var tet = keyToTet(element);
        
        // First add all face neighbors
        neighbors.addAll(findFaceNeighbors(element));
        
        // Find neighbors sharing edges
        for (int edge = 0; edge < TetreeConnectivity.EDGES_PER_TET; edge++) {
            var edgeNeighbors = findNeighborsSharingEdge(tet, edge);
            neighbors.addAll(edgeNeighbors);
        }
        
        neighbors.remove(element); // Remove self
        return new ArrayList<>(neighbors);
    }
    
    @Override
    public List<TetreeKey<?>> findVertexNeighbors(TetreeKey<?> element) {
        var neighbors = new HashSet<TetreeKey<?>>();
        var tet = keyToTet(element);
        
        // First add all edge neighbors (which includes face neighbors)
        neighbors.addAll(findEdgeNeighbors(element));
        
        // Find neighbors sharing vertices
        for (int vertex = 0; vertex < TetreeConnectivity.VERTICES_PER_TET; vertex++) {
            var vertexNeighbors = findNeighborsSharingVertex(tet, vertex);
            neighbors.addAll(vertexNeighbors);
        }
        
        neighbors.remove(element); // Remove self
        return new ArrayList<>(neighbors);
    }
    
    @Override
    public boolean isBoundaryElement(TetreeKey<?> element, Direction direction) {
        var tet = keyToTet(element);
        
        // Get the tetrahedron's bounding box
        var coords = tet.coordinates();
        
        // Find min and max coordinates across all vertices
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;
        
        for (var vertex : coords) {
            minX = Math.min(minX, vertex.x);
            maxX = Math.max(maxX, vertex.x);
            minY = Math.min(minY, vertex.y);
            maxY = Math.max(maxY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxZ = Math.max(maxZ, vertex.z);
        }
        
        // Maximum coordinate value (2^21 - 1)
        long maxCoord = (1L << Constants.getMaxRefinementLevel()) - 1;
        
        return switch (direction) {
            case POSITIVE_X -> Math.round(maxX) >= maxCoord;
            case NEGATIVE_X -> Math.round(minX) <= 0;
            case POSITIVE_Y -> Math.round(maxY) >= maxCoord;
            case NEGATIVE_Y -> Math.round(minY) <= 0;
            case POSITIVE_Z -> Math.round(maxZ) >= maxCoord;
            case NEGATIVE_Z -> Math.round(minZ) <= 0;
        };
    }
    
    @Override
    public Set<Direction> getBoundaryDirections(TetreeKey<?> element) {
        var directions = EnumSet.noneOf(Direction.class);
        for (var dir : Direction.values()) {
            if (isBoundaryElement(element, dir)) {
                directions.add(dir);
            }
        }
        return directions;
    }
    
    @Override
    public List<NeighborInfo<TetreeKey<?>>> findNeighborsWithOwners(TetreeKey<?> element, GhostType type) {
        // No partition/ownership resolver is wired into this detector.
        // Returning isLocal=true with rank=0 for every neighbor would silently
        // degrade the ghost layer in distributed configurations.
        // Fail loud until a real owner-resolver is injected via the constructor.
        throw new UnsupportedOperationException(
            "findNeighborsWithOwners requires a partition ownership resolver that has not been wired into TetreeNeighborDetector. "
            + "Either inject an owner-resolver through the constructor or use the local-only neighbor methods "
            + "(findFaceNeighbors/findEdgeNeighbors/findVertexNeighbors) for single-node use. "
            + "Remediation tracked in bead Luciferase-8neqb.");
    }
    
    /**
     * Find the face neighbor of a tetrahedron across a specific face.
     */
    private TetreeKey<?> findFaceNeighbor(Tet tet, int face) {
        if (tet.l == 0) {
            return null; // Root has no neighbors
        }

        // Delegate to the verified t8code face-neighbor algorithm (Tet.faceNeighbor, oracle-gated by
        // T8codeDtetFaceNeighborOracleTest / Luciferase-4bmd). The prior implementation passed a neighbor
        // TYPE (0-5, from getFaceNeighborType) into parentTet.child() as a Morton index, which silently
        // returned child[type] instead of the geometric face neighbor — corrupting every ghost face zone.
        // This mirrors the correct sibling impl in TetreeNeighborFinder.findFaceNeighbor.
        var neighbor = tet.faceNeighbor(face);
        if (neighbor == null) {
            return null; // Boundary of the positive octant
        }
        var neighborTet = neighbor.tet();
        if (!isWithinDomain(neighborTet)) {
            return null; // Outside domain bounds
        }
        return tetToKey(neighborTet);
    }

    /**
     * Domain-bounds check for a candidate neighbor: anchor in [0, rootLength). Mirrors
     * TetreeNeighborFinder.isWithinDomain.
     */
    private boolean isWithinDomain(Tet tet) {
        var maxCoord = Constants.lengthAtLevel((byte) 0);
        return tet.x() >= 0 && tet.x() < maxCoord && tet.y() >= 0 && tet.y() < maxCoord && tet.z() >= 0
        && tet.z() < maxCoord;
    }
    
    /**
     * Find all same-level neighbors (siblings AND cross-parent non-siblings) sharing a specific edge with
     * the given tetrahedron. The canonical-descent search in {@link #findNonSiblingNeighborsSharing} is
     * authoritative over the whole edge fan, so no separate sibling pass is needed — siblings touching the
     * edge are enumerated like any other fan member.
     */
    List<TetreeKey<?>> findNeighborsSharingEdge(Tet tet, int edge) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        if (tet.l == 0) {
            return neighbors; // Root has no neighbors
        }
        findNonSiblingNeighborsSharing(tet, neighbors, edge, true);
        return neighbors;
    }

    /**
     * Find all same-level neighbors (siblings AND cross-parent non-siblings) sharing a specific vertex with
     * the given tetrahedron. See {@link #findNeighborsSharingEdge} — the descent covers the whole vertex star.
     */
    List<TetreeKey<?>> findNeighborsSharingVertex(Tet tet, int vertex) {
        var neighbors = new ArrayList<TetreeKey<?>>();
        if (tet.l == 0) {
            return neighbors; // Root has no neighbors
        }
        findNonSiblingNeighborsSharing(tet, neighbors, vertex, false);
        return neighbors;
    }

    /**
     * Find neighbors (siblings AND cross-parent non-siblings) that share a given edge or vertex with
     * {@code tet}, at the same refinement level.
     *
     * <p>Implementation (Luciferase-v9vm): a same-level tetrahedron is a neighbor across the element iff its
     * vertex set contains all of the element's grid points (both endpoints for an edge, the single point for
     * a vertex). We enumerate those tets by a pruned top-down descent over the canonical Bey tiling: from the
     * root, recurse only into children whose cube encloses the target point, and at the target level keep the
     * tets that actually carry the point as a vertex. For an edge we intersect the sharer sets of its two
     * endpoints.
     *
     * <p>The descent uses {@code child()} exclusively, so it only ever yields canonical tiling tets — tets
     * that the tree can actually store. This is deliberately NOT a {@link Tet#faceNeighbor(int)} flood: the
     * Bey-SFC face neighbor is non-conforming (it returns geometrically-adjacent tets of types that are not
     * the tiling's type at that cell), so a face flood wanders off the tiling and reports phantom neighbors.
     * A tet's cube is exactly its axis-aligned bounding box (vertices span {@code v0..v7}), so cube-encloses
     * is an exact, cheap prune that keeps the descent narrow (only the &le;8 cubes around the point at each
     * level) and complete (every ancestor of a qualifying tet also encloses the point). The cost is therefore
     * LINEAR in the level, not exponential: measured node visits are ~64 per level (1409 visits, &lt;1ms, at
     * the max level 21), since the enclosing fan stays bounded at every level.
     *
     * <p>Scope: {@code TetreeNeighborDetector} is a pure-Tetree detector — the descent roots a fresh
     * {@code Tet(0,0,0,0,0)} with {@code minTetLevel == NO_TET_ANCESTOR}. The self-exclusion
     * {@code sharers.remove(tet)} relies on {@code Tet.equals} (which includes {@code minTetLevel}); it is
     * correct here because the query {@code tet} also carries {@code NO_TET_ANCESTOR}. A hybrid/pyramid
     * caller passing a tet with a real {@code minTetLevel} would not self-exclude — not a concern today, but
     * a trap if this detector is ever reused outside pure-Tetree context.
     *
     * @param elementIndex edge index (0-5) when {@code isEdge}, else vertex index (0-3)
     */
    private void findNonSiblingNeighborsSharing(Tet tet, List<TetreeKey<?>> neighbors,
                                               int elementIndex, boolean isEdge) {
        if (tet.l == 0) {
            return; // Root has no neighbors
        }

        // Grid points of the shared element: 2 endpoints for an edge, 1 for a vertex.
        var coords = tet.coordinates();
        int[] vertexIndices = isEdge ? EDGE_VERTICES[elementIndex] : new int[] { elementIndex };

        // Sharers of the first point; for an edge, intersect with sharers of the second endpoint so only
        // tets carrying the whole edge survive.
        var sharers = sharersOfPoint(coords[vertexIndices[0]], tet.l);
        for (int i = 1; i < vertexIndices.length; i++) {
            sharers.retainAll(sharersOfPoint(coords[vertexIndices[i]], tet.l));
        }
        sharers.remove(tet); // exclude self

        for (var sharer : sharers) {
            neighbors.add(tetToKey(sharer));
        }
    }

    /**
     * All in-domain canonical tiling tets at {@code level} that carry {@code p} as one of their four
     * vertices. Found by a pruned descent from the root: a tet's cube is its exact AABB, so we recurse only
     * into children whose cube encloses {@code p}, and at {@code level} keep those with {@code p} as an
     * actual vertex.
     */
    private Set<Tet> sharersOfPoint(Point3i p, byte level) {
        var result = new HashSet<Tet>();
        var stack = new ArrayDeque<Tet>();
        stack.push(new Tet(0, 0, 0, (byte) 0, (byte) 0));
        while (!stack.isEmpty()) {
            var t = stack.pop();
            if (!cubeEncloses(t, p)) {
                continue; // p outside this subtree's cube — prune
            }
            if (t.l == level) {
                if (isWithinDomain(t) && hasVertex(t, p)) {
                    result.add(t);
                }
            } else {
                for (int c = 0; c < 8; c++) {
                    stack.push(t.child(c));
                }
            }
        }
        return result;
    }

    /** True iff {@code p} lies within (inclusive) the closed cube spanned by {@code t}. */
    private boolean cubeEncloses(Tet t, Point3i p) {
        int h = Constants.lengthAtLevel(t.l);
        return p.x >= t.x() && p.x <= t.x() + h && p.y >= t.y() && p.y <= t.y() + h && p.z >= t.z()
        && p.z <= t.z() + h;
    }

    /** True iff {@code p} is one of {@code t}'s four vertices (exact integer match). */
    private boolean hasVertex(Tet t, Point3i p) {
        for (var c : t.coordinates()) {
            if (c.x == p.x && c.y == p.y && c.z == p.z) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convert a TetreeKey to a Tet object via the canonical TM-index decoder.
     * Delegates to {@link TetreeKey#toTet()} (which walks all path bits from
     * root to the key's level). The prior local implementation only read the
     * current level's 3-bit cell and silently dropped every higher-level bit,
     * producing tets positioned as if they were direct children of the root.
     */
    public Tet keyToTet(TetreeKey<?> key) {
        return key.toTet();
    }
    
    /**
     * Convert a Tet object to a TetreeKey.
     */
    private TetreeKey<?> tetToKey(Tet tet) {
        return tet.tmIndex();
    }
    
}
