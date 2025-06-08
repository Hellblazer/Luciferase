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
 * Multi-entity aware Axis-Aligned Bounding Box (AABB) intersection search for OctreeWithEntities.
 * Finds all entities whose positions intersect with 3D AABBs.
 *
 * @author hal.hildebrand
 */
public class AABBIntersectionSearch {
    
    /**
     * Type of intersection between entity and AABB
     */
    public enum IntersectionType {
        INSIDE,       // Entity is inside AABB
        ON_BOUNDARY,  // Entity is on AABB boundary (within tolerance)
        OUTSIDE       // Entity is outside AABB
    }
    
    /**
     * AABB intersection result with entity information
     */
    public static class EntityAABBIntersection<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distanceToAABBCenter;
        public final float distanceToReferencePoint;
        public final IntersectionType intersectionType;
        
        public EntityAABBIntersection(ID id, Content content, Point3f position, 
                                    float distanceToAABBCenter, float distanceToReferencePoint,
                                    IntersectionType intersectionType) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distanceToAABBCenter = distanceToAABBCenter;
            this.distanceToReferencePoint = distanceToReferencePoint;
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
        public static AABB fromCenterAndHalfExtents(Point3f center, float halfWidth, float halfHeight, float halfDepth) {
            validatePositiveCoordinates(center);
            if (halfWidth <= 0 || halfHeight <= 0 || halfDepth <= 0) {
                throw new IllegalArgumentException("Half-extents must be positive");
            }
            
            return new AABB(
                center.x - halfWidth, center.y - halfHeight, center.z - halfDepth,
                center.x + halfWidth, center.y + halfHeight, center.z + halfDepth
            );
        }
        
        /**
         * Create AABB from two corner points
         */
        public static AABB fromCorners(Point3f corner1, Point3f corner2) {
            validatePositiveCoordinates(corner1);
            validatePositiveCoordinates(corner2);
            
            float minX = Math.min(corner1.x, corner2.x);
            float minY = Math.min(corner1.y, corner2.y);
            float minZ = Math.min(corner1.z, corner2.z);
            float maxX = Math.max(corner1.x, corner2.x);
            float maxY = Math.max(corner1.y, corner2.y);
            float maxZ = Math.max(corner1.z, corner2.z);
            
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
        
        public Point3f getCenter() {
            return new Point3f(
                (minX + maxX) / 2.0f,
                (minY + maxY) / 2.0f,
                (minZ + maxZ) / 2.0f
            );
        }
        
        public float getWidth() {
            return maxX - minX;
        }
        
        public float getHeight() {
            return maxY - minY;
        }
        
        public float getDepth() {
            return maxZ - minZ;
        }
        
        public float getVolume() {
            return getWidth() * getHeight() * getDepth();
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AABB)) return false;
            AABB other = (AABB) obj;
            return Float.compare(minX, other.minX) == 0 &&
                   Float.compare(minY, other.minY) == 0 &&
                   Float.compare(minZ, other.minZ) == 0 &&
                   Float.compare(maxX, other.maxX) == 0 &&
                   Float.compare(maxY, other.maxY) == 0 &&
                   Float.compare(maxZ, other.maxZ) == 0;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
    
    /**
     * Find all content within the specified AABB (simplified version for SpatialIndex)
     * 
     * @param aabb the axis-aligned bounding box
     * @param spatialIndex the spatial index to search
     * @return list of content intersecting the AABB
     */
    public static <Content> List<Content> findContentInAABB(
            AABB aabb,
            SpatialIndex<Content> spatialIndex) {
        
        validatePositiveCoordinates(aabb.getCenter());
        
        // Convert AABB to Spatial.Cube for spatial index query
        Spatial.Cube cube = new Spatial.Cube(
            aabb.minX, aabb.minY, aabb.minZ,
            aabb.maxX - aabb.minX  // extent = max - min
        );
        
        return spatialIndex.bounding(cube)
                          .map(node -> node.content())
                          .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Find all entities that intersect with the AABB, ordered by distance from reference point
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param octree the multi-entity octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param boundaryTolerance tolerance for considering entities "on boundary" (e.g., 0.01f)
     * @return list of entity intersections sorted by distance from reference point
     */
    public static <ID extends EntityID, Content> List<EntityAABBIntersection<ID, Content>> findEntitiesIntersectingAABB(
            AABB aabb,
            OctreeWithEntities<ID, Content> octree,
            Point3f referencePoint,
            float boundaryTolerance) {
        
        validatePositiveCoordinates(referencePoint);
        
        List<EntityAABBIntersection<ID, Content>> results = new ArrayList<>();
        Point3f aabbCenter = aabb.getCenter();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Determine intersection type
            IntersectionType intersectionType = testPointAABBIntersection(position, aabb, boundaryTolerance);
            
            // Only include entities that intersect (inside or on boundary)
            if (intersectionType != IntersectionType.OUTSIDE) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distanceToCenter = position.distance(aabbCenter);
                    float distanceToRef = position.distance(referencePoint);
                    results.add(new EntityAABBIntersection<>(
                        entityId, content, position, distanceToCenter, distanceToRef, intersectionType
                    ));
                }
            }
        }
        
        // Sort by distance from reference point
        results.sort(Comparator.comparingDouble(e -> e.distanceToReferencePoint));
        
        return results;
    }
    
    /**
     * Find entities inside the AABB (excluding boundary)
     * 
     * @param aabb the axis-aligned bounding box
     * @param octree the multi-entity octree to search
     * @return list of entities inside the AABB
     */
    public static <ID extends EntityID, Content> List<EntityAABBIntersection<ID, Content>> findEntitiesInsideAABB(
            AABB aabb,
            OctreeWithEntities<ID, Content> octree) {
        
        List<EntityAABBIntersection<ID, Content>> results = new ArrayList<>();
        Point3f aabbCenter = aabb.getCenter();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Check if entity is inside AABB (strict containment)
            if (position.x > aabb.minX && position.x < aabb.maxX &&
                position.y > aabb.minY && position.y < aabb.maxY &&
                position.z > aabb.minZ && position.z < aabb.maxZ) {
                
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distanceToCenter = position.distance(aabbCenter);
                    results.add(new EntityAABBIntersection<>(
                        entityId, content, position, distanceToCenter, 0.0f, IntersectionType.INSIDE
                    ));
                }
            }
        }
        
        // Sort by distance from AABB center
        results.sort(Comparator.comparingDouble(e -> e.distanceToAABBCenter));
        
        return results;
    }
    
    /**
     * Find entities that intersect with multiple AABBs
     * Useful for complex spatial queries
     * 
     * @param aabbs list of AABBs to test
     * @param octree the multi-entity octree to search
     * @param requireAllAABBs if true, entity must intersect all AABBs; if false, any AABB
     * @return list of entities meeting the intersection criteria
     */
    public static <ID extends EntityID, Content> List<EntityAABBIntersection<ID, Content>> findEntitiesIntersectingMultipleAABBs(
            List<AABB> aabbs,
            OctreeWithEntities<ID, Content> octree,
            boolean requireAllAABBs) {
        
        List<EntityAABBIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            int intersectionCount = 0;
            float minDistanceToCenter = Float.MAX_VALUE;
            
            // Test against all AABBs
            for (AABB aabb : aabbs) {
                if (isPointInsideAABB(position, aabb)) {
                    intersectionCount++;
                    float distance = position.distance(aabb.getCenter());
                    minDistanceToCenter = Math.min(minDistanceToCenter, distance);
                }
            }
            
            // Check if entity meets criteria
            boolean include = requireAllAABBs ? 
                (intersectionCount == aabbs.size()) : 
                (intersectionCount > 0);
                
            if (include) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    results.add(new EntityAABBIntersection<>(
                        entityId, content, position, minDistanceToCenter, 0.0f, IntersectionType.INSIDE
                    ));
                }
            }
        }
        
        return results;
    }
    
    /**
     * Count entities by AABB intersection type
     * 
     * @param aabb the axis-aligned bounding box
     * @param octree the multi-entity octree to search
     * @param boundaryTolerance tolerance for boundary detection
     * @return array with [inside count, on boundary count, outside count]
     */
    public static <ID extends EntityID, Content> int[] countEntitiesByAABBIntersection(
            AABB aabb,
            OctreeWithEntities<ID, Content> octree,
            float boundaryTolerance) {
        
        int inside = 0;
        int onBoundary = 0;
        int outside = 0;
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Point3f position : entitiesWithPositions.values()) {
            IntersectionType type = testPointAABBIntersection(position, aabb, boundaryTolerance);
            
            switch (type) {
                case INSIDE -> inside++;
                case ON_BOUNDARY -> onBoundary++;
                case OUTSIDE -> outside++;
            }
        }
        
        return new int[] { inside, onBoundary, outside };
    }
    
    /**
     * Find entities in the intersection of two AABBs
     * 
     * @param aabb1 first AABB
     * @param aabb2 second AABB
     * @param octree the multi-entity octree to search
     * @return entities in the intersection region
     */
    public static <ID extends EntityID, Content> List<EntityAABBIntersection<ID, Content>> findEntitiesInAABBIntersection(
            AABB aabb1,
            AABB aabb2,
            OctreeWithEntities<ID, Content> octree) {
        
        // Calculate intersection AABB
        float intersectMinX = Math.max(aabb1.minX, aabb2.minX);
        float intersectMinY = Math.max(aabb1.minY, aabb2.minY);
        float intersectMinZ = Math.max(aabb1.minZ, aabb2.minZ);
        float intersectMaxX = Math.min(aabb1.maxX, aabb2.maxX);
        float intersectMaxY = Math.min(aabb1.maxY, aabb2.maxY);
        float intersectMaxZ = Math.min(aabb1.maxZ, aabb2.maxZ);
        
        // Check if there's a valid intersection
        if (intersectMaxX <= intersectMinX || intersectMaxY <= intersectMinY || intersectMaxZ <= intersectMinZ) {
            return new ArrayList<>(); // No intersection
        }
        
        // Create intersection AABB
        AABB intersectionAABB = new AABB(
            intersectMinX, intersectMinY, intersectMinZ,
            intersectMaxX, intersectMaxY, intersectMaxZ
        );
        
        return findEntitiesInsideAABB(intersectionAABB, octree);
    }
    
    /**
     * Batch processing for multiple AABB queries
     * 
     * @param aabbQueries list of AABBs to test
     * @param octree the multi-entity octree to search
     * @param referencePoint reference point for distance calculations
     * @return map of AABBs to their intersection results
     */
    public static <ID extends EntityID, Content> Map<AABB, List<EntityAABBIntersection<ID, Content>>> 
            batchAABBIntersections(
            List<AABB> aabbQueries,
            OctreeWithEntities<ID, Content> octree,
            Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        Map<AABB, List<EntityAABBIntersection<ID, Content>>> results = new HashMap<>();
        
        for (AABB aabb : aabbQueries) {
            List<EntityAABBIntersection<ID, Content>> intersections = 
                findEntitiesIntersectingAABB(aabb, octree, referencePoint, 0.001f);
            results.put(aabb, intersections);
        }
        
        return results;
    }
    
    /**
     * Find entities within AABB bounds considering entity bounds
     * This method is more accurate for entities with spatial extent
     * 
     * @param aabb the axis-aligned bounding box
     * @param octree the multi-entity octree to search
     * @param referencePoint reference point for distance calculations
     * @return entities whose bounds intersect with the AABB
     */
    public static <ID extends EntityID, Content> List<EntityAABBIntersection<ID, Content>> 
            findEntitiesWithBoundsIntersectingAABB(
            AABB aabb,
            OctreeWithEntities<ID, Content> octree,
            Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        List<EntityAABBIntersection<ID, Content>> results = new ArrayList<>();
        Point3f aabbCenter = aabb.getCenter();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Get entity bounds if available
            var bounds = octree.getEntityBounds(entityId);
            
            boolean intersects = false;
            if (bounds != null) {
                // Test bounds intersection with AABB
                intersects = boundsIntersectsAABB(bounds, aabb);
            } else {
                // No bounds, just test point
                intersects = isPointInsideAABB(position, aabb);
            }
            
            if (intersects) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distanceToCenter = position.distance(aabbCenter);
                    float distanceToRef = position.distance(referencePoint);
                    IntersectionType type = isPointInsideAABB(position, aabb) ? 
                        IntersectionType.INSIDE : IntersectionType.ON_BOUNDARY;
                    
                    results.add(new EntityAABBIntersection<>(
                        entityId, content, position, distanceToCenter, distanceToRef, type
                    ));
                }
            }
        }
        
        // Sort by distance from reference point
        results.sort(Comparator.comparingDouble(e -> e.distanceToReferencePoint));
        
        return results;
    }
    
    /**
     * Test if a point intersects with an AABB
     */
    private static IntersectionType testPointAABBIntersection(Point3f point, AABB aabb, float tolerance) {
        // Check if completely outside (with tolerance)
        if (point.x < aabb.minX - tolerance || point.x > aabb.maxX + tolerance ||
            point.y < aabb.minY - tolerance || point.y > aabb.maxY + tolerance ||
            point.z < aabb.minZ - tolerance || point.z > aabb.maxZ + tolerance) {
            return IntersectionType.OUTSIDE;
        }
        
        // Check if on boundary (within tolerance of any face)
        boolean nearMinX = Math.abs(point.x - aabb.minX) <= tolerance;
        boolean nearMaxX = Math.abs(point.x - aabb.maxX) <= tolerance;
        boolean nearMinY = Math.abs(point.y - aabb.minY) <= tolerance;
        boolean nearMaxY = Math.abs(point.y - aabb.maxY) <= tolerance;
        boolean nearMinZ = Math.abs(point.z - aabb.minZ) <= tolerance;
        boolean nearMaxZ = Math.abs(point.z - aabb.maxZ) <= tolerance;
        
        if ((nearMinX || nearMaxX) && point.y >= aabb.minY && point.y <= aabb.maxY && 
            point.z >= aabb.minZ && point.z <= aabb.maxZ) {
            return IntersectionType.ON_BOUNDARY;
        }
        if ((nearMinY || nearMaxY) && point.x >= aabb.minX && point.x <= aabb.maxX && 
            point.z >= aabb.minZ && point.z <= aabb.maxZ) {
            return IntersectionType.ON_BOUNDARY;
        }
        if ((nearMinZ || nearMaxZ) && point.x >= aabb.minX && point.x <= aabb.maxX && 
            point.y >= aabb.minY && point.y <= aabb.maxY) {
            return IntersectionType.ON_BOUNDARY;
        }
        
        // Must be inside
        return IntersectionType.INSIDE;
    }
    
    /**
     * Test if a point is inside an AABB (inclusive of boundaries)
     */
    private static boolean isPointInsideAABB(Point3f point, AABB aabb) {
        return point.x >= aabb.minX && point.x <= aabb.maxX &&
               point.y >= aabb.minY && point.y <= aabb.maxY &&
               point.z >= aabb.minZ && point.z <= aabb.maxZ;
    }
    
    /**
     * Test if entity bounds intersect with AABB
     */
    private static boolean boundsIntersectsAABB(com.hellblazer.luciferase.lucien.entity.EntityBounds bounds, AABB aabb) {
        // Check for separating axis
        return !(bounds.getMaxX() < aabb.minX || bounds.getMinX() > aabb.maxX ||
                 bounds.getMaxY() < aabb.minY || bounds.getMinY() > aabb.maxY ||
                 bounds.getMaxZ() < aabb.minZ || bounds.getMinZ() > aabb.maxZ);
    }
    
    private static void validatePositiveCoordinates(Point3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point must have positive coordinates");
        }
    }
}