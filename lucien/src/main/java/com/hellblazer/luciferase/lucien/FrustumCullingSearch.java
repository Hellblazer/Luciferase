package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Frustum culling search implementation for Octree
 * Finds octree cubes that intersect with a 3D camera frustum, with results ordered by distance from camera
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class FrustumCullingSearch {

    /**
     * Frustum intersection result with distance information
     */
    public static class FrustumIntersection<Content> {
        public final long index;
        public final Content content;
        public final Spatial.Cube cube;
        public final float distanceToCamera;
        public final Point3f cubeCenter;
        public final CullingResult cullingResult;

        public FrustumIntersection(long index, Content content, Spatial.Cube cube, 
                                 float distanceToCamera, Point3f cubeCenter, CullingResult cullingResult) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.distanceToCamera = distanceToCamera;
            this.cubeCenter = cubeCenter;
            this.cullingResult = cullingResult;
        }
    }

    /**
     * Result of frustum culling test
     */
    public enum CullingResult {
        INSIDE,      // Completely inside frustum
        INTERSECTING, // Partially inside frustum
        OUTSIDE       // Completely outside frustum
    }

    /**
     * Find all cubes that intersect with the frustum, ordered by distance from camera
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the octree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @return list of intersections sorted by distance from camera (closest first)
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> List<FrustumIntersection<Content>> frustumCulledAll(
            Frustum3D frustum, Octree<Content> octree, Point3f cameraPosition) {
        
        validatePositiveCoordinates(cameraPosition, "cameraPosition");
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<FrustumIntersection<Content>> intersections = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            CullingResult result = testFrustumCulling(frustum, cube);
            if (result != CullingResult.OUTSIDE) {
                Point3f cubeCenter = getCubeCenter(cube);
                float distance = calculateDistance(cameraPosition, cubeCenter);
                
                FrustumIntersection<Content> intersection = new FrustumIntersection<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    distance,
                    cubeCenter,
                    result
                );
                intersections.add(intersection);
            }
        }

        // Sort by distance from camera
        intersections.sort(Comparator.comparing(fi -> fi.distanceToCamera));
        
        return intersections;
    }

    /**
     * Find the first (closest to camera) cube that intersects with the frustum
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the octree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> FrustumIntersection<Content> frustumCulledFirst(
            Frustum3D frustum, Octree<Content> octree, Point3f cameraPosition) {
        
        List<FrustumIntersection<Content>> intersections = frustumCulledAll(frustum, octree, cameraPosition);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Find cubes that are completely inside the frustum
     * 
     * @param frustum the camera frustum to test against
     * @param octree the octree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @return list of intersections completely inside frustum, sorted by distance from camera
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> List<FrustumIntersection<Content>> cubesCompletelyInside(
            Frustum3D frustum, Octree<Content> octree, Point3f cameraPosition) {
        
        return frustumCulledAll(frustum, octree, cameraPosition).stream()
            .filter(fi -> fi.cullingResult == CullingResult.INSIDE)
            .collect(Collectors.toList());
    }

    /**
     * Find cubes that partially intersect the frustum (not completely inside or outside)
     * 
     * @param frustum the camera frustum to test against
     * @param octree the octree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @return list of intersections partially intersecting frustum, sorted by distance from camera
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> List<FrustumIntersection<Content>> cubesPartiallyIntersecting(
            Frustum3D frustum, Octree<Content> octree, Point3f cameraPosition) {
        
        return frustumCulledAll(frustum, octree, cameraPosition).stream()
            .filter(fi -> fi.cullingResult == CullingResult.INTERSECTING)
            .collect(Collectors.toList());
    }

    /**
     * Count the number of cubes that intersect with the frustum
     * This is more efficient than getting all intersections when only count is needed
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the octree to search in
     * @return number of cubes intersecting the frustum
     */
    public static <Content> long countFrustumIntersections(Frustum3D frustum, Octree<Content> octree) {
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return 0;
        }

        return map.entrySet().stream()
            .mapToLong(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                CullingResult result = testFrustumCulling(frustum, cube);
                return result != CullingResult.OUTSIDE ? 1 : 0;
            })
            .sum();
    }

    /**
     * Test if any cube in the octree intersects with the frustum
     * This is more efficient than getting all intersections when only existence check is needed
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the octree to search in
     * @return true if any cube intersects the frustum
     */
    public static <Content> boolean hasAnyIntersection(Frustum3D frustum, Octree<Content> octree) {
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return false;
        }

        return map.entrySet().stream()
            .anyMatch(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                CullingResult result = testFrustumCulling(frustum, cube);
                return result != CullingResult.OUTSIDE;
            });
    }

    /**
     * Get statistics about frustum culling results
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the octree to search in
     * @return statistics about culling results
     */
    public static <Content> CullingStatistics getFrustumCullingStatistics(Frustum3D frustum, Octree<Content> octree) {
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return new CullingStatistics(0, 0, 0, 0);
        }

        long totalCubes = 0;
        long insideCubes = 0;
        long intersectingCubes = 0;
        long outsideCubes = 0;

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            totalCubes++;
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            CullingResult result = testFrustumCulling(frustum, cube);
            
            switch (result) {
                case INSIDE -> insideCubes++;
                case INTERSECTING -> intersectingCubes++;
                case OUTSIDE -> outsideCubes++;
            }
        }

        return new CullingStatistics(totalCubes, insideCubes, intersectingCubes, outsideCubes);
    }

    /**
     * Statistics about frustum culling results
     */
    public static class CullingStatistics {
        public final long totalCubes;
        public final long insideCubes;
        public final long intersectingCubes;
        public final long outsideCubes;
        
        public CullingStatistics(long totalCubes, long insideCubes, long intersectingCubes, long outsideCubes) {
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
        
        public double getVisiblePercentage() {
            return totalCubes > 0 ? (double) (insideCubes + intersectingCubes) / totalCubes * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("CullingStats[total=%d, inside=%d(%.1f%%), intersecting=%d(%.1f%%), outside=%d(%.1f%%), visible=%.1f%%]",
                               totalCubes, insideCubes, getInsidePercentage(), 
                               intersectingCubes, getIntersectingPercentage(),
                               outsideCubes, getOutsidePercentage(), getVisiblePercentage());
        }
    }

    /**
     * Batch processing for multiple frustum culling queries
     * Useful for multiple camera views or time-based culling
     * 
     * @param frustums list of frustums to test
     * @param octree the octree to search in
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @return map of frustums to their intersection results
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> Map<Frustum3D, List<FrustumIntersection<Content>>> 
            batchFrustumCulling(List<Frustum3D> frustums, Octree<Content> octree, Point3f cameraPosition) {
        
        validatePositiveCoordinates(cameraPosition, "cameraPosition");
        
        return frustums.stream()
            .collect(Collectors.toMap(
                frustum -> frustum,
                frustum -> frustumCulledAll(frustum, octree, cameraPosition)
            ));
    }

    /**
     * Test frustum culling for a single cube
     * 
     * @param frustum the camera frustum
     * @param cube the cube to test
     * @return culling result (INSIDE, INTERSECTING, or OUTSIDE)
     */
    private static CullingResult testFrustumCulling(Frustum3D frustum, Spatial.Cube cube) {
        // Test if cube is completely inside frustum
        if (frustum.containsCube(cube)) {
            return CullingResult.INSIDE;
        }
        
        // Test if cube intersects frustum
        if (frustum.intersectsCube(cube)) {
            return CullingResult.INTERSECTING;
        }
        
        // Cube is completely outside
        return CullingResult.OUTSIDE;
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