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
package com.hellblazer.luciferase.lucien.entity;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.collision.CollisionShape;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Centralized entity management for spatial indices. Handles entity storage, lifecycle, position tracking, and location
 * management. This class consolidates common entity management functionality shared between Octree and Tetree.
 *
 * @param <ID>      The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public class EntityManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    // Entity storage: Entity ID → Entity (content, locations, position, bounds)
    private final Map<ID, Entity<Key, Content>> entities;

    // ID generation strategy
    private final EntityIDGenerator<ID> idGenerator;

    /**
     * Create an entity manager with thread-safe storage
     */
    public EntityManager(EntityIDGenerator<ID> idGenerator) {
        this.entities = new ConcurrentHashMap<>();
        this.idGenerator = Objects.requireNonNull(idGenerator, "ID generator cannot be null");
    }

    /**
     * Create an entity manager with custom storage implementation
     */
    public EntityManager(EntityIDGenerator<ID> idGenerator, Map<ID, Entity<Key, Content>> storageMap) {
        this.entities = Objects.requireNonNull(storageMap, "Storage map cannot be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "ID generator cannot be null");
    }

    // ===== Entity Creation and Storage =====

    /**
     * Add a spatial location to an entity
     */
    public void addEntityLocation(ID entityId, Key spatialIndex) {
        var entity = entities.get(entityId);
        if (entity != null) {
            entity.addLocation(spatialIndex);
        }
    }

    /**
     * Clear all entities
     */
    public void clear() {
        entities.clear();
    }

    // ===== Entity Retrieval =====

    /**
     * Clear all locations for an entity
     */
    public void clearEntityLocations(ID entityId) {
        var entity = entities.get(entityId);
        if (entity != null) {
            entity.clearLocations();
        }
    }

    /**
     * Check if an entity exists
     */
    public boolean containsEntity(ID entityId) {
        return entities.containsKey(entityId);
    }

    /**
     * Create or update an entity
     *
     * @param entityId the entity ID
     * @param content  the content to store
     * @param position the entity position
     * @param bounds   optional entity bounds
     * @return the created or updated entity
     */
    public Entity<Key, Content> createOrUpdateEntity(ID entityId, Content content, Point3f position,
                                                     EntityBounds bounds) {
        var entity = entities.get(entityId);
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
        return entity;
    }

    /**
     * Find entities within a bounding box region Note: This is a simple implementation - spatial indices may override
     * with more efficient versions
     */
    public List<ID> findEntitiesInRegion(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
        var result = new ArrayList<ID>();
        for (var entry : entities.entrySet()) {
            var pos = entry.getValue().getPosition();
            if (pos.x >= minX && pos.x <= maxX && pos.y >= minY && pos.y <= maxY && pos.z >= minZ && pos.z <= maxZ) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Generate a new entity ID
     */
    public ID generateEntityId() {
        return idGenerator.generateID();
    }

    /**
     * Get all entity IDs
     */
    public Set<ID> getAllEntityIds() {
        return new HashSet<>(entities.keySet());
    }

    // ===== Entity Location Management =====

    /**
     * Get content for multiple entities
     */
    public List<Content> getEntitiesContent(List<ID> entityIds) {
        return entityIds.stream().map(entities::get).filter(Objects::nonNull).map(Entity::getContent).collect(
        Collectors.toList());
    }

    /**
     * Get all entities with their positions
     */
    public Map<ID, Point3f> getEntitiesWithPositions() {
        var result = new HashMap<ID, Point3f>();
        for (var entry : entities.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getPosition());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Get an entity by ID
     */
    public Entity<Key, Content> getEntity(ID entityId) {
        return entities.get(entityId);
    }

    /**
     * Get entity bounds by ID
     */
    public EntityBounds getEntityBounds(ID entityId) {
        var entity = entities.get(entityId);
        return entity != null ? entity.getBounds() : null;
    }

    /**
     * Get collision shape for an entity
     */
    public CollisionShape getEntityCollisionShape(ID entityId) {
        var entity = entities.get(entityId);
        return entity != null ? entity.getCollisionShape() : null;
    }

    // ===== Entity Lifecycle =====

    /**
     * Get entity content by ID
     */
    public Content getEntityContent(ID entityId) {
        var entity = entities.get(entityId);
        return entity != null ? entity.getContent() : null;
    }

    /**
     * Get the total number of entities
     */
    public int getEntityCount() {
        return entities.size();
    }

    /**
     * Get all spatial locations for an entity
     */
    public Set<Key> getEntityLocations(ID entityId) {
        var entity = entities.get(entityId);
        return entity != null ? new HashSet<>(entity.getLocations()) : Collections.emptySet();
    }

    // ===== Statistics and Queries =====

    /**
     * Get entity position by ID
     */
    public Point3f getEntityPosition(ID entityId) {
        var entity = entities.get(entityId);
        return entity != null ? entity.getPosition() : null;
    }

    /**
     * Get the span count (number of nodes) for an entity
     */
    public int getEntitySpanCount(ID entityId) {
        var entity = entities.get(entityId);
        return entity != null ? entity.getSpanCount() : 0;
    }

    /**
     * Check if the manager is empty
     */
    public boolean isEmpty() {
        return entities.isEmpty();
    }

    /**
     * Remove an entity completely
     *
     * @return the removed entity, or null if not found
     */
    public Entity<Key, Content> removeEntity(ID entityId) {
        return entities.remove(entityId);
    }

    // ===== Spatial Region Queries =====

    /**
     * Remove a spatial location from an entity
     */
    public void removeEntityLocation(ID entityId, Key spatialIndex) {
        var entity = entities.get(entityId);
        if (entity != null) {
            entity.removeLocation(spatialIndex);
        }
    }

    /**
     * Set entity bounds
     */
    public void setEntityBounds(ID entityId, EntityBounds bounds) {
        var entity = entities.get(entityId);
        if (entity != null) {
            entity.setBounds(bounds);
        }
    }

    /**
     * Set collision shape for an entity
     */
    public void setEntityCollisionShape(ID entityId, CollisionShape shape) {
        var entity = entities.get(entityId);
        if (entity != null) {
            entity.setCollisionShape(shape);
        }
    }

    /**
     * Update an entity's position
     */
    public void updateEntityPosition(ID entityId, Point3f newPosition) {
        var entity = entities.get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("Entity not found: " + entityId);
        }
        entity.setPosition(newPosition);
    }
}
