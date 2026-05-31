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
    /**
     * Child types for each parent type, Bey-indexed, IDENTICAL to t8code's {@code t8_dtet_type_of_child}
     * (RDR-010 Luciferase-4pd: Tet type k IS t8code dtet type k, in geometry and connectivity).
     * Corner children (0-3) match the parent type; interior children (4-7) follow t8code's
     * interior-octahedron diagonal choice. Matches BeySubdivision.CHILD_TYPES exactly.
     */
    public static final byte[][] PARENT_TYPE_TO_CHILD_TYPE = {
    // Parent type 0
    { 0, 0, 0, 0, 4, 5, 2, 1 },
    // Parent type 1
    { 1, 1, 1, 1, 3, 2, 5, 0 },
    // Parent type 2
    { 2, 2, 2, 2, 0, 1, 4, 3 },
    // Parent type 3
    { 3, 3, 3, 3, 5, 4, 1, 2 },
    // Parent type 4
    { 4, 4, 4, 4, 2, 3, 0, 5 },
    // Parent type 5
    { 5, 5, 5, 5, 1, 0, 3, 4 } };

    /**
     * Pyramid (types 6 and 7) child types by local index 0..9 (RDR-010, Knapp 2026 Table 3.2). Each
     * pyramid refines into 10 children: 6 pyramids (types 6/7) and 4 tetrahedra (types 0/3). Held
     * separately from {@link #PARENT_TYPE_TO_CHILD_TYPE} because pyramids have 10 children (not the
     * Bey 8) and a parent type outside the tet 0-5 range. Row index = {@code parentType - 6}; values
     * verified against t8code {@code t8_dpyramid_parenttype_Iloc_to_type} rows 6,7 (origin/main).
     */
    public static final byte[][] PYRAMID_PARENT_TO_CHILD_TYPE = {
    // Parent type 6
    { 6, 3, 6, 0, 6, 0, 3, 6, 7, 6 },
    // Parent type 7
    { 7, 0, 3, 6, 7, 3, 7, 0, 7, 7 } };

    /**
     * Pyramid (types 6 and 7) child cube-ids by local index 0..9 (RDR-010, Knapp Table 3.1). The
     * cube-id selects the child anchor shift within the parent's surrounding cube (bit 0 -&gt; +x,
     * bit 1 -&gt; +y, bit 2 -&gt; +z, each by half the parent edge length). Row index =
     * {@code parentType - 6}; verified against t8code {@code t8_dpyramid_parenttype_Iloc_to_cid}
     * rows 6,7 (origin/main).
     */
    public static final byte[][] PYRAMID_PARENT_TO_CHILD_CID = {
    // Parent type 6
    { 0, 1, 1, 2, 2, 3, 3, 3, 3, 7 },
    // Parent type 7
    { 0, 4, 4, 4, 4, 5, 5, 6, 6, 7 } };

    /**
     * Pyramid parent type by (own type, cube-id) for the pyramid-of-pyramid step (RDR-010, Knapp
     * 2026 Algorithm 4.1). A pyramid's parent is always a pyramid; its type is selected by this
     * element's type and the cube-id it occupies within the parent's surrounding cube. Row index =
     * {@code ownType - 6}; column = cube-id 0..7. The {@code -1} entries are cube-ids unreachable
     * for a pyramid of that type. Verified against t8code {@code t8_dpyramid_type_cid_to_parenttype}
     * (origin/main).
     */
    public static final byte[][] PYRAMID_TYPE_CID_TO_PARENT_TYPE = {
    // Own type 6
    { 6, 6, 6, 6, 7, -1, -1, 6 },
    // Own type 7
    { 7, -1, -1, 6, 7, 7, 7, 7 } };

    /**
     * Reciprocal face index for a pyramid's face neighbor, by (pyramid type, face) (RDR-010 q3p,
     * Knapp 2026 §4.4). Row index = {@code pyramidType - 6}; column = face 0..4. Verified against
     * t8code {@code t8_dpyramid_type_face_to_nface} (origin/main).
     */
    public static final byte[][] PYRAMID_TYPE_FACE_TO_NFACE = {
    // Type 6
    { 2, 3, 2, 3, 4 },
    // Type 7
    { 1, 0, 1, 0, 4 } };

    /**
     * Tetrahedral parent type by (cube-id, own type) — the inverse of {@link #PARENT_TYPE_TO_CHILD_TYPE}
     * used to walk a tet's type up the refinement tree (RDR-010 q3p face-neighbor ancestor walk).
     * {@code [cubeId][type]}. Verified against t8code {@code t8_dtet_cid_type_to_parenttype}.
     */
    /**
     * Whether a deep pyramid-rooted tet hugs the pyramid-face corner at refinement level i, indexed
     * {@code [face][beyId]} — t8code {@code t8_dpyramid_face_childid_to_is_inside}, verbatim. A value of
     * {@code -1} means the child does NOT lie in the corner at this face (so the deep tet's face neighbor
     * is a tet, not the bounding pyramid); {@code 0} means it stays in the corner and the corner-walk
     * continues. Used by {@link Tet#faceNeighborElement(int)}'s deep boundary test (port of t8code
     * {@code t8_dpyramid_tet_boundary}). RDR-010 Luciferase-cjwr — restored after q3p Phase D removed it
     * on the (now-stale, pre-4pd) premise that the t8code deep tables did not apply to Luciferase tets.
     * <p>Scope of validation: {@link #CID_TYPE_TO_PARENTTYPE} is oracle-verified by
     * {@code T8codeDtetOracleTest} (Luciferase tet type k IS t8code dtet type k). {@link #TYPE_CID_TO_BEYID}
     * and {@code FACE_CHILDID_TO_IS_INSIDE} — the two tables the deep corner walk consumes — are NOT
     * exercised by {@code T8codeDtetOracleTest}; they are transcription-parity-asserted against the t8code
     * literals by {@code T8codeDpyramidTetBoundaryOracleTest.cornerWalkTablesMatchT8codeVerbatim}
     * (RDR-012 D3.1). The {@code tetBoundary} corner-walk <em>algorithm</em> that uses this table is
     * validated by {@code HybridFaceNeighborTest} (Phase A) and the D3.1 parity sweep.
     */
    public static final int[][] FACE_CHILDID_TO_IS_INSIDE = {
    { -1, 0, 0, 0, -1, -1, -1, -1 },
    { 0, -1, 0, 0, -1, -1, -1, -1 },
    { 0, 0, -1, 0, -1, -1, -1, -1 },
    { 0, 0, 0, -1, -1, -1, -1, -1 } };

    /**
     * Parent type by (child cube-id, child type) — t8code's {@code t8_dtet_cid_type_to_parenttype}
     * (origin/main), verbatim. {@code [cid][childType] -> parentType}. This is the canonical t8code
     * parent-type walk: a tet's parent type is an O(1) function of the tet's own type and the cube-id
     * (octant) it occupies within the parent. RDR-010 Luciferase-4pd.
     */
    public static final byte[][] CID_TYPE_TO_PARENTTYPE = {
    // cid 0
    { 0, 1, 2, 3, 4, 5 },
    // cid 1
    { 0, 1, 1, 1, 0, 0 },
    // cid 2
    { 2, 2, 2, 3, 3, 3 },
    // cid 3
    { 1, 1, 2, 2, 2, 1 },
    // cid 4
    { 5, 5, 4, 4, 4, 5 },
    // cid 5
    { 0, 0, 0, 5, 5, 5 },
    // cid 6
    { 4, 3, 3, 3, 4, 4 },
    // cid 7
    { 0, 1, 2, 3, 4, 5 } };

    // RDR-010 Luciferase-4pd: the T8_TO_LUC / LUC_TO_T8 type-translation arrays (Finding #15) were
    // deleted. Luciferase Tet type k is now IDENTICAL to t8code dtet type k in both geometry and
    // connectivity, so the pyramid connectivity tables above (faithful t8code copies) are consumed
    // directly with no translation.

    /** Number of children of a pyramid (6 pyramids + 4 tetrahedra; Knapp 2026 §3). */
    public static final int CHILDREN_PER_PYRAMID = 10;

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
     * The children touching a given parent face are TYPE-DEPENDENT in t8code's dtet refinement. This is a
     * verbatim port of t8code {@code t8_dtet_face_child_id_by_type[6][4][4]}
     * ({@code t8code/src/t8_schemes/t8_default/t8_default_tet/t8_dtet_connectivity.c}). The prior Luciferase
     * table used a single type-invariant pattern ({4,5,6,7}/{2,3,6,7}/{1,3,5,7}/{1,2,4,5}) for all 6 types,
     * which matched NONE of the t8code rows and propagated descendant neighbors through the wrong children
     * (Luciferase-koaw; T3 critique-luciferase-t8code-tet-neighbor S1).
     *
     * [parent_type][face_index][position] -> child_index (Morton child index, 0-7)
     */
    public static final byte[][][] CHILDREN_AT_FACE = {
    // Type 0
    { { 1, 4, 5, 7 }, { 0, 4, 6, 7 }, { 0, 1, 2, 7 }, { 0, 1, 3, 4 } },
    // Type 1
    { { 1, 4, 5, 7 }, { 0, 5, 6, 7 }, { 0, 1, 3, 7 }, { 0, 1, 2, 5 } },
    // Type 2
    { { 3, 4, 5, 7 }, { 0, 4, 6, 7 }, { 0, 1, 3, 7 }, { 0, 2, 3, 4 } },
    // Type 3
    { { 1, 5, 6, 7 }, { 0, 4, 6, 7 }, { 0, 1, 3, 7 }, { 0, 1, 2, 6 } },
    // Type 4
    { { 3, 5, 6, 7 }, { 0, 4, 5, 7 }, { 0, 1, 3, 7 }, { 0, 2, 3, 5 } },
    // Type 5
    { { 3, 5, 6, 7 }, { 0, 4, 6, 7 }, { 0, 2, 3, 7 }, { 0, 1, 3, 6 } } };

    /**
     * Face-to-face mapping between parent and child. Given parent type, child index, and parent face, returns which
     * face of the child corresponds to that parent face.
     *
     * A value of -1 indicates the child doesn't touch that parent face. This table is TYPE-DEPENDENT and
     * is derived geometrically from the verified {@code Tet.coordinates()} so that it is consistent with the
     * t8code per-type {@link #CHILDREN_AT_FACE}: for each (parentType, childIndex, parentFace), the value is
     * the child-local face whose 3 vertices all lie on the parent face plane, or -1 if the child does not
     * touch that face. (Derived offline and frozen here to avoid a static-init cycle Tet <-> TetreeConnectivity;
     * the exact values are re-derived geometrically and asserted by
     * TetreeConnectivityTest.faceChildFaceMatchesGeometry, and the table is cross-checked against
     * {@link #CHILDREN_AT_FACE} by TetreeConnectivityTest.testFaceChildFace.) The prior table was
     * type-invariant and matched the made-up Bey CHILDREN_AT_FACE pattern (Luciferase-koaw).
     *
     * <p>NOTE on index space: this is indexed by the MORTON child index (0-7), unlike t8code's
     * {@code t8_dtet_face_child_face(elem, face, face_child)} which takes {@code face_child} as the ordinal
     * among the 4 children on that face. t8code has no equivalent static lookup table (it computes the value
     * algorithmically in {@code t8_dtri_bits.c}), so this table is validated by geometry, not by transcription.
     *
     * [parent_type][child_index][parent_face] -> child_face
     */
    public static final byte[][][] FACE_CHILD_FACE = {
    // Type 0
    { { -1, 1, 2, 3 }, { 0, -1, 2, 3 }, { -1, -1, 1, -1 }, { -1, -1, -1, 3 }, { 0, 1, -1, 3 }, { 0, -1, -1, -1 },
      { -1, 2, -1, -1 }, { 0, 1, 2, -1 } },
    // Type 1
    { { -1, 1, 2, 3 }, { 0, -1, 2, 3 }, { -1, -1, -1, 3 }, { -1, -1, 1, -1 }, { 0, -1, -1, -1 }, { 0, 1, -1, 3 },
      { -1, 2, -1, -1 }, { 0, 1, 2, -1 } },
    // Type 2
    { { -1, 1, 2, 3 }, { -1, -1, 1, -1 }, { -1, -1, -1, 3 }, { 0, -1, 2, 3 }, { 0, 1, -1, 3 }, { 0, -1, -1, -1 },
      { -1, 2, -1, -1 }, { 0, 1, 2, -1 } },
    // Type 3
    { { -1, 1, 2, 3 }, { 0, -1, 2, 3 }, { -1, -1, -1, 3 }, { -1, -1, 1, -1 }, { -1, 2, -1, -1 }, { 0, -1, -1, -1 },
      { 0, 1, -1, 3 }, { 0, 1, 2, -1 } },
    // Type 4
    { { -1, 1, 2, 3 }, { -1, -1, 1, -1 }, { -1, -1, -1, 3 }, { 0, -1, 2, 3 }, { -1, 2, -1, -1 }, { 0, 1, -1, 3 },
      { 0, -1, -1, -1 }, { 0, 1, 2, -1 } },
    // Type 5
    { { -1, 1, 2, 3 }, { -1, -1, -1, 3 }, { -1, -1, 1, -1 }, { 0, -1, 2, 3 }, { -1, 2, -1, -1 }, { 0, -1, -1, -1 },
      { 0, 1, -1, 3 }, { 0, 1, 2, -1 } } };

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
