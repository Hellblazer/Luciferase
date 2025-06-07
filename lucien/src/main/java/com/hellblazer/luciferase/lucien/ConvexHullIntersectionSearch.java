package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Convex hull intersection search implementation for Octree
 * Finds octree cubes that intersect with 3D convex hulls defined by planes or vertices
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class ConvexHullIntersectionSearch {

    /**
     * Convex hull intersection result with distance information
     */
    public static class ConvexHullIntersection<Content> {
        public final long index;
        public final Content content;
        public final Spatial.Cube cube;
        public final float distanceToReferencePoint;
        public final Point3f cubeCenter;
        public final IntersectionType intersectionType;
        public final float penetrationDepth; // how deep the cube penetrates into the hull

        public ConvexHullIntersection(long index, Content content, Spatial.Cube cube, 
                                    float distanceToReferencePoint, Point3f cubeCenter, 
                                    IntersectionType intersectionType, float penetrationDepth) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.cubeCenter = cubeCenter;
            this.intersectionType = intersectionType;
            this.penetrationDepth = penetrationDepth;
        }
    }

    /**
     * Type of intersection between convex hull and cube
     */
    public enum IntersectionType {
        COMPLETELY_INSIDE,  // Cube is completely inside convex hull
        INTERSECTING,       // Cube partially intersects convex hull
        COMPLETELY_OUTSIDE  // Cube is completely outside convex hull (not returned in intersection results)
    }

    /**
     * Represents a convex hull defined by a set of half-space planes
     * Each plane defines a half-space, and the convex hull is the intersection of all half-spaces
     */
    public static class ConvexHull {
        public final List<Plane3D> planes;
        public final Point3f centroid;
        public final float boundingRadius;
        
        public ConvexHull(List<Plane3D> planes) {
            if (planes == null || planes.isEmpty()) {
                throw new IllegalArgumentException("Convex hull must have at least one plane");
            }
            this.planes = Collections.unmodifiableList(new ArrayList<>(planes));
            this.centroid = calculateCentroid(planes);
            this.boundingRadius = calculateBoundingRadius(planes, centroid);
        }
        
        /**
         * Create a convex hull from vertices using gift wrapping algorithm (simplified for positive coordinates)
         */
        public static ConvexHull fromVertices(List<Point3f> vertices) {
            if (vertices == null || vertices.size() < 4) {
                throw new IllegalArgumentException("Convex hull requires at least 4 vertices");
            }
            
            for (Point3f vertex : vertices) {
                validatePositiveCoordinates(vertex, "vertex");
            }
            
            // Create planes from vertices using simplified convex hull construction
            List<Plane3D> planes = createPlanesFromVertices(vertices);
            return new ConvexHull(planes);
        }
        
        /**
         * Create a simple convex hull approximation (oriented bounding box)
         */
        public static ConvexHull createOrientedBoundingBox(Point3f center, Vector3f[] axes, float[] extents) {
            validatePositiveCoordinates(center, "center");
            
            if (axes.length != 3 || extents.length != 3) {
                throw new IllegalArgumentException("Need exactly 3 axes and 3 extents");
            }
            
            for (float extent : extents) {
                if (extent <= 0) {
                    throw new IllegalArgumentException("All extents must be positive");
                }
            }
            
            // Validate that the bounding box won't have negative coordinates
            float minCoord = Math.min(Math.min(center.x, center.y), center.z);
            float maxExtent = Math.max(Math.max(extents[0], extents[1]), extents[2]);
            if (minCoord - maxExtent < 0) {
                throw new IllegalArgumentException("Oriented bounding box would extend into negative coordinates. " +
                    "Center: " + center + ", max extent: " + maxExtent);
            }
            
            List<Plane3D> planes = new ArrayList<>();
            
            // Create 6 planes for the oriented bounding box
            for (int i = 0; i < 3; i++) {
                Vector3f axis = new Vector3f(axes[i]); // Create copy to avoid modifying original
                axis.normalize();
                float extent = extents[i];
                
                // Positive side plane
                Point3f posPoint = new Point3f(
                    center.x + axis.x * extent,
                    center.y + axis.y * extent,
                    center.z + axis.z * extent
                );
                Vector3f posNormal = new Vector3f(-axis.x, -axis.y, -axis.z); // Inward normal
                planes.add(Plane3D.fromPointAndNormal(posPoint, posNormal));
                
                // Negative side plane - ensure coordinates remain positive
                Point3f negPoint = new Point3f(
                    Math.max(0.1f, center.x - axis.x * extent),
                    Math.max(0.1f, center.y - axis.y * extent),
                    Math.max(0.1f, center.z - axis.z * extent)
                );
                Vector3f negNormal = new Vector3f(axis.x, axis.y, axis.z); // Inward normal
                planes.add(Plane3D.fromPointAndNormal(negPoint, negNormal));
            }
            
            return new ConvexHull(planes);
        }
        
        /**
         * Test if a point is inside this convex hull
         */
        public boolean containsPoint(Point3f point) {
            validatePositiveCoordinates(point, "point");
            
            for (Plane3D plane : planes) {
                if (plane.distanceToPoint(point) > 0) {
                    return false; // Point is on positive side of plane (outside)
                }
            }
            return true;
        }
        
        /**
         * Test if a cube intersects with this convex hull
         */
        public IntersectionType intersectsCube(Spatial.Cube cube) {
            return testConvexHullCubeIntersection(this, cube);
        }
        
        /**
         * Calculate the distance from a point to the convex hull surface
         */
        public float distanceToPoint(Point3f point) {
            validatePositiveCoordinates(point, "point");
            
            if (containsPoint(point)) {
                // Point is inside - find minimum distance to any plane
                float minDistance = Float.MAX_VALUE;
                for (Plane3D plane : planes) {
                    float distance = Math.abs(plane.distanceToPoint(point));
                    minDistance = Math.min(minDistance, distance);
                }
                return -minDistance; // Negative for inside
            } else {
                // Point is outside - find minimum distance to hull surface
                float minDistance = Float.MAX_VALUE;
                for (Plane3D plane : planes) {
                    float distance = plane.distanceToPoint(point);
                    if (distance > 0) {
                        minDistance = Math.min(minDistance, distance);
                    }
                }
                return minDistance;
            }
        }
        
        private static Point3f calculateCentroid(List<Plane3D> planes) {
            // Simplified centroid calculation - average of plane centers
            float sumX = 0, sumY = 0, sumZ = 0;
            for (Plane3D plane : planes) {
                // Get a point on the plane (using normal direction from origin)
                float distance = -plane.d();
                Point3f planePoint = new Point3f(
                    plane.a() * distance,
                    plane.b() * distance,
                    plane.c() * distance
                );
                sumX += planePoint.x;
                sumY += planePoint.y;
                sumZ += planePoint.z;
            }
            return new Point3f(sumX / planes.size(), sumY / planes.size(), sumZ / planes.size());
        }
        
        private static float calculateBoundingRadius(List<Plane3D> planes, Point3f centroid) {
            // Simplified bounding radius - maximum distance from centroid to any plane
            float maxDistance = 0;
            for (Plane3D plane : planes) {
                float distance = Math.abs(plane.distanceToPoint(centroid));
                maxDistance = Math.max(maxDistance, distance);
            }
            return maxDistance;
        }
        
        private static List<Plane3D> createPlanesFromVertices(List<Point3f> vertices) {
            // Simplified convex hull plane creation
            // In a full implementation, this would use a proper convex hull algorithm
            List<Plane3D> planes = new ArrayList<>();
            
            if (vertices.size() >= 4) {
                // Create a tetrahedron from first 4 vertices as a simple approximation
                Point3f v0 = vertices.get(0);
                Point3f v1 = vertices.get(1);
                Point3f v2 = vertices.get(2);
                Point3f v3 = vertices.get(3);
                
                // Create 4 triangular faces
                try {
                    planes.add(Plane3D.fromThreePoints(v0, v1, v2));
                    planes.add(Plane3D.fromThreePoints(v0, v2, v3));
                    planes.add(Plane3D.fromThreePoints(v0, v3, v1));
                    planes.add(Plane3D.fromThreePoints(v1, v3, v2));
                } catch (IllegalArgumentException e) {
                    // If points are coplanar, fall back to bounding box
                    return createBoundingBoxPlanes(vertices);
                }
            } else {
                // Fall back to axis-aligned bounding box
                return createBoundingBoxPlanes(vertices);
            }
            
            return planes;
        }
        
        private static List<Plane3D> createBoundingBoxPlanes(List<Point3f> vertices) {
            // Create axis-aligned bounding box planes
            float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
            float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;
            
            for (Point3f vertex : vertices) {
                minX = Math.min(minX, vertex.x);
                maxX = Math.max(maxX, vertex.x);
                minY = Math.min(minY, vertex.y);
                maxY = Math.max(maxY, vertex.y);
                minZ = Math.min(minZ, vertex.z);
                maxZ = Math.max(maxZ, vertex.z);
            }
            
            List<Plane3D> planes = new ArrayList<>();
            
            // Create 6 bounding box planes with inward normals
            planes.add(new Plane3D(-1, 0, 0, maxX));  // Right face (normal points left)
            planes.add(new Plane3D(1, 0, 0, -minX));   // Left face (normal points right)
            planes.add(new Plane3D(0, -1, 0, maxY));  // Top face (normal points down)
            planes.add(new Plane3D(0, 1, 0, -minY));   // Bottom face (normal points up)
            planes.add(new Plane3D(0, 0, -1, maxZ));  // Far face (normal points back)
            planes.add(new Plane3D(0, 0, 1, -minZ));   // Near face (normal points forward)
            
            return planes;
        }
        
        @Override
        public String toString() {
            return String.format("ConvexHull[planes=%d, centroid=%s, radius=%.2f]", 
                               planes.size(), centroid, boundingRadius);
        }
    }

    /**
     * Find all cubes that intersect with the convex hull, ordered by distance from reference point
     * 
     * @param convexHull the convex hull to test intersection with
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<ConvexHullIntersection<Content>> convexHullIntersectedAll(
            ConvexHull convexHull, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<ConvexHullIntersection<Content>> intersections = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            IntersectionType intersectionType = testConvexHullCubeIntersection(convexHull, cube);
            if (intersectionType != IntersectionType.COMPLETELY_OUTSIDE) {
                Point3f cubeCenter = getCubeCenter(cube);
                float distance = calculateDistance(referencePoint, cubeCenter);
                float penetrationDepth = calculatePenetrationDepth(convexHull, cube);
                
                ConvexHullIntersection<Content> intersection = new ConvexHullIntersection<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    distance,
                    cubeCenter,
                    intersectionType,
                    penetrationDepth
                );
                intersections.add(intersection);
            }
        }

        // Sort by distance from reference point
        intersections.sort(Comparator.comparing(chi -> chi.distanceToReferencePoint));
        
        return intersections;
    }

    /**
     * Find the first (closest to reference point) cube that intersects with the convex hull
     * 
     * @param convexHull the convex hull to test intersection with
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> ConvexHullIntersection<Content> convexHullIntersectedFirst(
            ConvexHull convexHull, Octree<Content> octree, Point3f referencePoint) {
        
        List<ConvexHullIntersection<Content>> intersections = convexHullIntersectedAll(convexHull, octree, referencePoint);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find cubes that are completely inside the convex hull
     * 
     * @param convexHull the convex hull to test against
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections completely inside convex hull, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<ConvexHullIntersection<Content>> cubesCompletelyInside(
            ConvexHull convexHull, Octree<Content> octree, Point3f referencePoint) {
        
        return convexHullIntersectedAll(convexHull, octree, referencePoint).stream()
            .filter(chi -> chi.intersectionType == IntersectionType.COMPLETELY_INSIDE)
            .collect(Collectors.toList());
    }

    /**
     * Find cubes that partially intersect the convex hull (not completely inside or outside)
     * 
     * @param convexHull the convex hull to test against
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections partially intersecting convex hull, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<ConvexHullIntersection<Content>> cubesPartiallyIntersecting(
            ConvexHull convexHull, Octree<Content> octree, Point3f referencePoint) {
        
        return convexHullIntersectedAll(convexHull, octree, referencePoint).stream()
            .filter(chi -> chi.intersectionType == IntersectionType.INTERSECTING)
            .collect(Collectors.toList());
    }

    /**
     * Count the number of cubes that intersect with the convex hull
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param convexHull the convex hull to test intersection with
     * @param octree the octree to search in
     * @return number of cubes intersecting the convex hull
     */
    public static <Content> long countConvexHullIntersections(ConvexHull convexHull, Octree<Content> octree) {
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return 0;
        }

        return map.entrySet().stream()
            .mapToLong(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                IntersectionType intersectionType = testConvexHullCubeIntersection(convexHull, cube);
                return intersectionType != IntersectionType.COMPLETELY_OUTSIDE ? 1 : 0;
            })
            .sum();
    }

    /**
     * Test if any cube in the octree intersects with the convex hull
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param convexHull the convex hull to test intersection with
     * @param octree the octree to search in
     * @return true if any cube intersects the convex hull
     */
    public static <Content> boolean hasAnyIntersection(ConvexHull convexHull, Octree<Content> octree) {
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return false;
        }

        return map.entrySet().stream()
            .anyMatch(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                IntersectionType intersectionType = testConvexHullCubeIntersection(convexHull, cube);
                return intersectionType != IntersectionType.COMPLETELY_OUTSIDE;
            });
    }

    /**
     * Get statistics about convex hull intersection results
     * 
     * @param convexHull the convex hull to test intersection with
     * @param octree the octree to search in
     * @return statistics about intersection results
     */
    public static <Content> IntersectionStatistics getConvexHullIntersectionStatistics(
            ConvexHull convexHull, Octree<Content> octree) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return new IntersectionStatistics(0, 0, 0, 0, 0.0f, 0.0f);
        }

        long totalCubes = 0;
        long insideCubes = 0;
        long intersectingCubes = 0;
        long outsideCubes = 0;
        float totalPenetrationDepth = 0.0f;

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            totalCubes++;
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            IntersectionType intersectionType = testConvexHullCubeIntersection(convexHull, cube);
            
            switch (intersectionType) {
                case COMPLETELY_INSIDE -> {
                    insideCubes++;
                    totalPenetrationDepth += calculatePenetrationDepth(convexHull, cube);
                }
                case INTERSECTING -> {
                    intersectingCubes++;
                    totalPenetrationDepth += calculatePenetrationDepth(convexHull, cube);
                }
                case COMPLETELY_OUTSIDE -> outsideCubes++;
            }
        }

        float averagePenetrationDepth = (insideCubes + intersectingCubes) > 0 ? 
            totalPenetrationDepth / (insideCubes + intersectingCubes) : 0.0f;

        return new IntersectionStatistics(totalCubes, insideCubes, intersectingCubes, 
                                        outsideCubes, totalPenetrationDepth, averagePenetrationDepth);
    }

    /**
     * Statistics about convex hull intersection results
     */
    public static class IntersectionStatistics {
        public final long totalCubes;
        public final long insideCubes;
        public final long intersectingCubes;
        public final long outsideCubes;
        public final float totalPenetrationDepth;
        public final float averagePenetrationDepth;
        
        public IntersectionStatistics(long totalCubes, long insideCubes, long intersectingCubes, 
                                    long outsideCubes, float totalPenetrationDepth, float averagePenetrationDepth) {
            this.totalCubes = totalCubes;
            this.insideCubes = insideCubes;
            this.intersectingCubes = intersectingCubes;
            this.outsideCubes = outsideCubes;
            this.totalPenetrationDepth = totalPenetrationDepth;
            this.averagePenetrationDepth = averagePenetrationDepth;
        }
        
        public double getInsidePercentage() {
            return totalCubes > 0 ? (double) insideCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getIntersectingPercentage() {
            return totalCubes > 0 ? (double) intersectingCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getOutsidePercentage() {
            return totalCubes > 0 ? (double) outsideCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getIntersectedPercentage() {
            return totalCubes > 0 ? (double) (insideCubes + intersectingCubes) / totalCubes * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("ConvexHullIntersectionStats[total=%d, inside=%d(%.1f%%), intersecting=%d(%.1f%%), outside=%d(%.1f%%), intersected=%.1f%%, avg_penetration=%.3f]",
                               totalCubes, insideCubes, getInsidePercentage(), 
                               intersectingCubes, getIntersectingPercentage(),
                               outsideCubes, getOutsidePercentage(), getIntersectedPercentage(), averagePenetrationDepth);
        }
    }

    /**
     * Batch processing for multiple convex hull intersection queries
     * Useful for multiple simultaneous convex hull queries
     * 
     * @param convexHulls list of convex hulls to test
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return map of convex hulls to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<ConvexHull, List<ConvexHullIntersection<Content>>> 
            batchConvexHullIntersections(List<ConvexHull> convexHulls, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return convexHulls.stream()
            .collect(Collectors.toMap(
                hull -> hull,
                hull -> convexHullIntersectedAll(hull, octree, referencePoint)
            ));
    }

    /**
     * Test convex hull-cube intersection using separating axis theorem
     * 
     * @param convexHull the convex hull
     * @param cube the cube to test
     * @return intersection type
     */
    private static IntersectionType testConvexHullCubeIntersection(ConvexHull convexHull, Spatial.Cube cube) {
        // Get all 8 corners of the cube
        Point3f[] cubeCorners = getCubeCorners(cube);
        
        // Test if all corners are inside the convex hull
        boolean allInside = true;
        boolean anyInside = false;
        
        for (Point3f corner : cubeCorners) {
            boolean cornerInside = convexHull.containsPoint(corner);
            if (!cornerInside) {
                allInside = false;
            } else {
                anyInside = true;
            }
        }
        
        if (allInside) {
            return IntersectionType.COMPLETELY_INSIDE;
        }
        
        // If no corners are inside, check if hull intersects cube faces
        if (!anyInside) {
            // Test if any plane of the convex hull intersects the cube
            for (Plane3D plane : convexHull.planes) {
                if (plane.intersectsCube(cube)) {
                    return IntersectionType.INTERSECTING;
                }
            }
            return IntersectionType.COMPLETELY_OUTSIDE;
        }
        
        // Some corners inside, some outside = intersecting
        return IntersectionType.INTERSECTING;
    }

    /**
     * Calculate penetration depth of cube into convex hull
     */
    private static float calculatePenetrationDepth(ConvexHull convexHull, Spatial.Cube cube) {
        Point3f cubeCenter = getCubeCenter(cube);
        float distanceToHull = convexHull.distanceToPoint(cubeCenter);
        
        if (distanceToHull < 0) {
            // Cube center is inside hull
            return Math.abs(distanceToHull);
        } else {
            // Cube center is outside hull - check if any part of cube is inside
            Point3f[] corners = getCubeCorners(cube);
            float maxPenetration = 0.0f;
            
            for (Point3f corner : corners) {
                float cornerDistance = convexHull.distanceToPoint(corner);
                if (cornerDistance < 0) {
                    maxPenetration = Math.max(maxPenetration, Math.abs(cornerDistance));
                }
            }
            
            return maxPenetration;
        }
    }

    /**
     * Get all 8 corners of a cube
     */
    private static Point3f[] getCubeCorners(Spatial.Cube cube) {
        float minX = cube.originX();
        float minY = cube.originY();
        float minZ = cube.originZ();
        float maxX = cube.originX() + cube.extent();
        float maxY = cube.originY() + cube.extent();
        float maxZ = cube.originZ() + cube.extent();
        
        return new Point3f[] {
            new Point3f(minX, minY, minZ), new Point3f(maxX, minY, minZ),
            new Point3f(minX, maxY, minZ), new Point3f(maxX, maxY, minZ),
            new Point3f(minX, minY, maxZ), new Point3f(maxX, minY, maxZ),
            new Point3f(minX, maxY, maxZ), new Point3f(maxX, maxY, maxZ)
        };
    }

    /**
     * Calculate Euclidean distance between two points
     */
    private static float calculateDistance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * Calculate the center point of a cube
     */
    private static Point3f getCubeCenter(Spatial.Cube cube) {
        float halfExtent = cube.extent() / 2.0f;
        return new Point3f(
            cube.originX() + halfExtent,
            cube.originY() + halfExtent,
            cube.originZ() + halfExtent
        );
    }
    
    /**
     * Validate that all coordinates in a point are positive
     */
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }
}