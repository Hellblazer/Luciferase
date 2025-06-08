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

/**
 * Multi-entity aware plane intersection search for OctreeWithEntities.
 * Finds all entities whose positions intersect with a 3D plane.
 *
 * @author hal.hildebrand
 */
public class MultiEntityPlaneIntersectionSearch {
    
    /**
     * Plane intersection result with entity information
     */
    public static class EntityPlaneIntersection<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distanceToPlane;
        public final float distanceToReferencePoint;
        
        public EntityPlaneIntersection(ID id, Content content, Point3f position, 
                                     float distanceToPlane, float distanceToReferencePoint) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distanceToPlane = distanceToPlane;
            this.distanceToReferencePoint = distanceToReferencePoint;
        }
    }
    
    /**
     * Find all entities that intersect with the plane, ordered by distance from reference point
     * 
     * @param plane the plane to test intersection with
     * @param octree the multi-entity octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param tolerance distance tolerance for plane intersection (entities within this distance are considered intersecting)
     * @return list of entity intersections sorted by distance from reference point
     */
    public static <ID extends EntityID, Content> List<EntityPlaneIntersection<ID, Content>> findEntitiesIntersectingPlane(
            Plane3D plane,
            OctreeWithEntities<ID, Content> octree,
            Point3f referencePoint,
            float tolerance) {
        
        validatePositiveCoordinates(referencePoint);
        
        List<EntityPlaneIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Calculate distance from point to plane
            float distanceToPlane = plane.distanceToPoint(position);
            
            // Check if entity is within tolerance of the plane
            if (Math.abs(distanceToPlane) <= tolerance) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distanceToRef = position.distance(referencePoint);
                    results.add(new EntityPlaneIntersection<>(
                        entityId, content, position, distanceToPlane, distanceToRef
                    ));
                }
            }
        }
        
        // Sort by distance from reference point
        results.sort(Comparator.comparingDouble(e -> e.distanceToReferencePoint));
        
        return results;
    }
    
    /**
     * Find entities on a specific side of the plane
     * 
     * @param plane the plane to test
     * @param octree the multi-entity octree to search
     * @param positiveSide if true, return entities on positive side of plane; if false, negative side
     * @return list of entities on the specified side of the plane
     */
    public static <ID extends EntityID, Content> List<EntityPlaneIntersection<ID, Content>> findEntitiesOnSideOfPlane(
            Plane3D plane,
            OctreeWithEntities<ID, Content> octree,
            boolean positiveSide) {
        
        List<EntityPlaneIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Calculate signed distance from point to plane
            float distanceToPlane = plane.distanceToPoint(position);
            
            // Check if entity is on the desired side
            if ((positiveSide && distanceToPlane > 0) || (!positiveSide && distanceToPlane < 0)) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    results.add(new EntityPlaneIntersection<>(
                        entityId, content, position, distanceToPlane, 0.0f
                    ));
                }
            }
        }
        
        // Sort by absolute distance to plane
        results.sort(Comparator.comparingDouble(e -> Math.abs(e.distanceToPlane)));
        
        return results;
    }
    
    /**
     * Find entities between two parallel planes
     * 
     * @param plane1 first plane
     * @param plane2 second plane (should be parallel to plane1)
     * @param octree the multi-entity octree to search
     * @return list of entities between the two planes
     */
    public static <ID extends EntityID, Content> List<EntityPlaneIntersection<ID, Content>> findEntitiesBetweenPlanes(
            Plane3D plane1,
            Plane3D plane2,
            OctreeWithEntities<ID, Content> octree) {
        
        List<EntityPlaneIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Calculate distances to both planes
            float distance1 = plane1.distanceToPoint(position);
            float distance2 = plane2.distanceToPoint(position);
            
            // Check if entity is between the planes (different signs means between)
            if (distance1 * distance2 <= 0) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    results.add(new EntityPlaneIntersection<>(
                        entityId, content, position, Math.min(Math.abs(distance1), Math.abs(distance2)), 0.0f
                    ));
                }
            }
        }
        
        return results;
    }
    
    /**
     * Find entities that intersect with multiple planes (useful for frustum culling setup)
     * 
     * @param planes array of planes to test
     * @param octree the multi-entity octree to search
     * @param requireAllPlanes if true, entity must intersect all planes; if false, any plane
     * @return list of entities meeting the intersection criteria
     */
    public static <ID extends EntityID, Content> List<EntityPlaneIntersection<ID, Content>> findEntitiesIntersectingMultiplePlanes(
            Plane3D[] planes,
            OctreeWithEntities<ID, Content> octree,
            boolean requireAllPlanes) {
        
        List<EntityPlaneIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            int intersectionCount = 0;
            float minDistance = Float.MAX_VALUE;
            
            // Test against all planes
            for (Plane3D plane : planes) {
                float distance = Math.abs(plane.distanceToPoint(position));
                if (distance < 0.001f) { // Small tolerance for intersection
                    intersectionCount++;
                    minDistance = Math.min(minDistance, distance);
                }
            }
            
            // Check if entity meets criteria
            boolean include = requireAllPlanes ? 
                (intersectionCount == planes.length) : 
                (intersectionCount > 0);
                
            if (include) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    results.add(new EntityPlaneIntersection<>(
                        entityId, content, position, minDistance, 0.0f
                    ));
                }
            }
        }
        
        return results;
    }
    
    /**
     * Count entities on each side of a plane
     * 
     * @param plane the plane to test
     * @param octree the multi-entity octree to search
     * @return array with [negative side count, on plane count, positive side count]
     */
    public static <ID extends EntityID, Content> int[] countEntitiesBySide(
            Plane3D plane,
            OctreeWithEntities<ID, Content> octree,
            float tolerance) {
        
        int negativeSide = 0;
        int onPlane = 0;
        int positiveSide = 0;
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Point3f position : entitiesWithPositions.values()) {
            float distance = plane.distanceToPoint(position);
            
            if (Math.abs(distance) <= tolerance) {
                onPlane++;
            } else if (distance < 0) {
                negativeSide++;
            } else {
                positiveSide++;
            }
        }
        
        return new int[] { negativeSide, onPlane, positiveSide };
    }
    
    private static void validatePositiveCoordinates(Point3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point must have positive coordinates");
        }
    }
}