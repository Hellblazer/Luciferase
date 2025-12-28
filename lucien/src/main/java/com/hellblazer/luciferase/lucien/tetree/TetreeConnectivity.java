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

/**
 * Precomputed connectivity tables for tetrahedral tree operations. Based on t8code's t8_dtet_connectivity.c
 * implementation.
 *
 * These tables encode the Bey refinement scheme for tetrahedra, where each tetrahedron is refined into 8 children. The
 * tables provide O(1) lookup for parent-child relationships, face mappings, and sibling relationships.
 *
 * @author hal.hildebrand
 */
public final class TetreeConnectivity {

    // Number of children per tetrahedron in Bey refinement
    public static final int CHILDREN_PER_TET = 8;

    // Number of faces per tetrahedron
    public static final int FACES_PER_TET = 4;

    // Number of vertices per tetrahedron
    public static final int VERTICES_PER_TET = 4;

    // Number of edges per tetrahedron
    public static final int EDGES_PER_TET = 6;

    // Number of vertices per face
    public static final int VERTICES_PER_FACE = 3;

    // Number of tetrahedron types in grid subdivision
    public static final int TET_TYPES = 6;

    /**
     * Parent type to child type mapping. Given a parent tetrahedron type (0-5) and child index (0-7), returns the type
     * of that child tetrahedron.
     *
     * Based on t8code's t8_dtet_type_to_child_type table. [parent_type][child_index] -> child_type
     */
    public static final byte[][] PARENT_TYPE_TO_CHILD_TYPE = {
    // Parent type 0 - matches t8code t8_dtet_type_of_child[0]
    { 0, 0, 0, 0, 4, 5, 2, 1 },
    // Parent type 1 - matches t8code t8_dtet_type_of_child[1]
    { 1, 1, 1, 1, 3, 2, 5, 0 },
    // Parent type 2 - matches t8code t8_dtet_type_of_child[2]
    { 2, 2, 2, 2, 0, 1, 4, 3 },
    // Parent type 3 - matches t8code t8_dtet_type_of_child[3]
    { 3, 3, 3, 3, 5, 4, 1, 2 },
    // Parent type 4 - matches t8code t8_dtet_type_of_child[4]
    { 4, 4, 4, 4, 2, 3, 0, 5 },
    // Parent type 5 - matches t8code t8_dtet_type_of_child[5]
    { 5, 5, 5, 5, 1, 0, 3, 4 } };

    /**
     * Face corner indices for each tetrahedron type. Given a tetrahedron type (0-5) and face index (0-3), returns the
     * vertex indices that form that face.
     *
     * Face indexing follows t8code convention: - Face 0: opposite vertex 0 - Face 1: opposite vertex 1 - Face 2:
     * opposite vertex 2 - Face 3: opposite vertex 3
     *
     * [tet_type][face_index][corner_index] -> vertex_index
     */
    public static final byte[][][] FACE_CORNERS = {
    // Type 0
    { { 1, 2, 3 }, { 0, 2, 3 }, { 0, 1, 3 }, { 0, 1, 2 } },
    // Type 1
    { { 1, 2, 3 }, { 0, 2, 3 }, { 0, 1, 3 }, { 0, 1, 2 } },
    // Type 2
    { { 1, 2, 3 }, { 0, 2, 3 }, { 0, 1, 3 }, { 0, 1, 2 } },
    // Type 3
    { { 1, 2, 3 }, { 0, 2, 3 }, { 0, 1, 3 }, { 0, 1, 2 } },
    // Type 4
    { { 1, 2, 3 }, { 0, 2, 3 }, { 0, 1, 3 }, { 0, 1, 2 } },
    // Type 5
    { { 1, 2, 3 }, { 0, 2, 3 }, { 0, 1, 3 }, { 0, 1, 2 } } };

    /**
     * Children at each face for Bey refinement. Given a parent type and face index, returns which children touch that
     * face (up to 4 children per face).
     *
     * In Bey refinement, the children at each face are: - Face 0 (opposite vertex 0): children 4, 5, 6, 7 - Face 1
     * (opposite vertex 1): children 2, 3, 6, 7 - Face 2 (opposite vertex 2): children 1, 3, 5, 7 - Face 3 (opposite
     * vertex 3): children 1, 2, 4, 5
     *
     * [parent_type][face_index][position] -> child_index
     */
    public static final byte[][][] CHILDREN_AT_FACE = {
    // Type 0 - same pattern for all types in standard Bey refinement
    { { 4, 5, 6, 7 }, { 2, 3, 6, 7 }, { 1, 3, 5, 7 }, { 1, 2, 4, 5 } },
    // Type 1
    { { 4, 5, 6, 7 }, { 2, 3, 6, 7 }, { 1, 3, 5, 7 }, { 1, 2, 4, 5 } },
    // Type 2
    { { 4, 5, 6, 7 }, { 2, 3, 6, 7 }, { 1, 3, 5, 7 }, { 1, 2, 4, 5 } },
    // Type 3
    { { 4, 5, 6, 7 }, { 2, 3, 6, 7 }, { 1, 3, 5, 7 }, { 1, 2, 4, 5 } },
    // Type 4
    { { 4, 5, 6, 7 }, { 2, 3, 6, 7 }, { 1, 3, 5, 7 }, { 1, 2, 4, 5 } },
    // Type 5
    { { 4, 5, 6, 7 }, { 2, 3, 6, 7 }, { 1, 3, 5, 7 }, { 1, 2, 4, 5 } } };

    /**
     * Face-to-face mapping between parent and child. Given parent type, child index, and parent face, returns which
     * face of the child corresponds to that parent face.
     *
     * A value of -1 indicates the child doesn't touch that parent face. Based on CHILDREN_AT_FACE: - Face 0: children
     * 4,5,6,7 - Face 1: children 2,3,6,7 - Face 2: children 1,3,5,7 - Face 3: children 1,2,4,5
     *
     * [parent_type][child_index][parent_face] -> child_face
     */
    public static final byte[][][] FACE_CHILD_FACE = {
    // Type 0
    { { -1, -1, -1, -1 },  // Child 0 (interior)
      { -1, -1, 2, 3 },    // Child 1 (at faces 2,3)
      { -1, 1, -1, 3 },    // Child 2 (at faces 1,3)
      { -1, 1, 2, -1 },    // Child 3 (at faces 1,2)
      { 0, -1, -1, 3 },    // Child 4 (at faces 0,3)
      { 0, -1, 2, 3 },     // Child 5 (at faces 0,2,3)
      { 0, 1, -1, -1 },    // Child 6 (at faces 0,1)
      { 0, 1, 2, -1 }      // Child 7 (at faces 0,1,2)
    },
    // Type 1 (same pattern for standard Bey refinement)
    { { -1, -1, -1, -1 },  // Child 0 (interior)
      { -1, -1, 2, 3 },    // Child 1 (at faces 2,3)
      { -1, 1, -1, 3 },    // Child 2 (at faces 1,3)
      { -1, 1, 2, -1 },    // Child 3 (at faces 1,2)
      { 0, -1, -1, 3 },    // Child 4 (at faces 0,3)
      { 0, -1, 2, 3 },     // Child 5 (at faces 0,2,3)
      { 0, 1, -1, -1 },    // Child 6 (at faces 0,1)
      { 0, 1, 2, -1 }      // Child 7 (at faces 0,1,2)
    },
    // Type 2
    { { -1, -1, -1, -1 },  // Child 0 (interior)
      { -1, -1, 2, 3 },    // Child 1 (at faces 2,3)
      { -1, 1, -1, 3 },    // Child 2 (at faces 1,3)
      { -1, 1, 2, -1 },    // Child 3 (at faces 1,2)
      { 0, -1, -1, 3 },    // Child 4 (at faces 0,3)
      { 0, -1, 2, 3 },     // Child 5 (at faces 0,2,3)
      { 0, 1, -1, -1 },    // Child 6 (at faces 0,1)
      { 0, 1, 2, -1 }      // Child 7 (at faces 0,1,2)
    },
    // Type 3
    { { -1, -1, -1, -1 },  // Child 0 (interior)
      { -1, -1, 2, 3 },    // Child 1 (at faces 2,3)
      { -1, 1, -1, 3 },    // Child 2 (at faces 1,3)
      { -1, 1, 2, -1 },    // Child 3 (at faces 1,2)
      { 0, -1, -1, 3 },    // Child 4 (at faces 0,3)
      { 0, -1, 2, 3 },     // Child 5 (at faces 0,2,3)
      { 0, 1, -1, -1 },    // Child 6 (at faces 0,1)
      { 0, 1, 2, -1 }      // Child 7 (at faces 0,1,2)
    },
    // Type 4
    { { -1, -1, -1, -1 },  // Child 0 (interior)
      { -1, -1, 2, 3 },    // Child 1 (at faces 2,3)
      { -1, 1, -1, 3 },    // Child 2 (at faces 1,3)
      { -1, 1, 2, -1 },    // Child 3 (at faces 1,2)
      { 0, -1, -1, 3 },    // Child 4 (at faces 0,3)
      { 0, -1, 2, 3 },     // Child 5 (at faces 0,2,3)
      { 0, 1, -1, -1 },    // Child 6 (at faces 0,1)
      { 0, 1, 2, -1 }      // Child 7 (at faces 0,1,2)
    },
    // Type 5
    { { -1, -1, -1, -1 },  // Child 0 (interior)
      { -1, -1, 2, 3 },    // Child 1 (at faces 2,3)
      { -1, 1, -1, 3 },    // Child 2 (at faces 1,3)
      { -1, 1, 2, -1 },    // Child 3 (at faces 1,2)
      { 0, -1, -1, 3 },    // Child 4 (at faces 0,3)
      { 0, -1, 2, 3 },     // Child 5 (at faces 0,2,3)
      { 0, 1, -1, -1 },    // Child 6 (at faces 0,1)
      { 0, 1, 2, -1 }      // Child 7 (at faces 0,1,2)
    } };

    /**
     * Sibling relationships in Bey refinement. Given two child indices, returns true if they are siblings (i.e., they
     * share the same parent).
     *
     * All 8 children of a parent are siblings to each other. [child1_index][child2_index] -> are_siblings
     */
    public static final boolean[][] ARE_SIBLINGS = new boolean[8][8];

    /**
     * Child vertex positions relative to parent. For each child (0-7), stores which vertices coincide with parent
     * vertices and which are at edge midpoints or the center.
     *
     * Encoding: - 0-3: Parent vertex indices - 4-9: Edge midpoint indices (edges 01, 02, 03, 12, 13, 23) - 10: Center
     * point
     *
     * [child_index][child_vertex] -> parent_reference_point
     */
    public static final byte[][] CHILD_VERTEX_PARENT_VERTEX = {
    // Child 0 (interior octahedron)
    { 4, 5, 6, 10 },   // Vertices at edge midpoints and center
    // Child 1 (corner at vertex 0)
    { 0, 4, 5, 10 },   // Vertex 0 of parent, plus edge midpoints
    // Child 2 (corner at vertex 1)
    { 4, 1, 7, 10 },   // Vertex 1 of parent, plus edge midpoints
    // Child 3 (corner at vertex 2)
    { 5, 7, 2, 10 },   // Vertex 2 of parent, plus edge midpoints
    // Child 4 (corner at vertex 3)
    { 6, 8, 9, 3 },    // Vertex 3 of parent, plus edge midpoints
    // Child 5
    { 10, 5, 6, 9 },   // Mixed corners and center
    // Child 6
    { 4, 10, 8, 7 },   // Mixed corners and center
    // Child 7
    { 10, 9, 8, 7 }    // Edge midpoints and center
    };

    /**
     * Face neighbor type transitions. When crossing a face from one tetrahedron to its neighbor, the type may change.
     * This table encodes those transitions based on the actual t8code algorithm.
     *
     * [tet_type][face_index] -> neighbor_type
     */
    public static final byte[][] FACE_NEIGHBOR_TYPE = {
    // Type 0
    { 4, 5, 1, 2 },
    // Type 1
    { 3, 2, 0, 5 },
    // Type 2
    { 0, 1, 3, 4 },
    // Type 3
    { 5, 4, 2, 1 },
    // Type 4
    { 2, 3, 5, 0 },
    // Type 5
    { 1, 0, 4, 3 } };

    /**
     * Index to Bey number mapping - from t8code t8_dtet_index_to_bey_number. Maps from child index (0-7) to Bey child
     * ID used in Bey's tetrahedral refinement scheme.
     *
     * [parent_type][child_index] -> bey_number
     */
    public static final byte[][] INDEX_TO_BEY_NUMBER = {
    // Parent type 0
    { 0, 1, 4, 5, 2, 7, 6, 3 },
    // Parent type 1
    { 0, 1, 5, 4, 7, 2, 6, 3 },
    // Parent type 2
    { 0, 4, 5, 1, 2, 7, 6, 3 },
    // Parent type 3
    { 0, 1, 5, 4, 6, 7, 2, 3 },
    // Parent type 4
    { 0, 4, 5, 1, 6, 2, 7, 3 },
    // Parent type 5
    { 0, 5, 4, 1, 6, 7, 2, 3 } };

    /**
     * Bey ID to vertex mapping - from t8code t8_dtet_beyid_to_vertex. Maps from Bey child ID to the parent vertex that
     * the child is anchored at.
     *
     * Child 0 is interior (no parent vertex), children 1-3 are at parent vertices 0-3, children 4-7 are at edge
     * midpoints defined by this mapping.
     */
    public static final byte[] BEY_ID_TO_VERTEX = { 0, 1, 2, 3, 1, 1, 2, 2 };

    /**
     * Type and cube-ID to Bey child ID mapping - from t8code t8_dtet_type_cid_to_beyid.
     * Maps from (child type, cube ID) to Bey child ID.
     *
     * [child_type][cube_id] -> bey_child_id
     */
    public static final byte[][] TYPE_CID_TO_BEYID = {
    // Type 0
    { 0, 1, 4, 7, 5, 2, 6, 3 },
    // Type 1
    { 0, 1, 5, 2, 4, 7, 6, 3 },
    // Type 2
    { 0, 5, 1, 2, 4, 6, 7, 3 },
    // Type 3
    { 0, 4, 1, 7, 5, 6, 2, 3 },
    // Type 4
    { 0, 4, 5, 6, 1, 7, 2, 3 },
    // Type 5
    { 0, 5, 4, 6, 1, 2, 7, 3 } };

    /**
     * Bey number to Morton index mapping - inverse of INDEX_TO_BEY_NUMBER.
     * Maps from Bey child ID (0-7) to Morton child index used for tree storage.
     *
     * This is essential for ray traversal where CHILDREN_AT_FACE provides Bey indices,
     * but ESVTNodeUnified stores children indexed by Morton order.
     *
     * [parent_type][bey_number] -> morton_index
     */
    public static final byte[][] BEY_NUMBER_TO_INDEX = {
    // Parent type 0: Bey {0,1,2,3,4,5,6,7} -> Morton {0,1,4,7,2,3,6,5}
    { 0, 1, 4, 7, 2, 3, 6, 5 },
    // Parent type 1
    { 0, 1, 5, 7, 3, 2, 6, 4 },
    // Parent type 2
    { 0, 3, 4, 7, 1, 2, 6, 5 },
    // Parent type 3
    { 0, 1, 6, 7, 3, 2, 4, 5 },
    // Parent type 4
    { 0, 3, 5, 7, 1, 2, 4, 6 },
    // Parent type 5
    { 0, 3, 6, 7, 2, 1, 4, 5 } };

    // Static initializer for computed tables
    static {
        // Initialize sibling relationships (all children of same parent are siblings)
        for (var i = 0; i < 8; i++) {
            for (var j = 0; j < 8; j++) {
                ARE_SIBLINGS[i][j] = true; // All 8 children are siblings
            }
        }
    }

    // Private constructor to prevent instantiation
    private TetreeConnectivity() {
        throw new AssertionError("TetreeConnectivity is a utility class and should not be instantiated");
    }

    /**
     * Check if two child indices are siblings (share the same parent).
     *
     * @param child1 First child index (0-7)
     * @param child2 Second child index (0-7)
     * @return true if they are siblings
     */
    public static boolean areSiblings(int child1, int child2) {
        return ARE_SIBLINGS[child1][child2];
    }

    /**
     * Get Bey child ID for parent type and child index. The Bey child ID determines the position of the child within
     * its parent according to Bey's tetrahedral refinement scheme.
     *
     * @param parentType Type of parent tetrahedron (0-5)
     * @param childIndex Index of child (0-7) in Morton order
     * @return Bey child ID
     */
    public static byte getBeyChildId(byte parentType, int childIndex) {
        return INDEX_TO_BEY_NUMBER[parentType][childIndex];
    }

    /**
     * Get Morton child index for parent type and Bey child ID. This is the inverse of getBeyChildId().
     * Essential for ray traversal where CHILDREN_AT_FACE provides Bey indices but tree storage uses Morton order.
     *
     * @param parentType Type of parent tetrahedron (0-5)
     * @param beyChildId Bey child ID (0-7)
     * @return Morton child index for tree storage
     */
    public static byte getMortonChildId(byte parentType, int beyChildId) {
        return BEY_NUMBER_TO_INDEX[parentType][beyChildId];
    }

    /**
     * Get the Morton child index of a child tetrahedron within its parent.
     * This is used for sorting siblings in Morton order for ESVT tree construction.
     *
     * <p>The computation uses:
     * <ol>
     *   <li>Child's cube ID at its own level</li>
     *   <li>TYPE_CID_TO_BEYID to get Bey child ID from (childType, cubeId)</li>
     *   <li>BEY_NUMBER_TO_INDEX to convert Bey to Morton using parent type</li>
     * </ol>
     *
     * @param childType The child tetrahedron's type (0-5)
     * @param childCubeId The child's cube ID at its level
     * @param parentType The parent tetrahedron's type (0-5)
     * @return Morton child index (0-7)
     */
    public static byte getMortonChildIndex(byte childType, byte childCubeId, byte parentType) {
        // Get Bey child ID from child's type and cube position
        byte beyId = TYPE_CID_TO_BEYID[childType][childCubeId];
        // Convert Bey to Morton using parent's type
        return BEY_NUMBER_TO_INDEX[parentType][beyId];
    }

    /**
     * Get vertex number for Bey child ID. Returns which parent vertex the child is anchored at. Child 0 is interior,
     * children 1-3 are at parent vertices 0-3, children 4-7 are at edge midpoints.
     *
     * @param beyId Bey child ID (0-7)
     * @return Parent vertex number (0-3)
     */
    public static byte getBeyVertex(byte beyId) {
        return BEY_ID_TO_VERTEX[beyId];
    }

    /**
     * Given a parent face, find which face of a child corresponds to it.
     *
     * @param parentType Type of parent tetrahedron (0-5)
     * @param childIndex Index of child (0-7)
     * @param parentFace Face index on parent (0-3)
     * @return Face index on child, or -1 if child doesn't touch that parent face
     */
    public static byte getChildFace(byte parentType, int childIndex, int parentFace) {
        return FACE_CHILD_FACE[parentType][childIndex][parentFace];
    }

    /**
     * Get the type of a child tetrahedron given parent type and child index.
     *
     * @param parentType Type of parent tetrahedron (0-5)
     * @param childIndex Index of child (0-7)
     * @return Type of the child tetrahedron
     */
    public static byte getChildType(byte parentType, int childIndex) {
        return PARENT_TYPE_TO_CHILD_TYPE[parentType][childIndex];
    }

    /**
     * Get the children that touch a specific face of the parent.
     *
     * @param parentType Type of parent tetrahedron (0-5)
     * @param faceIndex  Index of face (0-3)
     * @return Array of child indices at that face
     */
    public static byte[] getChildrenAtFace(byte parentType, int faceIndex) {
        return CHILDREN_AT_FACE[parentType][faceIndex];
    }

    /**
     * Get the vertices that form a face of a tetrahedron.
     *
     * @param tetType   Type of tetrahedron (0-5)
     * @param faceIndex Index of face (0-3)
     * @return Array of 3 vertex indices forming the face
     */
    public static byte[] getFaceCorners(byte tetType, int faceIndex) {
        return FACE_CORNERS[tetType][faceIndex];
    }

    /**
     * Get the type of tetrahedron across a face boundary.
     *
     * @param tetType   Current tetrahedron type (0-5)
     * @param faceIndex Face to cross (0-3)
     * @return Type of the neighboring tetrahedron
     */
    public static byte getFaceNeighborType(byte tetType, int faceIndex) {
        return FACE_NEIGHBOR_TYPE[tetType][faceIndex];
    }
}
