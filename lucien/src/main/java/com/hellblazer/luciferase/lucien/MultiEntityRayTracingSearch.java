/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Multi-entity aware ray tracing search for OctreeWithEntities.
 * Finds all entities that intersect with a ray, handling multiple entities at the same location.
 *
 * @author hal.hildebrand
 */
public class MultiEntityRayTracingSearch {
    
    /**
     * Ray intersection result with entity information
     */
    public static class RayIntersection<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f intersectionPoint;
        public final float distance;
        public final long mortonIndex;
        
        public RayIntersection(ID id, Content content, Point3f intersectionPoint, 
                               float distance, long mortonIndex) {
            this.id = id;
            this.content = content;
            this.intersectionPoint = intersectionPoint;
            this.distance = distance;
            this.mortonIndex = mortonIndex;
        }
    }
    
    /**
     * Find all entities intersected by a ray
     * 
     * @param ray the ray to trace
     * @param octree the multi-entity octree to search
     * @param maxDistance maximum distance to trace
     * @return list of all entity intersections sorted by distance from ray origin
     */
    public static <ID extends EntityID, Content> List<RayIntersection<ID, Content>> traceRay(
            Ray3D ray,
            OctreeWithEntities<ID, Content> octree,
            float maxDistance) {
        
        List<RayIntersection<ID, Content>> intersections = new ArrayList<>();
        
        // Sample points along the ray to find intersected cubes
        int numSamples = (int)(maxDistance / Constants.lengthAtLevel(Constants.getMaxRefinementLevel()));
        Set<Long> visitedMortonIndices = new HashSet<>();
        
        for (int i = 0; i <= numSamples; i++) {
            float t = (i * maxDistance) / numSamples;
            Point3f samplePoint = new Point3f(
                ray.origin().x + t * ray.direction().x,
                ray.origin().y + t * ray.direction().y,
                ray.origin().z + t * ray.direction().z
            );
            
            // Skip negative coordinates
            if (samplePoint.x < 0 || samplePoint.y < 0 || samplePoint.z < 0) {
                continue;
            }
            
            // Check different levels for entities at this point
            for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
                long mortonIndex = computeMortonIndex(samplePoint, level);
                
                if (!visitedMortonIndices.contains(mortonIndex)) {
                    visitedMortonIndices.add(mortonIndex);
                    
                    // Get all entities at this location
                    List<ID> entityIds = octree.lookup(samplePoint, level);
                    
                    for (ID entityId : entityIds) {
                        Content content = octree.getEntity(entityId);
                        if (content != null) {
                            // Calculate actual intersection point with the cube
                            Spatial.Cube cube = getCubeForPoint(samplePoint, level);
                            Point3f intersection = calculateRayCubeIntersection(ray, cube);
                            
                            if (intersection != null) {
                                float distance = ray.origin().distance(intersection);
                                if (distance <= maxDistance) {
                                    intersections.add(new RayIntersection<>(
                                        entityId, content, intersection, distance, mortonIndex
                                    ));
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Sort by distance from ray origin
        intersections.sort(Comparator.comparingDouble(i -> i.distance));
        return intersections;
    }
    
    /**
     * Find the first entity hit by a ray
     * 
     * @param ray the ray to trace
     * @param octree the multi-entity octree to search
     * @param maxDistance maximum distance to trace
     * @return the first entity hit, or null if no hit
     */
    public static <ID extends EntityID, Content> RayIntersection<ID, Content> traceRayFirst(
            Ray3D ray,
            OctreeWithEntities<ID, Content> octree,
            float maxDistance) {
        
        List<RayIntersection<ID, Content>> intersections = traceRay(ray, octree, maxDistance);
        return intersections.isEmpty() ? null : intersections.get(0);
    }
    
    /**
     * Find all entities within a cone defined by a ray and angle
     * 
     * @param ray the central ray of the cone
     * @param halfAngle half angle of the cone in radians
     * @param octree the multi-entity octree to search
     * @param maxDistance maximum distance to trace
     * @return list of all entities within the cone
     */
    public static <ID extends EntityID, Content> List<RayIntersection<ID, Content>> traceCone(
            Ray3D ray,
            float halfAngle,
            OctreeWithEntities<ID, Content> octree,
            float maxDistance) {
        
        List<RayIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get the cone's bounding region
        float coneRadius = maxDistance * (float)Math.tan(halfAngle);
        
        // Sample the cone volume
        Spatial.Cube boundingCube = new Spatial.Cube(
            Math.min(ray.origin().x, ray.origin().x + ray.direction().x * maxDistance) - coneRadius,
            Math.min(ray.origin().y, ray.origin().y + ray.direction().y * maxDistance) - coneRadius,
            Math.min(ray.origin().z, ray.origin().z + ray.direction().z * maxDistance) - coneRadius,
            Math.max(Math.abs(ray.direction().x * maxDistance), coneRadius) * 2 +
            Math.max(Math.abs(ray.direction().y * maxDistance), coneRadius) * 2 +
            Math.max(Math.abs(ray.direction().z * maxDistance), coneRadius) * 2
        );
        
        // Get all entities in the bounding region
        List<ID> entityIds = octree.entitiesInRegion(boundingCube);
        
        for (ID entityId : entityIds) {
            Content content = octree.getEntity(entityId);
            if (content != null) {
                // Check if entity is within the cone
                // This requires position information which we don't have
                // For now, include all entities in bounding cube
                results.add(new RayIntersection<>(
                    entityId, content, ray.origin(), 0.0f, 0L
                ));
            }
        }
        
        return results;
    }
    
    /**
     * Cast multiple rays and find all unique entities hit
     * 
     * @param rays array of rays to trace
     * @param octree the multi-entity octree to search
     * @param maxDistance maximum distance to trace for each ray
     * @return map of entity IDs to their intersection info
     */
    public static <ID extends EntityID, Content> Map<ID, List<RayIntersection<ID, Content>>> traceMultipleRays(
            Ray3D[] rays,
            OctreeWithEntities<ID, Content> octree,
            float maxDistance) {
        
        Map<ID, List<RayIntersection<ID, Content>>> entityHits = new HashMap<>();
        
        for (Ray3D ray : rays) {
            List<RayIntersection<ID, Content>> intersections = traceRay(ray, octree, maxDistance);
            
            for (RayIntersection<ID, Content> intersection : intersections) {
                entityHits.computeIfAbsent(intersection.id, k -> new ArrayList<>())
                    .add(intersection);
            }
        }
        
        return entityHits;
    }
    
    /**
     * Find all entities visible from a point (line of sight)
     * 
     * @param viewPoint the viewing position
     * @param targetRegion the region to check visibility for
     * @param octree the multi-entity octree to search
     * @return list of visible entities
     */
    public static <ID extends EntityID, Content> List<ContainedEntity<ID, Content>> findVisibleEntities(
            Point3f viewPoint,
            Spatial.Cube targetRegion,
            OctreeWithEntities<ID, Content> octree) {
        
        List<ContainedEntity<ID, Content>> visibleEntities = new ArrayList<>();
        
        // Get all entities in the target region
        List<ID> targetEntityIds = octree.entitiesInRegion(targetRegion);
        
        for (ID entityId : targetEntityIds) {
            Content content = octree.getEntity(entityId);
            if (content != null) {
                // For each entity, we would need to trace a ray from viewPoint
                // to the entity position to check for occlusions
                // Without position info, we assume all are visible
                visibleEntities.add(new ContainedEntity<>(entityId, content, 0L, (byte)0));
            }
        }
        
        return visibleEntities;
    }
    
    // Helper methods
    
    private static long computeMortonIndex(Point3f point, byte level) {
        int gridSize = Constants.lengthAtLevel(level);
        int x = (int)(point.x / gridSize) * gridSize;
        int y = (int)(point.y / gridSize) * gridSize;
        int z = (int)(point.z / gridSize) * gridSize;
        return com.hellblazer.luciferase.geometry.MortonCurve.encode(x, y, z);
    }
    
    private static Spatial.Cube getCubeForPoint(Point3f point, byte level) {
        int gridSize = Constants.lengthAtLevel(level);
        float x = (float)((int)(point.x / gridSize) * gridSize);
        float y = (float)((int)(point.y / gridSize) * gridSize);
        float z = (float)((int)(point.z / gridSize) * gridSize);
        return new Spatial.Cube(x, y, z, gridSize);
    }
    
    private static Point3f calculateRayCubeIntersection(Ray3D ray, Spatial.Cube cube) {
        // Simplified ray-AABB intersection
        float tMin = 0.0f;
        float tMax = Float.MAX_VALUE;
        
        // Check X axis
        float invDirX = 1.0f / ray.direction().x;
        float t1 = (cube.originX() - ray.origin().x) * invDirX;
        float t2 = (cube.originX() + cube.extent() - ray.origin().x) * invDirX;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        // Check Y axis
        float invDirY = 1.0f / ray.direction().y;
        t1 = (cube.originY() - ray.origin().y) * invDirY;
        t2 = (cube.originY() + cube.extent() - ray.origin().y) * invDirY;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        // Check Z axis
        float invDirZ = 1.0f / ray.direction().z;
        t1 = (cube.originZ() - ray.origin().z) * invDirZ;
        t2 = (cube.originZ() + cube.extent() - ray.origin().z) * invDirZ;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        if (tMax < tMin || tMax < 0) {
            return null;
        }
        
        float t = tMin >= 0 ? tMin : tMax;
        return new Point3f(
            ray.origin().x + t * ray.direction().x,
            ray.origin().y + t * ray.direction().y,
            ray.origin().z + t * ray.direction().z
        );
    }
    
    /**
     * Container class for entities (reused from ContainmentSearch)
     */
    public static class ContainedEntity<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final long mortonIndex;
        public final byte level;
        
        public ContainedEntity(ID id, Content content, long mortonIndex, byte level) {
            this.id = id;
            this.content = content;
            this.mortonIndex = mortonIndex;
            this.level = level;
        }
    }
}