package com.hellblazer.luciferase.lucien.tetree;

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

    /**
     * Compute distance from a point to a tetrahedron (delegates to TetrahedralSearchBase)
     *
     * @param point    the query point (must have positive coordinates)
     * @param tetIndex the tetrahedral SFC index
     * @return minimum distance to tetrahedron
     */
    public static float distancePointToTetrahedron(Point3f point, long tetIndex) {
        return TetrahedralSearchBase.distanceToTetrahedron(point, tetIndex);
    }

    /**
     * Compute minimum distance between two tetrahedra
     *
     * @param tetIndex1 first tetrahedral SFC index
     * @param tetIndex2 second tetrahedral SFC index
     * @return minimum distance between the tetrahedra
     */
    public static float distanceTetrahedronToTetrahedron(long tetIndex1, long tetIndex2) {
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
    public static boolean frustumIntersectsTetrahedron(Frustum3D frustum, long tetIndex) {
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
     * Test if a plane intersects with a tetrahedron
     *
     * @param plane    the plane to test intersection with
     * @param tetIndex the tetrahedral SFC index
     * @return true if plane intersects tetrahedron
     */
    public static boolean planeIntersectsTetrahedron(Plane3D plane, long tetIndex) {
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
     * Test if a ray intersects with a tetrahedron and return detailed intersection information
     *
     * @param ray      the ray to test (must have positive origin coordinates)
     * @param tetIndex the tetrahedral SFC index
     * @return intersection result with detailed information
     */
    public static RayTetrahedronIntersection rayIntersectsTetrahedron(Ray3D ray, long tetIndex) {
        var tet = Tet.tetrahedron(tetIndex);
        var vertices = tet.coordinates();

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
        h.cross(ray.direction, edge2);

        float a = edge1.dot(h);
        if (a > -EPSILON && a < EPSILON) {
            return RayTetrahedronIntersection.noIntersection(); // Ray is parallel to triangle
        }

        float f = 1.0f / a;
        Vector3f s = new Vector3f(ray.origin.x - v0.x, ray.origin.y - v0.y, ray.origin.z - v0.z);
        float u = f * s.dot(h);

        if (u < 0.0f || u > 1.0f) {
            return RayTetrahedronIntersection.noIntersection();
        }

        Vector3f q = new Vector3f();
        q.cross(s, edge1);
        float v = f * ray.direction.dot(q);

        if (v < 0.0f || u + v > 1.0f) {
            return RayTetrahedronIntersection.noIntersection();
        }

        float t = f * edge2.dot(q);

        if (t > EPSILON && t <= ray.maxDistance) {
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
    public static IntersectionResult tetrahedronIntersection(long tetIndex1, long tetIndex2) {
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
     * 3D Ray representation for ray-tetrahedron intersection tests
     */
    public record Ray3D(Point3f origin, Vector3f direction, float maxDistance) {
        public Ray3D {
            if (origin.x < 0 || origin.y < 0 || origin.z < 0) {
                throw new IllegalArgumentException("Ray origin must have positive coordinates: " + origin);
            }
            if (maxDistance <= 0) {
                throw new IllegalArgumentException("Ray max distance must be positive: " + maxDistance);
            }
        }

        public Point3f pointAt(float t) {
            return new Point3f(origin.x + t * direction.x, origin.y + t * direction.y, origin.z + t * direction.z);
        }
    }

    // Helper methods

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
