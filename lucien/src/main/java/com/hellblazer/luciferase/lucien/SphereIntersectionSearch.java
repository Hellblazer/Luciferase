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
 * Multi-entity aware sphere intersection search for OctreeWithEntities.
 * Finds all entities whose positions intersect with 3D spheres.
 *
 * @author hal.hildebrand
 */
public class SphereIntersectionSearch {
    
    /**
     * Type of intersection between entity and sphere
     */
    public enum IntersectionType {
        INSIDE,       // Entity is inside sphere
        ON_SURFACE,   // Entity is on sphere surface (within tolerance)
        OUTSIDE       // Entity is outside sphere
    }
    
    /**
     * Sphere intersection result with entity information
     */
    public static class EntitySphereIntersection<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distanceToCenter;
        public final float distanceToReferencePoint;
        public final IntersectionType intersectionType;
        
        public EntitySphereIntersection(ID id, Content content, Point3f position, 
                                      float distanceToCenter, float distanceToReferencePoint,
                                      IntersectionType intersectionType) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distanceToCenter = distanceToCenter;
            this.distanceToReferencePoint = distanceToReferencePoint;
            this.intersectionType = intersectionType;
        }
    }
    
    /**
     * Find all entities that intersect with the sphere, ordered by distance from reference point
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the multi-entity octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param surfaceTolerance tolerance for considering entities "on surface" (e.g., 0.01f)
     * @return list of entity intersections sorted by distance from reference point
     */
    public static <ID extends EntityID, Content> List<EntitySphereIntersection<ID, Content>> findEntitiesIntersectingSphere(
            Point3f sphereCenter,
            float sphereRadius,
            OctreeWithEntities<ID, Content> octree,
            Point3f referencePoint,
            float surfaceTolerance) {
        
        validatePositiveCoordinates(sphereCenter);
        validatePositiveCoordinates(referencePoint);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive");
        }
        
        List<EntitySphereIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Calculate distance from entity to sphere center
            float distanceToCenter = position.distance(sphereCenter);
            
            // Determine intersection type
            IntersectionType intersectionType;
            if (Math.abs(distanceToCenter - sphereRadius) <= surfaceTolerance) {
                intersectionType = IntersectionType.ON_SURFACE;
            } else if (distanceToCenter < sphereRadius) {
                intersectionType = IntersectionType.INSIDE;
            } else {
                intersectionType = IntersectionType.OUTSIDE;
            }
            
            // Only include entities that intersect (inside or on surface)
            if (intersectionType != IntersectionType.OUTSIDE) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    float distanceToRef = position.distance(referencePoint);
                    results.add(new EntitySphereIntersection<>(
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
     * Find entities inside the sphere (excluding surface)
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the multi-entity octree to search
     * @return list of entities inside the sphere
     */
    public static <ID extends EntityID, Content> List<EntitySphereIntersection<ID, Content>> findEntitiesInsideSphere(
            Point3f sphereCenter,
            float sphereRadius,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(sphereCenter);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive");
        }
        
        List<EntitySphereIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Calculate distance from entity to sphere center
            float distanceToCenter = position.distance(sphereCenter);
            
            // Check if entity is inside sphere
            if (distanceToCenter < sphereRadius) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    results.add(new EntitySphereIntersection<>(
                        entityId, content, position, distanceToCenter, 0.0f, IntersectionType.INSIDE
                    ));
                }
            }
        }
        
        // Sort by distance from sphere center
        results.sort(Comparator.comparingDouble(e -> e.distanceToCenter));
        
        return results;
    }
    
    /**
     * Find entities within a spherical shell (between two concentric spheres)
     * 
     * @param center center of both spheres (positive coordinates only)
     * @param innerRadius inner sphere radius (positive)
     * @param outerRadius outer sphere radius (positive, must be > innerRadius)
     * @param octree the multi-entity octree to search
     * @return list of entities in the spherical shell
     */
    public static <ID extends EntityID, Content> List<EntitySphereIntersection<ID, Content>> findEntitiesInSphericalShell(
            Point3f center,
            float innerRadius,
            float outerRadius,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(center);
        
        if (innerRadius <= 0 || outerRadius <= 0) {
            throw new IllegalArgumentException("Radii must be positive");
        }
        if (outerRadius <= innerRadius) {
            throw new IllegalArgumentException("Outer radius must be greater than inner radius");
        }
        
        List<EntitySphereIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Calculate distance from entity to sphere center
            float distance = position.distance(center);
            
            // Check if entity is in the shell
            if (distance >= innerRadius && distance <= outerRadius) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    IntersectionType type = (distance == innerRadius || distance == outerRadius) ? 
                        IntersectionType.ON_SURFACE : IntersectionType.INSIDE;
                    results.add(new EntitySphereIntersection<>(
                        entityId, content, position, distance, 0.0f, type
                    ));
                }
            }
        }
        
        // Sort by distance from center
        results.sort(Comparator.comparingDouble(e -> e.distanceToCenter));
        
        return results;
    }
    
    /**
     * Count entities by sphere intersection type
     * 
     * @param sphereCenter center of the sphere
     * @param sphereRadius radius of the sphere
     * @param octree the multi-entity octree to search
     * @param surfaceTolerance tolerance for surface detection
     * @return array with [inside count, on surface count, outside count]
     */
    public static <ID extends EntityID, Content> int[] countEntitiesBySphereIntersection(
            Point3f sphereCenter,
            float sphereRadius,
            OctreeWithEntities<ID, Content> octree,
            float surfaceTolerance) {
        
        validatePositiveCoordinates(sphereCenter);
        
        int inside = 0;
        int onSurface = 0;
        int outside = 0;
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Point3f position : entitiesWithPositions.values()) {
            float distance = position.distance(sphereCenter);
            
            if (Math.abs(distance - sphereRadius) <= surfaceTolerance) {
                onSurface++;
            } else if (distance < sphereRadius) {
                inside++;
            } else {
                outside++;
            }
        }
        
        return new int[] { inside, onSurface, outside };
    }
    
    /**
     * Find entities that intersect with multiple spheres
     * Useful for complex spatial queries
     * 
     * @param spheres list of sphere queries (center and radius)
     * @param octree the multi-entity octree to search
     * @param requireAllSpheres if true, entity must intersect all spheres; if false, any sphere
     * @return list of entities meeting the intersection criteria
     */
    public static <ID extends EntityID, Content> List<EntitySphereIntersection<ID, Content>> findEntitiesIntersectingMultipleSpheres(
            List<SphereQuery> spheres,
            OctreeWithEntities<ID, Content> octree,
            boolean requireAllSpheres) {
        
        List<EntitySphereIntersection<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            int intersectionCount = 0;
            float minDistanceToCenter = Float.MAX_VALUE;
            
            // Test against all spheres
            for (SphereQuery sphere : spheres) {
                float distance = position.distance(sphere.center);
                if (distance <= sphere.radius) {
                    intersectionCount++;
                    minDistanceToCenter = Math.min(minDistanceToCenter, distance);
                }
            }
            
            // Check if entity meets criteria
            boolean include = requireAllSpheres ? 
                (intersectionCount == spheres.size()) : 
                (intersectionCount > 0);
                
            if (include) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    results.add(new EntitySphereIntersection<>(
                        entityId, content, position, minDistanceToCenter, 0.0f, IntersectionType.INSIDE
                    ));
                }
            }
        }
        
        return results;
    }
    
    /**
     * Batch processing for multiple sphere queries
     * 
     * @param sphereQueries list of sphere queries
     * @param octree the multi-entity octree to search
     * @param referencePoint reference point for distance calculations
     * @return map of sphere queries to their intersection results
     */
    public static <ID extends EntityID, Content> Map<SphereQuery, List<EntitySphereIntersection<ID, Content>>> 
            batchSphereIntersections(
            List<SphereQuery> sphereQueries,
            OctreeWithEntities<ID, Content> octree,
            Point3f referencePoint) {
        
        validatePositiveCoordinates(referencePoint);
        
        Map<SphereQuery, List<EntitySphereIntersection<ID, Content>>> results = new HashMap<>();
        
        for (SphereQuery query : sphereQueries) {
            List<EntitySphereIntersection<ID, Content>> intersections = 
                findEntitiesIntersectingSphere(query.center, query.radius, octree, referencePoint, 0.001f);
            results.put(query, intersections);
        }
        
        return results;
    }
    
    /**
     * Find the k nearest entities to a sphere surface
     * 
     * @param sphereCenter center of the sphere
     * @param sphereRadius radius of the sphere
     * @param octree the multi-entity octree to search
     * @param k number of nearest entities to find
     * @return k nearest entities to the sphere surface
     */
    public static <ID extends EntityID, Content> List<EntitySphereIntersection<ID, Content>> findKNearestToSphereSurface(
            Point3f sphereCenter,
            float sphereRadius,
            OctreeWithEntities<ID, Content> octree,
            int k) {
        
        validatePositiveCoordinates(sphereCenter);
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive");
        }
        
        List<EntitySphereIntersection<ID, Content>> allEntities = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            // Calculate distance from entity to sphere surface
            float distanceToCenter = position.distance(sphereCenter);
            float distanceToSurface = Math.abs(distanceToCenter - sphereRadius);
            
            Content content = octree.getEntity(entityId);
            if (content != null) {
                IntersectionType type = distanceToCenter < sphereRadius ? 
                    IntersectionType.INSIDE : IntersectionType.OUTSIDE;
                
                // Use distanceToSurface as the reference distance for sorting
                allEntities.add(new EntitySphereIntersection<>(
                    entityId, content, position, distanceToCenter, distanceToSurface, type
                ));
            }
        }
        
        // Sort by distance to surface and return top k
        allEntities.sort(Comparator.comparingDouble(e -> e.distanceToReferencePoint));
        
        return allEntities.stream()
            .limit(k)
            .collect(Collectors.toList());
    }
    
    /**
     * Represents a sphere query with center and radius
     */
    public static class SphereQuery {
        public final Point3f center;
        public final float radius;
        
        public SphereQuery(Point3f center, float radius) {
            validatePositiveCoordinates(center);
            if (radius <= 0) {
                throw new IllegalArgumentException("Sphere radius must be positive");
            }
            this.center = new Point3f(center); // Defensive copy
            this.radius = radius;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SphereQuery)) return false;
            SphereQuery other = (SphereQuery) obj;
            return center.equals(other.center) && Float.compare(radius, other.radius) == 0;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(center, radius);
        }
    }
    
    private static void validatePositiveCoordinates(Point3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point must have positive coordinates");
        }
    }
}