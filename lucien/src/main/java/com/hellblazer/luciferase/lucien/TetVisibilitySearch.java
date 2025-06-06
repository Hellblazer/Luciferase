package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Visibility search implementation for Tetree
 * Performs line-of-sight tests, occlusion queries, and visibility analysis in tetrahedral space
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class TetVisibilitySearch extends TetrahedralSearchBase {

    /**
     * Visibility result with detailed occlusion information for tetrahedra
     */
    public static class TetVisibilityResult<Content> {
        public final long index;
        public final Content content;
        public final Tet tetrahedron;
        public final Point3f tetrahedronCenter;
        public final float distanceFromObserver;
        public final float distanceFromTarget;
        public final VisibilityType visibilityType;
        public final float occlusionRatio; // percentage of line-of-sight blocked

        public TetVisibilityResult(long index, Content content, Tet tetrahedron, Point3f tetrahedronCenter,
                                 float distanceFromObserver, float distanceFromTarget, 
                                 VisibilityType visibilityType, float occlusionRatio) {
            this.index = index;
            this.content = content;
            this.tetrahedron = tetrahedron;
            this.tetrahedronCenter = tetrahedronCenter;
            this.distanceFromObserver = distanceFromObserver;
            this.distanceFromTarget = distanceFromTarget;
            this.visibilityType = visibilityType;
            this.occlusionRatio = occlusionRatio;
        }
    }

    /**
     * Line-of-sight test result for tetrahedral space
     */
    public static class TetLineOfSightResult<Content> {
        public final boolean hasLineOfSight;
        public final List<TetVisibilityResult<Content>> occludingTetrahedra;
        public final float totalOcclusionRatio;
        public final float distanceThroughOccluders;

        public TetLineOfSightResult(boolean hasLineOfSight, List<TetVisibilityResult<Content>> occludingTetrahedra,
                                  float totalOcclusionRatio, float distanceThroughOccluders) {
            this.hasLineOfSight = hasLineOfSight;
            this.occludingTetrahedra = occludingTetrahedra;
            this.totalOcclusionRatio = totalOcclusionRatio;
            this.distanceThroughOccluders = distanceThroughOccluders;
        }
    }

    /**
     * Type of visibility relationship
     */
    public enum VisibilityType {
        VISIBLE,             // Not blocking line of sight
        PARTIALLY_OCCLUDING, // Partially blocking line of sight
        FULLY_OCCLUDING,     // Completely blocking line of sight
        BEHIND_TARGET,       // Behind the target (not relevant for occlusion)
        BEFORE_OBSERVER      // In front of observer but not blocking target
    }

    /**
     * Test line of sight between two points through tetrahedral space
     * 
     * @param observer the observer position (positive coordinates only)
     * @param target the target position (positive coordinates only)
     * @param tetree the tetree to search for occluders
     * @param occlusionThreshold minimum tetrahedron volume to consider as occluder
     * @param strategy aggregation strategy for multiple simplicies
     * @return line of sight test result with occluding tetrahedra
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static <Content> TetLineOfSightResult<Content> testLineOfSight(
            Point3f observer, Point3f target, Tetree<Content> tetree, 
            double occlusionThreshold, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(observer);
        validatePositiveCoordinates(target);
        
        if (occlusionThreshold < 0) {
            throw new IllegalArgumentException("Occlusion threshold must be non-negative, got: " + occlusionThreshold);
        }
        
        // Create a line segment from observer to target as spatial volume for query
        float totalDistance = calculateDistance(observer, target);
        if (totalDistance < GEOMETRIC_TOLERANCE) {
            return new TetLineOfSightResult<>(true, Collections.emptyList(), 0.0f, 0.0f);
        }
        
        // Create ray from observer to target
        Vector3f direction = new Vector3f(target.x - observer.x, target.y - observer.y, target.z - observer.z);
        direction.normalize();
        Ray3D sightRay = new Ray3D(observer, direction);
        
        // Use a cylindrical volume around the line of sight for spatial query
        float searchRadius = Math.max(1.0f, totalDistance * 0.01f); // 1% of distance or minimum 1 unit
        
        // Get tetrahedra that might intersect the line of sight
        var candidateTetrahedra = spatialLineQuery(observer, target, searchRadius, tetree);
        
        List<TetVisibilityResult<Content>> occludingTetrahedra = new ArrayList<>();
        float totalOcclusionRatio = 0.0f;
        float distanceThroughOccluders = 0.0f;

        for (var simplex : aggregateSimplicies(candidateTetrahedra, strategy)) {
            var tet = Tet.tetrahedron(simplex.index());
            
            // Skip tetrahedra that are too small to be significant occluders
            double tetVolume = tetrahedronVolume(simplex.index());
            if (tetVolume < occlusionThreshold) {
                continue;
            }
            
            Point3f tetCenter = tetrahedronCenter(simplex.index());
            float distanceFromObserver = calculateDistance(observer, tetCenter);
            float distanceFromTarget = calculateDistance(target, tetCenter);
            
            // Check if tetrahedron intersects the line of sight
            TetRayIntersection intersection = rayTetrahedronIntersection(sightRay, tet);
            if (intersection.intersects && intersection.distance < totalDistance) {
                VisibilityType visibilityType = classifyVisibility(tet, observer, target, intersection.distance, totalDistance);
                float occlusionRatio = calculateOcclusionRatio(tet, sightRay, intersection.distance, totalDistance);
                
                if (visibilityType == VisibilityType.PARTIALLY_OCCLUDING || visibilityType == VisibilityType.FULLY_OCCLUDING) {
                    TetVisibilityResult<Content> result = new TetVisibilityResult<>(
                        simplex.index(), 
                        simplex.cell(), 
                        tet, 
                        tetCenter,
                        distanceFromObserver,
                        distanceFromTarget,
                        visibilityType,
                        occlusionRatio
                    );
                    occludingTetrahedra.add(result);
                    totalOcclusionRatio += occlusionRatio;
                    
                    // Estimate distance through this occluder
                    float exitDistance = intersection.distance + (float)Math.cbrt(tetVolume); // Rough estimate
                    distanceThroughOccluders += Math.min(exitDistance - intersection.distance, totalDistance - intersection.distance);
                }
            }
        }

        // Sort occluding tetrahedra by distance from observer
        occludingTetrahedra.sort(Comparator.comparing(vr -> vr.distanceFromObserver));
        
        // Determine if line of sight is blocked (threshold can be adjusted)
        boolean hasLineOfSight = totalOcclusionRatio < 0.5f; // 50% occlusion threshold
        
        return new TetLineOfSightResult<>(hasLineOfSight, occludingTetrahedra, totalOcclusionRatio, distanceThroughOccluders);
    }

    /**
     * Find all tetrahedra visible from an observer position within a viewing cone
     * 
     * @param observer the observer position (positive coordinates only)
     * @param viewDirection the viewing direction (will be normalized)
     * @param viewAngle the viewing angle in radians (cone half-angle)
     * @param maxViewDistance maximum viewing distance
     * @param tetree the tetree to search in
     * @param strategy aggregation strategy for multiple simplicies
     * @return list of visible tetrahedra sorted by distance
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static <Content> List<TetVisibilityResult<Content>> findVisibleTetrahedra(
            Point3f observer, Vector3f viewDirection, float viewAngle, float maxViewDistance, 
            Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(observer);
        
        if (viewAngle < 0 || viewAngle > Math.PI) {
            throw new IllegalArgumentException("View angle must be between 0 and π radians");
        }
        
        if (maxViewDistance < 0) {
            throw new IllegalArgumentException("Max view distance must be non-negative");
        }
        
        Vector3f normalizedDirection = new Vector3f(viewDirection);
        normalizedDirection.normalize();
        
        // Create a conical spatial volume for the query
        var viewingCone = createViewingCone(observer, normalizedDirection, viewAngle, maxViewDistance);
        var candidateTetrahedra = tetree.boundedBy(viewingCone);
        
        List<TetVisibilityResult<Content>> visibleTetrahedra = new ArrayList<>();

        for (var simplex : aggregateSimplicies(candidateTetrahedra, strategy)) {
            Point3f tetCenter = tetrahedronCenter(simplex.index());
            
            float distanceFromObserver = calculateDistance(observer, tetCenter);
            
            // Check if tetrahedron is within viewing distance
            if (distanceFromObserver > maxViewDistance) {
                continue;
            }
            
            // Check if tetrahedron is within viewing cone
            Vector3f toTet = new Vector3f(tetCenter.x - observer.x, tetCenter.y - observer.y, tetCenter.z - observer.z);
            toTet.normalize();
            
            float angle = normalizedDirection.angle(toTet);
            if (angle <= viewAngle) {
                // Tetrahedron is within viewing cone - perform basic occlusion test
                boolean isVisible = isTetrahedronVisible(observer, tetCenter, tetree, tetrahedronVolume(simplex.index()), strategy);
                
                if (isVisible) {
                    TetVisibilityResult<Content> result = new TetVisibilityResult<>(
                        simplex.index(), 
                        simplex.cell(), 
                        Tet.tetrahedron(simplex.index()), 
                        tetCenter,
                        distanceFromObserver,
                        0.0f, // Not applicable for this query
                        VisibilityType.VISIBLE,
                        0.0f
                    );
                    visibleTetrahedra.add(result);
                }
            }
        }

        // Sort by distance from observer
        visibleTetrahedra.sort(Comparator.comparing(vr -> vr.distanceFromObserver));
        
        return visibleTetrahedra;
    }

    /**
     * Calculate visibility statistics from an observer position in tetrahedral space
     * 
     * @param observer the observer position (positive coordinates only)
     * @param maxViewDistance maximum viewing distance for analysis
     * @param tetree the tetree to analyze
     * @param strategy aggregation strategy for multiple simplicies
     * @return statistics about visibility from the observer position
     * @throws IllegalArgumentException if observer coordinates are negative
     */
    public static <Content> TetVisibilityStatistics calculateVisibilityStatistics(
            Point3f observer, float maxViewDistance, Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(observer);
        
        if (maxViewDistance < 0) {
            throw new IllegalArgumentException("Max view distance must be non-negative");
        }
        
        // Create a spherical volume around the observer
        var viewingSphere = createSphericalVolume(observer, maxViewDistance);
        var candidateTetrahedra = tetree.boundedBy(viewingSphere);
        
        long totalTetrahedra = 0;
        long visibleTetrahedra = 0;
        long partiallyOccludedTetrahedra = 0;
        long fullyOccludedTetrahedra = 0;
        long tetrahedraOutOfRange = 0;
        float totalVisibilityRatio = 0.0f;

        for (var simplex : aggregateSimplicies(candidateTetrahedra, strategy)) {
            totalTetrahedra++;
            Point3f tetCenter = tetrahedronCenter(simplex.index());
            
            float distance = calculateDistance(observer, tetCenter);
            if (distance > maxViewDistance) {
                tetrahedraOutOfRange++;
                continue;
            }
            
            // Simplified visibility test
            double tetVolume = tetrahedronVolume(simplex.index());
            TetLineOfSightResult<Content> losResult = testLineOfSight(observer, tetCenter, tetree, tetVolume * 0.1, strategy);
            
            if (losResult.hasLineOfSight) {
                visibleTetrahedra++;
                totalVisibilityRatio += (1.0f - losResult.totalOcclusionRatio);
            } else if (losResult.totalOcclusionRatio < 1.0f) {
                partiallyOccludedTetrahedra++;
                totalVisibilityRatio += (1.0f - losResult.totalOcclusionRatio);
            } else {
                fullyOccludedTetrahedra++;
            }
        }

        float averageVisibilityRatio = (visibleTetrahedra + partiallyOccludedTetrahedra) > 0 ? 
            totalVisibilityRatio / (visibleTetrahedra + partiallyOccludedTetrahedra) : 0.0f;

        return new TetVisibilityStatistics(totalTetrahedra, visibleTetrahedra, partiallyOccludedTetrahedra, 
                                         fullyOccludedTetrahedra, tetrahedraOutOfRange, totalVisibilityRatio, averageVisibilityRatio);
    }

    /**
     * Statistics about visibility from an observer position in tetrahedral space
     */
    public static class TetVisibilityStatistics {
        public final long totalTetrahedra;
        public final long visibleTetrahedra;
        public final long partiallyOccludedTetrahedra;
        public final long fullyOccludedTetrahedra;
        public final long tetrahedraOutOfRange;
        public final float totalVisibilityRatio;
        public final float averageVisibilityRatio;
        
        public TetVisibilityStatistics(long totalTetrahedra, long visibleTetrahedra, long partiallyOccludedTetrahedra,
                                     long fullyOccludedTetrahedra, long tetrahedraOutOfRange, 
                                     float totalVisibilityRatio, float averageVisibilityRatio) {
            this.totalTetrahedra = totalTetrahedra;
            this.visibleTetrahedra = visibleTetrahedra;
            this.partiallyOccludedTetrahedra = partiallyOccludedTetrahedra;
            this.fullyOccludedTetrahedra = fullyOccludedTetrahedra;
            this.tetrahedraOutOfRange = tetrahedraOutOfRange;
            this.totalVisibilityRatio = totalVisibilityRatio;
            this.averageVisibilityRatio = averageVisibilityRatio;
        }
        
        public double getVisiblePercentage() {
            long inRange = totalTetrahedra - tetrahedraOutOfRange;
            return inRange > 0 ? (double) visibleTetrahedra / inRange * 100.0 : 0.0;
        }
        
        public double getPartiallyOccludedPercentage() {
            long inRange = totalTetrahedra - tetrahedraOutOfRange;
            return inRange > 0 ? (double) partiallyOccludedTetrahedra / inRange * 100.0 : 0.0;
        }
        
        public double getFullyOccludedPercentage() {
            long inRange = totalTetrahedra - tetrahedraOutOfRange;
            return inRange > 0 ? (double) fullyOccludedTetrahedra / inRange * 100.0 : 0.0;
        }
        
        public double getOutOfRangePercentage() {
            return totalTetrahedra > 0 ? (double) tetrahedraOutOfRange / totalTetrahedra * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("TetVisibilityStats[total=%d, visible=%d(%.1f%%), partial=%d(%.1f%%), occluded=%d(%.1f%%), out_of_range=%d(%.1f%%), avg_visibility=%.3f]",
                               totalTetrahedra, visibleTetrahedra, getVisiblePercentage(), 
                               partiallyOccludedTetrahedra, getPartiallyOccludedPercentage(),
                               fullyOccludedTetrahedra, getFullyOccludedPercentage(),
                               tetrahedraOutOfRange, getOutOfRangePercentage(), averageVisibilityRatio);
        }
    }

    /**
     * Find the best vantage points for observing a target in tetrahedral space
     * 
     * @param target the target position to observe (positive coordinates only)
     * @param candidatePositions list of candidate observer positions (positive coordinates only)
     * @param tetree the tetree containing potential occluders
     * @param strategy aggregation strategy for multiple simplicies
     * @return list of candidate positions sorted by visibility quality
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static <Content> List<TetVantagePoint> findBestVantagePoints(
            Point3f target, List<Point3f> candidatePositions, Tetree<Content> tetree, SimplexAggregationStrategy strategy) {
        
        validatePositiveCoordinates(target);
        for (Point3f pos : candidatePositions) {
            validatePositiveCoordinates(pos);
        }
        
        List<TetVantagePoint> vantagePoints = new ArrayList<>();
        
        for (Point3f candidate : candidatePositions) {
            TetLineOfSightResult<Content> losResult = testLineOfSight(candidate, target, tetree, 1.0, strategy);
            float distance = calculateDistance(candidate, target);
            float visibilityScore = calculateVisibilityScore(losResult, distance);
            
            TetVantagePoint vantagePoint = new TetVantagePoint(candidate, distance, losResult.hasLineOfSight, 
                                                             losResult.totalOcclusionRatio, visibilityScore);
            vantagePoints.add(vantagePoint);
        }
        
        // Sort by visibility score (higher is better)
        vantagePoints.sort((vp1, vp2) -> Float.compare(vp2.visibilityScore, vp1.visibilityScore));
        
        return vantagePoints;
    }

    /**
     * Represents a vantage point for observation in tetrahedral space
     */
    public static class TetVantagePoint {
        public final Point3f position;
        public final float distanceToTarget;
        public final boolean hasLineOfSight;
        public final float occlusionRatio;
        public final float visibilityScore;
        
        public TetVantagePoint(Point3f position, float distanceToTarget, boolean hasLineOfSight,
                             float occlusionRatio, float visibilityScore) {
            this.position = position;
            this.distanceToTarget = distanceToTarget;
            this.hasLineOfSight = hasLineOfSight;
            this.occlusionRatio = occlusionRatio;
            this.visibilityScore = visibilityScore;
        }
        
        @Override
        public String toString() {
            return String.format("TetVantagePoint[pos=%s, dist=%.2f, los=%s, occlusion=%.3f, score=%.3f]",
                               position, distanceToTarget, hasLineOfSight, occlusionRatio, visibilityScore);
        }
    }

    // Helper methods

    /**
     * Ray-tetrahedron intersection result
     */
    private static class TetRayIntersection {
        public final boolean intersects;
        public final float distance;

        public TetRayIntersection(boolean intersects, float distance) {
            this.intersects = intersects;
            this.distance = distance;
        }
    }

    /**
     * Test ray-tetrahedron intersection using Möller-Trumbore algorithm on each face
     */
    private static TetRayIntersection rayTetrahedronIntersection(Ray3D ray, Tet tet) {
        var vertices = tet.coordinates();
        float minDistance = Float.MAX_VALUE;
        boolean intersects = false;

        // Test ray against each triangular face of the tetrahedron
        for (int face = 0; face < 4; face++) {
            var faceVertices = getFaceVertices(vertices, face);
            
            // Convert to Point3f for Möller-Trumbore algorithm
            Point3f v0 = new Point3f(faceVertices[0].x, faceVertices[0].y, faceVertices[0].z);
            Point3f v1 = new Point3f(faceVertices[1].x, faceVertices[1].y, faceVertices[1].z);
            Point3f v2 = new Point3f(faceVertices[2].x, faceVertices[2].y, faceVertices[2].z);
            
            float distance = rayTriangleIntersection(ray, v0, v1, v2);
            if (distance >= 0 && distance < minDistance) {
                minDistance = distance;
                intersects = true;
            }
        }

        return new TetRayIntersection(intersects, intersects ? minDistance : Float.MAX_VALUE);
    }

    /**
     * Möller-Trumbore ray-triangle intersection algorithm
     */
    private static float rayTriangleIntersection(Ray3D ray, Point3f v0, Point3f v1, Point3f v2) {
        final float EPSILON = 1e-6f;
        
        Vector3f edge1 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3f edge2 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);
        
        Vector3f h = new Vector3f();
        h.cross(ray.direction(), edge2);
        
        float a = edge1.dot(h);
        if (a > -EPSILON && a < EPSILON) {
            return -1; // Ray is parallel to triangle
        }
        
        float f = 1.0f / a;
        Vector3f s = new Vector3f(ray.origin().x - v0.x, ray.origin().y - v0.y, ray.origin().z - v0.z);
        float u = f * s.dot(h);
        
        if (u < 0.0 || u > 1.0) {
            return -1;
        }
        
        Vector3f q = new Vector3f();
        q.cross(s, edge1);
        float v = f * ray.direction().dot(q);
        
        if (v < 0.0 || u + v > 1.0) {
            return -1;
        }
        
        float t = f * edge2.dot(q);
        
        if (t > EPSILON) {
            return t; // Ray intersection
        } else {
            return -1; // Line intersection but not ray intersection
        }
    }

    /**
     * Get the 3 vertices that form the specified face of the tetrahedron
     */
    private static javax.vecmath.Point3i[] getFaceVertices(javax.vecmath.Point3i[] tetrahedronVertices, int faceIndex) {
        return switch (faceIndex) {
            case 0 -> new javax.vecmath.Point3i[]{tetrahedronVertices[0], tetrahedronVertices[1], tetrahedronVertices[2]};
            case 1 -> new javax.vecmath.Point3i[]{tetrahedronVertices[0], tetrahedronVertices[1], tetrahedronVertices[3]};
            case 2 -> new javax.vecmath.Point3i[]{tetrahedronVertices[0], tetrahedronVertices[2], tetrahedronVertices[3]};
            case 3 -> new javax.vecmath.Point3i[]{tetrahedronVertices[1], tetrahedronVertices[2], tetrahedronVertices[3]};
            default -> throw new IllegalArgumentException("Face index must be 0-3");
        };
    }

    /**
     * Simple visibility test between two points in tetrahedral space
     */
    private static <Content> boolean isTetrahedronVisible(Point3f observer, Point3f target, 
                                                        Tetree<Content> tetree, double targetVolume, SimplexAggregationStrategy strategy) {
        TetLineOfSightResult<Content> result = testLineOfSight(observer, target, tetree, targetVolume * 0.5, strategy);
        return result.hasLineOfSight;
    }

    /**
     * Classify the visibility type of a tetrahedron relative to observer and target
     */
    private static VisibilityType classifyVisibility(Tet tet, Point3f observer, Point3f target,
                                                    float intersectionDistance, float totalDistance) {
        if (intersectionDistance > totalDistance) {
            return VisibilityType.BEHIND_TARGET;
        } else if (intersectionDistance < totalDistance * 0.1f) {
            return VisibilityType.BEFORE_OBSERVER;
        } else {
            // Use tetrahedron volume to determine occlusion type
            double volume = tetrahedronVolume(tet.index());
            return volume > 1000.0 ? VisibilityType.FULLY_OCCLUDING : VisibilityType.PARTIALLY_OCCLUDING;
        }
    }

    /**
     * Calculate occlusion ratio based on tetrahedron volume and position
     */
    private static float calculateOcclusionRatio(Tet tet, Ray3D ray, float intersectionDistance, float totalDistance) {
        // Simplified calculation based on tetrahedron volume relative to distance
        double volume = tetrahedronVolume(tet.index());
        float relativeSize = (float)(Math.cbrt(volume) / Math.max(intersectionDistance, 1.0f));
        return Math.min(1.0f, relativeSize * 0.05f); // Cap at 100% occlusion
    }

    /**
     * Calculate visibility score for a tetrahedral vantage point
     */
    private static float calculateVisibilityScore(TetLineOfSightResult<?> losResult, float distance) {
        float visibilityComponent = losResult.hasLineOfSight ? 1.0f : (1.0f - losResult.totalOcclusionRatio);
        float distanceComponent = Math.max(0.1f, 1000.0f / distance); // Closer is better, but not too close
        return visibilityComponent * distanceComponent;
    }

    /**
     * Create a spatial query for tetrahedra along a line segment
     */
    private static <Content> Stream<Tetree.Simplex<Content>> spatialLineQuery(Point3f start, Point3f end, float radius, Tetree<Content> tetree) {
        // Create a cylindrical volume around the line - simplified as AABB for now
        float minX = Math.min(start.x, end.x) - radius;
        float maxX = Math.max(start.x, end.x) + radius;
        float minY = Math.min(start.y, end.y) - radius;
        float maxY = Math.max(start.y, end.y) + radius;
        float minZ = Math.min(start.z, end.z) - radius;
        float maxZ = Math.max(start.z, end.z) + radius;
        
        var aabb = new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
        return tetree.boundedBy(aabb);
    }

    /**
     * Create a viewing cone spatial volume (simplified as sphere for now)
     */
    private static Spatial createViewingCone(Point3f center, Vector3f direction, float angle, float distance) {
        // Simplified as sphere - could be enhanced to proper cone
        return new Spatial.Sphere(center.x, center.y, center.z, distance);
    }

    /**
     * Create a spherical spatial volume
     */
    private static Spatial createSphericalVolume(Point3f center, float radius) {
        return new Spatial.Sphere(center.x, center.y, center.z, radius);
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
}