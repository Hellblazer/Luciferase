/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.entity;

import javax.vecmath.Point3f;

/**
 * Request object for batch entity insertion operations.
 * Encapsulates all data needed to insert a single entity during batch processing.
 * 
 * @param <ID> The type of entity identifier
 * @param <Content> The type of entity content
 * 
 * @author hal.hildebrand
 */
public record EntityInsertRequest<ID extends EntityID, Content>(
    ID entityId,
    Point3f position,
    byte level,
    Content content,
    EntityBounds bounds
) {
    /**
     * Create an insert request without bounds (point entity)
     */
    public EntityInsertRequest(ID entityId, Point3f position, byte level, Content content) {
        this(entityId, position, level, content, null);
    }
    
    /**
     * Check if this entity has bounds (vs being a point entity)
     */
    public boolean hasBounds() {
        return bounds != null;
    }
    
    /**
     * Validate the request parameters
     */
    public void validate() {
        if (entityId == null) {
            throw new IllegalArgumentException("Entity ID cannot be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }
        if (level < 0) {
            throw new IllegalArgumentException("Level must be non-negative");
        }
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        // Validate positive coordinates
        if (position.x < 0 || position.y < 0 || position.z < 0) {
            throw new IllegalArgumentException("Position coordinates must be non-negative");
        }
    }
}