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
package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Adapter that implements the Cursor interface backed by a SpatialIndex entity.
 * <p>
 * This allows simulation code using the Cursor abstraction to work with Lucien's
 * spatial indexing instead of Sentry's MutableGrid (Delaunay tetrahedralization).
 * <p>
 * Key differences from Vertex-based Cursor:
 * <ul>
 *   <li>Position updates are O(log n) instead of requiring full rebuild</li>
 *   <li>Neighbors are computed via k-NN queries instead of Delaunay topology</li>
 *   <li>Thread-safe for concurrent access</li>
 * </ul>
 *
 * @param <Key>     The spatial key type (MortonKey or TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public class SpatialCursor<Key extends SpatialKey<Key>, ID extends EntityID, Content> implements Cursor {

    private static final int   DEFAULT_K_NEIGHBORS   = 10;
    private static final float DEFAULT_MAX_DISTANCE  = Float.MAX_VALUE;
    private static final float DEFAULT_WORLD_SCALE   = 1.0f;

    private final SpatialIndex<Key, ID, Content> index;
    private final ID                             entityId;
    private final byte                           level;
    private final int                            kNeighbors;
    private final float                          maxNeighborDistance;
    private final float                          worldScale;

    /**
     * Creates a SpatialCursor with default neighbor query parameters.
     *
     * @param index    the spatial index containing the entity
     * @param entityId the entity ID this cursor represents
     * @param level    the spatial level for position updates
     */
    public SpatialCursor(SpatialIndex<Key, ID, Content> index, ID entityId, byte level) {
        this(index, entityId, level, DEFAULT_K_NEIGHBORS, DEFAULT_MAX_DISTANCE, DEFAULT_WORLD_SCALE);
    }

    /**
     * Creates a SpatialCursor with custom neighbor query parameters.
     *
     * @param index               the spatial index containing the entity
     * @param entityId            the entity ID this cursor represents
     * @param level               the spatial level for position updates
     * @param kNeighbors          number of neighbors to return in neighbor queries
     * @param maxNeighborDistance maximum distance for neighbor queries
     */
    public SpatialCursor(SpatialIndex<Key, ID, Content> index, ID entityId, byte level,
                         int kNeighbors, float maxNeighborDistance) {
        this(index, entityId, level, kNeighbors, maxNeighborDistance, DEFAULT_WORLD_SCALE);
    }

    /**
     * Creates a SpatialCursor with custom neighbor query parameters and world scale.
     *
     * @param index               the spatial index containing the entity
     * @param entityId            the entity ID this cursor represents
     * @param level               the spatial level for position updates
     * @param kNeighbors          number of neighbors to return in neighbor queries
     * @param maxNeighborDistance maximum distance for neighbor queries
     * @param worldScale          scale factor for converting world to normalized coordinates
     */
    public SpatialCursor(SpatialIndex<Key, ID, Content> index, ID entityId, byte level,
                         int kNeighbors, float maxNeighborDistance, float worldScale) {
        this.index = index;
        this.entityId = entityId;
        this.level = level;
        this.kNeighbors = kNeighbors;
        this.maxNeighborDistance = maxNeighborDistance;
        this.worldScale = worldScale;
    }

    @Override
    public Point3f getLocation() {
        return index.getEntityPosition(entityId);
    }

    @Override
    public void moveBy(Tuple3f delta) {
        var current = getLocation();
        if (current == null) {
            throw new IllegalStateException("Entity " + entityId + " not found in index");
        }
        // Normalize the delta to match the octree coordinate space
        var normalizedDelta = new Point3f(delta.x / worldScale, delta.y / worldScale, delta.z / worldScale);
        var newPosition = new Point3f(current);
        newPosition.add(normalizedDelta);
        // Clamp to valid range [0,1]
        newPosition.x = Math.max(0, Math.min(1, newPosition.x));
        newPosition.y = Math.max(0, Math.min(1, newPosition.y));
        newPosition.z = Math.max(0, Math.min(1, newPosition.z));
        index.updateEntity(entityId, newPosition, level);
    }

    @Override
    public void moveTo(Tuple3f position) {
        // Normalize position to octree coordinate space
        var newPosition = new Point3f(position.x / worldScale, position.y / worldScale, position.z / worldScale);
        // Clamp to valid range [0,1]
        newPosition.x = Math.max(0, Math.min(1, newPosition.x));
        newPosition.y = Math.max(0, Math.min(1, newPosition.y));
        newPosition.z = Math.max(0, Math.min(1, newPosition.z));
        index.updateEntity(entityId, newPosition, level);
    }

    @Override
    public Stream<Cursor> neighbors() {
        var position = getLocation();
        if (position == null) {
            return Stream.empty();
        }
        return index.kNearestNeighbors(position, kNeighbors + 1, maxNeighborDistance)
                    .stream()
                    .filter(id -> !id.equals(entityId)) // Exclude self
                    .map(id -> new SpatialCursor<>(index, id, level, kNeighbors, maxNeighborDistance, worldScale));
    }

    @Override
    public void visitNeighbors(Consumer<Cursor> consumer) {
        neighbors().forEach(consumer);
    }

    /**
     * @return the entity ID this cursor represents
     */
    public ID getEntityId() {
        return entityId;
    }

    /**
     * @return the spatial index backing this cursor
     */
    public SpatialIndex<Key, ID, Content> getIndex() {
        return index;
    }

    /**
     * @return the spatial level used for position updates
     */
    public byte getLevel() {
        return level;
    }

    @Override
    public String toString() {
        var pos = getLocation();
        return "SpatialCursor[id=" + entityId + ", pos=" + pos + ", level=" + level + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpatialCursor<?, ?, ?> other)) return false;
        return entityId.equals(other.entityId) && index == other.index;
    }

    @Override
    public int hashCode() {
        return entityId.hashCode();
    }
}
