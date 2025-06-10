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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Multi-entity spatial indexing interface.
 * This interface supports multiple entities per spatial node and entities spanning multiple nodes.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public interface SpatialIndexMultiEntity<ID extends EntityID, Content> {
    
    /**
     * Node wrapper that provides uniform access to spatial data with multiple entities
     */
    record SpatialNode<ID extends EntityID>(long mortonIndex, Set<ID> entityIds) {
        /**
         * Convert the node's Morton index to a spatial cube
         */
        public Spatial.Cube toCube() {
            return new Spatial.Cube(mortonIndex);
        }
    }
    
    // ===== Insert Operations =====
    
    /**
     * Insert content with auto-generated entity ID
     *
     * @param position the 3D position
     * @param level the refinement level
     * @param content the content to store
     * @return the generated entity ID
     */
    ID insert(Point3f position, byte level, Content content);
    
    /**
     * Insert content with explicit entity ID
     *
     * @param entityId the entity ID to use
     * @param position the 3D position
     * @param level the refinement level
     * @param content the content to store
     */
    void insert(ID entityId, Point3f position, byte level, Content content);
    
    /**
     * Insert content with explicit entity ID and bounds (for spanning)
     *
     * @param entityId the entity ID to use
     * @param position the 3D position
     * @param level the refinement level
     * @param content the content to store
     * @param bounds the entity bounds for spanning calculations
     */
    void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds);
    
    // ===== Lookup Operations =====
    
    /**
     * Look up all entities at a specific position
     *
     * @param position the 3D position
     * @param level the refinement level
     * @return list of entity IDs at that position
     */
    List<ID> lookup(Point3f position, byte level);
    
    /**
     * Find all entities within a bounding region
     *
     * @param region the bounding region
     * @return list of entity IDs in the region
     */
    List<ID> entitiesInRegion(Spatial.Cube region);
    
    // ===== Entity Management =====
    
    /**
     * Get content for a specific entity ID
     *
     * @param entityId the entity ID
     * @return the content, or null if not found
     */
    Content getEntity(ID entityId);
    
    /**
     * Get content for multiple entity IDs
     *
     * @param entityIds list of entity IDs
     * @return list of content in same order (null entries for not found)
     */
    List<Content> getEntities(List<ID> entityIds);
    
    /**
     * Check if an entity exists
     *
     * @param entityId the entity ID to check
     * @return true if the entity exists
     */
    boolean containsEntity(ID entityId);
    
    /**
     * Remove an entity from all nodes
     *
     * @param entityId the entity ID to remove
     * @return true if the entity was removed
     */
    boolean removeEntity(ID entityId);
    
    /**
     * Update an entity's position
     *
     * @param entityId the entity ID
     * @param newPosition the new position
     * @param level the refinement level
     */
    void updateEntity(ID entityId, Point3f newPosition, byte level);
    
    // ===== Entity Position/Bounds Queries =====
    
    /**
     * Get the position of a specific entity
     *
     * @param entityId the entity ID
     * @return the entity's position, or null if not found
     */
    Point3f getEntityPosition(ID entityId);
    
    /**
     * Get the bounds of a specific entity
     *
     * @param entityId the entity ID
     * @return the entity's bounds, or null if not found or not set
     */
    EntityBounds getEntityBounds(ID entityId);
    
    /**
     * Get all entities with their positions
     *
     * @return unmodifiable map of entity IDs to positions
     */
    Map<ID, Point3f> getEntitiesWithPositions();
    
    /**
     * Get the number of nodes an entity spans
     *
     * @param entityId the entity ID
     * @return the span count, or 0 if not found
     */
    int getEntitySpanCount(ID entityId);
    
    // ===== Spatial Queries =====
    
    /**
     * Stream all nodes in the spatial index
     *
     * @return stream of spatial nodes with their entity IDs
     */
    Stream<SpatialNode<ID>> nodes();
    
    /**
     * Get all nodes completely contained within a bounding volume
     * 
     * @param volume the bounding volume
     * @return stream of nodes contained within the volume
     */
    Stream<SpatialNode<ID>> boundedBy(Spatial volume);
    
    /**
     * Get all nodes that intersect with a bounding volume
     * 
     * @param volume the bounding volume
     * @return stream of nodes that intersect the volume
     */
    Stream<SpatialNode<ID>> bounding(Spatial volume);
    
    /**
     * Find the minimum enclosing node for a volume
     * 
     * @param volume the volume to enclose
     * @return the minimum enclosing node, or null if not found
     */
    SpatialNode<ID> enclosing(Spatial volume);
    
    /**
     * Find the enclosing node at a specific level
     * 
     * @param point the point to enclose
     * @param level the refinement level
     * @return the enclosing node at that level
     */
    SpatialNode<ID> enclosing(Tuple3i point, byte level);
    
    // ===== Map Operations =====
    
    /**
     * Get a navigable map view of Morton indices to nodes
     * This allows for range queries and ordered traversal
     * 
     * @return navigable map with Morton indices as keys and entity ID sets as values
     */
    NavigableMap<Long, Set<ID>> getSpatialMap();
    
    /**
     * Check if a node exists at the given Morton index
     * 
     * @param mortonIndex the Morton index to check
     * @return true if a node exists at that index
     */
    boolean hasNode(long mortonIndex);
    
    /**
     * Get the total number of nodes in the spatial index
     * 
     * @return the number of nodes
     */
    int nodeCount();
    
    /**
     * Get the total number of entities stored
     * 
     * @return the number of unique entities
     */
    int entityCount();
    
    // ===== Statistics =====
    
    /**
     * Get comprehensive statistics about the spatial index
     * 
     * @return statistics object
     */
    EntityStats getStats();
    
    /**
     * Statistics about the spatial index with entity information
     */
    record EntityStats(int nodeCount, int entityCount, int totalEntityReferences, int maxDepth) {
        /**
         * Calculate the average entities per node
         */
        public double averageEntitiesPerNode() {
            return nodeCount > 0 ? (double) totalEntityReferences / nodeCount : 0.0;
        }
        
        /**
         * Calculate the entity spanning factor (how many nodes per entity on average)
         */
        public double entitySpanningFactor() {
            return entityCount > 0 ? (double) totalEntityReferences / entityCount : 0.0;
        }
    }
}