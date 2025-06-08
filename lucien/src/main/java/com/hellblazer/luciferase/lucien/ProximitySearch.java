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
 * Multi-entity aware proximity search for OctreeWithEntities.
 * Finds entities based on distance relationships and proximity criteria.
 *
 * @author hal.hildebrand
 */
public class ProximitySearch {
    
    /**
     * Type of proximity relationship
     */
    public enum ProximityType {
        VERY_CLOSE,    // Within very close range
        CLOSE,         // Within close range
        MODERATE,      // Within moderate range
        FAR,           // Within far range
        VERY_FAR       // Beyond far range
    }
    
    /**
     * Proximity result with entity information
     */
    public static class EntityProximityResult<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distanceToQuery;
        public final float minDistanceToQuery; // minimum distance if entity has bounds
        public final float maxDistanceToQuery; // maximum distance if entity has bounds
        public final ProximityType proximityType;
        
        public EntityProximityResult(ID id, Content content, Point3f position,
                                   float distanceToQuery, float minDistanceToQuery, 
                                   float maxDistanceToQuery, ProximityType proximityType) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distanceToQuery = distanceToQuery;
            this.minDistanceToQuery = minDistanceToQuery;
            this.maxDistanceToQuery = maxDistanceToQuery;
            this.proximityType = proximityType;
        }
    }
    
    /**
     * Distance range specification for proximity queries
     */
    public static class DistanceRange {
        public final float minDistance;
        public final float maxDistance;
        public final ProximityType proximityType;
        
        public DistanceRange(float minDistance, float maxDistance, ProximityType proximityType) {
            if (minDistance < 0 || maxDistance < 0) {
                throw new IllegalArgumentException("Distances must be non-negative");
            }
            if (maxDistance < minDistance) {
                throw new IllegalArgumentException("Max distance must be >= min distance");
            }
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
            this.proximityType = proximityType;
        }
        
        public boolean contains(float distance) {
            return distance >= minDistance && distance <= maxDistance;
        }
    }
    
    /**
     * Find entities within a specific distance range from a query point
     * 
     * @param queryPoint the reference point for distance calculations (positive coordinates only)
     * @param distanceRange the distance range to search within
     * @param octree the multi-entity octree to search
     * @return list of entities within the distance range, sorted by distance
     */
    public static <ID extends EntityID, Content> List<EntityProximityResult<ID, Content>> findEntitiesWithinDistanceRange(
            Point3f queryPoint,
            DistanceRange distanceRange,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(queryPoint);
        
        List<EntityProximityResult<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            float distance = position.distance(queryPoint);
            
            // Get entity bounds for more accurate proximity calculation
            var bounds = octree.getEntityBounds(entityId);
            float minDistance = distance;
            float maxDistance = distance;
            
            if (bounds != null) {
                // Calculate min/max distances to bounds
                minDistance = calculateMinDistanceToBounds(queryPoint, bounds);
                maxDistance = calculateMaxDistanceToBounds(queryPoint, bounds);
            }
            
            // Check if entity overlaps with the distance range
            if (minDistance <= distanceRange.maxDistance && maxDistance >= distanceRange.minDistance) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    results.add(new EntityProximityResult<>(
                        entityId, content, position, distance, minDistance, maxDistance, 
                        distanceRange.proximityType
                    ));
                }
            }
        }
        
        // Sort by distance
        results.sort(Comparator.comparingDouble(e -> e.distanceToQuery));
        
        return results;
    }
    
    /**
     * Find entities within multiple distance ranges (proximity bands)
     * 
     * @param queryPoint the reference point for distance calculations
     * @param distanceRanges list of distance ranges to search within
     * @param octree the multi-entity octree to search
     * @return map of distance ranges to their proximity results
     */
    public static <ID extends EntityID, Content> Map<DistanceRange, List<EntityProximityResult<ID, Content>>> 
            findEntitiesInProximityBands(
            Point3f queryPoint,
            List<DistanceRange> distanceRanges,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(queryPoint);
        
        Map<DistanceRange, List<EntityProximityResult<ID, Content>>> results = new LinkedHashMap<>();
        
        for (DistanceRange range : distanceRanges) {
            List<EntityProximityResult<ID, Content>> rangeResults = 
                findEntitiesWithinDistanceRange(queryPoint, range, octree);
            results.put(range, rangeResults);
        }
        
        return results;
    }
    
    /**
     * Find the N closest entities to a query point
     * 
     * @param queryPoint the reference point for distance calculations
     * @param n number of closest entities to find
     * @param octree the multi-entity octree to search
     * @return list of the N closest entities, sorted by distance
     */
    public static <ID extends EntityID, Content> List<EntityProximityResult<ID, Content>> findNClosestEntities(
            Point3f queryPoint,
            int n,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(queryPoint);
        
        if (n <= 0) {
            throw new IllegalArgumentException("N must be positive");
        }
        
        List<EntityProximityResult<ID, Content>> allResults = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            float distance = position.distance(queryPoint);
            ProximityType proximityType = classifyDistance(distance);
            
            // Get entity bounds for more accurate proximity calculation
            var bounds = octree.getEntityBounds(entityId);
            float minDistance = distance;
            float maxDistance = distance;
            
            if (bounds != null) {
                minDistance = calculateMinDistanceToBounds(queryPoint, bounds);
                maxDistance = calculateMaxDistanceToBounds(queryPoint, bounds);
            }
            
            Content content = octree.getEntity(entityId);
            if (content != null) {
                allResults.add(new EntityProximityResult<>(
                    entityId, content, position, distance, minDistance, maxDistance, proximityType
                ));
            }
        }
        
        // Sort by distance and return top N
        allResults.sort(Comparator.comparingDouble(e -> e.distanceToQuery));
        
        return allResults.stream()
            .limit(n)
            .collect(Collectors.toList());
    }
    
    /**
     * Find entities within a specific distance from multiple query points
     * Returns entities within distance of ANY query point
     * 
     * @param queryPoints list of reference points (positive coordinates only)
     * @param maxDistance maximum distance from any query point
     * @param octree the multi-entity octree to search
     * @return list of entities within distance of any query point
     */
    public static <ID extends EntityID, Content> List<EntityProximityResult<ID, Content>> findEntitiesNearAnyPoint(
            List<Point3f> queryPoints,
            float maxDistance,
            OctreeWithEntities<ID, Content> octree) {
        
        for (Point3f point : queryPoints) {
            validatePositiveCoordinates(point);
        }
        
        if (maxDistance < 0) {
            throw new IllegalArgumentException("Max distance must be non-negative");
        }
        
        Map<ID, EntityProximityResult<ID, Content>> uniqueResults = new HashMap<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            float closestDistance = Float.MAX_VALUE;
            Point3f closestQueryPoint = null;
            
            // Find closest query point to this entity
            for (Point3f queryPoint : queryPoints) {
                float distance = position.distance(queryPoint);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestQueryPoint = queryPoint;
                }
            }
            
            if (closestDistance <= maxDistance && closestQueryPoint != null) {
                ProximityType proximityType = classifyDistance(closestDistance);
                
                // Get entity bounds for more accurate proximity calculation
                var bounds = octree.getEntityBounds(entityId);
                float minDistance = closestDistance;
                float maxDistanceToQuery = closestDistance;
                
                if (bounds != null) {
                    minDistance = calculateMinDistanceToBounds(closestQueryPoint, bounds);
                    maxDistanceToQuery = calculateMaxDistanceToBounds(closestQueryPoint, bounds);
                }
                
                Content content = octree.getEntity(entityId);
                if (content != null && minDistance <= maxDistance) {
                    EntityProximityResult<ID, Content> result = new EntityProximityResult<>(
                        entityId, content, position, closestDistance, minDistance, 
                        maxDistanceToQuery, proximityType
                    );
                    
                    // Keep the closest result for each entity
                    uniqueResults.merge(entityId, result, (existing, newResult) ->
                        existing.minDistanceToQuery <= newResult.minDistanceToQuery ? existing : newResult
                    );
                }
            }
        }
        
        // Convert to list and sort by distance
        List<EntityProximityResult<ID, Content>> results = new ArrayList<>(uniqueResults.values());
        results.sort(Comparator.comparingDouble(e -> e.minDistanceToQuery));
        
        return results;
    }
    
    /**
     * Find entities that are within distance of ALL specified query points
     * 
     * @param queryPoints list of reference points (positive coordinates only)
     * @param maxDistance maximum distance from each query point
     * @param octree the multi-entity octree to search
     * @return list of entities within distance of all query points
     */
    public static <ID extends EntityID, Content> List<EntityProximityResult<ID, Content>> findEntitiesNearAllPoints(
            List<Point3f> queryPoints,
            float maxDistance,
            OctreeWithEntities<ID, Content> octree) {
        
        for (Point3f point : queryPoints) {
            validatePositiveCoordinates(point);
        }
        
        if (maxDistance < 0) {
            throw new IllegalArgumentException("Max distance must be non-negative");
        }
        
        List<EntityProximityResult<ID, Content>> results = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            boolean withinDistanceOfAll = true;
            float maxDistanceToAnyPoint = 0.0f;
            
            // Check if entity is within distance of all query points
            for (Point3f queryPoint : queryPoints) {
                float distance = position.distance(queryPoint);
                
                // Check bounds if available
                var bounds = octree.getEntityBounds(entityId);
                if (bounds != null) {
                    distance = calculateMinDistanceToBounds(queryPoint, bounds);
                }
                
                if (distance > maxDistance) {
                    withinDistanceOfAll = false;
                    break;
                }
                maxDistanceToAnyPoint = Math.max(maxDistanceToAnyPoint, distance);
            }
            
            if (withinDistanceOfAll && !queryPoints.isEmpty()) {
                // Use first query point for primary distance calculation
                Point3f primaryQuery = queryPoints.get(0);
                float primaryDistance = position.distance(primaryQuery);
                ProximityType proximityType = classifyDistance(primaryDistance);
                
                // Get entity bounds for more accurate proximity calculation
                var bounds = octree.getEntityBounds(entityId);
                float minDistance = primaryDistance;
                float maxDistanceToQuery = primaryDistance;
                
                if (bounds != null) {
                    minDistance = calculateMinDistanceToBounds(primaryQuery, bounds);
                    maxDistanceToQuery = calculateMaxDistanceToBounds(primaryQuery, bounds);
                }
                
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    results.add(new EntityProximityResult<>(
                        entityId, content, position, primaryDistance, minDistance, 
                        maxDistanceToQuery, proximityType
                    ));
                }
            }
        }
        
        // Sort by distance to primary query point
        results.sort(Comparator.comparingDouble(e -> e.distanceToQuery));
        
        return results;
    }
    
    /**
     * Get proximity statistics for entities relative to a query point
     * 
     * @param queryPoint the reference point (positive coordinates only)
     * @param octree the multi-entity octree to search
     * @return statistics about entity proximity distribution
     */
    public static <ID extends EntityID, Content> ProximityStatistics getProximityStatistics(
            Point3f queryPoint,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(queryPoint);
        
        long totalEntities = 0;
        long veryCloseEntities = 0;
        long closeEntities = 0;
        long moderateEntities = 0;
        long farEntities = 0;
        float totalDistance = 0.0f;
        float minDistance = Float.MAX_VALUE;
        float maxDistance = Float.MIN_VALUE;
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Point3f position : entitiesWithPositions.values()) {
            totalEntities++;
            float distance = position.distance(queryPoint);
            
            totalDistance += distance;
            minDistance = Math.min(minDistance, distance);
            maxDistance = Math.max(maxDistance, distance);
            
            ProximityType proximityType = classifyDistance(distance);
            switch (proximityType) {
                case VERY_CLOSE -> veryCloseEntities++;
                case CLOSE -> closeEntities++;
                case MODERATE -> moderateEntities++;
                case FAR, VERY_FAR -> farEntities++;
            }
        }
        
        float averageDistance = totalEntities > 0 ? totalDistance / totalEntities : 0.0f;
        
        return new ProximityStatistics(
            totalEntities, veryCloseEntities, closeEntities, moderateEntities, 
            farEntities, averageDistance, minDistance, maxDistance
        );
    }
    
    /**
     * Find entities whose proximity classification changes between two points
     * Useful for analyzing movement effects
     * 
     * @param fromPoint starting reference point
     * @param toPoint ending reference point
     * @param octree the multi-entity octree to search
     * @return entities with changed proximity classification
     */
    public static <ID extends EntityID, Content> List<ProximityChangeResult<ID, Content>> 
            findEntitiesWithProximityChange(
            Point3f fromPoint,
            Point3f toPoint,
            OctreeWithEntities<ID, Content> octree) {
        
        validatePositiveCoordinates(fromPoint);
        validatePositiveCoordinates(toPoint);
        
        List<ProximityChangeResult<ID, Content>> changes = new ArrayList<>();
        
        // Get all entities with their positions
        Map<ID, Point3f> entitiesWithPositions = octree.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : entitiesWithPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f position = entry.getValue();
            
            float distanceFrom = position.distance(fromPoint);
            float distanceTo = position.distance(toPoint);
            
            ProximityType typeFrom = classifyDistance(distanceFrom);
            ProximityType typeTo = classifyDistance(distanceTo);
            
            if (typeFrom != typeTo) {
                Content content = octree.getEntity(entityId);
                if (content != null) {
                    changes.add(new ProximityChangeResult<>(
                        entityId, content, position, distanceFrom, distanceTo, typeFrom, typeTo
                    ));
                }
            }
        }
        
        // Sort by magnitude of distance change
        changes.sort(Comparator.comparingDouble(c -> Math.abs(c.distanceTo - c.distanceFrom)));
        
        return changes;
    }
    
    /**
     * Result of proximity change analysis
     */
    public static class ProximityChangeResult<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distanceFrom;
        public final float distanceTo;
        public final ProximityType typeFrom;
        public final ProximityType typeTo;
        
        public ProximityChangeResult(ID id, Content content, Point3f position,
                                   float distanceFrom, float distanceTo,
                                   ProximityType typeFrom, ProximityType typeTo) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distanceFrom = distanceFrom;
            this.distanceTo = distanceTo;
            this.typeFrom = typeFrom;
            this.typeTo = typeTo;
        }
        
        public float getDistanceChange() {
            return distanceTo - distanceFrom;
        }
        
        public boolean isGettingCloser() {
            return distanceTo < distanceFrom;
        }
    }
    
    /**
     * Statistics about proximity distribution
     */
    public static class ProximityStatistics {
        public final long totalEntities;
        public final long veryCloseEntities;
        public final long closeEntities;
        public final long moderateEntities;
        public final long farEntities;
        public final float averageDistance;
        public final float minDistance;
        public final float maxDistance;
        
        public ProximityStatistics(long totalEntities, long veryCloseEntities, long closeEntities,
                                 long moderateEntities, long farEntities, float averageDistance,
                                 float minDistance, float maxDistance) {
            this.totalEntities = totalEntities;
            this.veryCloseEntities = veryCloseEntities;
            this.closeEntities = closeEntities;
            this.moderateEntities = moderateEntities;
            this.farEntities = farEntities;
            this.averageDistance = averageDistance;
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
        }
        
        public double getVeryClosePercentage() {
            return totalEntities > 0 ? (double) veryCloseEntities / totalEntities * 100.0 : 0.0;
        }
        
        public double getClosePercentage() {
            return totalEntities > 0 ? (double) closeEntities / totalEntities * 100.0 : 0.0;
        }
        
        public double getModeratePercentage() {
            return totalEntities > 0 ? (double) moderateEntities / totalEntities * 100.0 : 0.0;
        }
        
        public double getFarPercentage() {
            return totalEntities > 0 ? (double) farEntities / totalEntities * 100.0 : 0.0;
        }
    }
    
    /**
     * Classify distance into proximity types
     */
    private static ProximityType classifyDistance(float distance) {
        if (distance < 100.0f) {
            return ProximityType.VERY_CLOSE;
        } else if (distance < 500.0f) {
            return ProximityType.CLOSE;
        } else if (distance < 1000.0f) {
            return ProximityType.MODERATE;
        } else if (distance < 5000.0f) {
            return ProximityType.FAR;
        } else {
            return ProximityType.VERY_FAR;
        }
    }
    
    /**
     * Calculate minimum distance from a point to entity bounds
     */
    private static float calculateMinDistanceToBounds(Point3f point, 
                                                     com.hellblazer.luciferase.lucien.entity.EntityBounds bounds) {
        float dx = Math.max(0, Math.max(bounds.getMinX() - point.x, point.x - bounds.getMaxX()));
        float dy = Math.max(0, Math.max(bounds.getMinY() - point.y, point.y - bounds.getMaxY()));
        float dz = Math.max(0, Math.max(bounds.getMinZ() - point.z, point.z - bounds.getMaxZ()));
        
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * Calculate maximum distance from a point to entity bounds corners
     */
    private static float calculateMaxDistanceToBounds(Point3f point,
                                                    com.hellblazer.luciferase.lucien.entity.EntityBounds bounds) {
        float maxDx = Math.max(Math.abs(bounds.getMinX() - point.x), Math.abs(bounds.getMaxX() - point.x));
        float maxDy = Math.max(Math.abs(bounds.getMinY() - point.y), Math.abs(bounds.getMaxY() - point.y));
        float maxDz = Math.max(Math.abs(bounds.getMinZ() - point.z), Math.abs(bounds.getMaxZ() - point.z));
        
        return (float) Math.sqrt(maxDx * maxDx + maxDy * maxDy + maxDz * maxDz);
    }
    
    private static void validatePositiveCoordinates(Point3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Point must have positive coordinates");
        }
    }
}