package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Proximity search implementation for Octree
 * Finds octree cubes based on distance relationships and proximity criteria
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class ProximitySearch {

    /**
     * Proximity result with detailed distance information
     */
    public static class ProximityResult<Content> {
        public final long index;
        public final Content content;
        public final Spatial.Cube cube;
        public final Point3f cubeCenter;
        public final float distanceToQuery;
        public final float minDistanceToQuery; // minimum distance from query point to cube surface
        public final float maxDistanceToQuery; // maximum distance from query point to cube corner
        public final ProximityType proximityType;

        public ProximityResult(long index, Content content, Spatial.Cube cube, Point3f cubeCenter,
                             float distanceToQuery, float minDistanceToQuery, float maxDistanceToQuery,
                             ProximityType proximityType) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.cubeCenter = cubeCenter;
            this.distanceToQuery = distanceToQuery;
            this.minDistanceToQuery = minDistanceToQuery;
            this.maxDistanceToQuery = maxDistanceToQuery;
            this.proximityType = proximityType;
        }
    }

    /**
     * Type of proximity relationship
     */
    public enum ProximityType {
        VERY_CLOSE,    // Within very close range
        CLOSE,         // Within close range
        MODERATE,      // Within moderate range
        FAR,           // Within far range
        VERY_FAR       // Beyond far range (typically not returned in proximity results)
    }

    /**
     * Distance range specification for proximity queries
     */
    public static class DistanceRange {
        public final float minDistance;
        public final float maxDistance;
        public final ProximityType proximityType;
        
        public DistanceRange(float minDistance, float maxDistance, ProximityType proximityType) {
            if (minDistance < 0 || maxDistance < 0) {
                throw new IllegalArgumentException("Distances must be non-negative");
            }
            if (maxDistance < minDistance) {
                throw new IllegalArgumentException("Max distance must be >= min distance");
            }
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
            this.proximityType = proximityType;
        }
        
        public boolean contains(float distance) {
            return distance >= minDistance && distance <= maxDistance;
        }
        
        @Override
        public String toString() {
            return String.format("DistanceRange[%.2f-%.2f, %s]", minDistance, maxDistance, proximityType);
        }
    }

    /**
     * Find cubes within a specific distance range from a query point
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRange the distance range to search within
     * @param octree the octree to search in
     * @return list of cubes within the distance range, sorted by distance
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> List<ProximityResult<Content>> cubesWithinDistanceRange(
            Point3f queryPoint, DistanceRange distanceRange, Octree<Content> octree) {
        
        validatePositiveCoordinates(queryPoint, "queryPoint");
        
        // Create bounding AABB for the distance range
        var boundingAABB = new Spatial.aabb(
            queryPoint.x - distanceRange.maxDistance, queryPoint.y - distanceRange.maxDistance, queryPoint.z - distanceRange.maxDistance,
            queryPoint.x + distanceRange.maxDistance, queryPoint.y + distanceRange.maxDistance, queryPoint.z + distanceRange.maxDistance
        );
        
        // Use Octree's efficient bounding method which uses Morton curve ranges
        return octree.bounding(boundingAABB)
            .map(hex -> {
                var cube = hex.toCube();
                Point3f cubeCenter = getCubeCenter(cube);
                
                float centerDistance = calculateDistance(queryPoint, cubeCenter);
                float minDistance = calculateMinDistanceToBox(queryPoint, cube);
                float maxDistance = calculateMaxDistanceToBox(queryPoint, cube);
                
                // Check if the cube overlaps with the distance range
                if (minDistance <= distanceRange.maxDistance && maxDistance >= distanceRange.minDistance) {
                    return new ProximityResult<>(
                        hex.index(),
                        hex.cell(),
                        cube,
                        cubeCenter,
                        centerDistance,
                        minDistance,
                        maxDistance,
                        distanceRange.proximityType
                    );
                }
                return null;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(pr -> pr.distanceToQuery))
            .toList();
    }
    
    /**
     * Find cubes within a specific distance range from a query point using adapter
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRange the distance range to search within
     * @param adapter the adapter to search in
     * @return list of cubes within the distance range, sorted by distance
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> List<ProximityResult<Content>> cubesWithinDistanceRange(
            Point3f queryPoint, DistanceRange distanceRange, SingleContentAdapter<Content> adapter) {
        
        validatePositiveCoordinates(queryPoint, "queryPoint");
        
        // Create bounding AABB for the distance range
        var boundingAABB = new Spatial.aabb(
            queryPoint.x - distanceRange.maxDistance, queryPoint.y - distanceRange.maxDistance, queryPoint.z - distanceRange.maxDistance,
            queryPoint.x + distanceRange.maxDistance, queryPoint.y + distanceRange.maxDistance, queryPoint.z + distanceRange.maxDistance
        );
        
        // Use adapter's efficient bounding method which uses Morton curve ranges
        return adapter.bounding(boundingAABB)
            .map(hex -> {
                var cube = hex.toCube();
                Point3f cubeCenter = getCubeCenter(cube);
                
                float centerDistance = calculateDistance(queryPoint, cubeCenter);
                float minDistance = calculateMinDistanceToBox(queryPoint, cube);
                float maxDistance = calculateMaxDistanceToBox(queryPoint, cube);
                
                // Check if the cube overlaps with the distance range
                if (minDistance <= distanceRange.maxDistance && maxDistance >= distanceRange.minDistance) {
                    return new ProximityResult<>(
                        hex.index(),
                        hex.cell(),
                        cube,
                        cubeCenter,
                        centerDistance,
                        minDistance,
                        maxDistance,
                        distanceRange.proximityType
                    );
                }
                return null;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(pr -> pr.distanceToQuery))
            .toList();
    }

    /**
     * Find cubes within multiple distance ranges (proximity bands)
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRanges list of distance ranges to search within
     * @param octree the octree to search in
     * @return map of distance ranges to their proximity results
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> Map<DistanceRange, List<ProximityResult<Content>>> cubesInProximityBands(
            Point3f queryPoint, List<DistanceRange> distanceRanges, Octree<Content> octree) {
        
        validatePositiveCoordinates(queryPoint, "queryPoint");
        
        Map<DistanceRange, List<ProximityResult<Content>>> results = new LinkedHashMap<>();
        
        for (DistanceRange range : distanceRanges) {
            List<ProximityResult<Content>> rangeResults = cubesWithinDistanceRange(queryPoint, range, octree);
            results.put(range, rangeResults);
        }
        
        return results;
    }

    /**
     * Find the N closest cubes to a query point
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param n number of closest cubes to find
     * @param octree the octree to search in
     * @return list of the N closest cubes, sorted by distance
     * @throws IllegalArgumentException if query point has negative coordinates or n is non-positive
     */
    public static <Content> List<ProximityResult<Content>> findNClosestCubes(
            Point3f queryPoint, int n, Octree<Content> octree) {
        
        validatePositiveCoordinates(queryPoint, "queryPoint");
        
        if (n <= 0) {
            throw new IllegalArgumentException("N must be positive, got: " + n);
        }
        
        if (octree.getMap().isEmpty()) {
            return Collections.emptyList();
        }

        // Use expanding search radius approach
        float searchRadius = 100; // Start with small radius
        List<ProximityResult<Content>> results;
        
        do {
            var boundingAABB = new Spatial.aabb(
                Math.max(0, queryPoint.x - searchRadius), 
                Math.max(0, queryPoint.y - searchRadius), 
                Math.max(0, queryPoint.z - searchRadius),
                queryPoint.x + searchRadius, 
                queryPoint.y + searchRadius, 
                queryPoint.z + searchRadius
            );
            
            results = octree.bounding(boundingAABB)
                .map(hex -> {
                    var cube = hex.toCube();
                    Point3f cubeCenter = getCubeCenter(cube);
                    
                    float centerDistance = calculateDistance(queryPoint, cubeCenter);
                    float minDistance = calculateMinDistanceToBox(queryPoint, cube);
                    float maxDistance = calculateMaxDistanceToBox(queryPoint, cube);
                    
                    ProximityType proximityType = classifyDistance(centerDistance);
                    
                    return new ProximityResult<>(
                        hex.index(),
                        hex.cell(),
                        cube,
                        cubeCenter,
                        centerDistance,
                        minDistance,
                        maxDistance,
                        proximityType
                    );
                })
                .sorted(Comparator.comparing(pr -> pr.distanceToQuery))
                .limit(n)
                .toList();
            
            // Double search radius if we haven't found enough
            if (results.size() < n) {
                searchRadius *= 2;
            }
        } while (results.size() < n && searchRadius < Float.MAX_VALUE / 2);

        return results;
    }

    /**
     * Find cubes within a specific distance from multiple query points
     * 
     * @param queryPoints list of reference points (positive coordinates only)
     * @param maxDistance maximum distance from any query point
     * @param octree the octree to search in
     * @return list of cubes within distance of any query point, with distances to closest query point
     * @throws IllegalArgumentException if any query point has negative coordinates
     */
    public static <Content> List<ProximityResult<Content>> cubesNearAnyPoint(
            List<Point3f> queryPoints, float maxDistance, Octree<Content> octree) {
        
        for (Point3f point : queryPoints) {
            validatePositiveCoordinates(point, "queryPoint");
        }
        
        if (maxDistance < 0) {
            throw new IllegalArgumentException("Max distance must be non-negative, got: " + maxDistance);
        }
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<ProximityResult<Content>> results = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            Point3f cubeCenter = getCubeCenter(cube);
            
            float closestDistance = Float.MAX_VALUE;
            Point3f closestQueryPoint = null;
            
            // Find closest query point to this cube
            for (Point3f queryPoint : queryPoints) {
                float distance = calculateMinDistanceToBox(queryPoint, cube);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestQueryPoint = queryPoint;
                }
            }
            
            if (closestDistance <= maxDistance && closestQueryPoint != null) {
                float centerDistance = calculateDistance(closestQueryPoint, cubeCenter);
                float maxDistanceToQuery = calculateMaxDistanceToBox(closestQueryPoint, cube);
                ProximityType proximityType = classifyDistance(centerDistance);
                
                ProximityResult<Content> result = new ProximityResult<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    cubeCenter,
                    centerDistance,
                    closestDistance,
                    maxDistanceToQuery,
                    proximityType
                );
                results.add(result);
            }
        }

        // Sort by closest distance
        results.sort(Comparator.comparing(pr -> pr.minDistanceToQuery));
        
        return results;
    }

    /**
     * Find cubes that are within distance of all specified query points
     * 
     * @param queryPoints list of reference points (positive coordinates only)
     * @param maxDistance maximum distance from each query point
     * @param octree the octree to search in
     * @return list of cubes within distance of all query points
     * @throws IllegalArgumentException if any query point has negative coordinates
     */
    public static <Content> List<ProximityResult<Content>> cubesNearAllPoints(
            List<Point3f> queryPoints, float maxDistance, Octree<Content> octree) {
        
        for (Point3f point : queryPoints) {
            validatePositiveCoordinates(point, "queryPoint");
        }
        
        if (maxDistance < 0) {
            throw new IllegalArgumentException("Max distance must be non-negative, got: " + maxDistance);
        }
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<ProximityResult<Content>> results = new ArrayList<>();
        
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            Point3f cubeCenter = getCubeCenter(cube);
            
            boolean withinDistanceOfAll = true;
            float maxDistanceToAnyPoint = 0.0f;
            
            // Check if cube is within distance of all query points
            for (Point3f queryPoint : queryPoints) {
                float distance = calculateMinDistanceToBox(queryPoint, cube);
                if (distance > maxDistance) {
                    withinDistanceOfAll = false;
                    break;
                }
                maxDistanceToAnyPoint = Math.max(maxDistanceToAnyPoint, distance);
            }
            
            if (withinDistanceOfAll && !queryPoints.isEmpty()) {
                // Use first query point for primary distance calculation
                Point3f primaryQuery = queryPoints.get(0);
                float centerDistance = calculateDistance(primaryQuery, cubeCenter);
                float minDistanceToQuery = calculateMinDistanceToBox(primaryQuery, cube);
                float maxDistanceToQuery = calculateMaxDistanceToBox(primaryQuery, cube);
                ProximityType proximityType = classifyDistance(centerDistance);
                
                ProximityResult<Content> result = new ProximityResult<>(
                    entry.getKey(), 
                    entry.getValue(), 
                    cube, 
                    cubeCenter,
                    centerDistance,
                    minDistanceToQuery,
                    maxDistanceToQuery,
                    proximityType
                );
                results.add(result);
            }
        }

        // Sort by distance to primary query point
        results.sort(Comparator.comparing(pr -> pr.distanceToQuery));
        
        return results;
    }

    /**
     * Get proximity statistics for cubes relative to a query point
     * 
     * @param queryPoint the reference point (positive coordinates only)
     * @param octree the octree to search in
     * @return statistics about cube proximity distribution
     * @throws IllegalArgumentException if query point has negative coordinates
     */
    public static <Content> ProximityStatistics getProximityStatistics(
            Point3f queryPoint, Octree<Content> octree) {
        
        validatePositiveCoordinates(queryPoint, "queryPoint");
        
        Map<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return new ProximityStatistics(0, 0, 0, 0, 0, 0.0f, 0.0f, 0.0f);
        }

        long totalCubes = 0;
        long veryCloseCubes = 0;
        long closeCubes = 0;
        long moderateCubes = 0;
        long farCubes = 0;
        float totalDistance = 0.0f;
        float minDistance = Float.MAX_VALUE;
        float maxDistance = Float.MIN_VALUE;

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            totalCubes++;
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            Point3f cubeCenter = getCubeCenter(cube);
            float distance = calculateDistance(queryPoint, cubeCenter);
            
            totalDistance += distance;
            minDistance = Math.min(minDistance, distance);
            maxDistance = Math.max(maxDistance, distance);
            
            ProximityType proximityType = classifyDistance(distance);
            switch (proximityType) {
                case VERY_CLOSE -> veryCloseCubes++;
                case CLOSE -> closeCubes++;
                case MODERATE -> moderateCubes++;
                case FAR, VERY_FAR -> farCubes++;
            }
        }

        float averageDistance = totalCubes > 0 ? totalDistance / totalCubes : 0.0f;

        return new ProximityStatistics(totalCubes, veryCloseCubes, closeCubes, moderateCubes, farCubes,
                                     averageDistance, minDistance, maxDistance);
    }

    /**
     * Statistics about proximity distribution
     */
    public static class ProximityStatistics {
        public final long totalCubes;
        public final long veryCloseCubes;
        public final long closeCubes;
        public final long moderateCubes;
        public final long farCubes;
        public final float averageDistance;
        public final float minDistance;
        public final float maxDistance;
        
        public ProximityStatistics(long totalCubes, long veryCloseCubes, long closeCubes, 
                                 long moderateCubes, long farCubes, float averageDistance,
                                 float minDistance, float maxDistance) {
            this.totalCubes = totalCubes;
            this.veryCloseCubes = veryCloseCubes;
            this.closeCubes = closeCubes;
            this.moderateCubes = moderateCubes;
            this.farCubes = farCubes;
            this.averageDistance = averageDistance;
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
        }
        
        public double getVeryClosePercentage() {
            return totalCubes > 0 ? (double) veryCloseCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getClosePercentage() {
            return totalCubes > 0 ? (double) closeCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getModeratePercentage() {
            return totalCubes > 0 ? (double) moderateCubes / totalCubes * 100.0 : 0.0;
        }
        
        public double getFarPercentage() {
            return totalCubes > 0 ? (double) farCubes / totalCubes * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("ProximityStats[total=%d, very_close=%d(%.1f%%), close=%d(%.1f%%), moderate=%d(%.1f%%), far=%d(%.1f%%), avg_dist=%.2f, range=[%.2f-%.2f]]",
                               totalCubes, veryCloseCubes, getVeryClosePercentage(), 
                               closeCubes, getClosePercentage(), moderateCubes, getModeratePercentage(),
                               farCubes, getFarPercentage(), averageDistance, minDistance, maxDistance);
        }
    }

    /**
     * Classify distance into proximity types
     */
    private static ProximityType classifyDistance(float distance) {
        if (distance < 100.0f) {
            return ProximityType.VERY_CLOSE;
        } else if (distance < 500.0f) {
            return ProximityType.CLOSE;
        } else if (distance < 1000.0f) {
            return ProximityType.MODERATE;
        } else if (distance < 5000.0f) {
            return ProximityType.FAR;
        } else {
            return ProximityType.VERY_FAR;
        }
    }

    /**
     * Calculate minimum distance from a point to a cube surface
     */
    private static float calculateMinDistanceToBox(Point3f point, Spatial.Cube cube) {
        float dx = Math.max(0, Math.max(cube.originX() - point.x, 
                                       point.x - (cube.originX() + cube.extent())));
        float dy = Math.max(0, Math.max(cube.originY() - point.y, 
                                       point.y - (cube.originY() + cube.extent())));
        float dz = Math.max(0, Math.max(cube.originZ() - point.z, 
                                       point.z - (cube.originZ() + cube.extent())));
        
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate maximum distance from a point to a cube corner
     */
    private static float calculateMaxDistanceToBox(Point3f point, Spatial.Cube cube) {
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();
        
        float maxDx = Math.max(Math.abs(cubeMinX - point.x), Math.abs(cubeMaxX - point.x));
        float maxDy = Math.max(Math.abs(cubeMinY - point.y), Math.abs(cubeMaxY - point.y));
        float maxDz = Math.max(Math.abs(cubeMinZ - point.z), Math.abs(cubeMaxZ - point.z));
        
        return (float) Math.sqrt(maxDx * maxDx + maxDy * maxDy + maxDz * maxDz);
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