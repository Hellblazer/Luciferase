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
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-entity aware visibility search for OctreeWithEntities.
 * Performs line-of-sight tests, occlusion queries, and visibility analysis with entity awareness.
 *
 * @author hal.hildebrand
 */
public class MultiEntityVisibilitySearch {
    
    /**
     * Type of visibility relationship
     */
    public enum VisibilityType {
        VISIBLE,              // Not blocking line of sight
        PARTIALLY_OCCLUDING,  // Partially blocking line of sight
        FULLY_OCCLUDING,      // Completely blocking line of sight
        BEHIND_TARGET,        // Behind the target (not relevant for occlusion)
        BEFORE_OBSERVER       // In front of observer but not blocking target
    }
    
    /**
     * Entity visibility result with occlusion information
     */
    public static class EntityVisibilityResult<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distanceFromObserver;
        public final float distanceFromTarget;
        public final VisibilityType visibilityType;
        public final float occlusionRatio; // percentage of line-of-sight blocked by this entity
        
        public EntityVisibilityResult(ID id, Content content, Point3f position,
                                    float distanceFromObserver, float distanceFromTarget,
                                    VisibilityType visibilityType, float occlusionRatio) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distanceFromObserver = distanceFromObserver;
            this.distanceFromTarget = distanceFromTarget;
            this.visibilityType = visibilityType;
            this.occlusionRatio = occlusionRatio;
        }
    }
    
    /**
     * Line-of-sight test result with entity information
     */
    public static class EntityLineOfSightResult<ID extends EntityID, Content> {
        public final boolean hasLineOfSight;
        public final List<EntityVisibilityResult<ID, Content>> occludingEntities;
        public final float totalOcclusionRatio;
        public final float distanceThroughOccluders;
        public final Map<ID, Float> entityOcclusionContributions; // How much each entity contributes to occlusion
        
        public EntityLineOfSightResult(boolean hasLineOfSight, 
                                     List<EntityVisibilityResult<ID, Content>> occludingEntities,
                                     float totalOcclusionRatio, float distanceThroughOccluders,
                                     Map<ID, Float> entityOcclusionContributions) {
            this.hasLineOfSight = hasLineOfSight;
            this.occludingEntities = occludingEntities;
            this.totalOcclusionRatio = totalOcclusionRatio;
            this.distanceThroughOccluders = distanceThroughOccluders;
            this.entityOcclusionContributions = entityOcclusionContributions;
        }
    }
    
    /**
     * Test line of sight between two points with entity awareness
     * 
     * @param observer the observer position (positive coordinates only)
     * @param target the target position (positive coordinates only)
     * @param octree the multi-entity octree to search for occluders
     * @param occlusionThreshold minimum entity size to consider as occluder
     * @return line of sight test result with occluding entities
     */
    public static <ID extends EntityID, Content> EntityLineOfSightResult<ID, Content> testLineOfSight(
            Point3f observer,
            Point3f target,
            OctreeWithEntities<ID, Content> octree,
            float occlusionThreshold) {
        
        validatePositiveCoordinates(observer);
        validatePositiveCoordinates(target);
        
        if (occlusionThreshold < 0) {
            throw new IllegalArgumentException("Occlusion threshold must be non-negative");
        }
        
        // Create ray from observer to target
        Vector3f direction = new Vector3f(target.x - observer.x, target.y - observer.y, target.z - observer.z);
        float totalDistance = direction.length();
        
        if (totalDistance < 1e-6f) {
            return new EntityLineOfSightResult<>(true, Collections.emptyList(), 0.0f, 0.0f, new HashMap<>());
        }
        
        direction.normalize();
        Ray3D sightRay = new Ray3D(observer, direction);
        
        List<EntityVisibilityResult<ID, Content>> occludingEntities = new ArrayList<>();
        Map<ID, Float> entityOcclusionContributions = new HashMap<>();
        float totalOcclusionRatio = 0.0f;
        float distanceThroughOccluders = 0.0f;
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f entityPos = entry.getValue();
            
            // Get entity bounds for more accurate occlusion testing
            EntityBounds bounds = octree.getEntityBounds(entityId);
            
            // Check if entity/bounds are large enough to be an occluder
            float entitySize = bounds != null ? calculateBoundsSize(bounds) : occlusionThreshold;
            if (entitySize < occlusionThreshold) {
                continue;
            }
            
            // Test intersection with ray
            RayIntersection intersection = bounds != null ?
                testRayBoundsIntersection(sightRay, bounds) :
                testRayPointIntersection(sightRay, entityPos, occlusionThreshold);
                
            if (intersection.intersects && intersection.distance < totalDistance) {
                float distanceFromObserver = observer.distance(entityPos);
                float distanceFromTarget = target.distance(entityPos);
                
                // Calculate occlusion contribution
                float occlusionRatio = calculateEntityOcclusionRatio(
                    entityPos, bounds, sightRay, intersection.distance, totalDistance
                );
                
                if (occlusionRatio > 0) {
                    VisibilityType visType = classifyEntityVisibility(
                        entityPos, bounds, observer, target, intersection.distance, totalDistance
                    );
                    
                    if (visType == VisibilityType.PARTIALLY_OCCLUDING || 
                        visType == VisibilityType.FULLY_OCCLUDING) {
                        
                        Content content = octree.getEntity(entityId);
                        if (content != null) {
                            EntityVisibilityResult<ID, Content> result = new EntityVisibilityResult<>(
                                entityId, content, entityPos, distanceFromObserver, 
                                distanceFromTarget, visType, occlusionRatio
                            );
                            
                            occludingEntities.add(result);
                            entityOcclusionContributions.put(entityId, occlusionRatio);
                            totalOcclusionRatio = Math.min(1.0f, totalOcclusionRatio + occlusionRatio);
                            
                            float occluderThickness = bounds != null ? 
                                calculateBoundsThicknessAlongRay(bounds, sightRay) : 
                                occlusionThreshold;
                            distanceThroughOccluders += occluderThickness;
                        }
                    }
                }
            }
        }
        
        // Sort occluding entities by distance from observer
        occludingEntities.sort(Comparator.comparingDouble(e -> e.distanceFromObserver));
        
        // Determine if line of sight is blocked (50% occlusion threshold)
        boolean hasLineOfSight = totalOcclusionRatio < 0.5f;
        
        return new EntityLineOfSightResult<>(
            hasLineOfSight, occludingEntities, totalOcclusionRatio, 
            distanceThroughOccluders, entityOcclusionContributions
        );
    }
    
    /**
     * Find all entities visible from an observer position within a viewing cone
     * 
     * @param observer the observer position (positive coordinates only)
     * @param viewDirection the viewing direction (will be normalized)
     * @param viewAngle the viewing angle in radians (cone half-angle)
     * @param maxViewDistance maximum viewing distance
     * @param octree the multi-entity octree to search
     * @return list of visible entities sorted by distance
     */
    public static <ID extends EntityID, Content> List<EntityVisibilityResult<ID, Content>> findVisibleEntities(
            Point3f observer,
            Vector3f viewDirection,
            float viewAngle,
            float maxViewDistance,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(observer);
        
        if (viewAngle < 0 || viewAngle > Math.PI) {
            throw new IllegalArgumentException("View angle must be between 0 and π radians");
        }
        
        if (maxViewDistance < 0) {
            throw new IllegalArgumentException("Max view distance must be non-negative");
        }
        
        Vector3f normalizedDirection = new Vector3f(viewDirection);
        normalizedDirection.normalize();
        
        List<EntityVisibilityResult<ID, Content>> visibleEntities = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f entityPos = entry.getValue();
            
            float distanceFromObserver = observer.distance(entityPos);
            
            // Check if entity is within viewing distance
            if (distanceFromObserver > maxViewDistance) {
                continue;
            }
            
            // Check if entity is within viewing cone
            Vector3f toEntity = new Vector3f(
                entityPos.x - observer.x, 
                entityPos.y - observer.y, 
                entityPos.z - observer.z
            );
            toEntity.normalize();
            
            float angle = normalizedDirection.angle(toEntity);
            if (angle <= viewAngle) {
                // Entity is within viewing cone - perform visibility test
                EntityLineOfSightResult<ID, Content> losResult = 
                    testLineOfSight(observer, entityPos, octree, 0.1f);
                
                if (losResult.hasLineOfSight || isEntitySelfVisible(entityId, losResult)) {
                    Content content = octree.getEntity(entityId);
                    if (content != null) {
                        // Calculate distance to target point along view direction
                        Point3f targetPoint = new Point3f();
                        targetPoint.scaleAdd(maxViewDistance, normalizedDirection, observer);
                        float distanceFromTarget = entityPos.distance(targetPoint);
                        
                        EntityVisibilityResult<ID, Content> result = new EntityVisibilityResult<>(
                            entityId, content, entityPos, distanceFromObserver,
                            distanceFromTarget, VisibilityType.VISIBLE, 0.0f
                        );
                        
                        visibleEntities.add(result);
                    }
                }
            }
        }
        
        // Sort by distance from observer
        visibleEntities.sort(Comparator.comparingDouble(e -> e.distanceFromObserver));
        
        return visibleEntities;
    }
    
    /**
     * Find entities occluding a specific target from an observer
     * 
     * @param observer the observer position
     * @param target the target position to view
     * @param octree the multi-entity octree
     * @return entities that occlude the target, sorted by occlusion contribution
     */
    public static <ID extends EntityID, Content> List<EntityVisibilityResult<ID, Content>> findOccludingEntities(
            Point3f observer,
            Point3f target,
            OctreeWithEntities<ID, Content> octree) {
        
        EntityLineOfSightResult<ID, Content> losResult = testLineOfSight(observer, target, octree, 0.1f);
        
        // Sort by occlusion contribution (highest first)
        List<EntityVisibilityResult<ID, Content>> occluders = new ArrayList<>(losResult.occludingEntities);
        occluders.sort((e1, e2) -> Float.compare(e2.occlusionRatio, e1.occlusionRatio));
        
        return occluders;
    }
    
    /**
     * Calculate visibility statistics from an observer position with entity information
     * 
     * @param observer the observer position
     * @param maxViewDistance maximum viewing distance for analysis
     * @param octree the multi-entity octree to analyze
     * @return statistics about entity visibility from the observer position
     */
    public static <ID extends EntityID, Content> EntityVisibilityStatistics calculateVisibilityStatistics(
            Point3f observer,
            float maxViewDistance,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(observer);
        
        if (maxViewDistance < 0) {
            throw new IllegalArgumentException("Max view distance must be non-negative");
        }
        
        long totalEntities = 0;
        long visibleEntities = 0;
        long partiallyOccludedEntities = 0;
        long fullyOccludedEntities = 0;
        long entitiesOutOfRange = 0;
        float totalVisibilityRatio = 0.0f;
        
        // Track which entities occlude others
        Map<ID, Integer> occluderCounts = new HashMap<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            totalEntities++;
            ID entityId = entry.getKey();
            Point3f entityPos = entry.getValue();
            
            float distance = observer.distance(entityPos);
            if (distance > maxViewDistance) {
                entitiesOutOfRange++;
                continue;
            }
            
            // Test visibility
            EntityLineOfSightResult<ID, Content> losResult = 
                testLineOfSight(observer, entityPos, octree, 0.01f);
            
            // Track occluders
            for (EntityVisibilityResult<ID, Content> occluder : losResult.occludingEntities) {
                if (!occluder.id.equals(entityId)) { // Don't count self-occlusion
                    occluderCounts.merge(occluder.id, 1, Integer::sum);
                }
            }
            
            if (losResult.hasLineOfSight || isEntitySelfVisible(entityId, losResult)) {
                visibleEntities++;
                totalVisibilityRatio += (1.0f - losResult.totalOcclusionRatio);
            } else if (losResult.totalOcclusionRatio < 1.0f) {
                partiallyOccludedEntities++;
                totalVisibilityRatio += (1.0f - losResult.totalOcclusionRatio);
            } else {
                fullyOccludedEntities++;
            }
        }
        
        float averageVisibilityRatio = (visibleEntities + partiallyOccludedEntities) > 0 ?
            totalVisibilityRatio / (visibleEntities + partiallyOccludedEntities) : 0.0f;
        
        // Find most significant occluders
        List<ID> topOccluders = occluderCounts.entrySet().stream()
            .sorted(Map.Entry.<ID, Integer>comparingByValue().reversed())
            .limit(5)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        return new EntityVisibilityStatistics(
            totalEntities, visibleEntities, partiallyOccludedEntities,
            fullyOccludedEntities, entitiesOutOfRange, totalVisibilityRatio,
            averageVisibilityRatio, occluderCounts, topOccluders
        );
    }
    
    /**
     * Find the best vantage points for observing a target entity
     * 
     * @param targetEntityId the entity to observe
     * @param candidatePositions list of candidate observer positions
     * @param octree the multi-entity octree
     * @return list of vantage points sorted by visibility quality
     */
    public static <ID extends EntityID, Content> List<EntityVantagePoint<ID>> findBestVantagePointsForEntity(
            ID targetEntityId,
            List<Point3f> candidatePositions,
            OctreeWithEntities<ID, Content> octree) {
        
        Point3f targetPos = octree.getEntityPosition(targetEntityId);
        if (targetPos == null) {
            return new ArrayList<>();
        }
        
        for (Point3f pos : candidatePositions) {
            validatePositiveCoordinates(pos);
        }
        
        List<EntityVantagePoint<ID>> vantagePoints = new ArrayList<>();
        
        for (Point3f candidate : candidatePositions) {
            EntityLineOfSightResult<ID, Content> losResult = 
                testLineOfSight(candidate, targetPos, octree, 0.1f);
            
            float distance = candidate.distance(targetPos);
            float visibilityScore = calculateVisibilityScore(losResult, distance);
            
            // Check if target entity itself is blocking the view
            boolean selfOccluding = losResult.occludingEntities.stream()
                .anyMatch(e -> e.id.equals(targetEntityId));
            
            EntityVantagePoint<ID> vantagePoint = new EntityVantagePoint<>(
                candidate, targetEntityId, distance, losResult.hasLineOfSight || selfOccluding,
                losResult.totalOcclusionRatio, visibilityScore, 
                losResult.occludingEntities.size()
            );
            
            vantagePoints.add(vantagePoint);
        }
        
        // Sort by visibility score (higher is better)
        vantagePoints.sort((vp1, vp2) -> Float.compare(vp2.visibilityScore, vp1.visibilityScore));
        
        return vantagePoints;
    }
    
    /**
     * Statistics about entity visibility from an observer position
     */
    public static class EntityVisibilityStatistics {
        public final long totalEntities;
        public final long visibleEntities;
        public final long partiallyOccludedEntities;
        public final long fullyOccludedEntities;
        public final long entitiesOutOfRange;
        public final float totalVisibilityRatio;
        public final float averageVisibilityRatio;
        public final Map<? extends EntityID, Integer> occluderFrequency; // How often each entity occludes others
        public final List<? extends EntityID> topOccluders; // Most significant occluders
        
        public EntityVisibilityStatistics(long totalEntities, long visibleEntities, 
                                        long partiallyOccludedEntities, long fullyOccludedEntities,
                                        long entitiesOutOfRange, float totalVisibilityRatio,
                                        float averageVisibilityRatio, 
                                        Map<? extends EntityID, Integer> occluderFrequency,
                                        List<? extends EntityID> topOccluders) {
            this.totalEntities = totalEntities;
            this.visibleEntities = visibleEntities;
            this.partiallyOccludedEntities = partiallyOccludedEntities;
            this.fullyOccludedEntities = fullyOccludedEntities;
            this.entitiesOutOfRange = entitiesOutOfRange;
            this.totalVisibilityRatio = totalVisibilityRatio;
            this.averageVisibilityRatio = averageVisibilityRatio;
            this.occluderFrequency = occluderFrequency;
            this.topOccluders = topOccluders;
        }
        
        public double getVisiblePercentage() {
            long inRange = totalEntities - entitiesOutOfRange;
            return inRange > 0 ? (double) visibleEntities / inRange * 100.0 : 0.0;
        }
        
        public double getPartiallyOccludedPercentage() {
            long inRange = totalEntities - entitiesOutOfRange;
            return inRange > 0 ? (double) partiallyOccludedEntities / inRange * 100.0 : 0.0;
        }
        
        public double getFullyOccludedPercentage() {
            long inRange = totalEntities - entitiesOutOfRange;
            return inRange > 0 ? (double) fullyOccludedEntities / inRange * 100.0 : 0.0;
        }
    }
    
    /**
     * Represents a vantage point for observing an entity
     */
    public static class EntityVantagePoint<ID extends EntityID> {
        public final Point3f position;
        public final ID targetEntityId;
        public final float distanceToTarget;
        public final boolean hasLineOfSight;
        public final float occlusionRatio;
        public final float visibilityScore;
        public final int occluderCount;
        
        public EntityVantagePoint(Point3f position, ID targetEntityId, float distanceToTarget,
                                boolean hasLineOfSight, float occlusionRatio, float visibilityScore,
                                int occluderCount) {
            this.position = position;
            this.targetEntityId = targetEntityId;
            this.distanceToTarget = distanceToTarget;
            this.hasLineOfSight = hasLineOfSight;
            this.occlusionRatio = occlusionRatio;
            this.visibilityScore = visibilityScore;
            this.occluderCount = occluderCount;
        }
    }
    
    /**
     * Ray intersection result
     */
    private static class RayIntersection {
        public final boolean intersects;
        public final float distance;
        
        public RayIntersection(boolean intersects, float distance) {
            this.intersects = intersects;
            this.distance = distance;
        }
    }
    
    // Helper methods
    
    /**
     * Check if an entity is self-visible (occluded only by itself)
     */
    private static <ID extends EntityID, Content> boolean isEntitySelfVisible(
            ID entityId, EntityLineOfSightResult<ID, Content> losResult) {
        return losResult.occludingEntities.size() == 1 && 
               losResult.occludingEntities.get(0).id.equals(entityId);
    }
    
    /**
     * Calculate the size of entity bounds
     */
    private static float calculateBoundsSize(EntityBounds bounds) {
        float width = bounds.getMaxX() - bounds.getMinX();
        float height = bounds.getMaxY() - bounds.getMinY();
        float depth = bounds.getMaxZ() - bounds.getMinZ();
        return Math.max(Math.max(width, height), depth);
    }
    
    /**
     * Calculate thickness of bounds along a ray
     */
    private static float calculateBoundsThicknessAlongRay(EntityBounds bounds, Ray3D ray) {
        // Simplified - calculate diagonal size
        float width = bounds.getMaxX() - bounds.getMinX();
        float height = bounds.getMaxY() - bounds.getMinY();
        float depth = bounds.getMaxZ() - bounds.getMinZ();
        return (float) Math.sqrt(width * width + height * height + depth * depth);
    }
    
    /**
     * Test ray intersection with entity bounds
     */
    private static RayIntersection testRayBoundsIntersection(Ray3D ray, EntityBounds bounds) {
        Point3f origin = ray.origin();
        Vector3f direction = ray.direction();
        
        float tmin = Float.NEGATIVE_INFINITY;
        float tmax = Float.POSITIVE_INFINITY;
        
        // Test each axis
        for (int axis = 0; axis < 3; axis++) {
            float originCoord = axis == 0 ? origin.x : (axis == 1 ? origin.y : origin.z);
            float dirCoord = axis == 0 ? direction.x : (axis == 1 ? direction.y : direction.z);
            float minCoord = axis == 0 ? bounds.getMinX() : (axis == 1 ? bounds.getMinY() : bounds.getMinZ());
            float maxCoord = axis == 0 ? bounds.getMaxX() : (axis == 1 ? bounds.getMaxY() : bounds.getMaxZ());
            
            if (Math.abs(dirCoord) < 1e-6f) {
                if (originCoord < minCoord || originCoord > maxCoord) {
                    return new RayIntersection(false, Float.MAX_VALUE);
                }
            } else {
                float invDir = 1.0f / dirCoord;
                float t1 = (minCoord - originCoord) * invDir;
                float t2 = (maxCoord - originCoord) * invDir;
                
                if (t1 > t2) {
                    float temp = t1; t1 = t2; t2 = temp;
                }
                
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                
                if (tmin > tmax) {
                    return new RayIntersection(false, Float.MAX_VALUE);
                }
            }
        }
        
        if (tmax < 0) {
            return new RayIntersection(false, Float.MAX_VALUE);
        }
        
        float t = (tmin >= 0) ? tmin : tmax;
        return new RayIntersection(true, t);
    }
    
    /**
     * Test ray intersection with a point (using a small sphere)
     */
    private static RayIntersection testRayPointIntersection(Ray3D ray, Point3f point, float radius) {
        Vector3f toPoint = new Vector3f(
            point.x - ray.origin().x,
            point.y - ray.origin().y,
            point.z - ray.origin().z
        );
        
        float projection = toPoint.dot(ray.direction());
        if (projection < 0) {
            return new RayIntersection(false, Float.MAX_VALUE);
        }
        
        Vector3f closestPoint = new Vector3f(ray.direction());
        closestPoint.scale(projection);
        closestPoint.add(ray.origin());
        
        float distance = point.distance(new Point3f(closestPoint));
        if (distance <= radius) {
            return new RayIntersection(true, projection);
        }
        
        return new RayIntersection(false, Float.MAX_VALUE);
    }
    
    /**
     * Calculate occlusion ratio for an entity
     */
    private static float calculateEntityOcclusionRatio(Point3f entityPos, EntityBounds bounds,
                                                     Ray3D ray, float intersectionDistance, 
                                                     float totalDistance) {
        // Calculate angular size of entity from observer
        float entitySize = bounds != null ? calculateBoundsSize(bounds) : 1.0f;
        float angularSize = entitySize / Math.max(intersectionDistance, 1.0f);
        
        // Simple occlusion model based on angular size
        float occlusionRatio = Math.min(1.0f, angularSize * angularSize * 0.5f);
        
        // Reduce occlusion for entities very close to observer or target
        float distanceRatio = intersectionDistance / totalDistance;
        if (distanceRatio < 0.1f || distanceRatio > 0.9f) {
            occlusionRatio *= 0.5f;
        }
        
        return occlusionRatio;
    }
    
    /**
     * Classify visibility type of an entity
     */
    private static VisibilityType classifyEntityVisibility(Point3f entityPos, EntityBounds bounds,
                                                         Point3f observer, Point3f target,
                                                         float intersectionDistance, float totalDistance) {
        if (intersectionDistance > totalDistance) {
            return VisibilityType.BEHIND_TARGET;
        } else if (intersectionDistance < totalDistance * 0.05f) {
            return VisibilityType.BEFORE_OBSERVER;
        } else {
            // Determine if fully or partially occluding based on entity size
            float entitySize = bounds != null ? calculateBoundsSize(bounds) : 1.0f;
            float angularSize = entitySize / intersectionDistance;
            return angularSize > 0.5f ? VisibilityType.FULLY_OCCLUDING : VisibilityType.PARTIALLY_OCCLUDING;
        }
    }
    
    /**
     * Calculate visibility score for a vantage point
     */
    private static float calculateVisibilityScore(EntityLineOfSightResult<?, ?> losResult, float distance) {
        float visibilityComponent = losResult.hasLineOfSight ? 1.0f : (1.0f - losResult.totalOcclusionRatio);
        float distanceComponent = Math.max(0.1f, 1000.0f / distance); // Closer is better, but not too close
        float occluderPenalty = 1.0f / (1.0f + losResult.occludingEntities.size() * 0.1f);
        return visibilityComponent * distanceComponent * occluderPenalty;
    }
    
    private static void validatePositiveCoordinates(Point3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point must have positive coordinates");
        }
    }
}