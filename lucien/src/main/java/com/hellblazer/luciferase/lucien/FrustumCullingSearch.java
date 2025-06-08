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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-entity aware frustum culling search for OctreeWithEntities.
 * Finds all entities whose positions are within a 3D camera frustum.
 *
 * @author hal.hildebrand
 */
public class FrustumCullingSearch {
    
    /**
     * Result of frustum culling test
     */
    public enum CullingResult {
        INSIDE,      // Completely inside frustum
        INTERSECTING, // Partially inside frustum
        OUTSIDE       // Completely outside frustum
    }
    
    /**
     * Frustum culling result with entity information
     */
    public static class EntityFrustumIntersection<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distanceToCamera;
        public final CullingResult cullingResult;
        
        public EntityFrustumIntersection(ID id, Content content, Point3f position, 
                                       float distanceToCamera, CullingResult cullingResult) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distanceToCamera = distanceToCamera;
            this.cullingResult = cullingResult;
        }
    }
    
    /**
     * Find all entities that are within the frustum, ordered by distance from camera
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the multi-entity octree to search
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @return list of entity intersections sorted by distance from camera (closest first)
     */
    public static <ID extends EntityID, Content> List<EntityFrustumIntersection<ID, Content>> findEntitiesInFrustum(
            Frustum3D frustum,
            OctreeWithEntities<ID, Content> octree,
            Point3f cameraPosition) {
        
        validatePositiveCoordinates(cameraPosition);
        
        List<EntityFrustumIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Test if entity position is inside frustum
            if (frustum.containsPoint(position)) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distance = position.distance(cameraPosition);
                    results.add(new EntityFrustumIntersection<>(
                        entityId, content, position, distance, CullingResult.INSIDE
                    ));
                }
            }
        }
        
        // Sort by distance from camera
        results.sort(Comparator.comparingDouble(e -> e.distanceToCamera));
        
        return results;
    }
    
    /**
     * Find entities within the frustum with bounds-based culling
     * This method considers entity bounds for more accurate culling
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the multi-entity octree to search
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @return list of entity intersections sorted by distance from camera
     */
    public static <ID extends EntityID, Content> List<EntityFrustumIntersection<ID, Content>> findEntitiesInFrustumWithBounds(
            Frustum3D frustum,
            OctreeWithEntities<ID, Content> octree,
            Point3f cameraPosition) {
        
        validatePositiveCoordinates(cameraPosition);
        
        List<EntityFrustumIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Get entity bounds if available
            var bounds = octree.getEntityBounds(entityId);
            
            CullingResult cullingResult;
            if (bounds != null) {
                // Test bounds against frustum
                // Calculate radius as half the maximum extent
                float dx = bounds.getMaxX() - bounds.getMinX();
                float dy = bounds.getMaxY() - bounds.getMinY();
                float dz = bounds.getMaxZ() - bounds.getMinZ();
                float halfRadius = Math.max(Math.max(dx, dy), dz) / 2.0f;
                cullingResult = testBoundsAgainstFrustum(frustum, position, halfRadius);
            } else {
                // No bounds, just test point
                cullingResult = frustum.containsPoint(position) ? CullingResult.INSIDE : CullingResult.OUTSIDE;
            }
            
            if (cullingResult != CullingResult.OUTSIDE) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distance = position.distance(cameraPosition);
                    results.add(new EntityFrustumIntersection<>(
                        entityId, content, position, distance, cullingResult
                    ));
                }
            }
        }
        
        // Sort by distance from camera
        results.sort(Comparator.comparingDouble(e -> e.distanceToCamera));
        
        return results;
    }
    
    /**
     * Get entities visible from a specific viewpoint (basic occlusion)
     * This is a simplified version that only checks if entities are in front of the camera
     * 
     * @param frustum the camera frustum
     * @param octree the multi-entity octree to search
     * @param cameraPosition camera position
     * @param lookDirection normalized look direction vector
     * @return visible entities sorted by distance
     */
    public static <ID extends EntityID, Content> List<EntityFrustumIntersection<ID, Content>> findVisibleEntities(
            Frustum3D frustum,
            OctreeWithEntities<ID, Content> octree,
            Point3f cameraPosition,
            javax.vecmath.Vector3f lookDirection) {
        
        validatePositiveCoordinates(cameraPosition);
        
        List<EntityFrustumIntersection<ID, Content>> frustumEntities = 
            findEntitiesInFrustum(frustum, octree, cameraPosition);
        
        // Filter entities that are in front of the camera
        return frustumEntities.stream()
            .filter(entity -> {
                // Calculate vector from camera to entity
                javax.vecmath.Vector3f toEntity = new javax.vecmath.Vector3f(
                    entity.position.x - cameraPosition.x,
                    entity.position.y - cameraPosition.y,
                    entity.position.z - cameraPosition.z
                );
                // Check if entity is in front (positive dot product)
                return toEntity.dot(lookDirection) > 0;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Count entities by culling result
     * 
     * @param frustum the camera frustum
     * @param octree the multi-entity octree to search
     * @return array with [inside count, intersecting count, outside count]
     */
    public static <ID extends EntityID, Content> int[] countEntitiesByCullingResult(
            Frustum3D frustum,
            OctreeWithEntities<ID, Content> octree) {
        
        int inside = 0;
        int intersecting = 0;
        int outside = 0;
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Get entity bounds if available
            var bounds = octree.getEntityBounds(entityId);
            
            CullingResult result;
            if (bounds != null) {
                // Calculate radius as half the maximum extent
                float dx = bounds.getMaxX() - bounds.getMinX();
                float dy = bounds.getMaxY() - bounds.getMinY();
                float dz = bounds.getMaxZ() - bounds.getMinZ();
                float halfRadius = Math.max(Math.max(dx, dy), dz) / 2.0f;
                result = testBoundsAgainstFrustum(frustum, position, halfRadius);
            } else {
                result = frustum.containsPoint(position) ? CullingResult.INSIDE : CullingResult.OUTSIDE;
            }
            
            switch (result) {
                case INSIDE -> inside++;
                case INTERSECTING -> intersecting++;
                case OUTSIDE -> outside++;
            }
        }
        
        return new int[] { inside, intersecting, outside };
    }
    
    /**
     * Find entities within multiple frustums (useful for shadow mapping, multi-view rendering)
     * 
     * @param frustums list of camera frustums
     * @param octree the multi-entity octree to search
     * @param cameraPositions corresponding camera positions for each frustum
     * @return map of frustums to their visible entities
     */
    public static <ID extends EntityID, Content> Map<Frustum3D, List<EntityFrustumIntersection<ID, Content>>> 
            batchFrustumCulling(
            List<Frustum3D> frustums,
            OctreeWithEntities<ID, Content> octree,
            List<Point3f> cameraPositions) {
        
        if (frustums.size() != cameraPositions.size()) {
            throw new IllegalArgumentException("Frustums and camera positions must have same size");
        }
        
        Map<Frustum3D, List<EntityFrustumIntersection<ID, Content>>> results = new HashMap<>();
        
        for (int i = 0; i < frustums.size(); i++) {
            Frustum3D frustum = frustums.get(i);
            Point3f cameraPos = cameraPositions.get(i);
            
            List<EntityFrustumIntersection<ID, Content>> entities = 
                findEntitiesInFrustum(frustum, octree, cameraPos);
            
            results.put(frustum, entities);
        }
        
        return results;
    }
    
    /**
     * Find entities that are visible in any of the provided frustums
     * Useful for level-of-detail (LOD) systems
     * 
     * @param frustums list of camera frustums (e.g., near, medium, far LOD frustums)
     * @param octree the multi-entity octree to search
     * @param cameraPosition camera position for distance calculations
     * @return entities visible in any frustum, with the frustum index they appear in
     */
    public static <ID extends EntityID, Content> List<EntityFrustumIntersection<ID, Content>> 
            findEntitiesInAnyFrustum(
            List<Frustum3D> frustums,
            OctreeWithEntities<ID, Content> octree,
            Point3f cameraPosition) {
        
        validatePositiveCoordinates(cameraPosition);
        
        Map<ID, EntityFrustumIntersection<ID, Content>> uniqueEntities = new HashMap<>();
        
        // Check each frustum and collect unique entities
        for (Frustum3D frustum : frustums) {
            List<EntityFrustumIntersection<ID, Content>> frustumEntities = 
                findEntitiesInFrustum(frustum, octree, cameraPosition);
            
            for (EntityFrustumIntersection<ID, Content> entity : frustumEntities) {
                // Keep the closest culling result for each entity
                uniqueEntities.merge(entity.id, entity, (existing, newEntity) -> 
                    existing.distanceToCamera <= newEntity.distanceToCamera ? existing : newEntity
                );
            }
        }
        
        // Convert to list and sort by distance
        List<EntityFrustumIntersection<ID, Content>> results = new ArrayList<>(uniqueEntities.values());
        results.sort(Comparator.comparingDouble(e -> e.distanceToCamera));
        
        return results;
    }
    
    /**
     * Test if entity bounds intersect with frustum
     */
    private static CullingResult testBoundsAgainstFrustum(Frustum3D frustum, Point3f center, float radius) {
        // Create AABB from sphere bounds
        float minX = center.x - radius;
        float minY = center.y - radius;
        float minZ = center.z - radius;
        float maxX = center.x + radius;
        float maxY = center.y + radius;
        float maxZ = center.z + radius;
        
        // Ensure positive coordinates
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        minZ = Math.max(0, minZ);
        
        // Test containment
        if (frustum.containsAABB(minX, minY, minZ, maxX, maxY, maxZ)) {
            return CullingResult.INSIDE;
        }
        
        // Test intersection
        if (frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ)) {
            return CullingResult.INTERSECTING;
        }
        
        return CullingResult.OUTSIDE;
    }
    
    private static void validatePositiveCoordinates(Point3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point must have positive coordinates");
        }
    }
}