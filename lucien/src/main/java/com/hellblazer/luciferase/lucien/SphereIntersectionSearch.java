package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sphere intersection search implementation for Octree
 * Finds octree cubes that intersect with 3D spheres, with results ordered by distance from reference point
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class SphereIntersectionSearch {

    /**
     * Sphere intersection result with distance information
     */
    public static class SphereIntersection<Content> {
        public final long index;
        public final Content content;
        public final Spatial.Cube cube;
        public final float distanceToReferencePoint;
        public final Point3f cubeCenter;
        public final IntersectionType intersectionType;

        public SphereIntersection(long index, Content content, Spatial.Cube cube, 
                                float distanceToReferencePoint, Point3f cubeCenter, IntersectionType intersectionType) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.cubeCenter = cubeCenter;
            this.intersectionType = intersectionType;
        }
    }

    /**
     * Type of intersection between sphere and cube
     */
    public enum IntersectionType {
        COMPLETELY_INSIDE,  // Cube is completely inside sphere
        INTERSECTING,       // Cube partially intersects sphere
        COMPLETELY_OUTSIDE  // Cube is completely outside sphere (not returned in intersection results)
    }

    /**
     * Find all cubes that intersect with the sphere, ordered by distance from reference point
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<SphereIntersection<Content>> sphereIntersectedAll(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        // Create bounding AABB for the sphere
        var boundingAABB = new Spatial.aabb(
            sphereCenter.x - sphereRadius, sphereCenter.y - sphereRadius, sphereCenter.z - sphereRadius,
            sphereCenter.x + sphereRadius, sphereCenter.y + sphereRadius, sphereCenter.z + sphereRadius
        );
        
        // Use Octree's efficient bounding method which uses Morton curve ranges
        return octree.bounding(boundingAABB)
            .map(hex -> {
                var cube = hex.toCube();
                var intersectionType = testSphereIntersection(sphereCenter, sphereRadius, cube);
                
                if (intersectionType != IntersectionType.COMPLETELY_OUTSIDE) {
                    Point3f cubeCenter = getCubeCenter(cube);
                    float distance = calculateDistance(referencePoint, cubeCenter);
                    
                    return new SphereIntersection<>(
                        hex.index(),
                        hex.cell(),
                        cube,
                        distance,
                        cubeCenter,
                        intersectionType
                    );
                }
                return null;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(si -> si.distanceToReferencePoint))
            .toList();
    }

    /**
     * Find the first (closest to reference point) cube that intersects with the sphere
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> SphereIntersection<Content> sphereIntersectedFirst(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree, Point3f referencePoint) {
        
        List<SphereIntersection<Content>> intersections = sphereIntersectedAll(sphereCenter, sphereRadius, octree, referencePoint);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find cubes that are completely inside the sphere
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections completely inside sphere, sorted by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<SphereIntersection<Content>> cubesCompletelyInside(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree, Point3f referencePoint) {
        
        return sphereIntersectedAll(sphereCenter, sphereRadius, octree, referencePoint).stream()
            .filter(si -> si.intersectionType == IntersectionType.COMPLETELY_INSIDE)
            .collect(Collectors.toList());
    }

    /**
     * Find cubes that partially intersect the sphere (not completely inside or outside)
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections partially intersecting sphere, sorted by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<SphereIntersection<Content>> cubesPartiallyIntersecting(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree, Point3f referencePoint) {
        
        return sphereIntersectedAll(sphereCenter, sphereRadius, octree, referencePoint).stream()
            .filter(si -> si.intersectionType == IntersectionType.INTERSECTING)
            .collect(Collectors.toList());
    }

    /**
     * Count the number of cubes that intersect with the sphere
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search in
     * @return number of cubes intersecting the sphere
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> long countSphereIntersections(Point3f sphereCenter, float sphereRadius, Octree<Content> octree) {
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        // Create bounding AABB for the sphere
        var boundingAABB = new Spatial.aabb(
            sphereCenter.x - sphereRadius, sphereCenter.y - sphereRadius, sphereCenter.z - sphereRadius,
            sphereCenter.x + sphereRadius, sphereCenter.y + sphereRadius, sphereCenter.z + sphereRadius
        );
        
        // Use efficient bounding stream and count
        return octree.bounding(boundingAABB)
            .filter(hex -> {
                var cube = hex.toCube();
                var intersectionType = testSphereIntersection(sphereCenter, sphereRadius, cube);
                return intersectionType != IntersectionType.COMPLETELY_OUTSIDE;
            })
            .count();
    }

    /**
     * Test if any cube in the octree intersects with the sphere
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search in
     * @return true if any cube intersects the sphere
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> boolean hasAnyIntersection(Point3f sphereCenter, float sphereRadius, Octree<Content> octree) {
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        // Create bounding AABB for the sphere
        var boundingAABB = new Spatial.aabb(
            sphereCenter.x - sphereRadius, sphereCenter.y - sphereRadius, sphereCenter.z - sphereRadius,
            sphereCenter.x + sphereRadius, sphereCenter.y + sphereRadius, sphereCenter.z + sphereRadius
        );
        
        // Use efficient bounding stream to check existence
        return octree.bounding(boundingAABB)
            .anyMatch(hex -> {
                var cube = hex.toCube();
                var intersectionType = testSphereIntersection(sphereCenter, sphereRadius, cube);
                return intersectionType != IntersectionType.COMPLETELY_OUTSIDE;
            });
    }

    /**
     * Get statistics about sphere intersection results
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search in
     * @return statistics about intersection results
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> IntersectionStatistics getSphereIntersectionStatistics(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree) {
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return new IntersectionStatistics(0, 0, 0, 0);
        }

        long totalCubes = 0;
        long insideCubes = 0;
        long intersectingCubes = 0;
        long outsideCubes = 0;

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            totalCubes++;
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            IntersectionType intersectionType = testSphereIntersection(sphereCenter, sphereRadius, cube);
            
            switch (intersectionType) {
                case COMPLETELY_INSIDE -> insideCubes++;
                case INTERSECTING -> intersectingCubes++;
                case COMPLETELY_OUTSIDE -> outsideCubes++;
            }
        }

        return new IntersectionStatistics(totalCubes, insideCubes, intersectingCubes, outsideCubes);
    }

    /**
     * Statistics about sphere intersection results
     */
    public static class IntersectionStatistics {
        public final long totalCubes;
        public final long insideCubes;
        public final long intersectingCubes;
        public final long outsideCubes;
        
        public IntersectionStatistics(long totalCubes, long insideCubes, long intersectingCubes, long outsideCubes) {
            this.totalCubes = totalCubes;
            this.insideCubes = insideCubes;
            this.intersectingCubes = intersectingCubes;
            this.outsideCubes = outsideCubes;
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
            return String.format("SphereIntersectionStats[total=%d, inside=%d(%.1f%%), intersecting=%d(%.1f%%), outside=%d(%.1f%%), intersected=%.1f%%]",
                               totalCubes, insideCubes, getInsidePercentage(), 
                               intersectingCubes, getIntersectingPercentage(),
                               outsideCubes, getOutsidePercentage(), getIntersectedPercentage());
        }
    }

    /**
     * Batch processing for multiple sphere intersection queries
     * Useful for multiple simultaneous sphere queries
     * 
     * @param sphereQueries list of sphere queries (center, radius pairs)
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return map of sphere queries to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<SphereQuery, List<SphereIntersection<Content>>> 
            batchSphereIntersections(List<SphereQuery> sphereQueries, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return sphereQueries.stream()
            .collect(Collectors.toMap(
                query -> query,
                query -> sphereIntersectedAll(query.center, query.radius, octree, referencePoint)
            ));
    }

    /**
     * Represents a sphere query with center and radius
     */
    public static class SphereQuery {
        public final Point3f center;
        public final float radius;
        
        public SphereQuery(Point3f center, float radius) {
            validatePositiveCoordinates(center, "center");
            if (radius <= 0) {
                throw new IllegalArgumentException("Sphere radius must be positive, got: " + radius);
            }
            this.center = new Point3f(center); // Defensive copy
            this.radius = radius;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SphereQuery)) return false;
            SphereQuery other = (SphereQuery) obj;
            return center.equals(other.center) && Float.compare(radius, other.radius) == 0;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(center, radius);
        }
        
        @Override
        public String toString() {
            return String.format("SphereQuery[center=%s, radius=%.2f]", center, radius);
        }
    }

    /**
     * Test sphere-cube intersection using optimized sphere-AABB intersection algorithm
     * Based on "Real-Time Rendering" by Akenine-Möller, Haines, and Hoffman
     * 
     * @param sphereCenter center of the sphere
     * @param sphereRadius radius of the sphere
     * @param cube the cube to test
     * @return intersection type
     */
    private static IntersectionType testSphereIntersection(Point3f sphereCenter, float sphereRadius, Spatial.Cube cube) {
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();
        
        // Calculate squared distance from sphere center to closest point on cube
        float dx = Math.max(0, Math.max(cubeMinX - sphereCenter.x, sphereCenter.x - cubeMaxX));
        float dy = Math.max(0, Math.max(cubeMinY - sphereCenter.y, sphereCenter.y - cubeMaxY));
        float dz = Math.max(0, Math.max(cubeMinZ - sphereCenter.z, sphereCenter.z - cubeMaxZ));
        
        float distanceSquared = dx * dx + dy * dy + dz * dz;
        float radiusSquared = sphereRadius * sphereRadius;
        
        // No intersection if distance to closest point > radius
        if (distanceSquared > radiusSquared) {
            return IntersectionType.COMPLETELY_OUTSIDE;
        }
        
        // Check if cube is completely inside sphere
        // Calculate distance from sphere center to farthest corner of cube
        float farthestDx = Math.max(Math.abs(cubeMinX - sphereCenter.x), Math.abs(cubeMaxX - sphereCenter.x));
        float farthestDy = Math.max(Math.abs(cubeMinY - sphereCenter.y), Math.abs(cubeMaxY - sphereCenter.y));
        float farthestDz = Math.max(Math.abs(cubeMinZ - sphereCenter.z), Math.abs(cubeMaxZ - sphereCenter.z));
        
        float farthestDistanceSquared = farthestDx * farthestDx + farthestDy * farthestDy + farthestDz * farthestDz;
        
        if (farthestDistanceSquared <= radiusSquared) {
            return IntersectionType.COMPLETELY_INSIDE;
        }
        
        // Cube partially intersects sphere
        return IntersectionType.INTERSECTING;
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