package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Containment search implementation for Octree
 * Finds octree cubes that are completely contained within specified spatial volumes
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class ContainmentSearch {

    /**
     * Containment result with distance and volume information
     */
    public static class ContainmentResult<Content> {
        public final long index;
        public final Content content;
        public final Spatial.Cube cube;
        public final float distanceToReferencePoint;
        public final Point3f cubeCenter;
        public final ContainmentType containmentType;
        public final float volumeRatio; // ratio of cube volume to container volume

        public ContainmentResult(long index, Content content, Spatial.Cube cube, 
                               float distanceToReferencePoint, Point3f cubeCenter, 
                               ContainmentType containmentType, float volumeRatio) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.cubeCenter = cubeCenter;
            this.containmentType = containmentType;
            this.volumeRatio = volumeRatio;
        }
    }

    /**
     * Type of containment relationship
     */
    public enum ContainmentType {
        COMPLETELY_CONTAINED,  // Cube is completely inside the container volume
        PARTIALLY_CONTAINED,   // Cube partially overlaps with container volume
        NOT_CONTAINED         // Cube is completely outside container volume
    }

    /**
     * Find all cubes completely contained within a sphere
     * 
     * @param sphereCenter center of the containing sphere (positive coordinates only)
     * @param sphereRadius radius of the containing sphere (positive)
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of completely contained cubes sorted by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<ContainmentResult<Content>> cubesContainedInSphere(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContainmentResult<Content>> results = new ArrayList<>();
        float sphereVolume = (4.0f / 3.0f) * (float) Math.PI * sphereRadius * sphereRadius * sphereRadius;
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            if (isCubeCompletelyInSphere(cube, sphereCenter, sphereRadius)) {
                Point3f cubeCenter = getCubeCenter(cube);
                float distance = calculateDistance(referencePoint, cubeCenter);
                float cubeVolume = cube.extent() * cube.extent() * cube.extent();
                float volumeRatio = cubeVolume / sphereVolume;
                
                ContainmentResult<Content> result = new ContainmentResult<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    distance,
                    cubeCenter,
                    ContainmentType.COMPLETELY_CONTAINED,
                    volumeRatio
                );
                results.add(result);
            }
        }

        // Sort by distance from reference point
        results.sort(Comparator.comparing(cr -> cr.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Find all cubes completely contained within an AABB
     * 
     * @param aabb the containing axis-aligned bounding box
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of completely contained cubes sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<ContainmentResult<Content>> cubesContainedInAABB(
            AABBIntersectionSearch.AABB aabb, Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContainmentResult<Content>> results = new ArrayList<>();
        float aabbVolume = aabb.getVolume();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            if (isCubeCompletelyInAABB(cube, aabb)) {
                Point3f cubeCenter = getCubeCenter(cube);
                float distance = calculateDistance(referencePoint, cubeCenter);
                float cubeVolume = cube.extent() * cube.extent() * cube.extent();
                float volumeRatio = cubeVolume / aabbVolume;
                
                ContainmentResult<Content> result = new ContainmentResult<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    distance,
                    cubeCenter,
                    ContainmentType.COMPLETELY_CONTAINED,
                    volumeRatio
                );
                results.add(result);
            }
        }

        // Sort by distance from reference point
        results.sort(Comparator.comparing(cr -> cr.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Find all cubes contained within a cylindrical volume
     * 
     * @param cylinderBase base center of the cylinder (positive coordinates only)
     * @param cylinderTop top center of the cylinder (positive coordinates only)
     * @param cylinderRadius radius of the cylinder (positive)
     * @param octree the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of completely contained cubes sorted by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<ContainmentResult<Content>> cubesContainedInCylinder(
            Point3f cylinderBase, Point3f cylinderTop, float cylinderRadius, 
            Octree<Content> octree, Point3f referencePoint) {
        
        validatePositiveCoordinates(cylinderBase, "cylinderBase");
        validatePositiveCoordinates(cylinderTop, "cylinderTop");
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        if (cylinderRadius <= 0) {
            throw new IllegalArgumentException("Cylinder radius must be positive, got: " + cylinderRadius);
        }
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContainmentResult<Content>> results = new ArrayList<>();
        float cylinderHeight = calculateDistance(cylinderBase, cylinderTop);
        float cylinderVolume = (float) Math.PI * cylinderRadius * cylinderRadius * cylinderHeight;
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            if (isCubeCompletelyInCylinder(cube, cylinderBase, cylinderTop, cylinderRadius)) {
                Point3f cubeCenter = getCubeCenter(cube);
                float distance = calculateDistance(referencePoint, cubeCenter);
                float cubeVolume = cube.extent() * cube.extent() * cube.extent();
                float volumeRatio = cubeVolume / cylinderVolume;
                
                ContainmentResult<Content> result = new ContainmentResult<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    distance,
                    cubeCenter,
                    ContainmentType.COMPLETELY_CONTAINED,
                    volumeRatio
                );
                results.add(result);
            }
        }

        // Sort by distance from reference point
        results.sort(Comparator.comparing(cr -> cr.distanceToReferencePoint));
        
        return results;
    }

    /**
     * Find cubes with a specific volume ratio within a container
     * 
     * @param containerVolume the volume of the container
     * @param minVolumeRatio minimum volume ratio (cube_volume / container_volume)
     * @param maxVolumeRatio maximum volume ratio (cube_volume / container_volume)
     * @param containedCubes list of contained cubes to filter
     * @return list of cubes within the specified volume ratio range
     */
    public static <Content> List<ContainmentResult<Content>> cubesWithVolumeRatio(
            float containerVolume, float minVolumeRatio, float maxVolumeRatio,
            List<ContainmentResult<Content>> containedCubes) {
        
        if (minVolumeRatio < 0 || maxVolumeRatio < 0 || minVolumeRatio > maxVolumeRatio) {
            throw new IllegalArgumentException("Invalid volume ratio range: [" + minVolumeRatio + ", " + maxVolumeRatio + "]");
        }
        
        return containedCubes.stream()
            .filter(cr -> cr.volumeRatio >= minVolumeRatio && cr.volumeRatio <= maxVolumeRatio)
            .collect(Collectors.toList());
    }

    /**
     * Count cubes completely contained within a sphere
     * More efficient than getting all results when only count is needed
     * 
     * @param sphereCenter center of the containing sphere (positive coordinates only)
     * @param sphereRadius radius of the containing sphere (positive)
     * @param octree the octree to search in
     * @return number of cubes completely contained in the sphere
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> long countCubesContainedInSphere(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree) {
        
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return 0;
        }

        return map.entrySet().stream()
            .mapToLong(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                return isCubeCompletelyInSphere(cube, sphereCenter, sphereRadius) ? 1 : 0;
            })
            .sum();
    }

    /**
     * Count cubes completely contained within an AABB
     * More efficient than getting all results when only count is needed
     * 
     * @param aabb the containing axis-aligned bounding box
     * @param octree the octree to search in
     * @return number of cubes completely contained in the AABB
     */
    public static <Content> long countCubesContainedInAABB(
            AABBIntersectionSearch.AABB aabb, Octree<Content> octree) {
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return 0;
        }

        return map.entrySet().stream()
            .mapToLong(entry -> {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                return isCubeCompletelyInAABB(cube, aabb) ? 1 : 0;
            })
            .sum();
    }

    /**
     * Get statistics about containment within a sphere
     * 
     * @param sphereCenter center of the containing sphere (positive coordinates only)
     * @param sphereRadius radius of the containing sphere (positive)
     * @param octree the octree to search in
     * @return statistics about containment results
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> ContainmentStatistics getContainmentStatisticsForSphere(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree) {
        
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return new ContainmentStatistics(0, 0, 0, 0, 0.0f, 0.0f);
        }

        long totalCubes = 0;
        long containedCubes = 0;
        long partiallyContainedCubes = 0;
        long notContainedCubes = 0;
        float totalVolumeRatio = 0.0f;
        float sphereVolume = (4.0f / 3.0f) * (float) Math.PI * sphereRadius * sphereRadius * sphereRadius;

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            totalCubes++;
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            if (isCubeCompletelyInSphere(cube, sphereCenter, sphereRadius)) {
                containedCubes++;
                float cubeVolume = cube.extent() * cube.extent() * cube.extent();
                totalVolumeRatio += cubeVolume / sphereVolume;
            } else if (cubePartiallyIntersectsSphere(cube, sphereCenter, sphereRadius)) {
                partiallyContainedCubes++;
            } else {
                notContainedCubes++;
            }
        }

        float averageVolumeRatio = containedCubes > 0 ? totalVolumeRatio / containedCubes : 0.0f;

        return new ContainmentStatistics(totalCubes, containedCubes, partiallyContainedCubes, 
                                       notContainedCubes, totalVolumeRatio, averageVolumeRatio);
    }

    /**
     * Statistics about containment results
     */
    public static class ContainmentStatistics {
        public final long totalCubes;
        public final long containedCubes;
        public final long partiallyContainedCubes;
        public final long notContainedCubes;
        public final float totalVolumeRatio;
        public final float averageVolumeRatio;
        
        public ContainmentStatistics(long totalCubes, long containedCubes, long partiallyContainedCubes, 
                                   long notContainedCubes, float totalVolumeRatio, float averageVolumeRatio) {
            this.totalCubes = totalCubes;
            this.containedCubes = containedCubes;
            this.partiallyContainedCubes = partiallyContainedCubes;
            this.notContainedCubes = notContainedCubes;
            this.totalVolumeRatio = totalVolumeRatio;
            this.averageVolumeRatio = averageVolumeRatio;
        }
        
        public double getContainedPercentage() {
            return totalCubes > 0 ? (double) containedCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getPartiallyContainedPercentage() {
            return totalCubes > 0 ? (double) partiallyContainedCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getNotContainedPercentage() {
            return totalCubes > 0 ? (double) notContainedCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getOverallContainmentPercentage() {
            return totalCubes > 0 ? (double) (containedCubes + partiallyContainedCubes) / totalCubes * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("ContainmentStats[total=%d, contained=%d(%.1f%%), partial=%d(%.1f%%), not_contained=%d(%.1f%%), avg_volume_ratio=%.3f]",
                               totalCubes, containedCubes, getContainedPercentage(), 
                               partiallyContainedCubes, getPartiallyContainedPercentage(),
                               notContainedCubes, getNotContainedPercentage(), averageVolumeRatio);
        }
    }

    /**
     * Test if a cube is completely contained within a sphere
     */
    private static boolean isCubeCompletelyInSphere(Spatial.Cube cube, Point3f sphereCenter, float sphereRadius) {
        // Calculate distance from sphere center to farthest corner of cube
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();
        
        float farthestDx = Math.max(Math.abs(cubeMinX - sphereCenter.x), Math.abs(cubeMaxX - sphereCenter.x));
        float farthestDy = Math.max(Math.abs(cubeMinY - sphereCenter.y), Math.abs(cubeMaxY - sphereCenter.y));
        float farthestDz = Math.max(Math.abs(cubeMinZ - sphereCenter.z), Math.abs(cubeMaxZ - sphereCenter.z));
        
        float farthestDistance = (float) Math.sqrt(farthestDx * farthestDx + farthestDy * farthestDy + farthestDz * farthestDz);
        
        return farthestDistance <= sphereRadius;
    }

    /**
     * Test if a cube is completely contained within an AABB
     */
    private static boolean isCubeCompletelyInAABB(Spatial.Cube cube, AABBIntersectionSearch.AABB aabb) {
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();
        
        return cubeMinX >= aabb.minX && cubeMaxX <= aabb.maxX &&
               cubeMinY >= aabb.minY && cubeMaxY <= aabb.maxY &&
               cubeMinZ >= aabb.minZ && cubeMaxZ <= aabb.maxZ;
    }

    /**
     * Test if a cube is completely contained within a cylinder
     */
    private static boolean isCubeCompletelyInCylinder(Spatial.Cube cube, Point3f cylinderBase, 
                                                     Point3f cylinderTop, float cylinderRadius) {
        // Check if all 8 corners of the cube are within the cylinder
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();
        
        Point3f[] corners = {
            new Point3f(cubeMinX, cubeMinY, cubeMinZ), new Point3f(cubeMaxX, cubeMinY, cubeMinZ),
            new Point3f(cubeMinX, cubeMaxY, cubeMinZ), new Point3f(cubeMaxX, cubeMaxY, cubeMinZ),
            new Point3f(cubeMinX, cubeMinY, cubeMaxZ), new Point3f(cubeMaxX, cubeMinY, cubeMaxZ),
            new Point3f(cubeMinX, cubeMaxY, cubeMaxZ), new Point3f(cubeMaxX, cubeMaxY, cubeMaxZ)
        };
        
        for (Point3f corner : corners) {
            if (!isPointInCylinder(corner, cylinderBase, cylinderTop, cylinderRadius)) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Test if a point is within a cylinder
     */
    private static boolean isPointInCylinder(Point3f point, Point3f cylinderBase, Point3f cylinderTop, float cylinderRadius) {
        // Vector from base to top
        float cylDx = cylinderTop.x - cylinderBase.x;
        float cylDy = cylinderTop.y - cylinderBase.y;
        float cylDz = cylinderTop.z - cylinderBase.z;
        float cylLength = (float) Math.sqrt(cylDx * cylDx + cylDy * cylDy + cylDz * cylDz);
        
        if (cylLength < 1e-6f) {
            return false; // Degenerate cylinder
        }
        
        // Normalize cylinder direction
        cylDx /= cylLength;
        cylDy /= cylLength;
        cylDz /= cylLength;
        
        // Vector from base to point
        float pointDx = point.x - cylinderBase.x;
        float pointDy = point.y - cylinderBase.y;
        float pointDz = point.z - cylinderBase.z;
        
        // Project point onto cylinder axis
        float axisProjection = pointDx * cylDx + pointDy * cylDy + pointDz * cylDz;
        
        // Check if point is between base and top
        if (axisProjection < 0 || axisProjection > cylLength) {
            return false;
        }
        
        // Calculate radial distance from cylinder axis
        float projX = cylinderBase.x + axisProjection * cylDx;
        float projY = cylinderBase.y + axisProjection * cylDy;
        float projZ = cylinderBase.z + axisProjection * cylDz;
        
        float radialDx = point.x - projX;
        float radialDy = point.y - projY;
        float radialDz = point.z - projZ;
        float radialDistance = (float) Math.sqrt(radialDx * radialDx + radialDy * radialDy + radialDz * radialDz);
        
        return radialDistance <= cylinderRadius;
    }

    /**
     * Test if a cube partially intersects with a sphere (for statistics)
     */
    private static boolean cubePartiallyIntersectsSphere(Spatial.Cube cube, Point3f sphereCenter, float sphereRadius) {
        // Calculate squared distance from sphere center to closest point on cube
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();
        
        float dx = Math.max(0, Math.max(cubeMinX - sphereCenter.x, sphereCenter.x - cubeMaxX));
        float dy = Math.max(0, Math.max(cubeMinY - sphereCenter.y, sphereCenter.y - cubeMaxY));
        float dz = Math.max(0, Math.max(cubeMinZ - sphereCenter.z, sphereCenter.z - cubeMaxZ));
        
        float distanceSquared = dx * dx + dy * dy + dz * dz;
        
        // Intersects but not completely contained
        return distanceSquared <= sphereRadius * sphereRadius && 
               !isCubeCompletelyInSphere(cube, sphereCenter, sphereRadius);
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