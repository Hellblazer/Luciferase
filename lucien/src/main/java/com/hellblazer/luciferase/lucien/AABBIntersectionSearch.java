package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Axis-Aligned Bounding Box (AABB) intersection search implementation for Octree Finds octree cubes that intersect with
 * 3D AABBs, with results ordered by distance from reference point All operations are constrained to positive
 * coordinates only
 *
 * @author hal.hildebrand
 */
public class AABBIntersectionSearch {

    /**
     * Find all cubes that intersect with the AABB, ordered by distance from reference point
     *
     * @param aabb           the axis-aligned bounding box to test intersection with
     * @param octree         the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections sorted by distance from reference point (closest first)
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> aabbIntersectedAll(AABB aabb, Octree<Content> octree,
                                                                               Point3f referencePoint) {

        validatePositiveCoordinates(referencePoint, "referencePoint");

        // Convert AABB to a Spatial.aabb that Octree understands
        var spatialAABB = new Spatial.aabb(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);

        // Use Octree's efficient bounding method which uses Morton curve ranges
        return octree.bounding(spatialAABB).map(hex -> {
            var cube = hex.toCube();
            var intersectionType = testAABBIntersection(aabb, cube);

            if (intersectionType != IntersectionType.COMPLETELY_OUTSIDE) {
                Point3f cubeCenter = getCubeCenter(cube);
                float distance = calculateDistance(referencePoint, cubeCenter);

                return new AABBIntersection<>(hex.index(), hex.cell(), cube, distance, cubeCenter, intersectionType);
            }
            return null;
        }).filter(Objects::nonNull).sorted(Comparator.comparing(ai -> ai.distanceToReferencePoint)).toList();
    }

    /**
     * Find the first (closest to reference point) cube that intersects with the AABB
     *
     * @param aabb           the axis-aligned bounding box to test intersection with
     * @param octree         the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return the closest intersection, or null if no intersection
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> AABBIntersection<Content> aabbIntersectedFirst(AABB aabb, Octree<Content> octree,
                                                                           Point3f referencePoint) {

        List<AABBIntersection<Content>> intersections = aabbIntersectedAll(aabb, octree, referencePoint);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Batch processing for multiple AABB intersection queries Useful for multiple simultaneous AABB queries
     *
     * @param aabbs          list of AABBs to test
     * @param octree         the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return map of AABBs to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<AABB, List<AABBIntersection<Content>>> batchAABBIntersections(List<AABB> aabbs,
                                                                                              Octree<Content> octree,
                                                                                              Point3f referencePoint) {

        validatePositiveCoordinates(referencePoint, "referencePoint");

        return aabbs.stream().collect(
        Collectors.toMap(aabb -> aabb, aabb -> aabbIntersectedAll(aabb, octree, referencePoint)));
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
     * Count the number of cubes that intersect with the AABB This is more efficient than getting all intersections when
     * only count is needed
     *
     * @param aabb   the axis-aligned bounding box to test intersection with
     * @param octree the octree to search in
     * @return number of cubes intersecting the AABB
     */
    public static <Content> long countAABBIntersections(AABB aabb, Octree<Content> octree) {
        // Convert AABB to a Spatial.aabb that Octree understands
        var spatialAABB = new Spatial.aabb(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);

        // Use efficient bounding stream and count
        return octree.bounding(spatialAABB).filter(hex -> {
            var cube = hex.toCube();
            var intersectionType = testAABBIntersection(aabb, cube);
            return intersectionType != IntersectionType.COMPLETELY_OUTSIDE;
        }).count();
    }

    /**
     * Find cubes that are completely inside the AABB
     *
     * @param aabb           the axis-aligned bounding box to test against
     * @param octree         the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections completely inside AABB, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> cubesCompletelyInside(AABB aabb, Octree<Content> octree,
                                                                                  Point3f referencePoint) {

        return aabbIntersectedAll(aabb, octree, referencePoint).stream().filter(
        ai -> ai.intersectionType == IntersectionType.COMPLETELY_INSIDE).collect(Collectors.toList());
    }

    /**
     * Find cubes that completely contain the AABB
     *
     * @param aabb           the axis-aligned bounding box to test against
     * @param octree         the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections that contain the AABB, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> cubesContainingAABB(AABB aabb, Octree<Content> octree,
                                                                                Point3f referencePoint) {

        return aabbIntersectedAll(aabb, octree, referencePoint).stream().filter(
        ai -> ai.intersectionType == IntersectionType.CONTAINS_AABB).collect(Collectors.toList());
    }

    /**
     * Find cubes that partially intersect the AABB (not completely inside or outside)
     *
     * @param aabb           the axis-aligned bounding box to test against
     * @param octree         the octree to search in
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @return list of intersections partially intersecting AABB, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersection<Content>> cubesPartiallyIntersecting(AABB aabb,
                                                                                       Octree<Content> octree,
                                                                                       Point3f referencePoint) {

        return aabbIntersectedAll(aabb, octree, referencePoint).stream().filter(
        ai -> ai.intersectionType == IntersectionType.INTERSECTING).collect(Collectors.toList());
    }

    /**
     * Get statistics about AABB intersection results
     *
     * @param aabb   the axis-aligned bounding box to test intersection with
     * @param octree the octree to search in
     * @return statistics about intersection results
     */
    public static <Content> IntersectionStatistics getAABBIntersectionStatistics(AABB aabb, Octree<Content> octree) {
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return new IntersectionStatistics(0, 0, 0, 0, 0);
        }

        long totalCubes = 0;
        long insideCubes = 0;
        long intersectingCubes = 0;
        long containingCubes = 0;
        long outsideCubes = 0;

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            totalCubes++;
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            IntersectionType intersectionType = testAABBIntersection(aabb, cube);

            switch (intersectionType) {
                case COMPLETELY_INSIDE -> insideCubes++;
                case INTERSECTING -> intersectingCubes++;
                case CONTAINS_AABB -> containingCubes++;
                case COMPLETELY_OUTSIDE -> outsideCubes++;
            }
        }

        return new IntersectionStatistics(totalCubes, insideCubes, intersectingCubes, containingCubes, outsideCubes);
    }

    /**
     * Calculate the center point of a cube
     */
    private static Point3f getCubeCenter(Spatial.Cube cube) {
        float halfExtent = cube.extent() / 2.0f;
        return new Point3f(cube.originX() + halfExtent, cube.originY() + halfExtent, cube.originZ() + halfExtent);
    }

    /**
     * Test if any cube in the octree intersects with the AABB This is more efficient than getting all intersections
     * when only existence check is needed
     *
     * @param aabb   the axis-aligned bounding box to test intersection with
     * @param octree the octree to search in
     * @return true if any cube intersects the AABB
     */
    public static <Content> boolean hasAnyIntersection(AABB aabb, Octree<Content> octree) {
        // Convert AABB to a Spatial.aabb that Octree understands
        var spatialAABB = new Spatial.aabb(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);

        // Use efficient bounding stream to check existence
        return octree.bounding(spatialAABB).anyMatch(hex -> {
            var cube = hex.toCube();
            var intersectionType = testAABBIntersection(aabb, cube);
            return intersectionType != IntersectionType.COMPLETELY_OUTSIDE;
        });
    }

    /**
     * Test AABB-cube intersection using standard AABB-AABB intersection algorithm
     *
     * @param aabb the axis-aligned bounding box
     * @param cube the cube to test
     * @return intersection type
     */
    private static IntersectionType testAABBIntersection(AABB aabb, Spatial.Cube cube) {
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();

        // Check for no intersection (separating axis test)
        if (cubeMaxX < aabb.minX || cubeMinX > aabb.maxX || cubeMaxY < aabb.minY || cubeMinY > aabb.maxY
        || cubeMaxZ < aabb.minZ || cubeMinZ > aabb.maxZ) {
            return IntersectionType.COMPLETELY_OUTSIDE;
        }

        // Check if cube completely contains AABB
        if (cubeMinX <= aabb.minX && cubeMaxX >= aabb.maxX && cubeMinY <= aabb.minY && cubeMaxY >= aabb.maxY
        && cubeMinZ <= aabb.minZ && cubeMaxZ >= aabb.maxZ) {
            return IntersectionType.CONTAINS_AABB;
        }

        // Check if cube is completely inside AABB
        if (cubeMinX >= aabb.minX && cubeMaxX <= aabb.maxX && cubeMinY >= aabb.minY && cubeMaxY <= aabb.maxY
        && cubeMinZ >= aabb.minZ && cubeMaxZ <= aabb.maxZ) {
            return IntersectionType.COMPLETELY_INSIDE;
        }

        // Cube partially intersects AABB
        return IntersectionType.INTERSECTING;
    }

    /**
     * Validate that all coordinates in a point are positive
     */
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }

    /**
     * Type of intersection between AABB and cube
     */
    public enum IntersectionType {
        COMPLETELY_INSIDE,  // Cube is completely inside AABB
        INTERSECTING,       // Cube partially intersects AABB
        CONTAINS_AABB,      // Cube completely contains AABB
        COMPLETELY_OUTSIDE  // Cube is completely outside AABB (not returned in intersection results)
    }

    /**
     * AABB intersection result with distance information
     */
    public static class AABBIntersection<Content> {
        public final long             index;
        public final Content          content;
        public final Spatial.Cube     cube;
        public final float            distanceToReferencePoint;
        public final Point3f          cubeCenter;
        public final IntersectionType intersectionType;

        public AABBIntersection(long index, Content content, Spatial.Cube cube, float distanceToReferencePoint,
                                Point3f cubeCenter, IntersectionType intersectionType) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.cubeCenter = cubeCenter;
            this.intersectionType = intersectionType;
        }
    }

    /**
     * Represents an axis-aligned bounding box with positive coordinates
     */
    public static class AABB {
        public final float minX, minY, minZ;
        public final float maxX, maxY, maxZ;

        public AABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            if (minX < 0 || minY < 0 || minZ < 0 || maxX < 0 || maxY < 0 || maxZ < 0) {
                throw new IllegalArgumentException("All AABB coordinates must be positive");
            }
            if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
                throw new IllegalArgumentException("Max coordinates must be greater than min coordinates");
            }

            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        /**
         * Create AABB from center point and half-extents
         */
        public static AABB fromCenterAndHalfExtents(Point3f center, float halfWidth, float halfHeight,
                                                    float halfDepth) {
            validatePositiveCoordinates(center, "center");
            if (halfWidth <= 0 || halfHeight <= 0 || halfDepth <= 0) {
                throw new IllegalArgumentException("Half-extents must be positive");
            }

            return new AABB(center.x - halfWidth, center.y - halfHeight, center.z - halfDepth, center.x + halfWidth,
                            center.y + halfHeight, center.z + halfDepth);
        }

        /**
         * Create AABB from two corner points
         */
        public static AABB fromCorners(Point3f corner1, Point3f corner2) {
            validatePositiveCoordinates(corner1, "corner1");
            validatePositiveCoordinates(corner2, "corner2");

            float minX = Math.min(corner1.x, corner2.x);
            float minY = Math.min(corner1.y, corner2.y);
            float minZ = Math.min(corner1.z, corner2.z);
            float maxX = Math.max(corner1.x, corner2.x);
            float maxY = Math.max(corner1.y, corner2.y);
            float maxZ = Math.max(corner1.z, corner2.z);

            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof final AABB other)) {
                return false;
            }
            return Float.compare(minX, other.minX) == 0 && Float.compare(minY, other.minY) == 0 && Float.compare(minZ,
                                                                                                                 other.minZ)
            == 0 && Float.compare(maxX, other.maxX) == 0 && Float.compare(maxY, other.maxY) == 0 && Float.compare(maxZ,
                                                                                                                  other.maxZ)
            == 0;
        }

        public Point3f getCenter() {
            return new Point3f((minX + maxX) / 2.0f, (minY + maxY) / 2.0f, (minZ + maxZ) / 2.0f);
        }

        public float getDepth() {
            return maxZ - minZ;
        }

        public float getHeight() {
            return maxY - minY;
        }

        public float getVolume() {
            return getWidth() * getHeight() * getDepth();
        }

        public float getWidth() {
            return maxX - minX;
        }

        @Override
        public int hashCode() {
            return Objects.hash(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public String toString() {
            return String.format("AABB[min=(%.2f,%.2f,%.2f), max=(%.2f,%.2f,%.2f)]", minX, minY, minZ, maxX, maxY,
                                 maxZ);
        }
    }

    /**
     * Statistics about AABB intersection results
     */
    public static class IntersectionStatistics {
        public final long totalCubes;
        public final long insideCubes;
        public final long intersectingCubes;
        public final long containingCubes;
        public final long outsideCubes;

        public IntersectionStatistics(long totalCubes, long insideCubes, long intersectingCubes, long containingCubes,
                                      long outsideCubes) {
            this.totalCubes = totalCubes;
            this.insideCubes = insideCubes;
            this.intersectingCubes = intersectingCubes;
            this.containingCubes = containingCubes;
            this.outsideCubes = outsideCubes;
        }

        public double getContainingPercentage() {
            return totalCubes > 0 ? (double) containingCubes / totalCubes * 100.0 : 0.0;
        }

        public double getInsidePercentage() {
            return totalCubes > 0 ? (double) insideCubes / totalCubes * 100.0 : 0.0;
        }

        public double getIntersectedPercentage() {
            return totalCubes > 0 ? (double) (insideCubes + intersectingCubes + containingCubes) / totalCubes * 100.0
                                  : 0.0;
        }

        public double getIntersectingPercentage() {
            return totalCubes > 0 ? (double) intersectingCubes / totalCubes * 100.0 : 0.0;
        }

        public double getOutsidePercentage() {
            return totalCubes > 0 ? (double) outsideCubes / totalCubes * 100.0 : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
            "AABBIntersectionStats[total=%d, inside=%d(%.1f%%), intersecting=%d(%.1f%%), containing=%d(%.1f%%), outside=%d(%.1f%%), intersected=%.1f%%]",
            totalCubes, insideCubes, getInsidePercentage(), intersectingCubes, getIntersectingPercentage(),
            containingCubes, getContainingPercentage(), outsideCubes, getOutsidePercentage(),
            getIntersectedPercentage());
        }
    }
}
