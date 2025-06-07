/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the Luciferase.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.common.IntArrayList;
import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.Entity;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Octree implementation with C++-style entity storage system.
 * Uses dual storage architecture:
 * - Spatial index: Morton code → Entity IDs
 * - Entity storage: Entity ID → Content
 * 
 * Supports multiple entities per node and entities spanning multiple nodes.
 * 
 * @param <ID> The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class OctreeWithEntities<ID extends EntityID, Content> {
    
    // Spatial index: Morton code → Node containing entity IDs
    private final NavigableMap<Long, OctreeNode<ID>> spatialIndex;
    
    // Consolidated entity storage: Entity ID → Entity (content, locations, position, bounds)
    private final Map<ID, Entity<Content>> entities;
    
    // ID generation strategy
    private final EntityIDGenerator<ID> idGenerator;
    
    // Configuration
    private final int maxEntitiesPerNode;
    private final byte maxDepth;
    private final EntitySpanningPolicy spanningPolicy;
    
    /**
     * Statistics tracking
     */
    public static class Stats {
        public int nodeCount = 0;
        public int entityCount = 0;
        public int maxDepth = 0;
        public int totalEntityReferences = 0; // Total entity IDs across all nodes
        
        @Override
        public String toString() {
            return String.format("Nodes: %d, Entities: %d, Max Depth: %d, Total Refs: %d",
                    nodeCount, entityCount, maxDepth, totalEntityReferences);
        }
    }
    
    /**
     * Create an octree with default configuration
     */
    public OctreeWithEntities(EntityIDGenerator<ID> idGenerator) {
        this(idGenerator, 10, Constants.getMaxRefinementLevel());
    }
    
    /**
     * Create an octree with custom configuration
     */
    public OctreeWithEntities(EntityIDGenerator<ID> idGenerator, 
                              int maxEntitiesPerNode,
                              byte maxDepth) {
        this(idGenerator, maxEntitiesPerNode, maxDepth, new EntitySpanningPolicy());
    }
    
    /**
     * Create an octree with custom configuration and spanning policy
     */
    public OctreeWithEntities(EntityIDGenerator<ID> idGenerator, 
                              int maxEntitiesPerNode,
                              byte maxDepth,
                              EntitySpanningPolicy spanningPolicy) {
        this.spatialIndex = new TreeMap<>();
        this.entities = new ConcurrentHashMap<>();
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.maxEntitiesPerNode = maxEntitiesPerNode;
        this.maxDepth = maxDepth;
        this.spanningPolicy = Objects.requireNonNull(spanningPolicy);
    }
    
    /**
     * Insert content with auto-generated ID
     * 
     * @return the generated entity ID
     */
    public ID insert(Point3f position, byte level, Content content) {
        ID entityId = idGenerator.generateID();
        insert(entityId, position, level, content);
        return entityId;
    }
    
    /**
     * Insert content with explicit ID
     */
    public void insert(ID entityId, Point3f position, byte level, Content content) {
        insert(entityId, position, level, content, null);
    }
    
    /**
     * Insert content with explicit ID and bounds
     */
    public void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds) {
        // Create or update entity
        Entity<Content> entity = entities.get(entityId);
        if (entity == null) {
            entity = bounds != null ? new Entity<>(content, position, bounds) : new Entity<>(content, position);
            entities.put(entityId, entity);
        } else {
            // Update existing entity
            entity.setPosition(position);
            if (bounds != null) {
                entity.setBounds(bounds);
            }
        }
        
        // If spanning is enabled and entity has bounds, check for spanning
        if (spanningPolicy.isSpanningEnabled() && bounds != null) {
            insertWithSpanning(entityId, bounds, level);
        } else {
            // Standard single-node insertion
            insertAtPosition(entityId, position, level);
        }
    }
    
    /**
     * Insert entity at a single position (no spanning)
     */
    private void insertAtPosition(ID entityId, Point3f position, byte level) {
        // Calculate Morton code for position
        long mortonCode = calculateMortonCode(position, level);
        
        // Get or create node
        OctreeNode<ID> node = spatialIndex.computeIfAbsent(mortonCode, 
            k -> new OctreeNode<>(maxEntitiesPerNode));
        
        // Add entity to node
        boolean shouldSplit = node.addEntity(entityId);
        
        // Track entity location
        Entity<Content> entity = entities.get(entityId);
        if (entity != null) {
            entity.addLocation(mortonCode);
        }
        
        // Handle subdivision if needed
        if (shouldSplit && level < maxDepth) {
            subdivideNode(mortonCode, level, node);
        }
    }
    
    /**
     * Insert entity with spanning across multiple nodes
     */
    private void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Find all nodes that the entity's bounds intersect
        Set<Long> intersectingNodes = findIntersectingNodes(bounds, level);
        
        // Add entity to all intersecting nodes
        for (Long mortonCode : intersectingNodes) {
            OctreeNode<ID> node = spatialIndex.computeIfAbsent(mortonCode,
                k -> new OctreeNode<>(maxEntitiesPerNode));
            
            node.addEntity(entityId);
            Entity<Content> entity = entities.get(entityId);
            if (entity != null) {
                entity.addLocation(mortonCode);
            }
            
            // Note: We don't trigger subdivision for spanning entities
            // to avoid cascading subdivisions
        }
    }
    
    /**
     * Find all nodes at the given level that intersect with the bounds
     */
    private Set<Long> findIntersectingNodes(EntityBounds bounds, byte level) {
        Set<Long> result = new HashSet<>();
        
        int cellSize = Constants.lengthAtLevel(level);
        
        // Calculate the range of grid cells that might intersect
        int minX = (int) Math.floor(bounds.getMinX() / cellSize);
        int minY = (int) Math.floor(bounds.getMinY() / cellSize);
        int minZ = (int) Math.floor(bounds.getMinZ() / cellSize);
        
        int maxX = (int) Math.floor(bounds.getMaxX() / cellSize);
        int maxY = (int) Math.floor(bounds.getMaxY() / cellSize);
        int maxZ = (int) Math.floor(bounds.getMaxZ() / cellSize);
        
        // Check each potential cell
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // Check if this cell actually intersects the bounds
                    float cellOriginX = x * cellSize;
                    float cellOriginY = y * cellSize;
                    float cellOriginZ = z * cellSize;
                    
                    if (bounds.intersectsCube(cellOriginX, cellOriginY, cellOriginZ, cellSize)) {
                        long mortonCode = MortonCurve.encode(x, y, z);
                        result.add(mortonCode);
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * Lookup entities at a specific position
     * 
     * @return list of entity IDs at the position
     */
    public List<ID> lookup(Point3f position, byte level) {
        long mortonCode = calculateMortonCode(position, level);
        OctreeNode<ID> node = spatialIndex.get(mortonCode);
        
        if (node == null) {
            return Collections.emptyList();
        }
        
        // If the node is empty (has been subdivided), look in child nodes
        if (node.isEmpty()) {
            // Calculate which child contains this position
            byte childLevel = (byte)(level + 1);
            if (childLevel <= maxDepth) {
                return lookup(position, childLevel);
            }
        }
        
        return new ArrayList<>(node.getEntityIds());
    }
    
    /**
     * Get content for a specific entity ID
     */
    public Content getEntity(ID entityId) {
        Entity<Content> entity = entities.get(entityId);
        return entity != null ? entity.getContent() : null;
    }
    
    /**
     * Get content for multiple entity IDs
     */
    public List<Content> getEntities(List<ID> entityIds) {
        return entityIds.stream()
                .map(entities::get)
                .filter(Objects::nonNull)
                .map(Entity::getContent)
                .collect(Collectors.toList());
    }
    
    /**
     * Remove an entity from all nodes and storage
     */
    public boolean removeEntity(ID entityId) {
        // Remove from entity storage
        Entity<Content> removed = entities.remove(entityId);
        if (removed == null) {
            return false;
        }
        
        // Get all locations where this entity appears
        Set<Long> locations = removed.getLocations();
        if (locations != null && !locations.isEmpty()) {
            // Remove from each node
            for (Long mortonCode : locations) {
                OctreeNode<ID> node = spatialIndex.get(mortonCode);
                if (node != null) {
                    node.removeEntity(entityId);
                    
                    // Remove empty nodes
                    if (node.isEmpty() && !node.hasChildren()) {
                        spatialIndex.remove(mortonCode);
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * Update entity position (remove from old nodes, add to new)
     */
    public void updateEntity(ID entityId, Point3f newPosition, byte level) {
        Entity<Content> entity = entities.get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("Entity not found: " + entityId);
        }
        
        // Update entity position
        entity.setPosition(newPosition);
        
        // Remove from all current locations
        Set<Long> oldLocations = new HashSet<>(entity.getLocations());
        for (Long mortonCode : oldLocations) {
            OctreeNode<ID> node = spatialIndex.get(mortonCode);
            if (node != null) {
                node.removeEntity(entityId);
            }
        }
        entity.clearLocations();
        
        // Re-insert at new position
        long newMortonCode = calculateMortonCode(newPosition, level);
        OctreeNode<ID> node = spatialIndex.computeIfAbsent(newMortonCode,
            k -> new OctreeNode<>(maxEntitiesPerNode));
        
        node.addEntity(entityId);
        entity.addLocation(newMortonCode);
    }
    
    /**
     * Find all entities within a bounding box
     */
    public List<ID> entitiesInRegion(Spatial.Cube region) {
        Set<ID> uniqueEntities = new HashSet<>();
        
        // Check all nodes in the spatial index
        // TODO: This is a simple implementation. In Phase 2, we'll implement
        // proper hierarchical traversal with early termination
        for (Map.Entry<Long, OctreeNode<ID>> entry : spatialIndex.entrySet()) {
            long mortonCode = entry.getKey();
            OctreeNode<ID> node = entry.getValue();
            
            // Decode the Morton code to get the cell position
            int[] coords = MortonCurve.decode(mortonCode);
            
            // For now, check if any entity in this node might be in the region
            // This is a conservative check - we include the node if it could
            // possibly overlap with the query region
            // TODO: Implement proper cell-region intersection test
            uniqueEntities.addAll(node.getEntityIds());
        }
        
        // Filter entities based on their actual positions
        List<ID> result = new ArrayList<>();
        for (ID entityId : uniqueEntities) {
            // Since we don't store entity positions directly, we include all for now
            // TODO: In Phase 2, track entity positions for accurate filtering
            result.add(entityId);
        }
        
        return result;
    }
    
    /**
     * Get statistics about the octree
     */
    public Stats getStats() {
        Stats stats = new Stats();
        stats.nodeCount = spatialIndex.size();
        stats.entityCount = entities.size();
        
        for (Map.Entry<Long, OctreeNode<ID>> entry : spatialIndex.entrySet()) {
            stats.totalEntityReferences += entry.getValue().getEntityCount();
            
            // Calculate depth from Morton code
            byte depth = Constants.toLevel(entry.getKey());
            stats.maxDepth = Math.max(stats.maxDepth, depth);
        }
        
        return stats;
    }
    
    /**
     * Get all entities (for debugging/testing)
     */
    public Map<ID, Content> getAllEntities() {
        Map<ID, Content> result = new HashMap<>();
        for (Map.Entry<ID, Entity<Content>> entry : entities.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getContent());
        }
        return Collections.unmodifiableMap(result);
    }
    
    /**
     * Check if an entity exists
     */
    public boolean containsEntity(ID entityId) {
        return entities.containsKey(entityId);
    }
    
    /**
     * Get the number of nodes containing a specific entity
     */
    public int getEntitySpanCount(ID entityId) {
        Entity<Content> entity = entities.get(entityId);
        return entity != null ? entity.getSpanCount() : 0;
    }
    
    // Private helper methods
    
    private long calculateMortonCode(Point3f position, byte level) {
        // Scale coordinates to the appropriate level of the hierarchy
        // At level 0 (coarsest), we have large cells
        // At maxLevel (finest), we have small cells
        int scale = 1 << (Constants.getMaxRefinementLevel() - level);
        int x = (int) (position.x / scale);
        int y = (int) (position.y / scale);
        int z = (int) (position.z / scale);
        
        // The Morton code itself represents the hierarchical position
        return MortonCurve.encode(x, y, z);
    }
    
    private void subdivideNode(long parentMorton, byte parentLevel, OctreeNode<ID> parentNode) {
        // Can't subdivide beyond max depth
        if (parentLevel >= maxDepth) {
            return;
        }
        
        byte childLevel = (byte)(parentLevel + 1);
        
        // Get entities to redistribute
        List<ID> parentEntities = new ArrayList<>(parentNode.getEntityIds());
        if (parentEntities.isEmpty()) {
            return; // Nothing to subdivide
        }
        
        // Create map to group entities by their child node
        Map<Long, List<ID>> childEntityMap = new HashMap<>();
        
        // Determine which child each entity belongs to
        for (ID entityId : parentEntities) {
            Entity<Content> entity = entities.get(entityId);
            if (entity != null) {
                Point3f entityPos = entity.getPosition();
                // Calculate which child this entity belongs to at the finer level
                long childMorton = calculateMortonCode(entityPos, childLevel);
                childEntityMap.computeIfAbsent(childMorton, k -> new ArrayList<>()).add(entityId);
            }
        }
        
        // Create child nodes and redistribute entities
        for (Map.Entry<Long, List<ID>> entry : childEntityMap.entrySet()) {
            long childMorton = entry.getKey();
            List<ID> childEntities = entry.getValue();
            
            if (!childEntities.isEmpty()) {
                // Create or get child node
                OctreeNode<ID> childNode = spatialIndex.computeIfAbsent(childMorton,
                    k -> new OctreeNode<>(maxEntitiesPerNode));
                
                // Add entities to child
                for (ID entityId : childEntities) {
                    childNode.addEntity(entityId);
                    // Update entity locations - add child location
                    Entity<Content> entity = entities.get(entityId);
                    if (entity != null) {
                        entity.addLocation(childMorton);
                    }
                }
                
                // Parent knows it has children because its entities have been redistributed
            }
        }
        
        // Clear entities from parent node (they've been redistributed to children)
        parentNode.clearEntities();
        
        // Remove parent from entity locations
        for (ID entityId : parentEntities) {
            Entity<Content> entity = entities.get(entityId);
            if (entity != null) {
                entity.removeLocation(parentMorton);
            }
        }
    }
}