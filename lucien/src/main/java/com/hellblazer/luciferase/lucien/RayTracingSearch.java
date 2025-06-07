package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ray tracing intersection search implementation for Octree
 * Finds octree cubes that intersect with a 3D ray, ordered by distance from ray origin
 * 
 * @author hal.hildebrand
 */
public class RayTracingSearch {

    /**
     * Ray intersection result with distance information
     */
    public static class RayIntersection<Content> {
        public final long index;
        public final Content content;
        public final Spatial.Cube cube;
        public final float distance;
        public final Point3f intersectionPoint;

        public RayIntersection(long index, Content content, Spatial.Cube cube, float distance, Point3f intersectionPoint) {
            this.index = index;
            this.content = content;
            this.cube = cube;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
        }
    }

    /**
     * Find all cubes that intersect with the ray, ordered by distance from ray origin
     * 
     * @param ray the ray to trace (must have positive origin coordinates)
     * @param octree the octree to search in
     * @return stream of intersections sorted by distance (closest first)
     */
    public static <Content> List<RayIntersection<Content>> rayIntersectedAll(
            Ray3D ray, Octree<Content> octree) {
        
        if (octree.getMap().isEmpty()) {
            return Collections.emptyList();
        }

        Point3f rayOrigin = ray.origin();
        Vector3f rayDirection = ray.direction();
        
        // Calculate bounding box for the ray (assume reasonable max distance)
        float maxDistance = 100000f;
        Point3f rayEnd = new Point3f(
            rayOrigin.x + rayDirection.x * maxDistance,
            rayOrigin.y + rayDirection.y * maxDistance,
            rayOrigin.z + rayDirection.z * maxDistance
        );
        
        float minX = Math.min(rayOrigin.x, rayEnd.x);
        float maxX = Math.max(rayOrigin.x, rayEnd.x);
        float minY = Math.min(rayOrigin.y, rayEnd.y);
        float maxY = Math.max(rayOrigin.y, rayEnd.y);
        float minZ = Math.min(rayOrigin.z, rayEnd.z);
        float maxZ = Math.max(rayOrigin.z, rayEnd.z);
        
        var boundingAABB = new Spatial.aabb(minX, minY, minZ, maxX, maxY, maxZ);
        
        // Use Octree's efficient bounding method which uses Morton curve ranges
        return octree.bounding(boundingAABB)
            .map(hex -> {
                var cube = hex.toCube();
                var intersection = rayBoxIntersection(ray, cube);
                
                if (intersection.intersects) {
                    return new RayIntersection<>(
                        hex.index(),
                        hex.cell(),
                        cube,
                        intersection.distance,
                        intersection.intersectionPoint
                    );
                }
                return null;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(ri -> ri.distance))
            .toList();
    }

    /**
     * Find the first (closest) cube that intersects with the ray
     * 
     * @param ray the ray to trace
     * @param octree the octree to search in
     * @return the closest intersection, or null if no intersection
     */
    public static <Content> RayIntersection<Content> rayIntersectedFirst(
            Ray3D ray, Octree<Content> octree) {
        
        List<RayIntersection<Content>> intersections = rayIntersectedAll(ray, octree);
        return intersections.isEmpty() ? null : intersections.get(0);
    }

    /**
     * Ray-AABB intersection using the slab method
     */
    private static class RayBoxIntersection {
        public final boolean intersects;
        public final float distance;
        public final Point3f intersectionPoint;

        public RayBoxIntersection(boolean intersects, float distance, Point3f intersectionPoint) {
            this.intersects = intersects;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
        }
    }

    /**
     * Test ray intersection with axis-aligned bounding box using slab method
     * Based on "Real-Time Rendering" by Akenine-Möller, Haines, and Hoffman
     */
    private static RayBoxIntersection rayBoxIntersection(Ray3D ray, Spatial.Cube cube) {
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
            // Ray is parallel to X slab
            if (origin.x < minX || origin.x > maxX) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
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
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        }

        // Y slab
        if (Math.abs(direction.y) < 1e-6f) {
            // Ray is parallel to Y slab
            if (origin.y < minY || origin.y > maxY) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
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
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        }

        // Z slab
        if (Math.abs(direction.z) < 1e-6f) {
            // Ray is parallel to Z slab
            if (origin.z < minZ || origin.z > maxZ) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
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
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        }

        // Ray intersects box if tmax >= 0 and tmin <= tmax
        if (tmax < 0) {
            return new RayBoxIntersection(false, Float.MAX_VALUE, null);
        }

        // Choose the closest intersection point (tmin if >= 0, otherwise tmax)
        float t = (tmin >= 0) ? tmin : tmax;
        Point3f intersectionPoint = ray.getPointAt(t);
        
        return new RayBoxIntersection(true, t, intersectionPoint);
    }
}