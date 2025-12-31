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

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Möller-Trumbore ray-triangle and ray-tetrahedron intersection.
 *
 * <p>Based on H1 validation (2025-12-27): Plücker coordinates were invalidated for
 * S0-S5 tetrahedral intersection. Möller-Trumbore is used instead.
 *
 * <p>For tetrahedra, we test all 4 triangular faces and find the first (entry)
 * and last (exit) intersections.
 *
 * <p><b>Face Convention:</b>
 * <ul>
 *   <li>Face 0: opposite vertex 0 (triangle v1, v2, v3)</li>
 *   <li>Face 1: opposite vertex 1 (triangle v0, v2, v3)</li>
 *   <li>Face 2: opposite vertex 2 (triangle v0, v1, v3)</li>
 *   <li>Face 3: opposite vertex 3 (triangle v0, v1, v2)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class MollerTrumboreIntersection {

    /** Small epsilon for floating-point comparisons */
    private static final float EPSILON = 1e-7f;

    /**
     * Result of a single triangle intersection test.
     */
    public static final class TriangleResult {
        public boolean hit;
        public float t;
        public float u;
        public float v;

        public TriangleResult() {
            reset();
        }

        public void reset() {
            hit = false;
            t = Float.MAX_VALUE;
            u = 0;
            v = 0;
        }
    }

    /**
     * Result of tetrahedron intersection test.
     */
    public static final class TetrahedronResult {
        public boolean hit;
        public float tEntry;         // Entry t-parameter
        public float tExit;          // Exit t-parameter
        public int entryFace;        // Face ray enters through (0-3)
        public int exitFace;         // Face ray exits through (0-3)
        public float entryU, entryV; // Barycentric coords at entry

        public TetrahedronResult() {
            reset();
        }

        public void reset() {
            hit = false;
            tEntry = Float.MAX_VALUE;
            tExit = -Float.MAX_VALUE;
            entryFace = -1;
            exitFace = -1;
            entryU = 0;
            entryV = 0;
        }
    }

    // Reusable scratch vectors to avoid allocation
    private final Vector3f edge1 = new Vector3f();
    private final Vector3f edge2 = new Vector3f();
    private final Vector3f h = new Vector3f();
    private final Vector3f s = new Vector3f();
    private final Vector3f q = new Vector3f();

    private MollerTrumboreIntersection() {
        // Utility class - use static methods or create instance for thread-local use
    }

    /**
     * Create a new intersection tester (thread-local scratch space).
     */
    public static MollerTrumboreIntersection create() {
        return new MollerTrumboreIntersection();
    }

    /**
     * Test ray-triangle intersection using Möller-Trumbore algorithm.
     *
     * @param rayOrigin Ray origin point
     * @param rayDir Ray direction (should be normalized)
     * @param v0 Triangle vertex 0
     * @param v1 Triangle vertex 1
     * @param v2 Triangle vertex 2
     * @param result Result object to fill
     * @return true if ray intersects triangle
     */
    public boolean intersectTriangle(Point3f rayOrigin, Vector3f rayDir,
                                     Point3f v0, Point3f v1, Point3f v2,
                                     TriangleResult result) {
        result.reset();

        // Edge vectors
        edge1.set(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        edge2.set(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);

        // h = rayDir × edge2
        h.cross(rayDir, edge2);

        // a = edge1 · h (determinant)
        float a = edge1.dot(h);

        // If determinant is near zero, ray is parallel to triangle
        if (a > -EPSILON && a < EPSILON) {
            return false;
        }

        float f = 1.0f / a;

        // s = rayOrigin - v0
        s.set(rayOrigin.x - v0.x, rayOrigin.y - v0.y, rayOrigin.z - v0.z);

        // u = f * (s · h)
        float u = f * s.dot(h);
        if (u < 0.0f || u > 1.0f) {
            return false;
        }

        // q = s × edge1
        q.cross(s, edge1);

        // v = f * (rayDir · q)
        float v = f * rayDir.dot(q);
        if (v < 0.0f || u + v > 1.0f) {
            return false;
        }

        // t = f * (edge2 · q)
        float t = f * edge2.dot(q);

        // Ray intersection (t > epsilon means intersection in front of ray origin)
        if (t > EPSILON) {
            result.hit = true;
            result.t = t;
            result.u = u;
            result.v = v;
            return true;
        }

        return false;
    }

    /**
     * Test ray-tetrahedron intersection by testing all 4 faces.
     *
     * @param rayOrigin Ray origin point
     * @param rayDir Ray direction (should be normalized)
     * @param v0 Tetrahedron vertex 0
     * @param v1 Tetrahedron vertex 1
     * @param v2 Tetrahedron vertex 2
     * @param v3 Tetrahedron vertex 3
     * @param result Result object to fill
     * @return true if ray intersects tetrahedron
     */
    public boolean intersectTetrahedron(Point3f rayOrigin, Vector3f rayDir,
                                        Point3f v0, Point3f v1, Point3f v2, Point3f v3,
                                        TetrahedronResult result) {
        result.reset();

        var triResult = new TriangleResult();
        float minT = Float.MAX_VALUE;
        float maxT = -Float.MAX_VALUE;
        int entryFace = -1;
        int exitFace = -1;
        float entryU = 0, entryV = 0;

        // Face 0: opposite v0, triangle (v1, v2, v3)
        if (intersectTriangle(rayOrigin, rayDir, v1, v2, v3, triResult)) {
            if (triResult.t < minT) {
                minT = triResult.t;
                entryFace = 0;
                entryU = triResult.u;
                entryV = triResult.v;
            }
            if (triResult.t > maxT) {
                maxT = triResult.t;
                exitFace = 0;
            }
        }

        // Face 1: opposite v1, triangle (v0, v2, v3)
        if (intersectTriangle(rayOrigin, rayDir, v0, v2, v3, triResult)) {
            if (triResult.t < minT) {
                minT = triResult.t;
                entryFace = 1;
                entryU = triResult.u;
                entryV = triResult.v;
            }
            if (triResult.t > maxT) {
                maxT = triResult.t;
                exitFace = 1;
            }
        }

        // Face 2: opposite v2, triangle (v0, v1, v3)
        if (intersectTriangle(rayOrigin, rayDir, v0, v1, v3, triResult)) {
            if (triResult.t < minT) {
                minT = triResult.t;
                entryFace = 2;
                entryU = triResult.u;
                entryV = triResult.v;
            }
            if (triResult.t > maxT) {
                maxT = triResult.t;
                exitFace = 2;
            }
        }

        // Face 3: opposite v3, triangle (v0, v1, v2)
        if (intersectTriangle(rayOrigin, rayDir, v0, v1, v2, triResult)) {
            if (triResult.t < minT) {
                minT = triResult.t;
                entryFace = 3;
                entryU = triResult.u;
                entryV = triResult.v;
            }
            if (triResult.t > maxT) {
                maxT = triResult.t;
                exitFace = 3;
            }
        }

        // Check for valid intersection (ray must enter and exit)
        if (entryFace >= 0 && exitFace >= 0 && minT < maxT) {
            result.hit = true;
            result.tEntry = minT;
            result.tExit = maxT;
            result.entryFace = entryFace;
            result.exitFace = exitFace;
            result.entryU = entryU;
            result.entryV = entryV;
            return true;
        }

        // Special case: ray origin inside tetrahedron
        // Only exit intersection, no entry
        if (entryFace < 0 && exitFace >= 0 && maxT > 0) {
            result.hit = true;
            result.tEntry = 0; // Ray starts inside
            result.tExit = maxT;
            result.entryFace = -1; // No entry face (inside)
            result.exitFace = exitFace;
            return true;
        }

        return false;
    }

    /**
     * Test ray-tetrahedron intersection using float arrays for vertices.
     *
     * @param rayOrigin Ray origin point
     * @param rayDir Ray direction
     * @param vertices Array of 4 vertices, each as [x, y, z]
     * @param result Result object to fill
     * @return true if ray intersects tetrahedron
     */
    public boolean intersectTetrahedron(Point3f rayOrigin, Vector3f rayDir,
                                        float[][] vertices,
                                        TetrahedronResult result) {
        return intersectTetrahedron(rayOrigin, rayDir,
            new Point3f(vertices[0][0], vertices[0][1], vertices[0][2]),
            new Point3f(vertices[1][0], vertices[1][1], vertices[1][2]),
            new Point3f(vertices[2][0], vertices[2][1], vertices[2][2]),
            new Point3f(vertices[3][0], vertices[3][1], vertices[3][2]),
            result);
    }

    /**
     * Static convenience method for single intersection test.
     */
    public static boolean testTriangle(Point3f rayOrigin, Vector3f rayDir,
                                       Point3f v0, Point3f v1, Point3f v2,
                                       TriangleResult result) {
        return create().intersectTriangle(rayOrigin, rayDir, v0, v1, v2, result);
    }

    /**
     * Static convenience method for tetrahedron intersection test.
     */
    public static boolean testTetrahedron(Point3f rayOrigin, Vector3f rayDir,
                                          Point3f v0, Point3f v1, Point3f v2, Point3f v3,
                                          TetrahedronResult result) {
        return create().intersectTetrahedron(rayOrigin, rayDir, v0, v1, v2, v3, result);
    }

    // ============================================================================
    // RAY-AABB (UNIT CUBE) INTERSECTION
    // ============================================================================
    //
    // Used for root-level intersection because the Tetree uses CUBIC octant
    // subdivision internally, not geometric Bey tetrahedron subdivision. The root
    // "tetrahedron type" describes orientation for surface normals, but the spatial
    // subdivision covers the FULL [0,1]^3 cube, not just 1/6 of it.

    /**
     * Result of ray-AABB intersection test.
     */
    public static final class AABBResult {
        public boolean hit;
        public float tEntry;      // Entry t-parameter
        public float tExit;       // Exit t-parameter
        public int entryFace;     // Face ray enters through (0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z)

        public AABBResult() {
            reset();
        }

        public void reset() {
            hit = false;
            tEntry = Float.MAX_VALUE;
            tExit = -Float.MAX_VALUE;
            entryFace = -1;
        }
    }

    /**
     * Test ray-AABB intersection for unit cube [0,1]³.
     *
     * <p>Uses the slab method with proper handling of direction signs.
     * This is used for root intersection in ESVT traversal because the
     * Tetree uses cubic octant subdivision, covering the full [0,1]³ cube.
     *
     * @param rayOrigin Ray origin point
     * @param rayDir Ray direction (should be normalized, non-zero)
     * @param result Result object to fill
     * @return true if ray intersects the unit cube
     */
    public boolean intersectUnitCube(Point3f rayOrigin, Vector3f rayDir, AABBResult result) {
        result.reset();

        // Add epsilon to avoid division by zero
        float dirX = (Math.abs(rayDir.x) < EPSILON) ? (rayDir.x >= 0 ? EPSILON : -EPSILON) : rayDir.x;
        float dirY = (Math.abs(rayDir.y) < EPSILON) ? (rayDir.y >= 0 ? EPSILON : -EPSILON) : rayDir.y;
        float dirZ = (Math.abs(rayDir.z) < EPSILON) ? (rayDir.z >= 0 ? EPSILON : -EPSILON) : rayDir.z;

        // Compute inverse direction
        float invDirX = 1.0f / dirX;
        float invDirY = 1.0f / dirY;
        float invDirZ = 1.0f / dirZ;

        // Compute t values for each slab (unit cube bounds: min=0, max=1)
        float tx1 = (0.0f - rayOrigin.x) * invDirX;
        float tx2 = (1.0f - rayOrigin.x) * invDirX;
        float ty1 = (0.0f - rayOrigin.y) * invDirY;
        float ty2 = (1.0f - rayOrigin.y) * invDirY;
        float tz1 = (0.0f - rayOrigin.z) * invDirZ;
        float tz2 = (1.0f - rayOrigin.z) * invDirZ;

        // Find entry/exit for each axis
        float txMin = Math.min(tx1, tx2);
        float txMax = Math.max(tx1, tx2);
        float tyMin = Math.min(ty1, ty2);
        float tyMax = Math.max(ty1, ty2);
        float tzMin = Math.min(tz1, tz2);
        float tzMax = Math.max(tz1, tz2);

        // Find overall entry/exit and track entry face
        float tEntry = txMin;
        int entryFace = (rayDir.x >= 0) ? 1 : 0;  // 0=+X face, 1=-X face

        if (tyMin > tEntry) {
            tEntry = tyMin;
            entryFace = (rayDir.y >= 0) ? 3 : 2;  // 2=+Y face, 3=-Y face
        }
        if (tzMin > tEntry) {
            tEntry = tzMin;
            entryFace = (rayDir.z >= 0) ? 5 : 4;  // 4=+Z face, 5=-Z face
        }

        float tExit = Math.min(txMax, Math.min(tyMax, tzMax));

        // Check for valid intersection
        boolean hit = (tEntry <= tExit) && (tExit > 0.0f);

        // Handle ray origin inside cube
        if (hit && tEntry < 0.0f) {
            tEntry = 0.0f;
        }

        if (hit) {
            result.hit = true;
            result.tEntry = tEntry;
            result.tExit = tExit;
            result.entryFace = entryFace;
            return true;
        }

        return false;
    }

    /**
     * Static convenience method for unit cube intersection test.
     */
    public static boolean testUnitCube(Point3f rayOrigin, Vector3f rayDir, AABBResult result) {
        return create().intersectUnitCube(rayOrigin, rayDir, result);
    }
}
