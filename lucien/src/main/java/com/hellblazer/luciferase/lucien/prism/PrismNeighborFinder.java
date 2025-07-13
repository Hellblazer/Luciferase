/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.prism;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Neighbor finding algorithms for triangular prisms in the spatial index.
 * 
 * A triangular prism has 5 faces:
 * - Face 0, 1, 2: Quadrilateral side faces (vertical faces)
 * - Face 3: Bottom triangular face (z = min)
 * - Face 4: Top triangular face (z = max)
 * 
 * The prism also has:
 * - 6 corners (vertices)
 * - 9 edges (3 vertical edges, 3 bottom edges, 3 top edges)
 * 
 * @author hal.hildebrand
 */
public class PrismNeighborFinder {
    
    /** Face indices for the 5 faces of a triangular prism */
    public static final int FACE_QUAD_0 = 0;  // Quadrilateral side face 0
    public static final int FACE_QUAD_1 = 1;  // Quadrilateral side face 1
    public static final int FACE_QUAD_2 = 2;  // Quadrilateral side face 2
    public static final int FACE_TRIANGLE_BOTTOM = 3;  // Bottom triangular face
    public static final int FACE_TRIANGLE_TOP = 4;     // Top triangular face
    
    /** Total number of faces on a triangular prism */
    public static final int NUM_FACES = 5;
    
    /** Face corners lookup table - which corners belong to each face */
    private static final int[][] FACE_CORNERS = {
        {1, 2, 4, 5},   // Face 0 (quad): corners 1,2,4,5
        {0, 2, 3, 5},   // Face 1 (quad): corners 0,2,3,5
        {0, 1, 3, 4},   // Face 2 (quad): corners 0,1,3,4
        {0, 1, 2, -1},  // Face 3 (bottom triangle): corners 0,1,2
        {3, 4, 5, -1}   // Face 4 (top triangle): corners 3,4,5
    };
    
    /**
     * Find the face neighbor of a prism across a given face.
     * Following t8code's algorithm for prism face neighbors.
     * 
     * @param prism The prism to find neighbors for
     * @param face The face index (0-4)
     * @return The neighboring prism key, or null if at boundary
     */
    public static PrismKey findFaceNeighbor(PrismKey prism, int face) {
        if (face < 0 || face >= NUM_FACES) {
            throw new IllegalArgumentException("Invalid face index: " + face);
        }
        
        // Extract components
        var triangle = prism.getTriangle();
        var line = prism.getLine();
        var level = prism.getLevel();
        
        if (face < 3) {
            // Quadrilateral side faces (0, 1, 2)
            // Keep the line component, find triangle neighbor
            var triangleNeighbor = triangle.neighbor(face);
            if (triangleNeighbor == null) {
                return null; // At boundary
            }
            return new PrismKey(triangleNeighbor, line);
        } else if (face == FACE_TRIANGLE_BOTTOM) {
            // Bottom triangular face
            // Keep the triangle component, find line neighbor below
            var lineNeighbor = line.neighbor(-1); // Move down
            if (lineNeighbor == null) {
                return null; // At bottom boundary
            }
            return new PrismKey(triangle, lineNeighbor);
        } else {
            // Top triangular face
            // Keep the triangle component, find line neighbor above
            var lineNeighbor = line.neighbor(1); // Move up
            if (lineNeighbor == null) {
                return null; // At top boundary
            }
            return new PrismKey(triangle, lineNeighbor);
        }
    }
    
    /**
     * Get the face number on the neighbor that corresponds to the shared face.
     * When two prisms share a face, this returns which face on the neighbor
     * is the shared face.
     * 
     * @param face The face index on the original prism
     * @return The corresponding face index on the neighbor
     */
    public static int getNeighborFace(int face) {
        if (face < 3) {
            // Quadrilateral faces: face neighbors have reciprocal face numbers
            // Following t8code: 0 -> 2, 1 -> 1, 2 -> 0
            return 2 - face;
        } else if (face == FACE_TRIANGLE_BOTTOM) {
            // Bottom face neighbor has top face facing back
            return FACE_TRIANGLE_TOP;
        } else {
            // Top face neighbor has bottom face facing back
            return FACE_TRIANGLE_BOTTOM;
        }
    }
    
    /**
     * Find all face neighbors of a prism (up to 5 neighbors).
     * 
     * @param prism The prism to find neighbors for
     * @return List of neighboring prism keys (excludes boundary neighbors)
     */
    public static List<PrismKey> findAllFaceNeighbors(PrismKey prism) {
        List<PrismKey> neighbors = new ArrayList<>();
        
        for (int face = 0; face < NUM_FACES; face++) {
            PrismKey neighbor = findFaceNeighbor(prism, face);
            if (neighbor != null) {
                neighbors.add(neighbor);
            }
        }
        
        return neighbors;
    }
    
    /**
     * Find the children of a prism that touch a given face.
     * When a prism is subdivided into 8 children, this returns which
     * children are adjacent to the specified face.
     * 
     * @param face The face index (0-4)
     * @return Array of child indices that touch this face
     */
    public static int[] getChildrenAtFace(int face) {
        if (face < 0 || face >= NUM_FACES) {
            throw new IllegalArgumentException("Invalid face index: " + face);
        }
        
        // Based on t8code's children_at_face lookup table
        // For triangular faces (bottom/top), 4 children touch the face
        // For quadrilateral faces, 4 children touch the face
        if (face < 3) {
            // Quadrilateral side faces
            // The specific children depend on the triangle type, but we use type 0
            switch (face) {
                case 0: return new int[]{1, 3, 5, 7};
                case 1: return new int[]{0, 3, 4, 7};
                case 2: return new int[]{0, 1, 4, 5};
                default: throw new IllegalStateException("Unreachable");
            }
        } else if (face == FACE_TRIANGLE_BOTTOM) {
            // Bottom face: children 0-3 (lower layer)
            return new int[]{0, 1, 2, 3};
        } else {
            // Top face: children 4-7 (upper layer)
            return new int[]{4, 5, 6, 7};
        }
    }
    
    /**
     * Find edge neighbors of a prism.
     * A triangular prism has 9 edges: 3 vertical, 3 on bottom, 3 on top.
     * 
     * @param prism The prism to find edge neighbors for
     * @return Set of neighboring prisms that share an edge
     */
    public static Set<PrismKey> findEdgeNeighbors(PrismKey prism) {
        Set<PrismKey> edgeNeighbors = new HashSet<>();
        
        // For each pair of faces, find neighbors that share the edge
        for (int face1 = 0; face1 < NUM_FACES; face1++) {
            for (int face2 = face1 + 1; face2 < NUM_FACES; face2++) {
                // Check if these faces share an edge
                if (facesShareEdge(face1, face2)) {
                    // Find the neighbor across face1, then its neighbor across the corresponding face
                    PrismKey neighbor1 = findFaceNeighbor(prism, face1);
                    if (neighbor1 != null) {
                        int neighborFace = getCorrespondingEdgeFace(face1, face2);
                        if (neighborFace >= 0) {
                            PrismKey edgeNeighbor = findFaceNeighbor(neighbor1, neighborFace);
                            if (edgeNeighbor != null && !edgeNeighbor.equals(prism)) {
                                edgeNeighbors.add(edgeNeighbor);
                            }
                        }
                    }
                }
            }
        }
        
        return edgeNeighbors;
    }
    
    /**
     * Find vertex (corner) neighbors of a prism.
     * A triangular prism has 6 vertices.
     * 
     * @param prism The prism to find vertex neighbors for
     * @return Set of neighboring prisms that share a vertex
     */
    public static Set<PrismKey> findVertexNeighbors(PrismKey prism) {
        Set<PrismKey> vertexNeighbors = new HashSet<>();
        
        // For each vertex, find all prisms that share it
        // This is done by following chains of face neighbors
        for (int vertex = 0; vertex < 6; vertex++) {
            Set<PrismKey> vertexRing = findVertexRing(prism, vertex);
            vertexNeighbors.addAll(vertexRing);
        }
        
        // Remove the original prism
        vertexNeighbors.remove(prism);
        
        return vertexNeighbors;
    }
    
    /**
     * Find all prisms that share a specific vertex with the given prism.
     * 
     * @param prism The prism
     * @param vertex The vertex index (0-5)
     * @return Set of prisms sharing this vertex
     */
    private static Set<PrismKey> findVertexRing(PrismKey prism, int vertex) {
        Set<PrismKey> ring = new HashSet<>();
        
        // Find which faces contain this vertex
        List<Integer> facesWithVertex = new ArrayList<>();
        for (int face = 0; face < NUM_FACES; face++) {
            if (faceContainsVertex(face, vertex)) {
                facesWithVertex.add(face);
            }
        }
        
        // Follow face neighbors and their neighbors to find vertex ring
        for (int face : facesWithVertex) {
            PrismKey neighbor = findFaceNeighbor(prism, face);
            if (neighbor != null) {
                ring.add(neighbor);
                // Continue around the vertex by following more face neighbors
                for (int otherFace : facesWithVertex) {
                    if (otherFace != face) {
                        int neighborFace = getNeighborFace(face);
                        PrismKey nextNeighbor = findFaceNeighbor(neighbor, neighborFace);
                        if (nextNeighbor != null) {
                            ring.add(nextNeighbor);
                        }
                    }
                }
            }
        }
        
        return ring;
    }
    
    /**
     * Check if two faces share an edge.
     * 
     * @param face1 First face index
     * @param face2 Second face index
     * @return true if the faces share an edge
     */
    private static boolean facesShareEdge(int face1, int face2) {
        // Count shared corners between the two faces
        int sharedCorners = 0;
        for (int corner1 : FACE_CORNERS[face1]) {
            if (corner1 < 0) break;
            for (int corner2 : FACE_CORNERS[face2]) {
                if (corner2 < 0) break;
                if (corner1 == corner2) {
                    sharedCorners++;
                }
            }
        }
        // Two faces share an edge if they have exactly 2 corners in common
        return sharedCorners == 2;
    }
    
    /**
     * Get the face on a face-neighbor that would lead to an edge neighbor.
     * 
     * @param face1 First face of the edge
     * @param face2 Second face of the edge
     * @return The face index to follow from the face1 neighbor, or -1 if invalid
     */
    private static int getCorrespondingEdgeFace(int face1, int face2) {
        // This is complex and depends on the specific edge
        // For now, return a simplified version
        // In a full implementation, this would use a lookup table
        return face2;
    }
    
    /**
     * Check if a face contains a specific vertex.
     * 
     * @param face Face index
     * @param vertex Vertex index
     * @return true if the face contains the vertex
     */
    private static boolean faceContainsVertex(int face, int vertex) {
        for (int corner : FACE_CORNERS[face]) {
            if (corner == vertex) {
                return true;
            }
            if (corner < 0) break; // -1 marks end of corners for triangular faces
        }
        return false;
    }
    
    /**
     * Find neighbors at different levels (cross-level neighbors).
     * This includes both coarser neighbors (parents) and finer neighbors (children).
     * 
     * @param prism The prism to find cross-level neighbors for
     * @param maxLevelDifference Maximum level difference to search
     * @return List of cross-level neighbors
     */
    public static List<PrismKey> findCrossLevelNeighbors(PrismKey prism, int maxLevelDifference) {
        List<PrismKey> crossLevelNeighbors = new ArrayList<>();
        int currentLevel = prism.getLevel();
        
        // Find coarser neighbors (go up the tree)
        PrismKey current = prism;
        for (int i = 1; i <= maxLevelDifference && currentLevel - i >= 0; i++) {
            PrismKey parent = current.parent();
            if (parent != null) {
                // Find all face neighbors of the parent
                List<PrismKey> parentNeighbors = findAllFaceNeighbors(parent);
                for (PrismKey parentNeighbor : parentNeighbors) {
                    // Check if this neighbor or its children are adjacent to original prism
                    if (isAdjacent(prism, parentNeighbor)) {
                        crossLevelNeighbors.add(parentNeighbor);
                    }
                }
                current = parent;
            }
        }
        
        // Find finer neighbors (children of face neighbors)
        List<PrismKey> faceNeighbors = findAllFaceNeighbors(prism);
        for (PrismKey neighbor : faceNeighbors) {
            findFinerNeighbors(prism, neighbor, maxLevelDifference, crossLevelNeighbors);
        }
        
        return crossLevelNeighbors;
    }
    
    /**
     * Recursively find finer neighbors (children) that are adjacent.
     */
    private static void findFinerNeighbors(PrismKey original, PrismKey neighbor, 
                                          int remainingLevels, List<PrismKey> result) {
        if (remainingLevels <= 0 || neighbor.getLevel() >= Triangle.MAX_LEVEL) {
            return;
        }
        
        // Generate children of the neighbor
        for (int i = 0; i < 8; i++) {
            PrismKey child = neighbor.child(i);
            if (isAdjacent(original, child)) {
                result.add(child);
                // Recursively check children
                findFinerNeighbors(original, child, remainingLevels - 1, result);
            }
        }
    }
    
    /**
     * Check if two prisms are adjacent (share a face, edge, or vertex).
     * 
     * @param prism1 First prism
     * @param prism2 Second prism
     * @return true if the prisms are adjacent
     */
    private static boolean isAdjacent(PrismKey prism1, PrismKey prism2) {
        // Simplified adjacency test using bounding boxes
        // In a full implementation, this would check for actual geometric adjacency
        
        // Get the bounds of each prism
        float[] bounds1 = PrismGeometry.computeBoundingBox(prism1);
        float[] bounds2 = PrismGeometry.computeBoundingBox(prism2);
        
        // Check if bounding boxes touch or overlap
        return !(bounds1[3] < bounds2[0] || bounds2[3] < bounds1[0] ||  // x-axis
                 bounds1[4] < bounds2[1] || bounds2[4] < bounds1[1] ||  // y-axis  
                 bounds1[5] < bounds2[2] || bounds2[5] < bounds1[2]);   // z-axis
    }
}