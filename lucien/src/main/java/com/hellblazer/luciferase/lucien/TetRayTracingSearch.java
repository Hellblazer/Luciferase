package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ray tracing intersection search implementation for Tetree
 * Finds tetrahedra that intersect with a 3D ray, ordered by distance from ray origin
 * 
 * Adapts cube-based ray tracing to tetrahedral geometry using sophisticated
 * ray-tetrahedron intersection algorithms and SFC optimization.
 * 
 * @author hal.hildebrand
 */
public class TetRayTracingSearch {

    /**
     * Ray intersection result with distance information for tetrahedra
     */
    public static class TetRayIntersection<Content> {
        public final long index;
        public final Content content;
        public final Tet tetrahedron;
        public final float distance;
        public final Point3f intersectionPoint;
        public final Vector3f normal;
        public final int intersectedFace; // Which tetrahedral face was hit (0-3)

        public TetRayIntersection(long index, Content content, Tet tetrahedron, float distance, 
                                Point3f intersectionPoint, Vector3f normal, int intersectedFace) {
            this.index = index;
            this.content = content;
            this.tetrahedron = tetrahedron;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
            this.normal = normal;
            this.intersectedFace = intersectedFace;
        }
    }

    /**
     * Configuration for ray tracing optimization
     */
    public static class RayTracingConfig {
        public final boolean useAABBPrefiltering;
        public final boolean useSFCOptimization;
        public final float maxRayDistance;
        public final boolean sortByDistance;
        public final int maxIntersections;
        
        public RayTracingConfig(boolean useAABBPrefiltering, boolean useSFCOptimization, 
                              float maxRayDistance, boolean sortByDistance, int maxIntersections) {
            this.useAABBPrefiltering = useAABBPrefiltering;
            this.useSFCOptimization = useSFCOptimization;
            this.maxRayDistance = maxRayDistance;
            this.sortByDistance = sortByDistance;
            this.maxIntersections = maxIntersections;
        }
        
        public static RayTracingConfig defaultConfig() {
            return new RayTracingConfig(true, true, Float.MAX_VALUE, true, Integer.MAX_VALUE);
        }
        
        public static RayTracingConfig fastConfig() {
            return new RayTracingConfig(true, true, 1000.0f, true, 100);
        }
        
        public static RayTracingConfig preciseConfig() {
            return new RayTracingConfig(false, false, Float.MAX_VALUE, true, Integer.MAX_VALUE);
        }
    }

    /**
     * Find all tetrahedra that intersect with the ray, ordered by distance from ray origin
     * 
     * @param ray the ray to trace (must have positive origin coordinates)
     * @param tetree the tetree to search in
     * @param config ray tracing configuration
     * @return list of intersections sorted by distance (closest first)
     */
    public static <Content> List<TetRayIntersection<Content>> rayIntersectedAll(
            Ray3D ray, Tetree<Content> tetree, RayTracingConfig config) {
        
        validateRay(ray);
        
        // Access the tetree contents using reflection since contents field is private
        NavigableMap<Long, Content> map;
        try {
            var field = tetree.getClass().getDeclaredField("contents");
            field.setAccessible(true);
            map = (NavigableMap<Long, Content>) field.get(tetree);
        } catch (Exception e) {
            throw new RuntimeException("Unable to access tetree contents", e);
        }
        
        if (map.isEmpty()) {
            return Collections.emptyList();
        }

        List<TetRayIntersection<Content>> intersections = new ArrayList<>();
        
        if (config.useSFCOptimization) {
            intersections = rayIntersectionsWithSFCOptimization(ray, tetree, config);
        } else {
            intersections = rayIntersectionsBruteForce(ray, tetree, config);
        }
        
        // Sort by distance if requested
        if (config.sortByDistance) {
            intersections.sort(Comparator.comparing(ri -> ri.distance));
        }
        
        // Limit results if requested
        if (config.maxIntersections < intersections.size()) {
            intersections = intersections.subList(0, config.maxIntersections);
        }
        
        return intersections;
    }

    /**
     * Find the first (closest) tetrahedron that intersects with the ray
     * 
     * @param ray the ray to trace
     * @param tetree the tetree to search in
     * @return the closest intersection, or null if no intersection
     */
    public static <Content> TetRayIntersection<Content> rayIntersectedFirst(
            Ray3D ray, Tetree<Content> tetree) {
        
        var config = new RayTracingConfig(true, true, Float.MAX_VALUE, true, 1);
        List<TetRayIntersection<Content>> intersections = rayIntersectedAll(ray, tetree, config);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Optimized ray intersection using SFC spatial optimization
     */
    private static <Content> List<TetRayIntersection<Content>> rayIntersectionsWithSFCOptimization(
            Ray3D ray, Tetree<Content> tetree, RayTracingConfig config) {
        
        List<TetRayIntersection<Content>> intersections = new ArrayList<>();
        
        // Create bounding box around the ray path
        var rayBounds = computeRayBounds(ray, config.maxRayDistance);
        
        // Create VolumeBounds for TetSpatialOptimizer
        var volumeBounds = new TetSpatialOptimizer.VolumeBounds(
            rayBounds.min.x, rayBounds.min.y, rayBounds.min.z,
            rayBounds.max.x, rayBounds.max.y, rayBounds.max.z
        );
        
        // Get SFC ranges that could intersect the ray
        var ranges = TetSpatialOptimizer.computeOptimizedSFCRanges(volumeBounds, true);
        
        // Access tetree contents using reflection
        NavigableMap<Long, Content> map = getTetreeContents(tetree);
        
        // Process each range
        for (var range : ranges) {
            var subMap = map.subMap(range.startIndex(), true, range.endIndex(), true);
            
            for (var entry : subMap.entrySet()) {
                var intersection = testRayTetrahedronIntersection(ray, entry.getKey(), entry.getValue(), config);
                if (intersection != null) {
                    intersections.add(intersection);
                }
            }
        }
        
        return intersections;
    }

    /**
     * Brute force ray intersection testing all tetrahedra
     */
    private static <Content> List<TetRayIntersection<Content>> rayIntersectionsBruteForce(
            Ray3D ray, Tetree<Content> tetree, RayTracingConfig config) {
        
        List<TetRayIntersection<Content>> intersections = new ArrayList<>();
        
        NavigableMap<Long, Content> map = getTetreeContents(tetree);
        for (Map.Entry<Long, Content> entry : map.entrySet()) {
            var intersection = testRayTetrahedronIntersection(ray, entry.getKey(), entry.getValue(), config);
            if (intersection != null) {
                intersections.add(intersection);
            }
        }
        
        return intersections;
    }

    /**
     * Test ray intersection with a single tetrahedron
     */
    private static <Content> TetRayIntersection<Content> testRayTetrahedronIntersection(
            Ray3D ray, long tetIndex, Content content, RayTracingConfig config) {
        
        var tet = Tet.tetrahedron(tetIndex);
        
        // AABB pre-filtering for performance
        if (config.useAABBPrefiltering) {
            if (!rayIntersectsTetrahedronAABB(ray, tet, config.maxRayDistance)) {
                return null;
            }
        }
        
        // Convert to TetrahedralGeometry ray format
        var geoRay = new TetrahedralGeometry.Ray3D(ray.origin(), ray.direction(), config.maxRayDistance);
        
        // Use TetrahedralGeometry for precise intersection
        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(geoRay, tetIndex);
        
        if (intersection.intersects) {
            return new TetRayIntersection<>(
                tetIndex, 
                content, 
                tet, 
                intersection.distance,
                intersection.intersectionPoint,
                intersection.normal,
                intersection.intersectedFace
            );
        }
        
        return null;
    }

    /**
     * Fast AABB intersection test for pre-filtering
     */
    private static boolean rayIntersectsTetrahedronAABB(Ray3D ray, Tet tet, float maxDistance) {
        var vertices = tet.coordinates();
        
        // Compute AABB of tetrahedron
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (var vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }
        
        // Ray-AABB intersection using slab method
        return rayAABBIntersection(ray, minX, minY, minZ, maxX, maxY, maxZ, maxDistance);
    }

    /**
     * Ray-AABB intersection using the slab method (adapted from RayTracingSearch)
     */
    private static boolean rayAABBIntersection(Ray3D ray, float minX, float minY, float minZ, 
                                             float maxX, float maxY, float maxZ, float maxDistance) {
        
        Point3f origin = ray.origin();
        Vector3f direction = ray.direction();
        
        float tmin = 0.0f;
        float tmax = maxDistance;

        // X slab
        if (Math.abs(direction.x) < 1e-6f) {
            if (origin.x < minX || origin.x > maxX) {
                return false;
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
                return false;
            }
        }

        // Y slab
        if (Math.abs(direction.y) < 1e-6f) {
            if (origin.y < minY || origin.y > maxY) {
                return false;
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
                return false;
            }
        }

        // Z slab
        if (Math.abs(direction.z) < 1e-6f) {
            if (origin.z < minZ || origin.z > maxZ) {
                return false;
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
                return false;
            }
        }

        return tmax >= 0;
    }

    /**
     * Compute bounding box around a ray path for SFC optimization
     */
    private static RayBounds computeRayBounds(Ray3D ray, float maxDistance) {
        Point3f origin = ray.origin();
        Vector3f direction = ray.direction();
        
        Point3f endPoint = new Point3f(
            origin.x + maxDistance * direction.x,
            origin.y + maxDistance * direction.y,
            origin.z + maxDistance * direction.z
        );
        
        float minX = Math.min(origin.x, endPoint.x);
        float minY = Math.min(origin.y, endPoint.y);
        float minZ = Math.min(origin.z, endPoint.z);
        float maxX = Math.max(origin.x, endPoint.x);
        float maxY = Math.max(origin.y, endPoint.y);
        float maxZ = Math.max(origin.z, endPoint.z);
        
        // Add small padding to account for tetrahedron extents
        float padding = 1.0f;
        
        return new RayBounds(
            new Point3f(minX - padding, minY - padding, minZ - padding),
            new Point3f(maxX + padding, maxY + padding, maxZ + padding)
        );
    }

    /**
     * Validate ray has positive coordinates
     */
    private static void validateRay(Ray3D ray) {
        if (ray.origin().x < 0 || ray.origin().y < 0 || ray.origin().z < 0) {
            throw new IllegalArgumentException("Ray origin must have positive coordinates: " + ray.origin());
        }
    }

    /**
     * Access Tetree contents using reflection (helper method to avoid code duplication)
     */
    private static <Content> NavigableMap<Long, Content> getTetreeContents(Tetree<Content> tetree) {
        try {
            var field = tetree.getClass().getDeclaredField("contents");
            field.setAccessible(true);
            return (NavigableMap<Long, Content>) field.get(tetree);
        } catch (Exception e) {
            throw new RuntimeException("Unable to access tetree contents", e);
        }
    }

    /**
     * Helper record for ray bounding box
     */
    private record RayBounds(Point3f min, Point3f max) {}

    /**
     * Ray tracing performance statistics
     */
    public static class RayTracingStats {
        public final int totalTetrahedraProcessed;
        public final int aabbPrefilterPassed;
        public final int preciseIntersectionTests;
        public final int actualIntersections;
        public final long executionTimeNanos;
        public final boolean usedSFCOptimization;
        
        public RayTracingStats(int totalTetrahedraProcessed, int aabbPrefilterPassed,
                             int preciseIntersectionTests, int actualIntersections,
                             long executionTimeNanos, boolean usedSFCOptimization) {
            this.totalTetrahedraProcessed = totalTetrahedraProcessed;
            this.aabbPrefilterPassed = aabbPrefilterPassed;
            this.preciseIntersectionTests = preciseIntersectionTests;
            this.actualIntersections = actualIntersections;
            this.executionTimeNanos = executionTimeNanos;
            this.usedSFCOptimization = usedSFCOptimization;
        }
        
        public double getPrefilterEfficiency() {
            return totalTetrahedraProcessed > 0 ? 
                (double) aabbPrefilterPassed / totalTetrahedraProcessed : 0.0;
        }
        
        public double getIntersectionRate() {
            return preciseIntersectionTests > 0 ? 
                (double) actualIntersections / preciseIntersectionTests : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("RayTracingStats[processed=%d, prefiltered=%d, tested=%d, " +
                "intersections=%d, time=%.2fms, SFC=%s, prefilter_eff=%.1f%%, intersection_rate=%.1f%%]",
                totalTetrahedraProcessed, aabbPrefilterPassed, preciseIntersectionTests, 
                actualIntersections, executionTimeNanos / 1_000_000.0, usedSFCOptimization,
                getPrefilterEfficiency() * 100, getIntersectionRate() * 100);
        }
    }

    /**
     * Instrumented ray tracing for performance analysis
     */
    public static <Content> List<TetRayIntersection<Content>> rayIntersectedAllWithStats(
            Ray3D ray, Tetree<Content> tetree, RayTracingConfig config) {
        
        long startTime = System.nanoTime();
        
        // TODO: Add instrumentation counters throughout the algorithm
        // For now, delegate to standard implementation
        var results = rayIntersectedAll(ray, tetree, config);
        
        long endTime = System.nanoTime();
        
        // Create basic stats (would be enhanced with actual counters)
        var stats = new RayTracingStats(
            getTetreeContents(tetree).size(),  // totalTetrahedraProcessed (approximation)
            results.size(),               // aabbPrefilterPassed (approximation)
            results.size(),               // preciseIntersectionTests (approximation)
            results.size(),               // actualIntersections
            endTime - startTime,          // executionTimeNanos
            config.useSFCOptimization     // usedSFCOptimization
        );
        
        // For debugging/profiling - could be made configurable
        if (System.getProperty("tetree.raytracing.debug") != null) {
            System.out.println("TetRayTracing: " + stats);
        }
        
        return results;
    }
}