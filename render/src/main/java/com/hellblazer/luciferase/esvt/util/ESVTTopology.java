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
package com.hellblazer.luciferase.esvt.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for topological relationships in ESVT.
 *
 * <p>This class provides navigation and relationship queries for the ESVT structure,
 * which uses Bey 8-way tetrahedral subdivision. Unlike ESVO's breadth-first octree layout,
 * ESVT uses a depth-first sparse encoding where child pointers are relative.</p>
 *
 * <p>Key differences from octree topology:</p>
 * <ul>
 *   <li>8 children per node (same as octree, but Bey subdivision)</li>
 *   <li>4 faces per tetrahedron (vs 6 for cube)</li>
 *   <li>6 edges per tetrahedron (vs 12 for cube)</li>
 *   <li>4 vertices per tetrahedron (vs 8 for cube)</li>
 *   <li>6 tetrahedron types (S0-S5) with type-dependent child ordering</li>
 * </ul>
 *
 * <p>Face adjacency:</p>
 * <ul>
 *   <li>Each face of a tetrahedron is shared with exactly one neighbor</li>
 *   <li>Face i is opposite vertex i</li>
 *   <li>Children at corners share faces with parent's neighbors</li>
 *   <li>Children at octahedral region share faces with each other</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class ESVTTopology {

    /** Number of children per ESVT node (Bey subdivision) */
    public static final int BEY_BRANCHING_FACTOR = 8;

    /** Number of tetrahedron types (S0-S5) */
    public static final int TET_TYPE_COUNT = 6;

    /** Number of faces per tetrahedron */
    public static final int FACES_PER_TET = 4;

    /** Number of edges per tetrahedron */
    public static final int EDGES_PER_TET = 6;

    /** Number of vertices per tetrahedron */
    public static final int VERTICES_PER_TET = 4;

    /**
     * Children touching each face: [faceIndex] -> array of 4 child indices
     * Face i is opposite vertex i, so children at corners touch it
     */
    public static final int[][] CHILDREN_AT_FACE = {
        {4, 5, 6, 7},  // Face 0: opposite v0, includes all octahedral children
        {2, 3, 6, 7},  // Face 1: opposite v1
        {1, 3, 5, 7},  // Face 2: opposite v2
        {1, 2, 4, 5}   // Face 3: opposite v3
    };

    /**
     * Children at corners: [cornerIndex] -> child index
     * Children 0-3 are at the corners (same vertex position as parent vertices)
     */
    public static final int[] CORNER_CHILDREN = {0, 1, 2, 3};

    /**
     * Children in octahedral region: indices 4-7
     * These fill the central octahedral cavity
     */
    public static final int[] OCTAHEDRAL_CHILDREN = {4, 5, 6, 7};

    /**
     * Face sharing between siblings.
     * [childA][childB] = face index shared by childA and childB, or -1 if none
     */
    public static final int[][] SIBLING_FACE_SHARING = {
        // Child 0 (corner v0)
        {-1, -1, -1, -1, 1, 2, 3, -1},
        // Child 1 (corner v1)
        {-1, -1, -1, -1, -1, 0, -1, 2},
        // Child 2 (corner v2)
        {-1, -1, -1, -1, -1, -1, 0, 1},
        // Child 3 (corner v3)
        {-1, -1, -1, -1, 0, -1, -1, -1},
        // Child 4 (octahedral)
        {1, -1, -1, 0, -1, 3, 2, -1},
        // Child 5 (octahedral)
        {2, 0, -1, -1, 3, -1, -1, 1},
        // Child 6 (octahedral)
        {3, -1, 0, -1, 2, -1, -1, 0},
        // Child 7 (octahedral)
        {-1, 2, 1, -1, -1, 1, 0, -1}
    };

    /**
     * Adjacency info for a face.
     */
    public record FaceAdjacency(
        int faceIndex,
        int[] touchingChildren,
        boolean isExternal
    ) {}

    /**
     * Edge info including connected vertices.
     */
    public record EdgeInfo(
        int edgeIndex,
        int vertex0,
        int vertex1
    ) {}

    /**
     * Sibling sharing info.
     */
    public record SiblingSharing(
        int siblingIndex,
        int sharedFace
    ) {}

    /**
     * Get the child type for a given parent type and child index.
     * Delegates to ESVTNodeGeometry for the actual lookup.
     *
     * @param parentType parent tetrahedron type (0-5)
     * @param childIndex child index (0-7)
     * @return child type (0-5)
     */
    public static int getChildType(int parentType, int childIndex) {
        return ESVTNodeGeometry.getChildType(parentType, childIndex);
    }

    /**
     * Get all child types for a given parent type.
     *
     * @param parentType parent tetrahedron type (0-5)
     * @return array of 8 child types
     */
    public static int[] getAllChildTypes(int parentType) {
        validateType(parentType);
        var types = new int[BEY_BRANCHING_FACTOR];
        for (int i = 0; i < BEY_BRANCHING_FACTOR; i++) {
            types[i] = ESVTNodeGeometry.PARENT_TYPE_TO_CHILD_TYPE[parentType][i];
        }
        return types;
    }

    /**
     * Get children that touch a specific face.
     *
     * @param faceIndex face index (0-3)
     * @return array of child indices touching this face
     */
    public static int[] getChildrenAtFace(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= FACES_PER_TET) {
            throw new IllegalArgumentException("Face index must be 0-3");
        }
        return CHILDREN_AT_FACE[faceIndex].clone();
    }

    /**
     * Check if a child is at a corner (children 0-3) or in the octahedral region (4-7).
     *
     * @param childIndex child index (0-7)
     * @return true if this is a corner child
     */
    public static boolean isCornerChild(int childIndex) {
        validateChildIndex(childIndex);
        return childIndex < 4;
    }

    /**
     * Check if a child is in the octahedral region (children 4-7).
     *
     * @param childIndex child index (0-7)
     * @return true if this is an octahedral child
     */
    public static boolean isOctahedralChild(int childIndex) {
        validateChildIndex(childIndex);
        return childIndex >= 4;
    }

    /**
     * Get the corner vertex that a corner child is located at.
     *
     * @param childIndex child index (0-3 for corner children)
     * @return vertex index (same as child index for corner children)
     * @throws IllegalArgumentException if childIndex is not a corner child
     */
    public static int getCornerVertex(int childIndex) {
        if (childIndex < 0 || childIndex >= 4) {
            throw new IllegalArgumentException("Only corner children (0-3) have a corner vertex");
        }
        return childIndex;
    }

    /**
     * Find siblings that share a face with a given child.
     *
     * @param childIndex the child to query
     * @return list of sibling sharing info
     */
    public static List<SiblingSharing> getShareFaceSiblings(int childIndex) {
        validateChildIndex(childIndex);
        var result = new ArrayList<SiblingSharing>();

        for (int sibling = 0; sibling < BEY_BRANCHING_FACTOR; sibling++) {
            if (sibling != childIndex) {
                int sharedFace = SIBLING_FACE_SHARING[childIndex][sibling];
                if (sharedFace >= 0) {
                    result.add(new SiblingSharing(sibling, sharedFace));
                }
            }
        }

        return result;
    }

    /**
     * Get the face shared between two sibling children, or -1 if none.
     *
     * @param childA first child index (0-7)
     * @param childB second child index (0-7)
     * @return shared face index (0-3) from childA's perspective, or -1 if no shared face
     */
    public static int getSharedFace(int childA, int childB) {
        validateChildIndex(childA);
        validateChildIndex(childB);
        return SIBLING_FACE_SHARING[childA][childB];
    }

    /**
     * Get adjacency information for a face.
     *
     * @param faceIndex face index (0-3)
     * @param hasParentNeighbor true if the parent has a neighbor at this face
     * @return face adjacency info
     */
    public static FaceAdjacency getFaceAdjacency(int faceIndex, boolean hasParentNeighbor) {
        if (faceIndex < 0 || faceIndex >= FACES_PER_TET) {
            throw new IllegalArgumentException("Face index must be 0-3");
        }

        return new FaceAdjacency(
            faceIndex,
            CHILDREN_AT_FACE[faceIndex].clone(),
            !hasParentNeighbor
        );
    }

    /**
     * Get information about a specific edge.
     *
     * @param edgeIndex edge index (0-5)
     * @return edge info with vertex endpoints
     */
    public static EdgeInfo getEdgeInfo(int edgeIndex) {
        if (edgeIndex < 0 || edgeIndex >= EDGES_PER_TET) {
            throw new IllegalArgumentException("Edge index must be 0-5");
        }

        var edgeVerts = ESVTNodeGeometry.EDGE_VERTICES[edgeIndex];
        return new EdgeInfo(edgeIndex, edgeVerts[0], edgeVerts[1]);
    }

    /**
     * Get all edges incident to a vertex.
     *
     * @param vertexIndex vertex index (0-3)
     * @return list of edge indices
     */
    public static List<Integer> getEdgesAtVertex(int vertexIndex) {
        if (vertexIndex < 0 || vertexIndex >= VERTICES_PER_TET) {
            throw new IllegalArgumentException("Vertex index must be 0-3");
        }

        var edges = new ArrayList<Integer>();
        for (int i = 0; i < EDGES_PER_TET; i++) {
            var edgeVerts = ESVTNodeGeometry.EDGE_VERTICES[i];
            if (edgeVerts[0] == vertexIndex || edgeVerts[1] == vertexIndex) {
                edges.add(i);
            }
        }
        return edges;
    }

    /**
     * Get all faces incident to a vertex.
     *
     * @param vertexIndex vertex index (0-3)
     * @return list of face indices (all faces except the one opposite this vertex)
     */
    public static List<Integer> getFacesAtVertex(int vertexIndex) {
        if (vertexIndex < 0 || vertexIndex >= VERTICES_PER_TET) {
            throw new IllegalArgumentException("Vertex index must be 0-3");
        }

        var faces = new ArrayList<Integer>();
        for (int f = 0; f < FACES_PER_TET; f++) {
            // Face f is opposite vertex f, so vertex v is on face f iff v != f
            if (f != vertexIndex) {
                faces.add(f);
            }
        }
        return faces;
    }

    /**
     * Get the faces that share a given edge.
     *
     * @param edgeIndex edge index (0-5)
     * @return list of 2 face indices sharing this edge
     */
    public static List<Integer> getFacesAtEdge(int edgeIndex) {
        if (edgeIndex < 0 || edgeIndex >= EDGES_PER_TET) {
            throw new IllegalArgumentException("Edge index must be 0-5");
        }

        var edgeVerts = ESVTNodeGeometry.EDGE_VERTICES[edgeIndex];
        var v0 = edgeVerts[0];
        var v1 = edgeVerts[1];

        // A face contains an edge if it does NOT contain the opposite vertices
        // Face f is opposite vertex f, so face f contains edge (v0,v1) iff f != v0 AND f != v1
        var faces = new ArrayList<Integer>();
        for (int f = 0; f < FACES_PER_TET; f++) {
            if (f != v0 && f != v1) {
                faces.add(f);
            }
        }
        return faces;
    }

    /**
     * Check if two faces share an edge.
     *
     * @param face1 first face index (0-3)
     * @param face2 second face index (0-3)
     * @return true if faces share an edge
     */
    public static boolean facesShareEdge(int face1, int face2) {
        if (face1 < 0 || face1 >= FACES_PER_TET || face2 < 0 || face2 >= FACES_PER_TET) {
            throw new IllegalArgumentException("Face indices must be 0-3");
        }
        // Any two distinct faces of a tetrahedron share exactly one edge
        return face1 != face2;
    }

    /**
     * Get the edge shared by two faces.
     *
     * @param face1 first face index (0-3)
     * @param face2 second face index (0-3)
     * @return edge index, or -1 if faces are the same
     */
    public static int getSharedEdge(int face1, int face2) {
        if (face1 < 0 || face1 >= FACES_PER_TET || face2 < 0 || face2 >= FACES_PER_TET) {
            throw new IllegalArgumentException("Face indices must be 0-3");
        }

        if (face1 == face2) {
            return -1;
        }

        // The shared edge connects the two vertices NOT opposite to face1 and face2
        for (int e = 0; e < EDGES_PER_TET; e++) {
            var edgeVerts = ESVTNodeGeometry.EDGE_VERTICES[e];
            if (edgeVerts[0] != face1 && edgeVerts[0] != face2 &&
                edgeVerts[1] != face1 && edgeVerts[1] != face2) {
                return e;
            }
        }
        return -1; // Should not reach here
    }

    /**
     * Calculate the tree level from a node's depth in the recursive structure.
     * In ESVT, depth corresponds directly to level (root = level 0).
     *
     * @param depth depth in the tree
     * @return level (same as depth)
     */
    public static int depthToLevel(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Depth must be non-negative");
        }
        return depth;
    }

    /**
     * Calculate the scale factor for a node at a given level.
     *
     * @param level tree level (0 = root)
     * @return scale factor (root = 1.0, level 1 = 0.5, etc.)
     */
    public static float getScaleAtLevel(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Level must be non-negative");
        }
        return 1.0f / (1 << level);
    }

    /**
     * Get the maximum number of nodes at a given level (theoretical maximum).
     *
     * @param level tree level
     * @return maximum nodes = 8^level
     */
    public static long getMaxNodesAtLevel(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Level must be non-negative");
        }
        return (long) Math.pow(BEY_BRANCHING_FACTOR, level);
    }

    // ========== Validation Helpers ==========

    private static void validateType(int tetType) {
        if (tetType < 0 || tetType >= TET_TYPE_COUNT) {
            throw new IllegalArgumentException("Tetrahedron type must be 0-5, got: " + tetType);
        }
    }

    private static void validateChildIndex(int childIndex) {
        if (childIndex < 0 || childIndex >= BEY_BRANCHING_FACTOR) {
            throw new IllegalArgumentException("Child index must be 0-7, got: " + childIndex);
        }
    }

    private ESVTTopology() {} // Prevent instantiation
}
