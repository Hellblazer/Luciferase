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
    public List<BaseTetreeKey<?>> findEdgeNeighbors(BaseTetreeKey<? extends BaseTetreeKey> tetIndex, int edgeIndex) {
        if (edgeIndex < 0 || edgeIndex > 5) {
            throw new IllegalArgumentException("Edge index must be between 0 and 5, got: " + edgeIndex);
        }

        var tet = Tet.tetrahedron(tetIndex);
        var edgeNeighbors = new ArrayList<BaseTetreeKey<?>>();

        // Each edge is shared by multiple faces
        // Edge-to-face mapping for tetrahedron:
        // Edge 0 (v0-v1): faces 0, 2
        // Edge 1 (v0-v2): faces 0, 3
        // Edge 2 (v0-v3): faces 1, 3
        // Edge 3 (v1-v2): faces 0, 1
        // Edge 4 (v1-v3): faces 1, 2
        // Edge 5 (v2-v3): faces 2, 3
        var edgeToFaces = new int[][] { { 0, 2 },  // Edge 0
                                        { 0, 3 },  // Edge 1
                                        { 1, 3 },  // Edge 2
                                        { 0, 1 },  // Edge 3
                                        { 1, 2 },  // Edge 4
                                        { 2, 3 }   // Edge 5
        };

        // Check neighbors across both faces that share this edge
        var uniqueNeighbors = new HashSet<BaseTetreeKey<?>>();
        for (var faceIndex : edgeToFaces[edgeIndex]) {
            var neighbor = findFaceNeighbor(tet, faceIndex);
            if (neighbor != null) {
                uniqueNeighbors.add(neighbor.tmIndex());
            }
        }

        // Also need to check for neighbors at different levels that share the edge
        var level = tet.l();

        // Check coarser level
        if (level > 0) {
            var parent = tet.parent();
            var parentEdgeNeighbors = findEdgeNeighborsAtLevel(parent, edgeIndex, (byte) (level - 1));
            uniqueNeighbors.addAll(parentEdgeNeighbors);
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
    public List<BaseTetreeKey<?>> findVertexNeighbors(BaseTetreeKey<? extends BaseTetreeKey> tetIndex,
                                                      int vertexIndex) {
        if (vertexIndex < 0 || vertexIndex > 3) {
            throw new IllegalArgumentException("Vertex index must be between 0 and 3, got: " + vertexIndex);
        }

        var tet = Tet.tetrahedron(tetIndex);
        var vertexNeighbors = new HashSet<BaseTetreeKey<?>>();

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

        // Check coarser levels
        if (level > 0) {
            var current = tet;
            for (var l = (byte) (level - 1); l >= 0; l--) {
                current = current.parent();
                // Find all neighbors at this level that share the vertex
                var coarserNeighbors = findVertexNeighborsAtLevel(current, vertexIndex, l);
                vertexNeighbors.addAll(coarserNeighbors);
            }
        }

        // Check finer levels
        if (level < Constants.getMaxRefinementLevel()) {
            var finerNeighbors = findVertexNeighborsAtFinerLevels(tet, vertexIndex, (byte) (level + 1));
            vertexNeighbors.addAll(finerNeighbors);
        }

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

    // Helper method to find edge neighbors at a specific level
    private List<TetreeKey> findEdgeNeighborsAtLevel(Tet tet, int edgeIndex, byte targetLevel) {
        var neighbors = new ArrayList<TetreeKey>();
        // Implementation would traverse to find all tets at target level sharing the edge
        // This is a placeholder for the complex geometric calculation
        return neighbors;
    }

    // Helper method to find vertex neighbors at finer levels
    private List<TetreeKey> findVertexNeighborsAtFinerLevels(Tet tet, int vertexIndex, byte startLevel) {
        var neighbors = new ArrayList<TetreeKey>();
        // Implementation would recursively check children that share the vertex
        // This is a placeholder for the complex geometric calculation
        return neighbors;
    }

    // Helper method to find vertex neighbors at a specific level
    private List<TetreeKey> findVertexNeighborsAtLevel(Tet tet, int vertexIndex, byte targetLevel) {
        var neighbors = new ArrayList<TetreeKey>();
        // Implementation would traverse to find all tets at target level sharing the vertex
        // This is a placeholder for the complex geometric calculation
        return neighbors;
    }

    // Helper method to check if tetrahedron is within domain bounds
    private boolean isWithinDomain(Tet tet) {
        var maxCoord = Constants.lengthAtLevel((byte) 0);
        return tet.x() >= 0 && tet.x() < maxCoord && tet.y() >= 0 && tet.y() < maxCoord && tet.z() >= 0
        && tet.z() < maxCoord;
    }
}
