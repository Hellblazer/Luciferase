package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;

/**
 * Specialized geometric predicates and operations for tetrahedral spatial structures. Provides precise geometric
 * computations for ray-tetrahedron intersection, frustum-tetrahedron intersection, and other tetrahedral-specific
 * operations.
 *
 * All operations assume positive coordinates as required by the tetrahedral SFC.
 *
 * @author hal.hildebrand
 */
public class TetrahedralGeometry {

    private static final float EPSILON = 1e-6f;

    // Cache for frequently accessed tetrahedron vertices
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
     * @param rays   Array of rays to test
     * @param tetKey The tetrahedron key
     * @return Array of intersection results
     */
    public static RayTetrahedronIntersection[] batchRayIntersectsTetrahedron(Ray3D[] rays,
                                                                             BaseTetreeKey<? extends BaseTetreeKey> tetKey) {
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

    private static void cacheVertices(BaseTetreeKey<? extends BaseTetreeKey> tetKey, Point3f v0, Point3f v1, Point3f v2,
                                      Point3f v3) {
        // Use the low bits for cache indexing
        int cacheIndex = (int) (tetKey.getLowBits() % CACHE_SIZE);
        synchronized (cacheLocks[cacheIndex]) {
            cachedIndices[cacheIndex] = tetKey.getLowBits();
            cachedVertices[cacheIndex][0].set(v0);
            cachedVertices[cacheIndex][1].set(v1);
            cachedVertices[cacheIndex][2].set(v2);
            cachedVertices[cacheIndex][3].set(v3);
        }
    }

    /**
     * Compute distance from a point to a tetrahedron (delegates to TetrahedralSearchBase)
     *
     * @param point    the query point (must have positive coordinates)
     * @param tetIndex the tetrahedral SFC index
     * @return minimum distance to tetrahedron
     */
    public static float distancePointToTetrahedron(Point3f point, TetreeKey tetIndex) {
        return TetrahedralSearchBase.distanceToTetrahedron(point, tetIndex);
    }

    /**
     * Compute minimum distance between two tetrahedra
     *
     * @param tetIndex1 first tetrahedral SFC index
     * @param tetIndex2 second tetrahedral SFC index
     * @return minimum distance between the tetrahedra
     */
    public static float distanceTetrahedronToTetrahedron(TetreeKey tetIndex1, TetreeKey tetIndex2) {
        if (tetIndex1 == tetIndex2) {
            return 0.0f;
        }

        var tet1 = Tet.tetrahedron(tetIndex1);
        var tet2 = Tet.tetrahedron(tetIndex2);
        var vertices1 = tet1.coordinates();
        var vertices2 = tet2.coordinates();

        float minDistance = Float.MAX_VALUE;

        // Check distance from each vertex of tet1 to tet2
        for (Point3i vertex : vertices1) {
            Point3f v = new Point3f(vertex.x, vertex.y, vertex.z);
            float dist = TetrahedralSearchBase.distanceToTetrahedron(v, tetIndex2);
            minDistance = Math.min(minDistance, dist);
        }

        // Check distance from each vertex of tet2 to tet1
        for (Point3i vertex : vertices2) {
            Point3f v = new Point3f(vertex.x, vertex.y, vertex.z);
            float dist = TetrahedralSearchBase.distanceToTetrahedron(v, tetIndex1);
            minDistance = Math.min(minDistance, dist);
        }

        return minDistance;
    }

    /**
     * Test if a frustum intersects with a tetrahedron
     *
     * @param frustum  the camera frustum
     * @param tetIndex the tetrahedral SFC index
     * @return true if frustum intersects tetrahedron
     */
    public static boolean frustumIntersectsTetrahedron(Frustum3D frustum, TetreeKey tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();

        // Test each vertex against all frustum planes
        for (Plane3D plane : frustum.getPlanes()) {
            boolean allVerticesOutside = true;

            for (Point3i vertex : vertices) {
                Point3f v = new Point3f(vertex.x, vertex.y, vertex.z);
                if (plane.distanceToPoint(v) >= -EPSILON) {
                    allVerticesOutside = false;
                    break;
                }
            }

            if (allVerticesOutside) {
                return false; // Tetrahedron is completely outside this plane
            }
        }

        return true; // Tetrahedron intersects or is inside frustum
    }

    private static Point3f[] getCachedVertices(BaseTetreeKey<? extends BaseTetreeKey> tetKey) {
        // Use the low bits for cache indexing
        int cacheIndex = (int) (tetKey.getLowBits() % CACHE_SIZE);
        synchronized (cacheLocks[cacheIndex]) {
            if (cachedIndices[cacheIndex] == tetKey.getLowBits()) {
                return cachedVertices[cacheIndex];
            }
        }
        return null;
    }

    private static Point3i[] getFaceVertices(Point3i[] tetrahedronVertices, int faceIndex) {
        return switch (faceIndex) {
            case 0 -> new Point3i[] { tetrahedronVertices[0], tetrahedronVertices[1], tetrahedronVertices[2] };
            case 1 -> new Point3i[] { tetrahedronVertices[0], tetrahedronVertices[1], tetrahedronVertices[3] };
            case 2 -> new Point3i[] { tetrahedronVertices[0], tetrahedronVertices[2], tetrahedronVertices[3] };
            case 3 -> new Point3i[] { tetrahedronVertices[1], tetrahedronVertices[2], tetrahedronVertices[3] };
            default -> throw new IllegalArgumentException("Face index must be 0-3");
        };
    }

    /**
     * Get precise bounding sphere for tetrahedron. Useful for early rejection tests.
     *
     * @param tetKey The tetrahedron key
     * @return Array containing [centerX, centerY, centerZ, radius]
     */
    public static float[] getTetrahedronBoundingSphere(BaseTetreeKey<? extends BaseTetreeKey> tetKey) {
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

    /**
     * Test if a plane intersects with a tetrahedron
     *
     * @param plane    the plane to test intersection with
     * @param tetIndex the tetrahedral SFC index
     * @return true if plane intersects tetrahedron
     */
    public static boolean planeIntersectsTetrahedron(Plane3D plane, TetreeKey tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();

        boolean hasPositive = false;
        boolean hasNegative = false;

        for (Point3i vertex : vertices) {
            Point3f v = new Point3f(vertex.x, vertex.y, vertex.z);
            float distance = plane.distanceToPoint(v);

            if (distance > EPSILON) {
                hasPositive = true;
            } else if (distance < -EPSILON) {
                hasNegative = true;
            }

            // Early exit if we found vertices on both sides
            if (hasPositive && hasNegative) {
                return true;
            }
        }

        // Plane intersects if vertices are on both sides, or touches if all on plane
        return hasPositive && hasNegative;
    }

    /**
     * 3D Ray representation for ray-tetrahedron intersection tests
     */
    // Ray3D has been moved to com.hellblazer.luciferase.lucien.Ray3D as a unified implementation

    // Helper methods
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
     * Test if a ray intersects with a tetrahedron and return detailed intersection information
     *
     * @param ray      the ray to test (must have positive origin coordinates)
     * @param tetIndex the tetrahedral SFC index
     * @return intersection result with detailed information
     */
    public static RayTetrahedronIntersection rayIntersectsTetrahedron(Ray3D ray,
                                                                      BaseTetreeKey<? extends BaseTetreeKey> tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();

        // First check if ray origin is inside the tetrahedron
        // Only check if ray origin has positive coordinates (tetrahedra only exist in positive space)
        boolean rayStartsInside = false;
        if (ray.origin().x >= 0 && ray.origin().y >= 0 && ray.origin().z >= 0) {
            // Don't use the potentially broken Tet.contains method
            // Instead, convert vertices to float and use proper geometric test
            Point3f[] floatVertices = new Point3f[4];
            for (int i = 0; i < 4; i++) {
                floatVertices[i] = new Point3f(vertices[i].x, vertices[i].y, vertices[i].z);
            }
            rayStartsInside = isPointInTetrahedronByVertices(ray.origin(), 
                floatVertices[0], floatVertices[1], floatVertices[2], floatVertices[3]);
        }

        // If ray starts inside, return distance 0 immediately
        if (rayStartsInside) {
            // Find the exit point if needed
            float closestDistance = Float.MAX_VALUE;
            Point3f closestIntersection = null;
            Vector3f closestNormal = null;
            int closestFace = -1;

            // Test intersection with each tetrahedral face to find exit point
            for (int face = 0; face < 4; face++) {
                Point3i[] faceVertices = getFaceVertices(vertices, face);

                // Test ray-triangle intersection
                var intersection = rayTriangleIntersection(ray, faceVertices);
                if (intersection.intersects && intersection.distance > EPSILON
                && intersection.distance < closestDistance) {
                    closestDistance = intersection.distance;
                    closestIntersection = intersection.intersectionPoint;
                    closestNormal = intersection.normal;
                    closestFace = face;
                }
            }

            // Return with distance 0 since ray starts inside
            return new RayTetrahedronIntersection(true, 0.0f, ray.origin(), closestNormal, closestFace);
        }

        // Ray starts outside - find entry point
        float closestDistance = Float.MAX_VALUE;
        Point3f closestIntersection = null;
        Vector3f closestNormal = null;
        int closestFace = -1;

        // Test intersection with each tetrahedral face
        for (int face = 0; face < 4; face++) {
            Point3i[] faceVertices = getFaceVertices(vertices, face);

            // Test ray-triangle intersection
            var intersection = rayTriangleIntersection(ray, faceVertices);
            if (intersection.intersects && intersection.distance < closestDistance) {
                closestDistance = intersection.distance;
                closestIntersection = intersection.intersectionPoint;
                closestNormal = intersection.normal;
                closestFace = face;
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
     * Enhanced ray-tetrahedron intersection with vertex caching.
     *
     * @param ray    The ray to test
     * @param tetKey The tetrahedron key
     * @return Intersection result with detailed information
     */
    public static RayTetrahedronIntersection rayIntersectsTetrahedronCached(Ray3D ray,
                                                                            BaseTetreeKey<? extends BaseTetreeKey> tetKey) {
        // Get cached vertices if available
        Point3f[] vertices = getCachedVertices(tetKey);
        if (vertices == null) {
            // Fall back to regular method if not in cache
            return rayIntersectsTetrahedron(ray, tetKey);
        }

        // Create a custom implementation for cached vertices
        return rayIntersectsTetrahedronWithVertices(ray, vertices[0], vertices[1], vertices[2], vertices[3]);
    }

    /**
     * Fast ray-tetrahedron intersection test (boolean only, no intersection details). Optimized for cases where we only
     * need to know if intersection occurs.
     *
     * @param ray      The ray to test
     * @param tetIndex The tetrahedron index
     * @return true if ray intersects tetrahedron, false otherwise
     */
    public static boolean rayIntersectsTetrahedronFast(Ray3D ray, BaseTetreeKey<? extends BaseTetreeKey> tetIndex) {
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
        var result = rayTriangleIntersectionFloat(ray, v0, v1, v2);
        if (result.intersects && result.distance < closestDistance) {
            closestDistance = result.distance;
            closestIntersection = result.intersectionPoint;
            closestNormal = result.normal;
            closestFace = 0;
        }

        // Face 1: v0, v1, v3
        result = rayTriangleIntersectionFloat(ray, v0, v1, v3);
        if (result.intersects && result.distance < closestDistance) {
            closestDistance = result.distance;
            closestIntersection = result.intersectionPoint;
            closestNormal = result.normal;
            closestFace = 1;
        }

        // Face 2: v0, v2, v3
        result = rayTriangleIntersectionFloat(ray, v0, v2, v3);
        if (result.intersects && result.distance < closestDistance) {
            closestDistance = result.distance;
            closestIntersection = result.intersectionPoint;
            closestNormal = result.normal;
            closestFace = 2;
        }

        // Face 3: v1, v2, v3
        result = rayTriangleIntersectionFloat(ray, v1, v2, v3);
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

    private static RayTetrahedronIntersection rayTriangleIntersection(Ray3D ray, Point3i[] triangleVertices) {
        // Möller-Trumbore ray-triangle intersection algorithm
        Point3f v0 = new Point3f(triangleVertices[0].x, triangleVertices[0].y, triangleVertices[0].z);
        Point3f v1 = new Point3f(triangleVertices[1].x, triangleVertices[1].y, triangleVertices[1].z);
        Point3f v2 = new Point3f(triangleVertices[2].x, triangleVertices[2].y, triangleVertices[2].z);

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

    /**
     * Ray-triangle intersection using Möller-Trumbore algorithm (float version).
     */
    private static RayTetrahedronIntersection rayTriangleIntersectionFloat(Ray3D ray, Point3f v0, Point3f v1,
                                                                           Point3f v2) {
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

    private static boolean tetrahedraShareSpace(Point3i[] vertices1, Point3i[] vertices2) {
        // Simplified spatial overlap test - could be made more sophisticated
        // Check if bounding boxes overlap

        float min1X = Float.MAX_VALUE, min1Y = Float.MAX_VALUE, min1Z = Float.MAX_VALUE;
        float max1X = Float.MIN_VALUE, max1Y = Float.MIN_VALUE, max1Z = Float.MIN_VALUE;

        for (Point3i v : vertices1) {
            min1X = Math.min(min1X, v.x);
            min1Y = Math.min(min1Y, v.y);
            min1Z = Math.min(min1Z, v.z);
            max1X = Math.max(max1X, v.x);
            max1Y = Math.max(max1Y, v.y);
            max1Z = Math.max(max1Z, v.z);
        }

        float min2X = Float.MAX_VALUE, min2Y = Float.MAX_VALUE, min2Z = Float.MAX_VALUE;
        float max2X = Float.MIN_VALUE, max2Y = Float.MIN_VALUE, max2Z = Float.MIN_VALUE;

        for (Point3i v : vertices2) {
            min2X = Math.min(min2X, v.x);
            min2Y = Math.min(min2Y, v.y);
            min2Z = Math.min(min2Z, v.z);
            max2X = Math.max(max2X, v.x);
            max2Y = Math.max(max2Y, v.y);
            max2Z = Math.max(max2Z, v.z);
        }

        // AABB overlap test
        return !(max1X < min2X || min1X > max2X || max1Y < min2Y || min1Y > max2Y || max1Z < min2Z || min1Z > max2Z);
    }

    /**
     * Test intersection between two tetrahedra
     *
     * @param tetIndex1 first tetrahedral SFC index
     * @param tetIndex2 second tetrahedral SFC index
     * @return type of intersection between the tetrahedra
     */
    public static IntersectionResult tetrahedronIntersection(BaseTetreeKey<? extends BaseTetreeKey> tetIndex1,
                                                             BaseTetreeKey<? extends BaseTetreeKey> tetIndex2) {
        if (tetIndex1 == tetIndex2) {
            return IntersectionResult.IDENTICAL;
        }

        var tet1 = Tet.tetrahedron(tetIndex1);
        var tet2 = Tet.tetrahedron(tetIndex2);
        var vertices1 = tet1.coordinates();
        var vertices2 = tet2.coordinates();

        // Check if any vertex of tet1 is inside tet2
        boolean tet1InTet2 = false;
        for (Point3i vertex : vertices1) {
            Point3f v = new Point3f(vertex.x, vertex.y, vertex.z);
            if (TetrahedralSearchBase.pointInTetrahedron(v, tetIndex2)) {
                tet1InTet2 = true;
                break;
            }
        }

        // Check if any vertex of tet2 is inside tet1
        boolean tet2InTet1 = false;
        for (Point3i vertex : vertices2) {
            Point3f v = new Point3f(vertex.x, vertex.y, vertex.z);
            if (TetrahedralSearchBase.pointInTetrahedron(v, tetIndex1)) {
                tet2InTet1 = true;
                break;
            }
        }

        if (tet1InTet2 && tet2InTet1) {
            return IntersectionResult.PARTIAL_OVERLAP;
        } else if (tet1InTet2 || tet2InTet1) {
            return IntersectionResult.COMPLETE_OVERLAP;
        }

        // Check for edge-face intersections (simplified test)
        if (tetrahedraShareSpace(vertices1, vertices2)) {
            return IntersectionResult.TOUCHING;
        }

        return IntersectionResult.NO_INTERSECTION;
    }

    /**
     * Result of tetrahedron-tetrahedron intersection test
     */
    public enum IntersectionResult {
        NO_INTERSECTION,     // Tetrahedra do not intersect
        TOUCHING,           // Tetrahedra touch at a point or edge
        PARTIAL_OVERLAP,    // Tetrahedra partially overlap
        COMPLETE_OVERLAP,   // One tetrahedron completely contains the other
        IDENTICAL          // Tetrahedra are the same
    }

    /**
     * 3D Plane representation for plane-tetrahedron intersection tests
     */
    public record Plane3D(Point3f point, Vector3f normal) {
        public Plane3D {
            float length = normal.length();
            if (length < EPSILON) {
                throw new IllegalArgumentException("Plane normal must have non-zero length");
            }
            normal.normalize(); // Ensure unit normal
        }

        public float distanceToPoint(Point3f p) {
            return normal.x * (p.x - point.x) + normal.y * (p.y - point.y) + normal.z * (p.z - point.z);
        }
    }
    
    /**
     * Test if a point is inside a tetrahedron using barycentric coordinates.
     * This provides an exact geometric test for point containment.
     *
     * @param point    The point to test
     * @param vertices The tetrahedron vertices (must be 4 points)
     * @return true if the point is inside the tetrahedron
     */
    public static boolean containsPoint(Point3f point, Point3f[] vertices) {
        if (vertices.length != 4) {
            throw new IllegalArgumentException("Tetrahedron must have exactly 4 vertices");
        }
        
        // Compute barycentric coordinates
        // For a tetrahedron with vertices v0, v1, v2, v3 and point p,
        // p = u*v0 + v*v1 + w*v2 + t*v3 where u+v+w+t = 1
        // Point is inside if all coordinates are in [0,1]
        
        Point3f v0 = vertices[0];
        Point3f v1 = vertices[1];
        Point3f v2 = vertices[2];
        Point3f v3 = vertices[3];
        
        // Build the matrix for the barycentric coordinate system
        // [v1-v0, v2-v0, v3-v0] * [v, w, t]^T = p-v0
        // u = 1 - v - w - t
        
        Vector3f v10 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3f v20 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);
        Vector3f v30 = new Vector3f(v3.x - v0.x, v3.y - v0.y, v3.z - v0.z);
        Vector3f vp0 = new Vector3f(point.x - v0.x, point.y - v0.y, point.z - v0.z);
        
        // Compute the determinant of the matrix [v10, v20, v30]
        float det = v10.x * (v20.y * v30.z - v20.z * v30.y) - 
                   v10.y * (v20.x * v30.z - v20.z * v30.x) + 
                   v10.z * (v20.x * v30.y - v20.y * v30.x);
        
        if (Math.abs(det) < EPSILON) {
            // Degenerate tetrahedron
            return false;
        }
        
        float invDet = 1.0f / det;
        
        // Solve for barycentric coordinates using Cramer's rule
        float v = invDet * (vp0.x * (v20.y * v30.z - v20.z * v30.y) - 
                           vp0.y * (v20.x * v30.z - v20.z * v30.x) + 
                           vp0.z * (v20.x * v30.y - v20.y * v30.x));
        
        float w = invDet * (v10.x * (vp0.y * v30.z - vp0.z * v30.y) - 
                           v10.y * (vp0.x * v30.z - vp0.z * v30.x) + 
                           v10.z * (vp0.x * v30.y - vp0.y * v30.x));
        
        float t = invDet * (v10.x * (v20.y * vp0.z - v20.z * vp0.y) - 
                           v10.y * (v20.x * vp0.z - v20.z * vp0.x) + 
                           v10.z * (v20.x * vp0.y - v20.y * vp0.x));
        
        float u = 1.0f - v - w - t;
        
        // Point is inside if all barycentric coordinates are in [0,1]
        return u >= -EPSILON && u <= 1.0f + EPSILON &&
               v >= -EPSILON && v <= 1.0f + EPSILON &&
               w >= -EPSILON && w <= 1.0f + EPSILON &&
               t >= -EPSILON && t <= 1.0f + EPSILON;
    }
    
    /**
     * Test if two tetrahedra intersect each other.
     * Returns true if the tetrahedra share any volume.
     *
     * @param vertices1 First tetrahedron vertices (must be 4 points)
     * @param vertices2 Second tetrahedron vertices (must be 4 points)
     * @return true if the tetrahedra intersect
     */
    public static boolean tetrahedraIntersect(Point3f[] vertices1, Point3f[] vertices2) {
        if (vertices1.length != 4 || vertices2.length != 4) {
            throw new IllegalArgumentException("Both tetrahedra must have exactly 4 vertices");
        }
        
        // First check if any vertex of one tetrahedron is inside the other
        for (Point3f v : vertices1) {
            if (containsPoint(v, vertices2)) {
                return true;
            }
        }
        
        for (Point3f v : vertices2) {
            if (containsPoint(v, vertices1)) {
                return true;
            }
        }
        
        // Check for edge-face intersections using SAT
        // This is a simplified version - a complete implementation would check
        // all 15 potential separating axes (4 face normals from each tet + 
        // 6 cross products between edges)
        
        // For now, check if edges of one tet intersect faces of the other
        return checkEdgeFaceIntersections(vertices1, vertices2) || 
               checkEdgeFaceIntersections(vertices2, vertices1);
    }
    
    /**
     * Helper method to check if edges of tet1 intersect faces of tet2
     */
    private static boolean checkEdgeFaceIntersections(Point3f[] tet1, Point3f[] tet2) {
        // Define the 6 edges of a tetrahedron
        int[][] edges = {{0,1}, {0,2}, {0,3}, {1,2}, {1,3}, {2,3}};
        
        // Define the 4 faces of a tetrahedron
        int[][] faces = {{0,1,2}, {0,1,3}, {0,2,3}, {1,2,3}};
        
        // Check each edge of tet1 against each face of tet2
        for (int[] edge : edges) {
            Point3f p1 = tet1[edge[0]];
            Point3f p2 = tet1[edge[1]];
            
            for (int[] face : faces) {
                Point3f f0 = tet2[face[0]];
                Point3f f1 = tet2[face[1]];
                Point3f f2 = tet2[face[2]];
                
                if (segmentIntersectsTriangle(p1, p2, f0, f1, f2)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Test if a line segment intersects a triangle
     */
    private static boolean segmentIntersectsTriangle(Point3f p1, Point3f p2, 
                                                    Point3f t0, Point3f t1, Point3f t2) {
        // Use ray-triangle intersection with segment bounds check
        Vector3f dir = new Vector3f(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z);
        float segmentLength = dir.length();
        if (segmentLength < EPSILON) return false;
        
        dir.scale(1.0f / segmentLength);
        Ray3D ray = new Ray3D(p1, dir);
        
        // Möller–Trumbore intersection algorithm
        Vector3f edge1 = new Vector3f(t1.x - t0.x, t1.y - t0.y, t1.z - t0.z);
        Vector3f edge2 = new Vector3f(t2.x - t0.x, t2.y - t0.y, t2.z - t0.z);
        Vector3f h = new Vector3f();
        h.cross(dir, edge2);
        float a = edge1.dot(h);
        
        if (Math.abs(a) < EPSILON) return false;
        
        float f = 1.0f / a;
        Vector3f s = new Vector3f(p1.x - t0.x, p1.y - t0.y, p1.z - t0.z);
        float u = f * s.dot(h);
        
        if (u < 0.0f || u > 1.0f) return false;
        
        Vector3f q = new Vector3f();
        q.cross(s, edge1);
        float v = f * dir.dot(q);
        
        if (v < 0.0f || u + v > 1.0f) return false;
        
        float t = f * edge2.dot(q);
        return t >= EPSILON && t <= segmentLength;
    }
    
    /**
     * Test if an axis-aligned bounding box intersects a tetrahedron.
     * Uses the Separating Axis Theorem (SAT) for accurate intersection testing.
     *
     * @param bounds   The entity bounds (AABB)
     * @param vertices The tetrahedron vertices (must be 4 points)
     * @return true if the AABB intersects the tetrahedron
     */
    public static boolean aabbIntersectsTetrahedron(EntityBounds bounds, Point3f[] vertices) {
        if (vertices.length != 4) {
            throw new IllegalArgumentException("Tetrahedron must have exactly 4 vertices");
        }
        
        // First do a quick AABB vs AABB test
        float tetMinX = Float.MAX_VALUE, tetMinY = Float.MAX_VALUE, tetMinZ = Float.MAX_VALUE;
        float tetMaxX = Float.MIN_VALUE, tetMaxY = Float.MIN_VALUE, tetMaxZ = Float.MIN_VALUE;
        
        for (Point3f v : vertices) {
            tetMinX = Math.min(tetMinX, v.x);
            tetMinY = Math.min(tetMinY, v.y);
            tetMinZ = Math.min(tetMinZ, v.z);
            tetMaxX = Math.max(tetMaxX, v.x);
            tetMaxY = Math.max(tetMaxY, v.y);
            tetMaxZ = Math.max(tetMaxZ, v.z);
        }
        
        // Quick rejection test
        if (bounds.getMaxX() < tetMinX || bounds.getMinX() > tetMaxX ||
            bounds.getMaxY() < tetMinY || bounds.getMinY() > tetMaxY ||
            bounds.getMaxZ() < tetMinZ || bounds.getMinZ() > tetMaxZ) {
            return false;
        }
        
        // If AABB contains all tetrahedron vertices, they intersect
        boolean allInside = true;
        for (Point3f v : vertices) {
            if (v.x < bounds.getMinX() || v.x > bounds.getMaxX() ||
                v.y < bounds.getMinY() || v.y > bounds.getMaxY() ||
                v.z < bounds.getMinZ() || v.z > bounds.getMaxZ()) {
                allInside = false;
                break;
            }
        }
        if (allInside) return true;
        
        // Check if any AABB corner is inside the tetrahedron
        Point3f[] corners = new Point3f[8];
        corners[0] = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
        corners[1] = new Point3f(bounds.getMaxX(), bounds.getMinY(), bounds.getMinZ());
        corners[2] = new Point3f(bounds.getMinX(), bounds.getMaxY(), bounds.getMinZ());
        corners[3] = new Point3f(bounds.getMaxX(), bounds.getMaxY(), bounds.getMinZ());
        corners[4] = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMaxZ());
        corners[5] = new Point3f(bounds.getMaxX(), bounds.getMinY(), bounds.getMaxZ());
        corners[6] = new Point3f(bounds.getMinX(), bounds.getMaxY(), bounds.getMaxZ());
        corners[7] = new Point3f(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());
        
        for (Point3f corner : corners) {
            if (pointInTetrahedronForAABB(corner, vertices)) {
                return true;
            }
        }
        
        // Use SAT for edge-face intersections
        // Test AABB edges against tetrahedron faces
        return satTestAABBTetrahedron(bounds, vertices);
    }
    
    /**
     * Test if a point is inside a tetrahedron using barycentric coordinates (for AABB test)
     */
    private static boolean pointInTetrahedronForAABB(Point3f p, Point3f[] vertices) {
        // Use the same orientation test as Tet.containsUltraFast
        Point3f v0 = vertices[0];
        Point3f v1 = vertices[1];
        Point3f v2 = vertices[2];
        Point3f v3 = vertices[3];
        
        // Check if point is on the correct side of all four faces
        return sameSide(p, v0, v1, v2, v3) &&
               sameSide(p, v1, v0, v2, v3) &&
               sameSide(p, v2, v0, v1, v3) &&
               sameSide(p, v3, v0, v1, v2);
    }
    
    /**
     * Check if two points are on the same side of a plane defined by three points
     */
    private static boolean sameSide(Point3f p1, Point3f p2, Point3f a, Point3f b, Point3f c) {
        Vector3f ab = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
        Vector3f ac = new Vector3f(c.x - a.x, c.y - a.y, c.z - a.z);
        Vector3f normal = new Vector3f();
        normal.cross(ab, ac);
        
        Vector3f ap1 = new Vector3f(p1.x - a.x, p1.y - a.y, p1.z - a.z);
        Vector3f ap2 = new Vector3f(p2.x - a.x, p2.y - a.y, p2.z - a.z);
        
        float dot1 = normal.dot(ap1);
        float dot2 = normal.dot(ap2);
        
        return dot1 * dot2 >= 0;
    }
    
    /**
     * Separating Axis Theorem test for AABB-tetrahedron intersection
     */
    private static boolean satTestAABBTetrahedron(EntityBounds bounds, Point3f[] vertices) {
        // For a complete SAT test, we would need to check:
        // 1. The 3 AABB face normals (axis-aligned)
        // 2. The 4 tetrahedron face normals
        // 3. The 9 cross products of AABB edges with tetrahedron edges
        
        // For now, we'll do a simplified test checking tetrahedron faces
        // against AABB edges, which catches most cases
        
        // Check each tetrahedron face
        int[][] faces = {{0, 1, 2}, {0, 1, 3}, {0, 2, 3}, {1, 2, 3}};
        
        for (int[] face : faces) {
            Vector3f v1 = new Vector3f(vertices[face[1]].x - vertices[face[0]].x,
                                     vertices[face[1]].y - vertices[face[0]].y,
                                     vertices[face[1]].z - vertices[face[0]].z);
            Vector3f v2 = new Vector3f(vertices[face[2]].x - vertices[face[0]].x,
                                     vertices[face[2]].y - vertices[face[0]].y,
                                     vertices[face[2]].z - vertices[face[0]].z);
            Vector3f normal = new Vector3f();
            normal.cross(v1, v2);
            normal.normalize();
            
            // Project AABB onto this normal
            float r = Math.abs(normal.x) * (bounds.getMaxX() - bounds.getMinX()) * 0.5f +
                     Math.abs(normal.y) * (bounds.getMaxY() - bounds.getMinY()) * 0.5f +
                     Math.abs(normal.z) * (bounds.getMaxZ() - bounds.getMinZ()) * 0.5f;
            
            Point3f center = new Point3f((bounds.getMinX() + bounds.getMaxX()) * 0.5f,
                                       (bounds.getMinY() + bounds.getMaxY()) * 0.5f,
                                       (bounds.getMinZ() + bounds.getMaxZ()) * 0.5f);
            
            float s = normal.x * (center.x - vertices[face[0]].x) +
                     normal.y * (center.y - vertices[face[0]].y) +
                     normal.z * (center.z - vertices[face[0]].z);
            
            // Check separation
            if (Math.abs(s) > r) {
                // Check if we're on the outside of this face
                boolean outside = true;
                for (int i = 0; i < 4; i++) {
                    if (i == face[0] || i == face[1] || i == face[2]) continue;
                    float d = normal.x * (vertices[i].x - vertices[face[0]].x) +
                             normal.y * (vertices[i].y - vertices[face[0]].y) +
                             normal.z * (vertices[i].z - vertices[face[0]].z);
                    if (s * d > 0) {
                        outside = false;
                        break;
                    }
                }
                if (outside) return false;
            }
        }
        
        return true;
    }

    /**
     * 3D Frustum representation for frustum culling operations
     */
    public static class Frustum3D {
        private final Plane3D[] planes;

        public Frustum3D(Point3f position, Vector3f forward, Vector3f up, Vector3f right, float near, float far,
                         float fovY, float aspect) {
            if (position.x < 0 || position.y < 0 || position.z < 0) {
                throw new IllegalArgumentException("Frustum position must have positive coordinates: " + position);
            }

            this.planes = new Plane3D[6];

            float halfHeight = far * (float) Math.tan(Math.toRadians(fovY / 2.0));
            float halfWidth = halfHeight * aspect;

            Point3f farCenter = new Point3f(position.x + far * forward.x, position.y + far * forward.y,
                                            position.z + far * forward.z);

            // Near and far planes
            planes[0] = new Plane3D(
            new Point3f(position.x + near * forward.x, position.y + near * forward.y, position.z + near * forward.z),
            new Vector3f(forward));
            planes[1] = new Plane3D(farCenter, new Vector3f(-forward.x, -forward.y, -forward.z));

            // Left and right planes
            Vector3f leftNormal = new Vector3f();
            leftNormal.cross(up,
                             new Vector3f(forward.x + right.x * halfWidth / far, forward.y + right.y * halfWidth / far,
                                          forward.z + right.z * halfWidth / far));
            leftNormal.normalize();
            planes[2] = new Plane3D(position, leftNormal);

            Vector3f rightNormal = new Vector3f();
            rightNormal.cross(new Vector3f(forward.x - right.x * halfWidth / far, forward.y - right.y * halfWidth / far,
                                           forward.z - right.z * halfWidth / far), up);
            rightNormal.normalize();
            planes[3] = new Plane3D(position, rightNormal);

            // Top and bottom planes
            Vector3f topNormal = new Vector3f();
            topNormal.cross(right,
                            new Vector3f(forward.x + up.x * halfHeight / far, forward.y + up.y * halfHeight / far,
                                         forward.z + up.z * halfHeight / far));
            topNormal.normalize();
            planes[4] = new Plane3D(position, topNormal);

            Vector3f bottomNormal = new Vector3f();
            bottomNormal.cross(new Vector3f(forward.x - up.x * halfHeight / far, forward.y - up.y * halfHeight / far,
                                            forward.z - up.z * halfHeight / far), right);
            bottomNormal.normalize();
            planes[5] = new Plane3D(position, bottomNormal);
        }

        public Plane3D[] getPlanes() {
            return planes.clone();
        }
    }

    /**
     * Result of ray-tetrahedron intersection test
     */
    public static class RayTetrahedronIntersection {
        public final boolean  intersects;
        public final float    distance;
        public final Point3f  intersectionPoint;
        public final Vector3f normal;
        public final int      intersectedFace; // Which tetrahedral face was hit (0-3)

        public RayTetrahedronIntersection(boolean intersects, float distance, Point3f intersectionPoint,
                                          Vector3f normal, int intersectedFace) {
            this.intersects = intersects;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
            this.normal = normal;
            this.intersectedFace = intersectedFace;
        }

        public static RayTetrahedronIntersection noIntersection() {
            return new RayTetrahedronIntersection(false, Float.MAX_VALUE, null, null, -1);
        }
    }
}
