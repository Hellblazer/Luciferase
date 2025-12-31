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

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Utility class for tetrahedral geometry calculations in ESVT.
 *
 * <p>This class provides geometric calculations for ESVT nodes using the S0-S5
 * characteristic tetrahedra that tile a cube. Each tetrahedron type has specific
 * vertex positions and face orientations.</p>
 *
 * <p>Tetrahedron Types (S0-S5):</p>
 * <ul>
 *   <li>All types share vertices at cube corners (0,0,0) and (1,1,1)</li>
 *   <li>Types differ in which two additional cube corners are used</li>
 *   <li>6 tetrahedra perfectly tile the unit cube</li>
 * </ul>
 *
 * <p>Face numbering convention:</p>
 * <ul>
 *   <li>Face 0: opposite vertex 0</li>
 *   <li>Face 1: opposite vertex 1</li>
 *   <li>Face 2: opposite vertex 2</li>
 *   <li>Face 3: opposite vertex 3</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class ESVTNodeGeometry {

    /**
     * Standard tetrahedron vertex coordinates for each S0-S5 type.
     * [type][vertex] -> (x, y, z) in unit cube [0,1]³
     */
    public static final float[][][] SIMPLEX_STANDARD = {
        // Type 0: corners c0, c1, c5, c7
        {{0,0,0}, {1,0,0}, {1,0,1}, {1,1,1}},
        // Type 1: corners c0, c7, c3, c1
        {{0,0,0}, {1,1,1}, {1,1,0}, {1,0,0}},
        // Type 2: corners c0, c2, c3, c7
        {{0,0,0}, {0,1,0}, {1,1,0}, {1,1,1}},
        // Type 3: corners c0, c7, c6, c2
        {{0,0,0}, {1,1,1}, {0,1,1}, {0,1,0}},
        // Type 4: corners c0, c4, c6, c7
        {{0,0,0}, {0,0,1}, {0,1,1}, {1,1,1}},
        // Type 5: corners c0, c7, c5, c4
        {{0,0,0}, {1,1,1}, {1,0,1}, {0,0,1}}
    };

    /**
     * Child type derivation: [parentType][childIndex] -> childType
     */
    public static final int[][] PARENT_TYPE_TO_CHILD_TYPE = {
        {0, 0, 0, 0, 4, 5, 2, 1},  // Parent type 0
        {1, 1, 1, 1, 3, 2, 5, 0},  // Parent type 1
        {2, 2, 2, 2, 0, 1, 4, 3},  // Parent type 2
        {3, 3, 3, 3, 5, 4, 1, 2},  // Parent type 3
        {4, 4, 4, 4, 2, 3, 0, 5},  // Parent type 4
        {5, 5, 5, 5, 1, 0, 3, 4}   // Parent type 5
    };

    /**
     * Face vertices: [faceIndex] -> array of 3 vertex indices forming the face
     * Face i is opposite to vertex i
     */
    public static final int[][] FACE_VERTICES = {
        {1, 2, 3},  // Face 0: opposite v0
        {0, 2, 3},  // Face 1: opposite v1
        {0, 1, 3},  // Face 2: opposite v2
        {0, 1, 2}   // Face 3: opposite v3
    };

    /**
     * Edge vertices: each edge as a pair of vertex indices
     */
    public static final int[][] EDGE_VERTICES = {
        {0, 1}, {0, 2}, {0, 3},  // Edges from v0
        {1, 2}, {1, 3},          // Edges from v1 (not to v0)
        {2, 3}                   // Edge from v2 to v3
    };

    /**
     * Result of tetrahedral geometry calculations.
     */
    public record TetrahedronGeometry(
        Point3f[] vertices,
        Point3f centroid,
        float volume,
        float inscribedRadius,
        float circumscribedRadius
    ) {}

    /**
     * Axis-aligned bounding box for a tetrahedron.
     */
    public record TetrahedronBounds(
        Point3f min,
        Point3f max
    ) {
        public Point3f center() {
            return new Point3f(
                (min.x + max.x) / 2.0f,
                (min.y + max.y) / 2.0f,
                (min.z + max.z) / 2.0f
            );
        }

        public Vector3f size() {
            return new Vector3f(
                max.x - min.x,
                max.y - min.y,
                max.z - min.z
            );
        }
    }

    /**
     * Get the 4 vertices of a tetrahedron for a given type.
     *
     * @param tetType tetrahedron type (0-5)
     * @return array of 4 vertex positions
     */
    public static Point3f[] getVertices(int tetType) {
        validateType(tetType);
        var vertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(
                SIMPLEX_STANDARD[tetType][i][0],
                SIMPLEX_STANDARD[tetType][i][1],
                SIMPLEX_STANDARD[tetType][i][2]
            );
        }
        return vertices;
    }

    /**
     * Get the 4 vertices of a tetrahedron scaled and translated.
     *
     * @param tetType tetrahedron type (0-5)
     * @param origin origin point
     * @param scale scale factor
     * @return array of 4 vertex positions
     */
    public static Point3f[] getVertices(int tetType, Point3f origin, float scale) {
        validateType(tetType);
        var vertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(
                origin.x + SIMPLEX_STANDARD[tetType][i][0] * scale,
                origin.y + SIMPLEX_STANDARD[tetType][i][1] * scale,
                origin.z + SIMPLEX_STANDARD[tetType][i][2] * scale
            );
        }
        return vertices;
    }

    /**
     * Calculate the centroid of a tetrahedron (average of 4 vertices).
     *
     * @param tetType tetrahedron type (0-5)
     * @return centroid point
     */
    public static Point3f getCentroid(int tetType) {
        validateType(tetType);
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) {
            cx += SIMPLEX_STANDARD[tetType][i][0];
            cy += SIMPLEX_STANDARD[tetType][i][1];
            cz += SIMPLEX_STANDARD[tetType][i][2];
        }
        return new Point3f(cx / 4.0f, cy / 4.0f, cz / 4.0f);
    }

    /**
     * Calculate the centroid of a tetrahedron with position and scale.
     *
     * @param tetType tetrahedron type (0-5)
     * @param origin origin point
     * @param scale scale factor
     * @return centroid point
     */
    public static Point3f getCentroid(int tetType, Point3f origin, float scale) {
        var c = getCentroid(tetType);
        return new Point3f(
            origin.x + c.x * scale,
            origin.y + c.y * scale,
            origin.z + c.z * scale
        );
    }

    /**
     * Calculate the outward-facing normal of a face.
     *
     * @param tetType tetrahedron type (0-5)
     * @param faceIndex face index (0-3)
     * @return normalized face normal pointing outward
     */
    public static Vector3f getFaceNormal(int tetType, int faceIndex) {
        validateType(tetType);
        if (faceIndex < 0 || faceIndex > 3) {
            throw new IllegalArgumentException("Face index must be 0-3");
        }

        var vertices = getVertices(tetType);
        var fv = FACE_VERTICES[faceIndex];

        // Get three vertices of the face
        var v0 = vertices[fv[0]];
        var v1 = vertices[fv[1]];
        var v2 = vertices[fv[2]];

        // Calculate edges
        var edge1 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        var edge2 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);

        // Cross product for normal
        var normal = new Vector3f();
        normal.cross(edge1, edge2);
        normal.normalize();

        // Ensure normal points outward (away from opposite vertex)
        var oppositeVertex = vertices[faceIndex];
        var faceCentroid = new Point3f(
            (v0.x + v1.x + v2.x) / 3.0f,
            (v0.y + v1.y + v2.y) / 3.0f,
            (v0.z + v1.z + v2.z) / 3.0f
        );
        var toOpposite = new Vector3f(
            oppositeVertex.x - faceCentroid.x,
            oppositeVertex.y - faceCentroid.y,
            oppositeVertex.z - faceCentroid.z
        );

        if (normal.dot(toOpposite) > 0) {
            normal.negate();
        }

        return normal;
    }

    /**
     * Get the vertices of a specific face.
     *
     * @param tetType tetrahedron type (0-5)
     * @param faceIndex face index (0-3)
     * @return array of 3 vertex positions
     */
    public static Point3f[] getFaceVertices(int tetType, int faceIndex) {
        validateType(tetType);
        if (faceIndex < 0 || faceIndex > 3) {
            throw new IllegalArgumentException("Face index must be 0-3");
        }

        var vertices = getVertices(tetType);
        var fv = FACE_VERTICES[faceIndex];
        return new Point3f[] {
            vertices[fv[0]],
            vertices[fv[1]],
            vertices[fv[2]]
        };
    }

    /**
     * Calculate the area of a face.
     *
     * @param tetType tetrahedron type (0-5)
     * @param faceIndex face index (0-3)
     * @param scale scale factor
     * @return face area
     */
    public static float getFaceArea(int tetType, int faceIndex, float scale) {
        var faceVerts = getFaceVertices(tetType, faceIndex);

        var v0 = faceVerts[0];
        var v1 = faceVerts[1];
        var v2 = faceVerts[2];

        var edge1 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        var edge2 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);

        var cross = new Vector3f();
        cross.cross(edge1, edge2);

        return 0.5f * cross.length() * scale * scale;
    }

    /**
     * Calculate the volume of a tetrahedron.
     *
     * @param tetType tetrahedron type (0-5)
     * @param scale scale factor
     * @return volume
     */
    public static float getVolume(int tetType, float scale) {
        validateType(tetType);
        var vertices = getVertices(tetType);

        // Volume = |det(v1-v0, v2-v0, v3-v0)| / 6
        var v0 = vertices[0];
        var a = new Vector3f(vertices[1].x - v0.x, vertices[1].y - v0.y, vertices[1].z - v0.z);
        var b = new Vector3f(vertices[2].x - v0.x, vertices[2].y - v0.y, vertices[2].z - v0.z);
        var c = new Vector3f(vertices[3].x - v0.x, vertices[3].y - v0.y, vertices[3].z - v0.z);

        // Scalar triple product
        var cross = new Vector3f();
        cross.cross(b, c);
        float det = a.dot(cross);

        return Math.abs(det) / 6.0f * scale * scale * scale;
    }

    /**
     * Calculate the inscribed sphere radius of a tetrahedron.
     *
     * @param tetType tetrahedron type (0-5)
     * @param scale scale factor
     * @return inscribed sphere radius
     */
    public static float getInscribedRadius(int tetType, float scale) {
        // r = 3V / A where V = volume, A = total surface area
        float volume = getVolume(tetType, scale);
        float totalArea = 0;
        for (int i = 0; i < 4; i++) {
            totalArea += getFaceArea(tetType, i, scale);
        }
        return 3.0f * volume / totalArea;
    }

    /**
     * Calculate the circumscribed sphere radius of a tetrahedron.
     *
     * @param tetType tetrahedron type (0-5)
     * @param scale scale factor
     * @return circumscribed sphere radius
     */
    public static float getCircumscribedRadius(int tetType, float scale) {
        var vertices = getVertices(tetType);

        // For a regular tetrahedron: R = sqrt(6)/4 * edge_length
        // For irregular tetrahedra, we need to solve for circumcenter

        // Use the formula: R = abc / (4V) where a,b,c are edge products
        // Simplified: find max distance from centroid to vertices
        var centroid = getCentroid(tetType);
        float maxDist = 0;
        for (var v : vertices) {
            float dx = v.x - centroid.x;
            float dy = v.y - centroid.y;
            float dz = v.z - centroid.z;
            float dist = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            maxDist = Math.max(maxDist, dist);
        }

        return maxDist * scale;
    }

    /**
     * Calculate the axis-aligned bounding box of a tetrahedron.
     *
     * @param tetType tetrahedron type (0-5)
     * @param origin origin point
     * @param scale scale factor
     * @return bounding box
     */
    public static TetrahedronBounds getBounds(int tetType, Point3f origin, float scale) {
        var vertices = getVertices(tetType, origin, scale);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (var v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }

        return new TetrahedronBounds(
            new Point3f(minX, minY, minZ),
            new Point3f(maxX, maxY, maxZ)
        );
    }

    /**
     * Get complete geometry information for a tetrahedron.
     *
     * @param tetType tetrahedron type (0-5)
     * @param scale scale factor
     * @return geometry record with all computed values
     */
    public static TetrahedronGeometry getGeometry(int tetType, float scale) {
        return new TetrahedronGeometry(
            getVertices(tetType),
            getCentroid(tetType),
            getVolume(tetType, scale),
            getInscribedRadius(tetType, scale),
            getCircumscribedRadius(tetType, scale)
        );
    }

    /**
     * Get the child type for a given parent type and child index.
     *
     * @param parentType parent tetrahedron type (0-5)
     * @param childIndex Bey subdivision child index (0-7)
     * @return child tetrahedron type (0-5)
     */
    public static int getChildType(int parentType, int childIndex) {
        validateType(parentType);
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        return PARENT_TYPE_TO_CHILD_TYPE[parentType][childIndex];
    }

    /**
     * Calculate Bey child vertices for a given parent type and child index.
     *
     * @param parentType parent tetrahedron type (0-5)
     * @param childIndex child index (0-7)
     * @param scale scale factor for the child (typically parent_scale * 0.5)
     * @return array of 4 child vertex positions
     */
    public static Point3f[] getChildVertices(int parentType, int childIndex, float scale) {
        validateType(parentType);
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }

        var parentVerts = getVertices(parentType);

        // Edge midpoints
        var m01 = midpoint(parentVerts[0], parentVerts[1]);
        var m02 = midpoint(parentVerts[0], parentVerts[2]);
        var m03 = midpoint(parentVerts[0], parentVerts[3]);
        var m12 = midpoint(parentVerts[1], parentVerts[2]);
        var m13 = midpoint(parentVerts[1], parentVerts[3]);
        var m23 = midpoint(parentVerts[2], parentVerts[3]);

        // Bey children
        return switch (childIndex) {
            case 0 -> scaleVertices(new Point3f[] {parentVerts[0], m01, m02, m03}, scale);
            case 1 -> scaleVertices(new Point3f[] {parentVerts[1], m01, m12, m13}, scale);
            case 2 -> scaleVertices(new Point3f[] {parentVerts[2], m02, m12, m23}, scale);
            case 3 -> scaleVertices(new Point3f[] {parentVerts[3], m03, m13, m23}, scale);
            case 4 -> scaleVertices(new Point3f[] {m01, m02, m03, m12}, scale);
            case 5 -> scaleVertices(new Point3f[] {m01, m02, m12, m13}, scale);
            case 6 -> scaleVertices(new Point3f[] {m02, m03, m12, m23}, scale);
            case 7 -> scaleVertices(new Point3f[] {m03, m12, m13, m23}, scale);
            default -> throw new IllegalArgumentException("Invalid child index");
        };
    }

    /**
     * Check if a point is inside a tetrahedron using barycentric coordinates.
     *
     * @param point the point to test
     * @param tetType tetrahedron type (0-5)
     * @param origin origin point
     * @param scale scale factor
     * @return true if point is inside or on the boundary
     */
    public static boolean containsPoint(Point3f point, int tetType, Point3f origin, float scale) {
        var vertices = getVertices(tetType, origin, scale);
        return containsPoint(point, vertices);
    }

    /**
     * Check if a point is inside a tetrahedron using barycentric coordinates.
     *
     * @param point the point to test
     * @param vertices the 4 tetrahedron vertices
     * @return true if point is inside or on the boundary
     */
    public static boolean containsPoint(Point3f point, Point3f[] vertices) {
        // Calculate barycentric coordinates using same-side test
        var v0 = vertices[0];
        var v1 = vertices[1];
        var v2 = vertices[2];
        var v3 = vertices[3];

        // Check if point is on same side of each face as opposite vertex
        return sameSide(point, v0, v1, v2, v3) &&
               sameSide(point, v1, v0, v2, v3) &&
               sameSide(point, v2, v0, v1, v3) &&
               sameSide(point, v3, v0, v1, v2);
    }

    // ========== Private Helpers ==========

    private static void validateType(int tetType) {
        if (tetType < 0 || tetType > 5) {
            throw new IllegalArgumentException("Tetrahedron type must be 0-5, got: " + tetType);
        }
    }

    private static Point3f midpoint(Point3f a, Point3f b) {
        return new Point3f(
            (a.x + b.x) * 0.5f,
            (a.y + b.y) * 0.5f,
            (a.z + b.z) * 0.5f
        );
    }

    private static Point3f[] scaleVertices(Point3f[] vertices, float scale) {
        var result = new Point3f[vertices.length];
        for (int i = 0; i < vertices.length; i++) {
            result[i] = new Point3f(
                vertices[i].x * scale,
                vertices[i].y * scale,
                vertices[i].z * scale
            );
        }
        return result;
    }

    private static boolean sameSide(Point3f p, Point3f opposite, Point3f a, Point3f b, Point3f c) {
        // Calculate normal of plane ABC
        var ab = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
        var ac = new Vector3f(c.x - a.x, c.y - a.y, c.z - a.z);
        var normal = new Vector3f();
        normal.cross(ab, ac);

        // Check if p and opposite are on same side
        var ap = new Vector3f(p.x - a.x, p.y - a.y, p.z - a.z);
        var ao = new Vector3f(opposite.x - a.x, opposite.y - a.y, opposite.z - a.z);

        float dotP = normal.dot(ap);
        float dotO = normal.dot(ao);

        // Same side if signs match (both positive or both negative)
        return dotP * dotO >= -1e-6f;
    }

    private ESVTNodeGeometry() {} // Prevent instantiation
}
