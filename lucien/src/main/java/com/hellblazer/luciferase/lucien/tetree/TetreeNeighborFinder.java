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
import java.util.Set;

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
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            Tet neighbor = findFaceNeighbor(tet1, face);
            if (neighbor != null && neighbor.equals(tet2)) {
                return true;
            }
        }

        // Also check the reverse (in case of level differences)
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            Tet neighbor = findFaceNeighbor(tet2, face);
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
        List<Tet> neighbors = new ArrayList<>();

        // Check all 4 faces
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            Tet neighbor = findFaceNeighbor(tet, face);
            if (neighbor != null) {
                neighbors.add(neighbor);
            }
        }

        return neighbors;
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
        Tet.FaceNeighbor neighbor = tet.faceNeighbor(faceIndex);

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

        List<Tet> neighbors = new ArrayList<>();

        // For each face, find neighbor at appropriate level
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            Tet immediateNeighbor = findFaceNeighbor(tet, face);
            if (immediateNeighbor == null) {
                continue; // Boundary face
            }

            // Adjust neighbor to target level
            if (immediateNeighbor.l() == targetLevel) {
                // Already at target level
                neighbors.add(immediateNeighbor);
            } else if (immediateNeighbor.l() < targetLevel) {
                // Neighbor is coarser, find descendants at target level
                List<Tet> descendants = findDescendantsAtLevel(immediateNeighbor, targetLevel, face);
                neighbors.addAll(descendants);
            } else {
                // Neighbor is finer, find ancestor at target level
                Tet ancestor = findAncestorAtLevel(immediateNeighbor, targetLevel);
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

        List<Tet> result = new ArrayList<>();
        List<Tet> currentLayer = new ArrayList<>();
        List<Tet> nextLayer = new ArrayList<>();

        // Start with the given tetrahedron
        currentLayer.add(tet);
        result.add(tet);

        // Expand layer by layer
        for (int d = 0; d < distance; d++) {
            for (Tet current : currentLayer) {
                List<Tet> neighbors = findAllNeighbors(current);
                for (Tet neighbor : neighbors) {
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
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            Tet neighbor = findFaceNeighbor(tet1, face);
            if (neighbor != null && neighbor.equals(tet2)) {
                return face;
            }
        }
        return -1;
    }

    // Find ancestor at a specific level
    private Tet findAncestorAtLevel(Tet descendant, byte targetLevel) {
        if (descendant.l() <= targetLevel) {
            return descendant;
        }

        Tet current = descendant;
        while (current.l() > targetLevel && current.l() > 0) {
            current = current.parent();
        }

        return current.l() == targetLevel ? current : null;
    }

    // Find descendants at a specific level touching a given face
    private List<Tet> findDescendantsAtLevel(Tet ancestor, byte targetLevel, int ancestorFace) {
        List<Tet> descendants = new ArrayList<>();

        if (ancestor.l() >= targetLevel) {
            return descendants; // No descendants at this level
        }

        // Get children that touch the ancestor face
        byte[] childrenAtFace = TetreeConnectivity.getChildrenAtFace(ancestor.type(), ancestorFace);

        for (byte childIndex : childrenAtFace) {
            try {
                Tet child = ancestor.child(childIndex);

                if (child.l() == targetLevel) {
                    descendants.add(child);
                } else if (child.l() < targetLevel) {
                    // Recursively find descendants
                    // Determine which face of the child corresponds to ancestor's face
                    byte childFace = TetreeConnectivity.getChildFace(ancestor.type(), childIndex, ancestorFace);
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

    // Helper method to check if tetrahedron is within domain bounds
    private boolean isWithinDomain(Tet tet) {
        int maxCoord = Constants.lengthAtLevel((byte) 0);
        return tet.x() >= 0 && tet.x() < maxCoord && tet.y() >= 0 && tet.y() < maxCoord && tet.z() >= 0
        && tet.z() < maxCoord;
    }

    /**
     * Find all neighbors that share a specific edge with the given tetrahedron.
     * Each tetrahedron has 6 edges (connecting pairs of its 4 vertices).
     *
     * @param tetIndex  The SFC index of the tetrahedron
     * @param edgeIndex The edge index (0-5)
     * @return List of neighbor tetrahedron indices sharing the specified edge
     */
    public List<Long> findEdgeNeighbors(long tetIndex, int edgeIndex) {
        if (edgeIndex < 0 || edgeIndex > 5) {
            throw new IllegalArgumentException("Edge index must be between 0 and 5, got: " + edgeIndex);
        }

        Tet tet = Tet.tetrahedron(tetIndex);
        List<Long> edgeNeighbors = new ArrayList<>();

        // Each edge is shared by multiple faces
        // Edge-to-face mapping for tetrahedron:
        // Edge 0 (v0-v1): faces 0, 2
        // Edge 1 (v0-v2): faces 0, 3
        // Edge 2 (v0-v3): faces 1, 3
        // Edge 3 (v1-v2): faces 0, 1
        // Edge 4 (v1-v3): faces 1, 2
        // Edge 5 (v2-v3): faces 2, 3
        int[][] edgeToFaces = {
            {0, 2},  // Edge 0
            {0, 3},  // Edge 1
            {1, 3},  // Edge 2
            {0, 1},  // Edge 3
            {1, 2},  // Edge 4
            {2, 3}   // Edge 5
        };

        // Check neighbors across both faces that share this edge
        Set<Long> uniqueNeighbors = new HashSet<>();
        for (int faceIndex : edgeToFaces[edgeIndex]) {
            Tet neighbor = findFaceNeighbor(tet, faceIndex);
            if (neighbor != null) {
                uniqueNeighbors.add(neighbor.index());
            }
        }

        // Also need to check for neighbors at different levels that share the edge
        byte level = tet.l();
        
        // Check coarser level
        if (level > 0) {
            Tet parent = tet.parent();
            List<Long> parentEdgeNeighbors = findEdgeNeighborsAtLevel(parent, edgeIndex, (byte)(level - 1));
            uniqueNeighbors.addAll(parentEdgeNeighbors);
        }

        // Check finer level
        if (level < Constants.getMaxRefinementLevel()) {
            List<Long> childEdgeNeighbors = findEdgeNeighborsAtLevel(tet, edgeIndex, (byte)(level + 1));
            uniqueNeighbors.addAll(childEdgeNeighbors);
        }

        edgeNeighbors.addAll(uniqueNeighbors);
        return edgeNeighbors;
    }

    /**
     * Find all neighbors that share a specific vertex with the given tetrahedron.
     * Each tetrahedron has 4 vertices.
     *
     * @param tetIndex    The SFC index of the tetrahedron
     * @param vertexIndex The vertex index (0-3)
     * @return List of neighbor tetrahedron indices sharing the specified vertex
     */
    public List<Long> findVertexNeighbors(long tetIndex, int vertexIndex) {
        if (vertexIndex < 0 || vertexIndex > 3) {
            throw new IllegalArgumentException("Vertex index must be between 0 and 3, got: " + vertexIndex);
        }

        Tet tet = Tet.tetrahedron(tetIndex);
        Set<Long> vertexNeighbors = new HashSet<>();

        // Vertex-to-face mapping for tetrahedron:
        // Vertex 0: faces 1, 2, 3
        // Vertex 1: faces 0, 2, 3
        // Vertex 2: faces 0, 1, 3
        // Vertex 3: faces 0, 1, 2
        int[][] vertexToFaces = {
            {1, 2, 3},  // Vertex 0
            {0, 2, 3},  // Vertex 1
            {0, 1, 3},  // Vertex 2
            {0, 1, 2}   // Vertex 3
        };

        // First, find all face neighbors
        for (int faceIndex : vertexToFaces[vertexIndex]) {
            Tet neighbor = findFaceNeighbor(tet, faceIndex);
            if (neighbor != null) {
                vertexNeighbors.add(neighbor.index());
            }
        }

        // Also find edge neighbors for edges containing this vertex
        // Vertex-to-edge mapping:
        // Vertex 0: edges 0, 1, 2
        // Vertex 1: edges 0, 3, 4
        // Vertex 2: edges 1, 3, 5
        // Vertex 3: edges 2, 4, 5
        int[][] vertexToEdges = {
            {0, 1, 2},  // Vertex 0
            {0, 3, 4},  // Vertex 1
            {1, 3, 5},  // Vertex 2
            {2, 4, 5}   // Vertex 3
        };

        for (int edgeIndex : vertexToEdges[vertexIndex]) {
            List<Long> edgeNeighborsList = findEdgeNeighbors(tetIndex, edgeIndex);
            vertexNeighbors.addAll(edgeNeighborsList);
        }

        // Check different levels for vertex neighbors
        byte level = tet.l();
        
        // Check coarser levels
        if (level > 0) {
            Tet current = tet;
            for (byte l = (byte)(level - 1); l >= 0; l--) {
                current = current.parent();
                // Find all neighbors at this level that share the vertex
                List<Long> coarserNeighbors = findVertexNeighborsAtLevel(current, vertexIndex, l);
                vertexNeighbors.addAll(coarserNeighbors);
            }
        }

        // Check finer levels
        if (level < Constants.getMaxRefinementLevel()) {
            List<Long> finerNeighbors = findVertexNeighborsAtFinerLevels(tet, vertexIndex, (byte)(level + 1));
            vertexNeighbors.addAll(finerNeighbors);
        }

        // Remove self
        vertexNeighbors.remove(tetIndex);
        
        return new ArrayList<>(vertexNeighbors);
    }

    // Helper method to find edge neighbors at a specific level
    private List<Long> findEdgeNeighborsAtLevel(Tet tet, int edgeIndex, byte targetLevel) {
        List<Long> neighbors = new ArrayList<>();
        // Implementation would traverse to find all tets at target level sharing the edge
        // This is a placeholder for the complex geometric calculation
        return neighbors;
    }

    // Helper method to find vertex neighbors at a specific level
    private List<Long> findVertexNeighborsAtLevel(Tet tet, int vertexIndex, byte targetLevel) {
        List<Long> neighbors = new ArrayList<>();
        // Implementation would traverse to find all tets at target level sharing the vertex
        // This is a placeholder for the complex geometric calculation
        return neighbors;
    }

    // Helper method to find vertex neighbors at finer levels
    private List<Long> findVertexNeighborsAtFinerLevels(Tet tet, int vertexIndex, byte startLevel) {
        List<Long> neighbors = new ArrayList<>();
        // Implementation would recursively check children that share the vertex
        // This is a placeholder for the complex geometric calculation
        return neighbors;
    }
}
