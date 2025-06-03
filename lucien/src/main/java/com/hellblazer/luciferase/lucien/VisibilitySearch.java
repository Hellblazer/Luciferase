package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Visibility search implementation for Octree
 * Performs line-of-sight tests, occlusion queries, and visibility analysis
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class VisibilitySearch {

    /**
     * Visibility result with detailed occlusion information
     */
    public static class VisibilityResult<Content> {
        public final long index;
        public final Content content;
        public final Spatial.Cube cube;
        public final Point3f cubeCenter;
        public final float distanceFromObserver;
        public final float distanceFromTarget;
        public final VisibilityType visibilityType;
        public final float occlusionRatio; // percentage of line-of-sight blocked

        public VisibilityResult(long index, Content content, Spatial.Cube cube, Point3f cubeCenter,
                              float distanceFromObserver, float distanceFromTarget, 
                              VisibilityType visibilityType, float occlusionRatio) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.cubeCenter = cubeCenter;
            this.distanceFromObserver = distanceFromObserver;
            this.distanceFromTarget = distanceFromTarget;
            this.visibilityType = visibilityType;
            this.occlusionRatio = occlusionRatio;
        }
    }

    /**
     * Line-of-sight test result
     */
    public static class LineOfSightResult<Content> {
        public final boolean hasLineOfSight;
        public final List<VisibilityResult<Content>> occludingCubes;
        public final float totalOcclusionRatio;
        public final float distanceThroughOccluders;

        public LineOfSightResult(boolean hasLineOfSight, List<VisibilityResult<Content>> occludingCubes,
                               float totalOcclusionRatio, float distanceThroughOccluders) {
            this.hasLineOfSight = hasLineOfSight;
            this.occludingCubes = occludingCubes;
            this.totalOcclusionRatio = totalOcclusionRatio;
            this.distanceThroughOccluders = distanceThroughOccluders;
        }
    }

    /**
     * Type of visibility relationship
     */
    public enum VisibilityType {
        VISIBLE,           // Not blocking line of sight
        PARTIALLY_OCCLUDING, // Partially blocking line of sight
        FULLY_OCCLUDING,   // Completely blocking line of sight
        BEHIND_TARGET,     // Behind the target (not relevant for occlusion)
        BEFORE_OBSERVER    // In front of observer but not blocking target
    }

    /**
     * Test line of sight between two points
     * 
     * @param observer the observer position (positive coordinates only)
     * @param target the target position (positive coordinates only)
     * @param octree the octree to search for occluders
     * @param occlusionThreshold minimum cube size to consider as occluder
     * @return line of sight test result with occluding cubes
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static <Content> LineOfSightResult<Content> testLineOfSight(
            Point3f observer, Point3f target, Octree<Content> octree, float occlusionThreshold) {
        
        validatePositiveCoordinates(observer, "observer");
        validatePositiveCoordinates(target, "target");
        
        if (occlusionThreshold < 0) {
            throw new IllegalArgumentException("Occlusion threshold must be non-negative, got: " + occlusionThreshold);
        }
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return new LineOfSightResult<>(true, Collections.emptyList(), 0.0f, 0.0f);
        }

        // Create ray from observer to target
        Vector3f direction = new Vector3f(target.x - observer.x, target.y - observer.y, target.z - observer.z);
        float totalDistance = direction.length();
        if (totalDistance < 1e-6f) {
            return new LineOfSightResult<>(true, Collections.emptyList(), 0.0f, 0.0f); // Same point
        }
        direction.normalize();
        
        Ray3D sightRay = new Ray3D(observer, direction);
        List<VisibilityResult<Content>> occludingCubes = new ArrayList<>();
        float totalOcclusionRatio = 0.0f;
        float distanceThroughOccluders = 0.0f;

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            
            // Skip cubes that are too small to be significant occluders
            if (cube.extent() < occlusionThreshold) {
                continue;
            }
            
            Point3f cubeCenter = getCubeCenter(cube);
            float distanceFromObserver = calculateDistance(observer, cubeCenter);
            float distanceFromTarget = calculateDistance(target, cubeCenter);
            
            // Check if cube intersects the line of sight
            RayIntersection intersection = rayBoxIntersection(sightRay, cube);
            if (intersection.intersects && intersection.distance < totalDistance) {
                VisibilityType visibilityType = classifyVisibility(cube, observer, target, intersection.distance, totalDistance);
                float occlusionRatio = calculateOcclusionRatio(cube, sightRay, intersection.distance, totalDistance);
                
                if (visibilityType == VisibilityType.PARTIALLY_OCCLUDING || visibilityType == VisibilityType.FULLY_OCCLUDING) {
                    VisibilityResult<Content> result = new VisibilityResult<>(
                        entry.getKey(), 
                        entry.getValue(), 
                        cube, 
                        cubeCenter,
                        distanceFromObserver,
                        distanceFromTarget,
                        visibilityType,
                        occlusionRatio
                    );
                    occludingCubes.add(result);
                    totalOcclusionRatio += occlusionRatio;
                    distanceThroughOccluders += Math.min(cube.extent(), totalDistance - intersection.distance);
                }
            }
        }

        // Sort occluding cubes by distance from observer
        occludingCubes.sort(Comparator.comparing(vr -> vr.distanceFromObserver));
        
        // Determine if line of sight is blocked (threshold can be adjusted)
        boolean hasLineOfSight = totalOcclusionRatio < 0.5f; // 50% occlusion threshold
        
        return new LineOfSightResult<>(hasLineOfSight, occludingCubes, totalOcclusionRatio, distanceThroughOccluders);
    }

    /**
     * Find all cubes visible from an observer position within a viewing cone
     * 
     * @param observer the observer position (positive coordinates only)
     * @param viewDirection the viewing direction (will be normalized)
     * @param viewAngle the viewing angle in radians (cone half-angle)
     * @param maxViewDistance maximum viewing distance
     * @param octree the octree to search in
     * @return list of visible cubes sorted by distance
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static <Content> List<VisibilityResult<Content>> findVisibleCubes(
            Point3f observer, Vector3f viewDirection, float viewAngle, float maxViewDistance, 
            Octree<Content> octree) {
        
        validatePositiveCoordinates(observer, "observer");
        
        if (viewAngle < 0 || viewAngle > Math.PI) {
            throw new IllegalArgumentException("View angle must be between 0 and π radians");
        }
        
        if (maxViewDistance < 0) {
            throw new IllegalArgumentException("Max view distance must be non-negative");
        }
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        Vector3f normalizedDirection = new Vector3f(viewDirection);
        normalizedDirection.normalize();
        
        List<VisibilityResult<Content>> visibleCubes = new ArrayList<>();

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            Point3f cubeCenter = getCubeCenter(cube);
            
            float distanceFromObserver = calculateDistance(observer, cubeCenter);
            
            // Check if cube is within viewing distance
            if (distanceFromObserver > maxViewDistance) {
                continue;
            }
            
            // Check if cube is within viewing cone
            Vector3f toCube = new Vector3f(cubeCenter.x - observer.x, cubeCenter.y - observer.y, cubeCenter.z - observer.z);
            toCube.normalize();
            
            float angle = normalizedDirection.angle(toCube);
            if (angle <= viewAngle) {
                // Cube is within viewing cone - perform basic occlusion test
                boolean isVisible = isObjectVisible(observer, cubeCenter, octree, cube.extent());
                
                if (isVisible) {
                    VisibilityResult<Content> result = new VisibilityResult<>(
                        entry.getKey(), 
                        entry.getValue(), 
                        cube, 
                        cubeCenter,
                        distanceFromObserver,
                        0.0f, // Not applicable for this query
                        VisibilityType.VISIBLE,
                        0.0f
                    );
                    visibleCubes.add(result);
                }
            }
        }

        // Sort by distance from observer
        visibleCubes.sort(Comparator.comparing(vr -> vr.distanceFromObserver));
        
        return visibleCubes;
    }

    /**
     * Calculate visibility statistics from an observer position
     * 
     * @param observer the observer position (positive coordinates only)
     * @param maxViewDistance maximum viewing distance for analysis
     * @param octree the octree to analyze
     * @return statistics about visibility from the observer position
     * @throws IllegalArgumentException if observer coordinates are negative
     */
    public static <Content> VisibilityStatistics calculateVisibilityStatistics(
            Point3f observer, float maxViewDistance, Octree<Content> octree) {
        
        validatePositiveCoordinates(observer, "observer");
        
        if (maxViewDistance < 0) {
            throw new IllegalArgumentException("Max view distance must be non-negative");
        }
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.isEmpty()) {
            return new VisibilityStatistics(0, 0, 0, 0, 0, 0.0f, 0.0f);
        }

        long totalCubes = 0;
        long visibleCubes = 0;
        long partiallyOccludedCubes = 0;
        long fullyOccludedCubes = 0;
        long cubesOutOfRange = 0;
        float totalVisibilityRatio = 0.0f;

        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            totalCubes++;
            Spatial.Cube cube = Octree.toCube(entry.getKey());
            Point3f cubeCenter = getCubeCenter(cube);
            
            float distance = calculateDistance(observer, cubeCenter);
            if (distance > maxViewDistance) {
                cubesOutOfRange++;
                continue;
            }
            
            // Simplified visibility test
            LineOfSightResult<Content> losResult = testLineOfSight(observer, cubeCenter, octree, cube.extent() * 0.1f);
            
            if (losResult.hasLineOfSight) {
                visibleCubes++;
                totalVisibilityRatio += (1.0f - losResult.totalOcclusionRatio);
            } else if (losResult.totalOcclusionRatio < 1.0f) {
                partiallyOccludedCubes++;
                totalVisibilityRatio += (1.0f - losResult.totalOcclusionRatio);
            } else {
                fullyOccludedCubes++;
            }
        }

        float averageVisibilityRatio = (visibleCubes + partiallyOccludedCubes) > 0 ? 
            totalVisibilityRatio / (visibleCubes + partiallyOccludedCubes) : 0.0f;

        return new VisibilityStatistics(totalCubes, visibleCubes, partiallyOccludedCubes, 
                                      fullyOccludedCubes, cubesOutOfRange, totalVisibilityRatio, averageVisibilityRatio);
    }

    /**
     * Statistics about visibility from an observer position
     */
    public static class VisibilityStatistics {
        public final long totalCubes;
        public final long visibleCubes;
        public final long partiallyOccludedCubes;
        public final long fullyOccludedCubes;
        public final long cubesOutOfRange;
        public final float totalVisibilityRatio;
        public final float averageVisibilityRatio;
        
        public VisibilityStatistics(long totalCubes, long visibleCubes, long partiallyOccludedCubes,
                                  long fullyOccludedCubes, long cubesOutOfRange, 
                                  float totalVisibilityRatio, float averageVisibilityRatio) {
            this.totalCubes = totalCubes;
            this.visibleCubes = visibleCubes;
            this.partiallyOccludedCubes = partiallyOccludedCubes;
            this.fullyOccludedCubes = fullyOccludedCubes;
            this.cubesOutOfRange = cubesOutOfRange;
            this.totalVisibilityRatio = totalVisibilityRatio;
            this.averageVisibilityRatio = averageVisibilityRatio;
        }
        
        public double getVisiblePercentage() {
            long inRange = totalCubes - cubesOutOfRange;
            return inRange > 0 ? (double) visibleCubes / inRange * 100.0 : 0.0;
        }
        
        public double getPartiallyOccludedPercentage() {
            long inRange = totalCubes - cubesOutOfRange;
            return inRange > 0 ? (double) partiallyOccludedCubes / inRange * 100.0 : 0.0;
        }
        
        public double getFullyOccludedPercentage() {
            long inRange = totalCubes - cubesOutOfRange;
            return inRange > 0 ? (double) fullyOccludedCubes / inRange * 100.0 : 0.0;
        }
        
        public double getOutOfRangePercentage() {
            return totalCubes > 0 ? (double) cubesOutOfRange / totalCubes * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("VisibilityStats[total=%d, visible=%d(%.1f%%), partial=%d(%.1f%%), occluded=%d(%.1f%%), out_of_range=%d(%.1f%%), avg_visibility=%.3f]",
                               totalCubes, visibleCubes, getVisiblePercentage(), 
                               partiallyOccludedCubes, getPartiallyOccludedPercentage(),
                               fullyOccludedCubes, getFullyOccludedPercentage(),
                               cubesOutOfRange, getOutOfRangePercentage(), averageVisibilityRatio);
        }
    }

    /**
     * Find the best vantage points for observing a target
     * 
     * @param target the target position to observe (positive coordinates only)
     * @param candidatePositions list of candidate observer positions (positive coordinates only)
     * @param octree the octree containing potential occluders
     * @return list of candidate positions sorted by visibility quality
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static <Content> List<VantagePoint> findBestVantagePoints(
            Point3f target, List<Point3f> candidatePositions, Octree<Content> octree) {
        
        validatePositiveCoordinates(target, "target");
        for (Point3f pos : candidatePositions) {
            validatePositiveCoordinates(pos, "candidatePosition");
        }
        
        List<VantagePoint> vantagePoints = new ArrayList<>();
        
        for (Point3f candidate : candidatePositions) {
            LineOfSightResult<Content> losResult = testLineOfSight(candidate, target, octree, 1.0f);
            float distance = calculateDistance(candidate, target);
            float visibilityScore = calculateVisibilityScore(losResult, distance);
            
            VantagePoint vantagePoint = new VantagePoint(candidate, distance, losResult.hasLineOfSight, 
                                                       losResult.totalOcclusionRatio, visibilityScore);
            vantagePoints.add(vantagePoint);
        }
        
        // Sort by visibility score (higher is better)
        vantagePoints.sort((vp1, vp2) -> Float.compare(vp2.visibilityScore, vp1.visibilityScore));
        
        return vantagePoints;
    }

    /**
     * Represents a vantage point for observation
     */
    public static class VantagePoint {
        public final Point3f position;
        public final float distanceToTarget;
        public final boolean hasLineOfSight;
        public final float occlusionRatio;
        public final float visibilityScore;
        
        public VantagePoint(Point3f position, float distanceToTarget, boolean hasLineOfSight,
                          float occlusionRatio, float visibilityScore) {
            this.position = position;
            this.distanceToTarget = distanceToTarget;
            this.hasLineOfSight = hasLineOfSight;
            this.occlusionRatio = occlusionRatio;
            this.visibilityScore = visibilityScore;
        }
        
        @Override
        public String toString() {
            return String.format("VantagePoint[pos=%s, dist=%.2f, los=%s, occlusion=%.3f, score=%.3f]",
                               position, distanceToTarget, hasLineOfSight, occlusionRatio, visibilityScore);
        }
    }

    // Helper methods

    /**
     * Simple visibility test between two points
     */
    private static <Content> boolean isObjectVisible(Point3f observer, Point3f target, 
                                                   Octree<Content> octree, float targetSize) {
        LineOfSightResult<Content> result = testLineOfSight(observer, target, octree, targetSize * 0.5f);
        return result.hasLineOfSight;
    }

    /**
     * Classify the visibility type of a cube relative to observer and target
     */
    private static VisibilityType classifyVisibility(Spatial.Cube cube, Point3f observer, Point3f target,
                                                    float intersectionDistance, float totalDistance) {
        if (intersectionDistance > totalDistance) {
            return VisibilityType.BEHIND_TARGET;
        } else if (intersectionDistance < totalDistance * 0.1f) {
            return VisibilityType.BEFORE_OBSERVER;
        } else {
            // Simplified - would need more sophisticated analysis for partial vs full occlusion
            return cube.extent() > 10.0f ? VisibilityType.FULLY_OCCLUDING : VisibilityType.PARTIALLY_OCCLUDING;
        }
    }

    /**
     * Calculate occlusion ratio based on cube size and position
     */
    private static float calculateOcclusionRatio(Spatial.Cube cube, Ray3D ray, float intersectionDistance, float totalDistance) {
        // Simplified calculation - in practice would depend on cube size relative to angular size from observer
        float relativeSize = cube.extent() / Math.max(intersectionDistance, 1.0f);
        return Math.min(1.0f, relativeSize * 0.1f); // Cap at 100% occlusion
    }

    /**
     * Calculate visibility score for a vantage point
     */
    private static float calculateVisibilityScore(LineOfSightResult<?> losResult, float distance) {
        float visibilityComponent = losResult.hasLineOfSight ? 1.0f : (1.0f - losResult.totalOcclusionRatio);
        float distanceComponent = Math.max(0.1f, 1000.0f / distance); // Closer is better, but not too close
        return visibilityComponent * distanceComponent;
    }

    /**
     * Ray-box intersection for visibility testing
     */
    private static class RayIntersection {
        public final boolean intersects;
        public final float distance;

        public RayIntersection(boolean intersects, float distance) {
            this.intersects = intersects;
            this.distance = distance;
        }
    }

    /**
     * Test ray-box intersection (simplified version)
     */
    private static RayIntersection rayBoxIntersection(Ray3D ray, Spatial.Cube cube) {
        Point3f origin = ray.origin();
        Vector3f direction = ray.direction();
        
        float minX = cube.originX();
        float maxX = cube.originX() + cube.extent();
        float minY = cube.originY();
        float maxY = cube.originY() + cube.extent();
        float minZ = cube.originZ();
        float maxZ = cube.originZ() + cube.extent();

        float tmin = Float.NEGATIVE_INFINITY;
        float tmax = Float.POSITIVE_INFINITY;

        // X slab
        if (Math.abs(direction.x) < 1e-6f) {
            if (origin.x < minX || origin.x > maxX) {
                return new RayIntersection(false, Float.MAX_VALUE);
            }
        } else {
            float invDirX = 1.0f / direction.x;
            float t1 = (minX - origin.x) * invDirX;
            float t2 = (maxX - origin.x) * invDirX;
            
            if (t1 > t2) {
                float temp = t1; t1 = t2; t2 = temp;
            }
            
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            
            if (tmin > tmax) {
                return new RayIntersection(false, Float.MAX_VALUE);
            }
        }

        // Y slab
        if (Math.abs(direction.y) < 1e-6f) {
            if (origin.y < minY || origin.y > maxY) {
                return new RayIntersection(false, Float.MAX_VALUE);
            }
        } else {
            float invDirY = 1.0f / direction.y;
            float t1 = (minY - origin.y) * invDirY;
            float t2 = (maxY - origin.y) * invDirY;
            
            if (t1 > t2) {
                float temp = t1; t1 = t2; t2 = temp;
            }
            
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            
            if (tmin > tmax) {
                return new RayIntersection(false, Float.MAX_VALUE);
            }
        }

        // Z slab
        if (Math.abs(direction.z) < 1e-6f) {
            if (origin.z < minZ || origin.z > maxZ) {
                return new RayIntersection(false, Float.MAX_VALUE);
            }
        } else {
            float invDirZ = 1.0f / direction.z;
            float t1 = (minZ - origin.z) * invDirZ;
            float t2 = (maxZ - origin.z) * invDirZ;
            
            if (t1 > t2) {
                float temp = t1; t1 = t2; t2 = temp;
            }
            
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            
            if (tmin > tmax) {
                return new RayIntersection(false, Float.MAX_VALUE);
            }
        }

        if (tmax < 0) {
            return new RayIntersection(false, Float.MAX_VALUE);
        }

        float t = (tmin >= 0) ? tmin : tmax;
        return new RayIntersection(true, t);
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