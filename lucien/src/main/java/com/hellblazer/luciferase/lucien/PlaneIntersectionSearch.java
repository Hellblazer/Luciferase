package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Plane intersection search implementation for Octree
 * Finds octree cubes that intersect with a 3D plane, with results ordered by distance from a reference point
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class PlaneIntersectionSearch {

    /**
     * Plane intersection result with distance information
     */
    public static class PlaneIntersection<Content> {
        public final long index;
        public final Content content;
        public final Spatial.Cube cube;
        public final float distanceToReferencePoint;
        public final Point3f cubeCenter;

        public PlaneIntersection(long index, Content content, Spatial.Cube cube, 
                               float distanceToReferencePoint, Point3f cubeCenter) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.cubeCenter = cubeCenter;
        }
    }

    /**
     * Find all cubes that intersect with the plane, ordered by distance from reference point
     * 
     * @param plane the plane to test intersection with
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<PlaneIntersection<Content>> planeIntersectedAll(
            Plane3D plane, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<PlaneIntersection<Content>> intersections = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            if (plane.intersectsCube(cube)) {
                Point3f cubeCenter = getCubeCenter(cube);
                float distance = calculateDistance(referencePoint, cubeCenter);
                
                PlaneIntersection<Content> intersection = new PlaneIntersection<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    distance,
                    cubeCenter
                );
                intersections.add(intersection);
            }
        }

        // Sort by distance from reference point
        intersections.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
        
        return intersections;
    }

    /**
     * Find the first (closest to reference point) cube that intersects with the plane
     * 
     * @param plane the plane to test intersection with
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> PlaneIntersection<Content> planeIntersectedFirst(
            Plane3D plane, Octree<Content> octree, Point3f referencePoint) {
        
        List<PlaneIntersection<Content>> intersections = planeIntersectedAll(plane, octree, referencePoint);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find all cubes that intersect with the plane, ordered by distance along plane normal
     * This provides a different ordering than distance from reference point
     * 
     * @param plane the plane to test intersection with
     * @param octree the octree to search in
     * @return list of intersections sorted by signed distance from plane (closest to plane first)
     */
    public static <Content> List<PlaneIntersection<Content>> planeIntersectedAllByPlaneDistance(
            Plane3D plane, Octree<Content> octree) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<PlaneIntersection<Content>> intersections = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            if (plane.intersectsCube(cube)) {
                Point3f cubeCenter = getCubeCenter(cube);
                float planeDistance = Math.abs(plane.distanceToPoint(cubeCenter));
                
                PlaneIntersection<Content> intersection = new PlaneIntersection<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    planeDistance,  // Use plane distance instead of reference point distance
                    cubeCenter
                );
                intersections.add(intersection);
            }
        }

        // Sort by absolute distance from plane
        intersections.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
        
        return intersections;
    }

    /**
     * Find cubes that are on the positive side of the plane (in direction of normal)
     * 
     * @param plane the plane to test against
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections on positive side, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<PlaneIntersection<Content>> cubesOnPositiveSide(
            Plane3D plane, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<PlaneIntersection<Content>> results = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            Point3f cubeCenter = getCubeCenter(cube);
            
            // Check if cube center is on positive side of plane
            if (plane.distanceToPoint(cubeCenter) > 0) {
                float distance = calculateDistance(referencePoint, cubeCenter);
                
                PlaneIntersection<Content> result = new PlaneIntersection<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    distance,
                    cubeCenter
                );
                results.add(result);
            }
        }

        // Sort by distance from reference point
        results.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Find cubes that are on the negative side of the plane (opposite to normal direction)
     * 
     * @param plane the plane to test against
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections on negative side, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<PlaneIntersection<Content>> cubesOnNegativeSide(
            Plane3D plane, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<PlaneIntersection<Content>> results = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            Point3f cubeCenter = getCubeCenter(cube);
            
            // Check if cube center is on negative side of plane
            if (plane.distanceToPoint(cubeCenter) < 0) {
                float distance = calculateDistance(referencePoint, cubeCenter);
                
                PlaneIntersection<Content> result = new PlaneIntersection<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    distance,
                    cubeCenter
                );
                results.add(result);
            }
        }

        // Sort by distance from reference point
        results.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Count the number of cubes that intersect with the plane
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param plane the plane to test intersection with
     * @param octree the octree to search in
     * @return number of cubes intersecting the plane
     */
    public static <Content> long countPlaneIntersections(Plane3D plane, Octree<Content> octree) {
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return 0;
        }

        return map.entrySet().stream()
            .mapToLong(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                return plane.intersectsCube(cube) ? 1 : 0;
            })
            .sum();
    }

    /**
     * Test if any cube in the octree intersects with the plane
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param plane the plane to test intersection with
     * @param octree the octree to search in
     * @return true if any cube intersects the plane
     */
    public static <Content> boolean hasAnyIntersection(Plane3D plane, Octree<Content> octree) {
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return false;
        }

        return map.entrySet().stream()
            .anyMatch(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                return plane.intersectsCube(cube);
            });
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