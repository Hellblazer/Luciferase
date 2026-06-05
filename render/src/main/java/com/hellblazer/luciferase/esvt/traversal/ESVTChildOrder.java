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
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Precomputed child traversal order tables for ESVT.
 *
 * <p>When traversing a tetrahedron, we know which face the ray enters through.
 * Only 4 of the 8 Bey children touch each face. This class provides the
 * children that touch each entry face, ordered by centroid distance from
 * the face center for approximate front-to-back ordering.
 *
 * <p><b>Table Structure:</b>
 * <ul>
 *   <li>CHILD_ORDER[tetType][entryFace][position] → childIndex</li>
 *   <li>6 types × 4 faces × 4 children = 96 bytes</li>
 * </ul>
 *
 * <p><b>Face Convention:</b>
 * <ul>
 *   <li>Face 0: opposite vertex 0, children {4, 5, 6, 7}</li>
 *   <li>Face 1: opposite vertex 1, children {2, 3, 6, 7}</li>
 *   <li>Face 2: opposite vertex 2, children {1, 3, 5, 7}</li>
 *   <li>Face 3: opposite vertex 3, children {1, 2, 4, 5}</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class ESVTChildOrder {

    /**
     * Child traversal order indexed by [tetType][entryFace][position].
     * Returns child index (0-7) to traverse at each position.
     *
     * <p>Children are ordered by approximate distance from entry face center
     * to child centroid, enabling front-to-back traversal.
     */
    public static final byte[][][] CHILD_ORDER = new byte[6][4][4];

    /**
     * Inverse lookup: which position in CHILD_ORDER does a given child appear?
     * CHILD_POSITION[tetType][entryFace][childIndex] → position (0-3) or -1 if not at face
     */
    public static final byte[][][] CHILD_POSITION = new byte[6][4][8];

    /**
     * Child centroids in unit [0,1] space for each parent type and child index.
     * CHILD_CENTROIDS[tetType][childIndex] → {x, y, z}
     *
     * <p>Precomputed from Bey subdivision geometry.
     */
    private static final float[][][] CHILD_CENTROIDS = new float[6][8][3];

    /**
     * Face centers in unit [0,1] space for each tet type.
     * FACE_CENTERS[tetType][faceIndex] → {x, y, z}
     */
    private static final float[][][] FACE_CENTERS = new float[6][4][3];

    static {
        computeChildCentroids();
        computeFaceCenters();
        computeChildOrder();
        computeChildPosition();
    }

    private ESVTChildOrder() {
        // Utility class
    }

    /**
     * Get the children to traverse for a given type and entry face.
     *
     * @param tetType Tetrahedron type (0-5)
     * @param entryFace Entry face index (0-3)
     * @return Array of 4 child indices in front-to-back order
     */
    public static byte[] getChildOrder(int tetType, int entryFace) {
        return CHILD_ORDER[tetType][entryFace];
    }

    /**
     * Get a specific child from the traversal order.
     *
     * @param tetType Tetrahedron type (0-5)
     * @param entryFace Entry face index (0-3)
     * @param position Position in traversal order (0-3)
     * @return Child index (0-7)
     */
    public static byte getChild(int tetType, int entryFace, int position) {
        return CHILD_ORDER[tetType][entryFace][position];
    }

    /**
     * Check if a child touches the given entry face.
     *
     * @param tetType Tetrahedron type (0-5)
     * @param entryFace Entry face index (0-3)
     * @param childIndex Child index (0-7)
     * @return true if child touches the entry face
     */
    public static boolean childTouchesFace(int tetType, int entryFace, int childIndex) {
        return CHILD_POSITION[tetType][entryFace][childIndex] >= 0;
    }

    /**
     * Get the centroid of a child tetrahedron in unit space.
     *
     * @param tetType Parent tetrahedron type (0-5)
     * @param childIndex Child index (0-7)
     * @return Centroid as {x, y, z}
     */
    public static float[] getChildCentroid(int tetType, int childIndex) {
        return CHILD_CENTROIDS[tetType][childIndex];
    }

    /**
     * Get the center of a face in unit space.
     *
     * @param tetType Tetrahedron type (0-5)
     * @param faceIndex Face index (0-3)
     * @return Face center as {x, y, z}
     */
    public static float[] getFaceCenter(int tetType, int faceIndex) {
        return FACE_CENTERS[tetType][faceIndex];
    }

    /**
     * Derive child centroids from the authoritative Bey/t8code child geometry.
     *
     * <p>For each parent type and Morton child index, compute the centroid by:
     * <ol>
     *   <li>Looking up the Bey child index from {@link TetreeConnectivity#INDEX_TO_BEY_NUMBER}
     *       ({@code beyChild = INDEX_TO_BEY_NUMBER[type][mortonChild]})</li>
     *   <li>Computing the four vertices of Bey child {@code beyChild} from the parent's unit-cube
     *       vertices ({@link Constants#SIMPLEX_STANDARD}) and their edge midpoints</li>
     *   <li>Averaging the four vertices to get the centroid in [0,1] space</li>
     * </ol>
     *
     * <p>Bey child vertex sets (t8code / BeySubdivision.getBeyChild convention):
     * <ul>
     *   <li>Bey 0: {v0, m01, m02, m03}</li>
     *   <li>Bey 1: {m01, v1, m12, m13}</li>
     *   <li>Bey 2: {m02, m12, v2, m23}</li>
     *   <li>Bey 3: {m03, m13, m23, v3}</li>
     *   <li>Bey 4: {m01, m02, m03, m13}</li>
     *   <li>Bey 5: {m01, m02, m12, m13}</li>
     *   <li>Bey 6: {m02, m03, m13, m23}</li>
     *   <li>Bey 7: {m02, m12, m13, m23}</li>
     * </ul>
     *
     * <p>This replaces the previous hand-written midpoint sets which treated Morton indices 0-7
     * as if they were Bey indices 0-7, producing wrong centroids wherever Morton order differs
     * from Bey order (Luciferase-7wzml.171).
     */
    private static void computeChildCentroids() {
        for (int type = 0; type < 6; type++) {
            Point3i[] pv = Constants.SIMPLEX_STANDARD[type];
            float[] v0 = toFloat(pv[0]);
            float[] v1 = toFloat(pv[1]);
            float[] v2 = toFloat(pv[2]);
            float[] v3 = toFloat(pv[3]);

            // All six edge midpoints of the parent tetrahedron
            float[] m01 = midpoint(v0, v1);
            float[] m02 = midpoint(v0, v2);
            float[] m03 = midpoint(v0, v3);
            float[] m12 = midpoint(v1, v2);
            float[] m13 = midpoint(v1, v3);
            float[] m23 = midpoint(v2, v3);

            // Bey child vertex sets (indices 0-7), matching t8code / BeySubdivision.getBeyChild
            float[][] beyVerts = {
                centroid(v0,  m01, m02, m03), // Bey 0: {v0,  m01, m02, m03}
                centroid(m01, v1,  m12, m13), // Bey 1: {m01, v1,  m12, m13}
                centroid(m02, m12, v2,  m23), // Bey 2: {m02, m12, v2,  m23}
                centroid(m03, m13, m23, v3),  // Bey 3: {m03, m13, m23, v3}
                centroid(m01, m02, m03, m13), // Bey 4: {m01, m02, m03, m13}
                centroid(m01, m02, m12, m13), // Bey 5: {m01, m02, m12, m13}
                centroid(m02, m03, m13, m23), // Bey 6: {m02, m03, m13, m23}
                centroid(m02, m12, m13, m23), // Bey 7: {m02, m12, m13, m23}
            };

            for (int mortonChild = 0; mortonChild < 8; mortonChild++) {
                int beyChild = TetreeConnectivity.INDEX_TO_BEY_NUMBER[type][mortonChild];
                CHILD_CENTROIDS[type][mortonChild] = beyVerts[beyChild];
            }
        }
    }

    private static float[] toFloat(Point3i p) {
        return new float[] { p.x, p.y, p.z };
    }

    /**
     * Compute face centers from tet vertices.
     */
    private static void computeFaceCenters() {
        for (int type = 0; type < 6; type++) {
            Point3i[] verts = Constants.SIMPLEX_STANDARD[type];
            float[] v0 = {verts[0].x, verts[0].y, verts[0].z};
            float[] v1 = {verts[1].x, verts[1].y, verts[1].z};
            float[] v2 = {verts[2].x, verts[2].y, verts[2].z};
            float[] v3 = {verts[3].x, verts[3].y, verts[3].z};

            // Face 0: opposite v0, center of (v1, v2, v3)
            FACE_CENTERS[type][0] = triangleCentroid(v1, v2, v3);

            // Face 1: opposite v1, center of (v0, v2, v3)
            FACE_CENTERS[type][1] = triangleCentroid(v0, v2, v3);

            // Face 2: opposite v2, center of (v0, v1, v3)
            FACE_CENTERS[type][2] = triangleCentroid(v0, v1, v3);

            // Face 3: opposite v3, center of (v0, v1, v2)
            FACE_CENTERS[type][3] = triangleCentroid(v0, v1, v2);
        }
    }

    /**
     * Compute child order by sorting children at each face by distance from face center.
     */
    private static void computeChildOrder() {
        for (int type = 0; type < 6; type++) {
            for (int face = 0; face < 4; face++) {
                // Get children at this face from TetreeConnectivity
                byte[] children = TetreeConnectivity.CHILDREN_AT_FACE[type][face];
                float[] faceCenter = FACE_CENTERS[type][face];

                // Create array with distances for sorting
                int[] indices = new int[4];
                float[] distances = new float[4];
                for (int i = 0; i < 4; i++) {
                    indices[i] = children[i];
                    float[] childCenter = CHILD_CENTROIDS[type][children[i]];
                    distances[i] = distance(faceCenter, childCenter);
                }

                // Sort by distance (simple bubble sort for 4 elements)
                for (int i = 0; i < 3; i++) {
                    for (int j = i + 1; j < 4; j++) {
                        if (distances[j] < distances[i]) {
                            // Swap
                            float tmpDist = distances[i];
                            distances[i] = distances[j];
                            distances[j] = tmpDist;
                            int tmpIdx = indices[i];
                            indices[i] = indices[j];
                            indices[j] = tmpIdx;
                        }
                    }
                }

                // Store sorted order
                for (int i = 0; i < 4; i++) {
                    CHILD_ORDER[type][face][i] = (byte) indices[i];
                }
            }
        }
    }

    /**
     * Compute inverse position lookup.
     */
    private static void computeChildPosition() {
        // Initialize all to -1 (not at face)
        for (int type = 0; type < 6; type++) {
            for (int face = 0; face < 4; face++) {
                for (int child = 0; child < 8; child++) {
                    CHILD_POSITION[type][face][child] = -1;
                }
            }
        }

        // Fill in positions from CHILD_ORDER
        for (int type = 0; type < 6; type++) {
            for (int face = 0; face < 4; face++) {
                for (int pos = 0; pos < 4; pos++) {
                    int child = CHILD_ORDER[type][face][pos];
                    CHILD_POSITION[type][face][child] = (byte) pos;
                }
            }
        }
    }

    // Helper methods

    private static float[] midpoint(float[] a, float[] b) {
        return new float[]{
            (a[0] + b[0]) / 2,
            (a[1] + b[1]) / 2,
            (a[2] + b[2]) / 2
        };
    }

    private static float[] centroid(float[] a, float[] b, float[] c, float[] d) {
        return new float[]{
            (a[0] + b[0] + c[0] + d[0]) / 4,
            (a[1] + b[1] + c[1] + d[1]) / 4,
            (a[2] + b[2] + c[2] + d[2]) / 4
        };
    }

    private static float[] triangleCentroid(float[] a, float[] b, float[] c) {
        return new float[]{
            (a[0] + b[0] + c[0]) / 3,
            (a[1] + b[1] + c[1]) / 3,
            (a[2] + b[2] + c[2]) / 3
        };
    }

    private static float distance(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
