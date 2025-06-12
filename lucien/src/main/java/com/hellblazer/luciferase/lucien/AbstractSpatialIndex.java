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

import com.hellblazer.luciferase.lucien.entity.*;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Stream;

/**
 * Abstract base class for spatial index implementations. Provides common functionality for entity management,
 * configuration, and basic spatial operations while allowing concrete implementations to specialize the spatial
 * decomposition strategy.
 *
 * @param <ID>       The type of EntityID used
 * @param <Content>  The type of content stored
 * @param <NodeType> The type of spatial node used by the implementation
 * @author hal.hildebrand
 */
public abstract class AbstractSpatialIndex<ID extends EntityID, Content, NodeType extends SpatialNodeStorage<ID>>
implements SpatialIndex<ID, Content> {

    // Common fields
    protected final EntityManager<ID, Content> entityManager;
    protected final int                        maxEntitiesPerNode;
    protected final byte                       maxDepth;
    protected final EntitySpanningPolicy       spanningPolicy;

    /**
     * Constructor with common parameters
     */
    protected AbstractSpatialIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                                   EntitySpanningPolicy spanningPolicy) {
        this.entityManager = new EntityManager<>(idGenerator);
        this.maxEntitiesPerNode = maxEntitiesPerNode;
        this.maxDepth = maxDepth;
        this.spanningPolicy = Objects.requireNonNull(spanningPolicy);
    }

    // ===== Abstract Methods for Subclasses =====

    @Override
    public Stream<SpatialNode<ID>> boundedBy(Spatial volume) {
        validateSpatialConstraints(volume);

        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, false).filter(entry -> isNodeContainedInVolume(entry.getKey(), volume)).map(
        entry -> new SpatialNode<>(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds())));
    }

    @Override
    public Stream<SpatialNode<ID>> bounding(Spatial volume) {
        validateSpatialConstraints(volume);

        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, true).filter(entry -> doesNodeIntersectVolume(entry.getKey(), volume)).map(
        entry -> new SpatialNode<>(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds())));
    }

    @Override
    public boolean containsEntity(ID entityId) {
        return entityManager.containsEntity(entityId);
    }

    @Override
    public int entityCount() {
        return entityManager.getEntityCount();
    }

    @Override
    public List<Content> getEntities(List<ID> entityIds) {
        return entityManager.getEntitiesContent(entityIds);
    }

    @Override
    public Map<ID, Point3f> getEntitiesWithPositions() {
        return entityManager.getEntitiesWithPositions();
    }

    @Override
    public Content getEntity(ID entityId) {
        return entityManager.getEntityContent(entityId);
    }

    @Override
    public EntityBounds getEntityBounds(ID entityId) {
        return entityManager.getEntityBounds(entityId);
    }

    @Override
    public Point3f getEntityPosition(ID entityId) {
        return entityManager.getEntityPosition(entityId);
    }

    @Override
    public int getEntitySpanCount(ID entityId) {
        return entityManager.getEntitySpanCount(entityId);
    }

    // ===== Common Entity Management Methods =====

    @Override
    public NavigableMap<Long, Set<ID>> getSpatialMap() {
        NavigableMap<Long, Set<ID>> map = new TreeMap<>();
        for (var entry : getSpatialIndex().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                map.put(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds()));
            }
        }
        return map;
    }

    @Override
    public EntityStats getStats() {
        int nodeCount = 0;
        int entityCount = entityManager.getEntityCount();
        int totalEntityReferences = 0;
        int maxDepth = 0;

        for (Map.Entry<Long, NodeType> entry : getSpatialIndex().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                nodeCount++;
            }
            totalEntityReferences += entry.getValue().getEntityCount();

            // Calculate depth from spatial index
            byte depth = getLevelFromIndex(entry.getKey());
            maxDepth = Math.max(maxDepth, depth);
        }

        return new EntityStats(nodeCount, entityCount, totalEntityReferences, maxDepth);
    }

    @Override
    public boolean hasNode(long spatialIndex) {
        NodeType node = getSpatialIndex().get(spatialIndex);
        return node != null && !node.isEmpty();
    }

    @Override
    public ID insert(Point3f position, byte level, Content content) {
        ID entityId = entityManager.generateEntityId();
        insert(entityId, position, level, content);
        return entityId;
    }

    @Override
    public void insert(ID entityId, Point3f position, byte level, Content content) {
        insert(entityId, position, level, content, null);
    }

    @Override
    public void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds) {
        // Validate spatial constraints
        validateSpatialConstraints(position);

        // Create or update entity
        entityManager.createOrUpdateEntity(entityId, content, position, bounds);

        // If spanning is enabled and entity has bounds, check for spanning
        if (spanningPolicy.isSpanningEnabled() && bounds != null) {
            insertWithSpanning(entityId, bounds, level);
        } else {
            // Standard single-node insertion
            insertAtPosition(entityId, position, level);
        }
    }

    @Override
    public int nodeCount() {
        return (int) getSpatialIndex().values().stream().filter(node -> !node.isEmpty()).count();
    }

    @Override
    public Stream<SpatialNode<ID>> nodes() {
        return getSpatialIndex().entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).map(
        entry -> new SpatialNode<>(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds())));
    }

    // ===== Common Insert Operations =====

    @Override
    public boolean removeEntity(ID entityId) {
        // Get all locations where this entity appears
        Set<Long> locations = entityManager.getEntityLocations(entityId);

        // Remove from entity storage
        Entity<Content> removed = entityManager.removeEntity(entityId);
        if (removed == null) {
            return false;
        }

        if (!locations.isEmpty()) {
            // Remove from each node
            for (Long spatialIndex : locations) {
                NodeType node = getSpatialIndex().get(spatialIndex);
                if (node != null) {
                    node.removeEntity(entityId);

                    // Remove empty nodes
                    cleanupEmptyNode(spatialIndex, node);
                }
            }
        }

        return true;
    }

    @Override
    public void updateEntity(ID entityId, Point3f newPosition, byte level) {
        validateSpatialConstraints(newPosition);

        // Update entity position
        entityManager.updateEntityPosition(entityId, newPosition);

        // Remove from all current locations
        Set<Long> oldLocations = entityManager.getEntityLocations(entityId);
        for (Long spatialIndex : oldLocations) {
            NodeType node = getSpatialIndex().get(spatialIndex);
            if (node != null) {
                node.removeEntity(entityId);

                // Remove empty nodes
                cleanupEmptyNode(spatialIndex, node);
            }
        }
        entityManager.clearEntityLocations(entityId);

        // Re-insert at new position
        insertAtPosition(entityId, newPosition, level);
    }

    /**
     * Calculate the spatial index for a position at a given level
     */
    protected abstract long calculateSpatialIndex(Point3f position, byte level);

    /**
     * Clean up empty nodes from the spatial index
     */
    protected void cleanupEmptyNode(long spatialIndex, NodeType node) {
        if (node.isEmpty() && !hasChildren(spatialIndex)) {
            getSpatialIndex().remove(spatialIndex);
            onNodeRemoved(spatialIndex);
        }
    }

    /**
     * Create a new node instance
     */
    protected abstract NodeType createNode();

    /**
     * Check if a node's bounds intersect with a volume
     */
    protected abstract boolean doesNodeIntersectVolume(long nodeIndex, Spatial volume);

    // ===== Common Remove Operations =====

    /**
     * Find minimum containing level for bounds
     */
    protected byte findMinimumContainingLevel(VolumeBounds bounds) {
        float maxExtent = bounds.maxExtent();

        // Find the level where cell size >= maxExtent
        for (byte level = 0; level <= maxDepth; level++) {
            if (getCellSizeAtLevel(level) >= maxExtent) {
                return level;
            }
        }
        return maxDepth;
    }

    /**
     * Get the cell size at a given level (to be implemented by subclasses)
     */
    protected abstract int getCellSizeAtLevel(byte level);

    /**
     * Extract the level from a spatial index
     */
    protected abstract byte getLevelFromIndex(long index);

    /**
     * Get the spatial bounds of a node
     */
    protected abstract Spatial getNodeBounds(long index);

    // ===== Common Update Operations =====

    /**
     * Get the spatial index storage map
     */
    protected abstract Map<Long, NodeType> getSpatialIndex();

    // ===== Common Query Operations =====

    /**
     * Get volume bounds helper
     */
    protected VolumeBounds getVolumeBounds(Spatial volume) {
        return VolumeBounds.from(volume);
    }

    /**
     * Hook for subclasses to handle node subdivision
     */
    protected void handleNodeSubdivision(long spatialIndex, byte level, NodeType node) {
        // Default: no subdivision. Subclasses can override
    }

    /**
     * Check if a node has children (to be implemented by subclasses if needed)
     */
    protected boolean hasChildren(long spatialIndex) {
        return false; // Default: no children tracking
    }

    /**
     * Insert entity at a single position (no spanning)
     */
    protected void insertAtPosition(ID entityId, Point3f position, byte level) {
        long spatialIndex = calculateSpatialIndex(position, level);

        // Get or create node
        NodeType node = getSpatialIndex().computeIfAbsent(spatialIndex, k -> createNode());

        // Add entity to node
        boolean shouldSplit = node.addEntity(entityId);

        // Track entity location
        entityManager.addEntityLocation(entityId, spatialIndex);

        // Handle subdivision if needed (delegated to subclasses)
        if (shouldSplit && level < maxDepth) {
            handleNodeSubdivision(spatialIndex, level, node);
        }
    }

    // ===== Common Statistics =====

    /**
     * Hook for subclasses to handle entity spanning
     */
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Default: single node insertion. Subclasses can override for spanning
        Point3f position = entityManager.getEntityPosition(entityId);
        if (position != null) {
            insertAtPosition(entityId, position, level);
        }
    }

    // ===== Common Utility Methods =====

    /**
     * Check if a node's bounds are contained within a volume
     */
    protected abstract boolean isNodeContainedInVolume(long nodeIndex, Spatial volume);

    /**
     * Hook for subclasses when a node is removed
     */
    protected void onNodeRemoved(long spatialIndex) {
        // Subclasses can override if they need to maintain additional structures
    }

    /**
     * Perform spatial range query specific to the implementation
     */
    protected abstract Stream<Map.Entry<Long, NodeType>> spatialRangeQuery(VolumeBounds bounds,
                                                                           boolean includeIntersecting);

    // ===== Common Spatial Query Base =====

    /**
     * Validate spatial constraints (e.g., positive coordinates for Tetree)
     */
    protected abstract void validateSpatialConstraints(Point3f position);

    /**
     * Validate spatial constraints for volumes
     */
    protected abstract void validateSpatialConstraints(Spatial volume);
}
