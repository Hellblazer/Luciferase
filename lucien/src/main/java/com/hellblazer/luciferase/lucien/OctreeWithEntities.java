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

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Octree implementation with C++-style entity storage system. Uses dual storage architecture: - Spatial index: Morton
 * code → Entity IDs - Entity storage: Entity ID → Content
 *
 * Supports multiple entities per node and entities spanning multiple nodes.
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class OctreeWithEntities<ID extends EntityID, Content> {

    // Spatial index: Morton code → Node containing entity IDs
    final NavigableMap<Long, OctreeNode<ID>> spatialIndex;

    // Consolidated entity storage: Entity ID → Entity (content, locations, position, bounds)
    private final Map<ID, Entity<Content>> entities;

    // ID generation strategy
    private final EntityIDGenerator<ID> idGenerator;

    // Configuration
    private final int                  maxEntitiesPerNode;
    private final byte                 maxDepth;
    private final EntitySpanningPolicy spanningPolicy;
    final boolean              singleContentMode;  // When true, enforces one entity per location

    /**
     * Create an octree with default configuration
     */
    public OctreeWithEntities(EntityIDGenerator<ID> idGenerator) {
        this(idGenerator, 10, Constants.getMaxRefinementLevel());
    }
    
    /**
     * Create an octree in single content mode (for SpatialIndex compatibility)
     */
    public OctreeWithEntities(EntityIDGenerator<ID> idGenerator, boolean singleContentMode) {
        this(idGenerator, 1, Constants.getMaxRefinementLevel(), new EntitySpanningPolicy(), singleContentMode);
    }

    /**
     * Create an octree with custom configuration
     */
    public OctreeWithEntities(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth) {
        this(idGenerator, maxEntitiesPerNode, maxDepth, new EntitySpanningPolicy(), false);
    }

    /**
     * Create an octree with custom configuration and spanning policy
     */
    public OctreeWithEntities(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                              EntitySpanningPolicy spanningPolicy) {
        this(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy, false);
    }
    
    /**
     * Create an octree with full configuration options
     */
    public OctreeWithEntities(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                              EntitySpanningPolicy spanningPolicy, boolean singleContentMode) {
        this.spatialIndex = new TreeMap<>();
        this.entities = new ConcurrentHashMap<>();
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.maxEntitiesPerNode = singleContentMode ? 1 : maxEntitiesPerNode;
        this.maxDepth = maxDepth;
        this.spanningPolicy = Objects.requireNonNull(spanningPolicy);
        this.singleContentMode = singleContentMode;
    }

    /**
     * Check if an entity exists
     */
    public boolean containsEntity(ID entityId) {
        return entities.containsKey(entityId);
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
     * Get content for multiple entity IDs
     */
    public List<Content> getEntities(List<ID> entityIds) {
        return entityIds.stream().map(entities::get).filter(Objects::nonNull).map(Entity::getContent).collect(
        Collectors.toList());
    }

    /**
     * Get content for a specific entity ID
     */
    public Content getEntity(ID entityId) {
        Entity<Content> entity = entities.get(entityId);
        return entity != null ? entity.getContent() : null;
    }
    
    /**
     * Get the position of a specific entity
     * 
     * @param entityId the entity ID to get the position for
     * @return the entity's position, or null if entity not found
     */
    public Point3f getEntityPosition(ID entityId) {
        Entity<Content> entity = entities.get(entityId);
        return entity != null ? entity.getPosition() : null;
    }
    
    /**
     * Get all entities with their positions
     * 
     * @return map of entity IDs to their positions
     */
    public Map<ID, Point3f> getEntitiesWithPositions() {
        Map<ID, Point3f> result = new HashMap<>();
        for (Map.Entry<ID, Entity<Content>> entry : entities.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getPosition());
        }
        return Collections.unmodifiableMap(result);
    }
    
    /**
     * Get entity bounds if available
     * 
     * @param entityId the entity ID to get bounds for
     * @return the entity's bounds, or null if not set or entity not found
     */
    public EntityBounds getEntityBounds(ID entityId) {
        Entity<Content> entity = entities.get(entityId);
        return entity != null ? entity.getBounds() : null;
    }

    /**
     * Get the number of nodes containing a specific entity
     */
    public int getEntitySpanCount(ID entityId) {
        Entity<Content> entity = entities.get(entityId);
        return entity != null ? entity.getSpanCount() : 0;
    }

    /**
     * Get entity-based statistics about the octree
     */
    public Stats getEntityStats() {
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
            byte childLevel = (byte) (level + 1);
            if (childLevel <= maxDepth) {
                return lookup(position, childLevel);
            }
        }

        return new ArrayList<>(node.getEntityIds());
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
        OctreeNode<ID> node = spatialIndex.computeIfAbsent(newMortonCode, k -> new OctreeNode<>(maxEntitiesPerNode));

        node.addEntity(entityId);
        entity.addLocation(newMortonCode);
    }
    
    // Helper methods for spatial range queries
    
    Stream<SpatialNode<Content>> spatialRangeQuery(Spatial volume, boolean includeIntersecting) {
        List<SpatialNode<Content>> results = new ArrayList<>();
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return results.stream();
        }
        
        for (Map.Entry<Long, OctreeNode<ID>> entry : spatialIndex.entrySet()) {
            long mortonIndex = entry.getKey();
            OctreeNode<ID> node = entry.getValue();
            
            if (node.isEmpty()) {
                continue;
            }
            
            Spatial.Cube cube = Octree.toCube(mortonIndex);
            
            // Check if cube intersects or is contained in volume
            boolean include = false;
            if (includeIntersecting) {
                include = doesCubeIntersectVolume(cube, volume);
            } else {
                include = isCubeContainedInVolume(cube, volume);
            }
            
            if (include) {
                ID entityId = node.getEntityIds().iterator().next();
                Content content = getEntity(entityId);
                results.add(new SpatialNode<>(mortonIndex, content));
            }
        }
        
        return results.stream();
    }
    
    boolean doesCubeIntersectVolume(Spatial.Cube cube, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube other ->
                cube.originX() < other.originX() + other.extent() && 
                cube.originX() + cube.extent() > other.originX() &&
                cube.originY() < other.originY() + other.extent() && 
                cube.originY() + cube.extent() > other.originY() &&
                cube.originZ() < other.originZ() + other.extent() && 
                cube.originZ() + cube.extent() > other.originZ();
                
            case Spatial.Sphere sphere -> {
                // Find closest point on cube to sphere center
                float closestX = Math.max(cube.originX(), Math.min(sphere.centerX(), cube.originX() + cube.extent()));
                float closestY = Math.max(cube.originY(), Math.min(sphere.centerY(), cube.originY() + cube.extent()));
                float closestZ = Math.max(cube.originZ(), Math.min(sphere.centerZ(), cube.originZ() + cube.extent()));
                
                // Check if closest point is within sphere radius
                float dx = closestX - sphere.centerX();
                float dy = closestY - sphere.centerY();
                float dz = closestZ - sphere.centerZ();
                yield (dx * dx + dy * dy + dz * dz) <= (sphere.radius() * sphere.radius());
            }
            
            default -> true; // Conservative: include for other volume types
        };
    }
    
    boolean isCubeContainedInVolume(Spatial.Cube cube, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube other ->
                cube.originX() >= other.originX() && 
                cube.originY() >= other.originY() && 
                cube.originZ() >= other.originZ() &&
                cube.originX() + cube.extent() <= other.originX() + other.extent() &&
                cube.originY() + cube.extent() <= other.originY() + other.extent() &&
                cube.originZ() + cube.extent() <= other.originZ() + other.extent();
                
            case Spatial.Sphere sphere -> {
                // Check all 8 corners of the cube
                for (int i = 0; i < 8; i++) {
                    float x = cube.originX() + ((i & 1) != 0 ? cube.extent() : 0);
                    float y = cube.originY() + ((i & 2) != 0 ? cube.extent() : 0);
                    float z = cube.originZ() + ((i & 4) != 0 ? cube.extent() : 0);
                    
                    float dx = x - sphere.centerX();
                    float dy = y - sphere.centerY();
                    float dz = z - sphere.centerZ();
                    
                    if ((dx * dx + dy * dy + dz * dz) > (sphere.radius() * sphere.radius())) {
                        yield false;
                    }
                }
                yield true;
            }
            
            default -> false; // Conservative: exclude for other volume types
        };
    }
    
    VolumeBounds getVolumeBounds(Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> new VolumeBounds(
                cube.originX(), cube.originY(), cube.originZ(),
                cube.originX() + cube.extent(), cube.originY() + cube.extent(),
                cube.originZ() + cube.extent()
            );
            case Spatial.Sphere sphere -> new VolumeBounds(
                sphere.centerX() - sphere.radius(),
                sphere.centerY() - sphere.radius(),
                sphere.centerZ() - sphere.radius(),
                sphere.centerX() + sphere.radius(),
                sphere.centerY() + sphere.radius(),
                sphere.centerZ() + sphere.radius()
            );
            case Spatial.aabb aabb -> new VolumeBounds(
                aabb.originX(), aabb.originY(), aabb.originZ(),
                aabb.originX() + aabb.extentX(), aabb.originY() + aabb.extentY(),
                aabb.originZ() + aabb.extentZ()
            );
            case Spatial.aabt aabt -> new VolumeBounds(
                aabt.originX(), aabt.originY(), aabt.originZ(),
                aabt.originX() + aabt.extentX(), aabt.originY() + aabt.extentY(),
                aabt.originZ() + aabt.extentZ()
            );
            case Spatial.Parallelepiped para -> new VolumeBounds(
                para.originX(), para.originY(), para.originZ(),
                para.originX() + para.extentX(),
                para.originY() + para.extentY(),
                para.originZ() + para.extentZ()
            );
            case Spatial.Tetrahedron tet -> {
                var vertices = new javax.vecmath.Tuple3f[] { tet.a(), tet.b(), tet.c(), tet.d() };
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
                for (var vertex : vertices) {
                    minX = Math.min(minX, vertex.x);
                    minY = Math.min(minY, vertex.y);
                    minZ = Math.min(minZ, vertex.z);
                    maxX = Math.max(maxX, vertex.x);
                    maxY = Math.max(maxY, vertex.y);
                    maxZ = Math.max(maxZ, vertex.z);
                }
                yield new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
            }
            default -> null;
        };
    }
    
    byte findMinimumContainingLevel(VolumeBounds bounds) {
        float maxExtent = Math.max(Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY),
                                   bounds.maxZ - bounds.minZ);
        
        // Find the level where cube length >= maxExtent
        for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
            if (Constants.lengthAtLevel(level) >= maxExtent) {
                return level;
            }
        }
        return Constants.getMaxRefinementLevel();
    }

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
     * Insert entity at a single position (no spanning)
     */
    private void insertAtPosition(ID entityId, Point3f position, byte level) {
        // Calculate Morton code for position
        long mortonCode = calculateMortonCode(position, level);

        // Get or create node
        OctreeNode<ID> node = spatialIndex.computeIfAbsent(mortonCode, k -> new OctreeNode<>(maxEntitiesPerNode));

        // Add entity to node
        boolean shouldSplit = node.addEntity(entityId);

        // Track entity location
        Entity<Content> entity = entities.get(entityId);
        if (entity != null) {
            entity.addLocation(mortonCode);
        }
        
        // In single content mode, we don't split nodes
        if (singleContentMode) {
            return;
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
            OctreeNode<ID> node = spatialIndex.computeIfAbsent(mortonCode, k -> new OctreeNode<>(maxEntitiesPerNode));

            node.addEntity(entityId);
            Entity<Content> entity = entities.get(entityId);
            if (entity != null) {
                entity.addLocation(mortonCode);
            }

            // Note: We don't trigger subdivision for spanning entities
            // to avoid cascading subdivisions
        }
    }

    // Private helper methods

    private void subdivideNode(long parentMorton, byte parentLevel, OctreeNode<ID> parentNode) {
        // Can't subdivide beyond max depth
        if (parentLevel >= maxDepth) {
            return;
        }

        byte childLevel = (byte) (parentLevel + 1);

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

    
    /**
     * Statistics tracking
     */
    public static class Stats {
        public int nodeCount             = 0;
        public int entityCount           = 0;
        public int maxDepth              = 0;
        public int totalEntityReferences = 0; // Total entity IDs across all nodes

        @Override
        public String toString() {
            return String.format("Nodes: %d, Entities: %d, Max Depth: %d, Total Refs: %d", nodeCount, entityCount,
                                 maxDepth, totalEntityReferences);
        }
    }
    
    /**
     * Helper record for volume bounds
     */
    record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }
}
