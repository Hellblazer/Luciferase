/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * <p>
 * This file is part of the Luciferase project.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Ray3D;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.math.BigInteger;

/**
 * Enhanced tetrahedral geometry utilities with optimizations for ray-tetrahedron intersection. This class provides
 * cached tetrahedron vertices and additional optimization paths.
 *
 * @author hal.hildebrand
 */
public class EnhancedTetrahedralGeometry extends TetrahedralGeometry {

    // Cache for frequently accessed tetrahedron vertices
    private static final float       EPSILON        = 1e-6f;
    private static final int         CACHE_SIZE     = 1024;
    private static final long[]      cachedIndices  = new long[CACHE_SIZE];
    private static final Point3f[][] cachedVertices = new Point3f[CACHE_SIZE][4];
    private static final Object[]    cacheLocks     = new Object[CACHE_SIZE];

    static {
        // Initialize cache locks and arrays
        for (int i = 0; i < CACHE_SIZE; i++) {
            cacheLocks[i] = new Object();
            cachedIndices[i] = -1;
            cachedVertices[i] = new Point3f[] { new Point3f(), new Point3f(), new Point3f(), new Point3f() };
        }
    }

    /**
     * Batch ray-tetrahedron intersection test for multiple rays against the same tetrahedron. Optimized for testing
     * many rays against a single tetrahedron.
     *
     * @param rays     Array of rays to test
     * @param tetIndex The tetrahedron index
     * @return Array of intersection results
     */
    public static RayTetrahedronIntersection[] batchRayIntersectsTetrahedron(Ray3D[] rays, TetreeKey tetKey) {
        // Get vertices once
        Tet tet = Tet.tetrahedron(tetKey);
        Point3i[] coords = tet.coordinates();

        Point3f v0 = new Point3f(coords[0].x, coords[0].y, coords[0].z);
        Point3f v1 = new Point3f(coords[1].x, coords[1].y, coords[1].z);
        Point3f v2 = new Point3f(coords[2].x, coords[2].y, coords[2].z);
        Point3f v3 = new Point3f(coords[3].x, coords[3].y, coords[3].z);

        // Cache vertices for future use
        cacheVertices(tetKey, v0, v1, v2, v3);

        // Process all rays
        RayTetrahedronIntersection[] results = new RayTetrahedronIntersection[rays.length];
        for (int i = 0; i < rays.length; i++) {
            results[i] = rayIntersectsTetrahedronWithVertices(rays[i], v0, v1, v2, v3);
        }

        return results;
    }

    private static void cacheVertices(TetreeKey tetKey, Point3f v0, Point3f v1, Point3f v2, Point3f v3) {
        int cacheIndex = (int) (tetKey.getTmIndex().longValue() % CACHE_SIZE);
        synchronized (cacheLocks[cacheIndex]) {
            cachedIndices[cacheIndex] = tetKey.getTmIndex().longValue();
            cachedVertices[cacheIndex][0].set(v0);
            cachedVertices[cacheIndex][1].set(v1);
            cachedVertices[cacheIndex][2].set(v2);
            cachedVertices[cacheIndex][3].set(v3);
        }
    }

    private static Point3f[] getCachedVertices(TetreeKey tetKey) {
        int cacheIndex = (int) (tetKey.getTmIndex().longValue() % CACHE_SIZE);
        synchronized (cacheLocks[cacheIndex]) {
            if (cachedIndices[cacheIndex] == tetKey.getTmIndex().longValue()) {
                return cachedVertices[cacheIndex];
            }
        }
        return null;
    }

    /**
     * Get precise bounding sphere for tetrahedron. Useful for early rejection tests.
     *
     * @param tetIndex The tetrahedron index
     * @return Array containing [centerX, centerY, centerZ, radius]
     */
    public static float[] getTetrahedronBoundingSphere(TetreeKey tetKey) {
        Tet tet = Tet.tetrahedron(tetKey);
        Point3i[] coords = tet.coordinates();

        // Calculate centroid
        float centerX = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4.0f;
        float centerY = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4.0f;
        float centerZ = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4.0f;

        // Find radius as max distance from center to any vertex
        float maxDistSq = 0;
        for (Point3i coord : coords) {
            float dx = coord.x - centerX;
            float dy = coord.y - centerY;
            float dz = coord.z - centerZ;
            float distSq = dx * dx + dy * dy + dz * dz;
            maxDistSq = Math.max(maxDistSq, distSq);
        }

        return new float[] { centerX, centerY, centerZ, (float) Math.sqrt(maxDistSq) };
    }

    /**
     * Check if a point is inside a tetrahedron using barycentric coordinates.
     */
    private static boolean isPointInTetrahedronByVertices(Point3f p, Point3f v0, Point3f v1, Point3f v2, Point3f v3) {
        // Use barycentric coordinate test
        Vector3f vp0 = new Vector3f(p.x - v0.x, p.y - v0.y, p.z - v0.z);
        Vector3f v10 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3f v20 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);
        Vector3f v30 = new Vector3f(v3.x - v0.x, v3.y - v0.y, v3.z - v0.z);

        // Solve for barycentric coordinates
        float det = v10.x * (v20.y * v30.z - v20.z * v30.y) - v10.y * (v20.x * v30.z - v20.z * v30.x) + v10.z * (
        v20.x * v30.y - v20.y * v30.x);

        if (Math.abs(det) < EPSILON) {
            return false; // Degenerate tetrahedron
        }

        float invDet = 1.0f / det;

        // Compute barycentric coordinates
        float b1 = invDet * (vp0.x * (v20.y * v30.z - v20.z * v30.y) - vp0.y * (v20.x * v30.z - v20.z * v30.x)
                             + vp0.z * (v20.x * v30.y - v20.y * v30.x));

        float b2 = invDet * (v10.x * (vp0.y * v30.z - vp0.z * v30.y) - v10.y * (vp0.x * v30.z - vp0.z * v30.x)
                             + v10.z * (vp0.x * v30.y - vp0.y * v30.x));

        float b3 = invDet * (v10.x * (v20.y * vp0.z - v20.z * vp0.y) - v10.y * (v20.x * vp0.z - v20.z * vp0.x)
                             + v10.z * (v20.x * vp0.y - v20.y * vp0.x));

        float b0 = 1.0f - b1 - b2 - b3;

        // Point is inside if all barycentric coordinates are non-negative
        return b0 >= -EPSILON && b1 >= -EPSILON && b2 >= -EPSILON && b3 >= -EPSILON;
    }

    private static boolean rayIntersectsFaceFast(Ray3D ray, Point3f v0, Point3f v1, Point3f v2) {
        Vector3f edge1 = new Vector3f();
        Vector3f edge2 = new Vector3f();
        Vector3f h = new Vector3f();
        Vector3f s = new Vector3f();
        Vector3f q = new Vector3f();

        edge1.sub(v1, v0);
        edge2.sub(v2, v0);

        h.cross(ray.direction(), edge2);
        float a = edge1.dot(h);

        if (a > -EPSILON && a < EPSILON) {
            return false;
        }

        float f = 1.0f / a;
        s.sub(ray.origin(), v0);
        float u = f * s.dot(h);

        if (u < 0.0f || u > 1.0f) {
            return false;
        }

        q.cross(s, edge1);
        float v = f * ray.direction().dot(q);

        if (v < 0.0f || u + v > 1.0f) {
            return false;
        }

        float t = f * edge2.dot(q);
        return t > EPSILON;
    }

    /**
     * Fast ray-sphere intersection test for early rejection.
     *
     * @param ray    The ray to test
     * @param sphere Bounding sphere [centerX, centerY, centerZ, radius]
     * @return true if ray might intersect sphere, false if definitely no intersection
     */
    public static boolean rayIntersectsSphere(Ray3D ray, float[] sphere) {
        float dx = ray.origin().x - sphere[0];
        float dy = ray.origin().y - sphere[1];
        float dz = ray.origin().z - sphere[2];

        float a = ray.direction().dot(ray.direction());
        float b = 2.0f * (ray.direction().x * dx + ray.direction().y * dy + ray.direction().z * dz);
        float c = dx * dx + dy * dy + dz * dz - sphere[3] * sphere[3];

        float discriminant = b * b - 4 * a * c;
        return discriminant >= 0;
    }

    /**
     * Enhanced ray-tetrahedron intersection with vertex caching.
     *
     * @param ray      The ray to test
     * @param tetIndex The tetrahedron index
     * @return Intersection result with detailed information
     */
    public static RayTetrahedronIntersection rayIntersectsTetrahedronCached(Ray3D ray, TetreeKey tetKey) {
        // Get cached vertices if available
        Point3f[] vertices = getCachedVertices(tetKey);
        if (vertices == null) {
            // Fall back to regular method if not in cache
            return rayIntersectsTetrahedron(ray, tetKey);
        }

        // Create a custom implementation for cached vertices
        return rayIntersectsTetrahedronWithVertices(ray, vertices[0], vertices[1], vertices[2], vertices[3]);
    }

    // Private helper methods

    /**
     * Fast ray-tetrahedron intersection test (boolean only, no intersection details). Optimized for cases where we only
     * need to know if intersection occurs.
     *
     * @param ray      The ray to test
     * @param tetIndex The tetrahedron index
     * @return true if ray intersects tetrahedron, false otherwise
     */
    public static boolean rayIntersectsTetrahedronFast(Ray3D ray, TetreeKey tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        Point3i[] coords = tet.coordinates();

        // Convert to float coordinates
        Point3f v0 = new Point3f(coords[0].x, coords[0].y, coords[0].z);
        Point3f v1 = new Point3f(coords[1].x, coords[1].y, coords[1].z);
        Point3f v2 = new Point3f(coords[2].x, coords[2].y, coords[2].z);
        Point3f v3 = new Point3f(coords[3].x, coords[3].y, coords[3].z);

        // Fast test - check if ray origin is inside first
        if (ray.origin().x >= 0 && ray.origin().y >= 0 && ray.origin().z >= 0) {
            if (TetrahedralSearchBase.pointInTetrahedron(ray.origin(), tetIndex)) {
                return true;
            }
        }

        // Test each face using simplified Möller-Trumbore (no intersection point calculation)
        return rayIntersectsFaceFast(ray, v0, v1, v2) || rayIntersectsFaceFast(ray, v0, v1, v3)
        || rayIntersectsFaceFast(ray, v0, v2, v3) || rayIntersectsFaceFast(ray, v1, v2, v3);
    }

    /**
     * Ray-tetrahedron intersection test with pre-computed vertices.
     *
     * @param ray The ray to test
     * @param v0  First vertex of tetrahedron
     * @param v1  Second vertex of tetrahedron
     * @param v2  Third vertex of tetrahedron
     * @param v3  Fourth vertex of tetrahedron
     * @return Intersection result
     */
    private static RayTetrahedronIntersection rayIntersectsTetrahedronWithVertices(Ray3D ray, Point3f v0, Point3f v1,
                                                                                   Point3f v2, Point3f v3) {

        // Check if ray origin is inside the tetrahedron
        boolean rayStartsInside = false;
        if (ray.origin().x >= 0 && ray.origin().y >= 0 && ray.origin().z >= 0) {
            // Use barycentric coordinates to check if point is inside
            rayStartsInside = isPointInTetrahedronByVertices(ray.origin(), v0, v1, v2, v3);
        }

        float closestDistance = Float.MAX_VALUE;
        Point3f closestIntersection = null;
        Vector3f closestNormal = null;
        int closestFace = -1;

        // Test intersection with each tetrahedral face
        // Face 0: v0, v1, v2
        var result = rayTriangleIntersection(ray, v0, v1, v2);
        if (result.intersects && result.distance < closestDistance) {
            closestDistance = result.distance;
            closestIntersection = result.intersectionPoint;
            closestNormal = result.normal;
            closestFace = 0;
        }

        // Face 1: v0, v1, v3
        result = rayTriangleIntersection(ray, v0, v1, v3);
        if (result.intersects && result.distance < closestDistance) {
            closestDistance = result.distance;
            closestIntersection = result.intersectionPoint;
            closestNormal = result.normal;
            closestFace = 1;
        }

        // Face 2: v0, v2, v3
        result = rayTriangleIntersection(ray, v0, v2, v3);
        if (result.intersects && result.distance < closestDistance) {
            closestDistance = result.distance;
            closestIntersection = result.intersectionPoint;
            closestNormal = result.normal;
            closestFace = 2;
        }

        // Face 3: v1, v2, v3
        result = rayTriangleIntersection(ray, v1, v2, v3);
        if (result.intersects && result.distance < closestDistance) {
            closestDistance = result.distance;
            closestIntersection = result.intersectionPoint;
            closestNormal = result.normal;
            closestFace = 3;
        }

        // If ray starts inside the tetrahedron, we have an intersection
        if (rayStartsInside) {
            if (closestIntersection != null) {
                // Ray exits the tetrahedron
                return new RayTetrahedronIntersection(true, closestDistance, closestIntersection, closestNormal,
                                                      closestFace);
            } else {
                // Ray doesn't exit within maxDistance, but still intersects
                return new RayTetrahedronIntersection(true, 0.0f, ray.origin(), null, -1);
            }
        }

        // Ray starts outside - only intersects if we found an entry point
        if (closestIntersection != null) {
            return new RayTetrahedronIntersection(true, closestDistance, closestIntersection, closestNormal,
                                                  closestFace);
        } else {
            return RayTetrahedronIntersection.noIntersection();
        }
    }

    /**
     * Ray-triangle intersection using Möller-Trumbore algorithm.
     */
    private static RayTetrahedronIntersection rayTriangleIntersection(Ray3D ray, Point3f v0, Point3f v1, Point3f v2) {
        Vector3f edge1 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3f edge2 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);

        Vector3f h = new Vector3f();
        h.cross(ray.direction(), edge2);

        float a = edge1.dot(h);
        if (a > -EPSILON && a < EPSILON) {
            return RayTetrahedronIntersection.noIntersection(); // Ray is parallel to triangle
        }

        float f = 1.0f / a;
        Vector3f s = new Vector3f(ray.origin().x - v0.x, ray.origin().y - v0.y, ray.origin().z - v0.z);
        float u = f * s.dot(h);

        if (u < 0.0f || u > 1.0f) {
            return RayTetrahedronIntersection.noIntersection();
        }

        Vector3f q = new Vector3f();
        q.cross(s, edge1);
        float v = f * ray.direction().dot(q);

        if (v < 0.0f || u + v > 1.0f) {
            return RayTetrahedronIntersection.noIntersection();
        }

        float t = f * edge2.dot(q);

        if (t > EPSILON && t <= ray.maxDistance()) {
            Point3f intersection = ray.pointAt(t);

            // Compute face normal
            Vector3f normal = new Vector3f();
            normal.cross(edge1, edge2);
            normal.normalize();

            return new RayTetrahedronIntersection(true, t, intersection, normal, -1);
        }

        return RayTetrahedronIntersection.noIntersection();
    }
}
