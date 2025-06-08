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
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Multi-entity aware k-Nearest Neighbor search for OctreeWithEntities.
 * This implementation properly handles multiple entities at the same spatial location.
 *
 * @author hal.hildebrand
 */
public class KNearestNeighborSearch {
    
    /**
     * Result container that includes entity ID for disambiguation
     */
    public static class KNNCandidate<ID extends EntityID, Content> {
        public final ID id;
        public final Content content;
        public final Point3f position;
        public final float distance;
        
        public KNNCandidate(ID id, Content content, Point3f position, float distance) {
            this.id = id;
            this.content = content;
            this.position = position;
            this.distance = distance;
        }
    }
    
    /**
     * Find k nearest entities to the query point, properly handling multiple entities per location
     * 
     * @param queryPoint the point to search around
     * @param k number of nearest entities to find
     * @param octree the multi-entity octree to search
     * @param searchRadius optional maximum search radius (use Float.MAX_VALUE for unlimited)
     * @return list of k nearest entities sorted by distance
     */
    public static <ID extends EntityID, Content> List<KNNCandidate<ID, Content>> findKNearestEntities(
            Point3f queryPoint,
            int k,
            OctreeWithEntities<ID, Content> octree,
            float searchRadius) {
        
        if (k <= 0) {
            return Collections.emptyList();
        }
        
        // Validate positive coordinates
        if (queryPoint.x < 0 || queryPoint.y < 0 || queryPoint.z < 0) {
            throw new IllegalArgumentException("Query point must have positive coordinates");
        }
        
        // Priority queue to maintain k nearest candidates
        PriorityQueue<KNNCandidate<ID, Content>> candidates = new PriorityQueue<>(
            k + 1,
            (a, b) -> Float.compare(b.distance, a.distance) // Max heap
        );
        
        // Get all entities within search radius
        Spatial.Sphere searchSphere = new Spatial.Sphere(
            queryPoint.x, queryPoint.y, queryPoint.z, searchRadius
        );
        
        // Convert to cube for the search
        Spatial.Cube searchRegion = new Spatial.Cube(
            queryPoint.x - searchRadius,
            queryPoint.y - searchRadius,
            queryPoint.z - searchRadius,
            searchRadius * 2
        );
        
        // Get all entities in the region
        List<ID> entityIds = octree.entitiesInRegion(searchRegion);
        
        // Process each entity
        for (ID entityId : entityIds) {
            Content content = octree.getEntity(entityId);
            if (content == null) continue;
            
            // Get the entity's actual position
            Point3f entityPos = octree.getEntityPosition(entityId);
            if (entityPos == null) continue;
            
            float distance = queryPoint.distance(entityPos);
            
            // Only consider entities within search radius
            if (distance <= searchRadius) {
                KNNCandidate<ID, Content> candidate = 
                    new KNNCandidate<>(entityId, content, entityPos, distance);
                
                candidates.offer(candidate);
                
                // Maintain only k candidates
                if (candidates.size() > k) {
                    candidates.poll();
                }
            }
        }
        
        // Convert to sorted list
        List<KNNCandidate<ID, Content>> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingDouble(c -> c.distance));
        
        return result;
    }
    
    /**
     * Simplified version without search radius limit
     */
    public static <ID extends EntityID, Content> List<KNNCandidate<ID, Content>> findKNearestEntities(
            Point3f queryPoint,
            int k,
            OctreeWithEntities<ID, Content> octree) {
        return findKNearestEntities(queryPoint, k, octree, Float.MAX_VALUE);
    }
    /**
     * Multi-entity aware distance calculation strategies
     */
    public enum DistanceStrategy {
        /**
         * Use the closest entity at each spatial location
         */
        CLOSEST_ENTITY,
        
        /**
         * Use the average position of all entities at a location
         */
        AVERAGE_POSITION,
        
        /**
         * Consider each entity independently
         */
        EACH_ENTITY
    }
    
    /**
     * Advanced k-NN search with configurable distance strategy
     */
    public static <ID extends EntityID, Content> List<KNNCandidate<ID, Content>> findKNearestEntitiesAdvanced(
            Point3f queryPoint,
            int k,
            OctreeWithEntities<ID, Content> octree,
            DistanceStrategy strategy) {
        
        // Implementation would vary based on strategy
        // For now, we use the EACH_ENTITY strategy as default
        return findKNearestEntities(queryPoint, k, octree);
    }
}