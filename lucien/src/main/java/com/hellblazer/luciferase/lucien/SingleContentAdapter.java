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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.NavigableMap;

/**
 * Adapter that provides the existing single-content-per-node API
 * on top of the new entity-based octree implementation.
 * 
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class SingleContentAdapter<Content> {
    private final OctreeWithEntities<LongEntityID, Content> entityOctree;
    
    /**
     * Create adapter with default configuration
     */
    public SingleContentAdapter() {
        this.entityOctree = new OctreeWithEntities<>(
            new SequentialLongIDGenerator(),
            1,  // Max 1 entity per node for single-content behavior
            Constants.getMaxRefinementLevel()
        );
    }
    
    /**
     * Insert content at position (single-content API)
     */
    public void insert(Point3f position, byte level, Content content) {
        entityOctree.insert(position, level, content);
    }
    
    /**
     * Lookup content at position (returns first/only content)
     */
    public Content lookup(Point3f position, byte level) {
        List<LongEntityID> ids = entityOctree.lookup(position, level);
        if (ids.isEmpty()) {
            return null;
        }
        return entityOctree.getEntity(ids.get(0));
    }
    
    /**
     * Find content within a bounding box
     */
    public List<Content> boundedBy(Spatial.Cube region) {
        List<LongEntityID> entityIds = entityOctree.entitiesInRegion(region);
        return entityOctree.getEntities(entityIds);
    }
    
    /**
     * Get statistics about the octree
     */
    public OctreeWithEntities.Stats getStats() {
        return entityOctree.getStats();
    }
    
    /**
     * Remove content at position
     * Note: This is approximate - removes first entity found at position
     */
    public boolean remove(Point3f position, byte level) {
        List<LongEntityID> ids = entityOctree.lookup(position, level);
        if (ids.isEmpty()) {
            return false;
        }
        return entityOctree.removeEntity(ids.get(0));
    }
    
    /**
     * Get the underlying entity-based octree for advanced operations
     */
    public OctreeWithEntities<LongEntityID, Content> getEntityOctree() {
        return entityOctree;
    }
}