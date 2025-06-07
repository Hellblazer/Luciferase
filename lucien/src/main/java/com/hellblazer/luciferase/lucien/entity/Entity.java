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
package com.hellblazer.luciferase.lucien.entity;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Set;

/**
 * Entity container that holds all entity-related data.
 * Consolidates content, locations, position, and bounds into a single object.
 * 
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class Entity<Content> {
    private final Content content;
    private final Set<Long> locations;
    private Point3f position;
    private EntityBounds bounds;
    
    /**
     * Create an entity with content and position
     */
    public Entity(Content content, Point3f position) {
        this.content = content;
        this.position = new Point3f(position);
        this.locations = new HashSet<>();
        this.bounds = null;
    }
    
    /**
     * Create an entity with content, position, and bounds
     */
    public Entity(Content content, Point3f position, EntityBounds bounds) {
        this.content = content;
        this.position = new Point3f(position);
        this.locations = new HashSet<>();
        this.bounds = bounds;
    }
    
    /**
     * Get the entity's content
     */
    public Content getContent() {
        return content;
    }
    
    /**
     * Get the set of Morton codes where this entity is located
     */
    public Set<Long> getLocations() {
        return locations;
    }
    
    /**
     * Add a location (Morton code) where this entity exists
     */
    public void addLocation(long mortonCode) {
        locations.add(mortonCode);
    }
    
    /**
     * Remove a location where this entity exists
     */
    public void removeLocation(long mortonCode) {
        locations.remove(mortonCode);
    }
    
    /**
     * Clear all locations
     */
    public void clearLocations() {
        locations.clear();
    }
    
    /**
     * Get the number of nodes this entity spans
     */
    public int getSpanCount() {
        return locations.size();
    }
    
    /**
     * Check if entity exists in any nodes
     */
    public boolean hasLocations() {
        return !locations.isEmpty();
    }
    
    /**
     * Get the entity's position
     */
    public Point3f getPosition() {
        return new Point3f(position);
    }
    
    /**
     * Update the entity's position
     */
    public void setPosition(Point3f newPosition) {
        this.position = new Point3f(newPosition);
    }
    
    /**
     * Get the entity's bounds (may be null)
     */
    public EntityBounds getBounds() {
        return bounds;
    }
    
    /**
     * Set the entity's bounds
     */
    public void setBounds(EntityBounds bounds) {
        this.bounds = bounds;
    }
    
    /**
     * Check if entity has bounds
     */
    public boolean hasBounds() {
        return bounds != null;
    }
}