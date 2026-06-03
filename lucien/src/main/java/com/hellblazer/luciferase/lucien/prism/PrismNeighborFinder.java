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
            // Quadrilateral side faces (0, 1, 2). Keep the line; use the t8 face-neighbor, which
            // crosses the shared S0/S1 diagonal (face 1 is the hypotenuse) into the sibling root
            // rather than reporting it as a boundary (RDR-009 P4). Only the outer square edges
            // return null.
            var triangleNeighbor = triangle.faceNeighbor(face);
            if (triangleNeighbor == null) {
                return null; // At outer boundary
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
    
    // Luciferase-ef43s: findEdgeNeighbors/findVertexNeighbors (and helpers findVertexRing, facesShareEdge,
    // getCorrespondingEdgeFace, faceContainsVertex + the FACE_CORNERS table) were removed — they had NO
    // callers and getCorrespondingEdgeFace was a structurally-wrong stub (returned face2 with a 'would use a
    // lookup table' TODO). findFaceNeighbor/findAllFaceNeighbors and cross-level neighbors remain. Re-add
    // edge/vertex neighbors with a real lookup table + involution test when a consumer needs them.
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