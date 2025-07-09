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
package com.hellblazer.luciferase.lucien.internal;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;

/**
 * Entity with pre-computed spatial index for efficient batch operations.
 * This class is used internally for optimizing bulk insertions by pre-computing
 * spatial indices and grouping entities by their spatial location.
 *
 * @param <Key> The type of spatial key used
 * @param <ID> The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class IndexedEntity<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    private final ID entityId;
    private final Point3f position;
    private final Content content;
    private final EntityBounds bounds;
    private final Key spatialIndex;

    public IndexedEntity(ID entityId, Point3f position, Content content, EntityBounds bounds, Key spatialIndex) {
        this.entityId = entityId;
        this.position = position;
        this.content = content;
        this.bounds = bounds;
        this.spatialIndex = spatialIndex;
    }

    public ID getEntityId() {
        return entityId;
    }

    public Point3f getPosition() {
        return position;
    }

    public Content getContent() {
        return content;
    }

    public EntityBounds getBounds() {
        return bounds;
    }

    public Key getSpatialIndex() {
        return spatialIndex;
    }
}