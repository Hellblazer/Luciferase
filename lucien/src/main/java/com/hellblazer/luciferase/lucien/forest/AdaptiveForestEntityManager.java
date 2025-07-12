/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;

/**
 * Entity manager for AdaptiveForest that automatically tracks entity density
 * and triggers adaptation as needed. This extends ForestEntityManager to add
 * density tracking integration.
 * 
 * @param <Key> The spatial key type
 * @param <ID> The entity ID type
 * @param <Content> The entity content type
 */
public class AdaptiveForestEntityManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> 
    extends ForestEntityManager<Key, ID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptiveForestEntityManager.class);
    
    private final AdaptiveForest<Key, ID, Content> adaptiveForest;
    
    /**
     * Create a new adaptive forest entity manager
     */
    public AdaptiveForestEntityManager(AdaptiveForest<Key, ID, Content> adaptiveForest, 
                                      EntityIDGenerator<ID> idGenerator) {
        super(adaptiveForest, idGenerator);
        this.adaptiveForest = adaptiveForest;
    }
    
    @Override
    public String insert(ID entityId, Content content, Point3f position, EntityBounds bounds) {
        // Perform normal insertion
        var treeId = super.insert(entityId, content, position, bounds);
        
        // Track density for adaptation
        if (treeId != null) {
            adaptiveForest.trackEntityInsertion(treeId, entityId, position);
        }
        
        return treeId;
    }
    
    @Override
    public boolean remove(ID entityId) {
        // Get location before removal
        var location = getEntityLocation(entityId);
        
        // Perform normal removal
        boolean removed = super.remove(entityId);
        
        // Track density change if removed
        if (removed && location != null) {
            adaptiveForest.trackEntityRemoval(location.getTreeId(), entityId);
        }
        
        return removed;
    }
    
    @Override
    public boolean updatePosition(ID entityId, Point3f newPosition) {
        // Get old location
        var oldLocation = getEntityLocation(entityId);
        
        // Perform normal update
        boolean updated = super.updatePosition(entityId, newPosition);
        
        // Track density changes if updated
        if (updated) {
            var newLocation = getEntityLocation(entityId);
            if (oldLocation != null && newLocation != null) {
                adaptiveForest.trackEntityMovement(
                    oldLocation.getTreeId(), 
                    newLocation.getTreeId(),
                    entityId, 
                    newPosition
                );
            }
        }
        
        return updated;
    }
    
    /**
     * Migrate entity to a different tree with density tracking
     */
    public boolean migrateEntity(ID entityId, String targetTreeId) {
        // Get old location
        var oldLocation = getEntityLocation(entityId);
        
        // Check if entity exists and target tree exists
        if (oldLocation == null || adaptiveForest.getTree(targetTreeId) == null) {
            return false;
        }
        
        // Get entity position before migration
        var tree = adaptiveForest.getTree(oldLocation.getTreeId());
        if (tree == null) {
            return false;
        }
        var position = tree.getSpatialIndex().getEntityPosition(entityId);
        
        // Remove from old tree and insert into new tree
        if (remove(entityId) && position != null) {
            var content = tree.getSpatialIndex().getEntity(entityId);
            var newTreeId = insert(entityId, content, position, null);
            
            if (newTreeId != null && newTreeId.equals(targetTreeId)) {
                // Track density changes
                adaptiveForest.trackEntityMovement(
                    oldLocation.getTreeId(),
                    targetTreeId,
                    entityId,
                    position
                );
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Force immediate density analysis and adaptation check
     */
    public void triggerAdaptationCheck() {
        // Force immediate adaptation check
        adaptiveForest.checkAndAdapt();
    }
    
    /**
     * Get current adaptation statistics
     */
    public AdaptationStats getAdaptationStats() {
        var stats = adaptiveForest.getAdaptationStatistics();
        return new AdaptationStats(
            (Integer) stats.get("subdivisionCount"),
            (Integer) stats.get("mergeCount"),
            (Double) stats.get("averageDensity"),
            (Boolean) stats.get("adaptationEnabled")
        );
    }
    
    /**
     * Adaptation statistics wrapper
     */
    public static class AdaptationStats {
        public final int subdivisionCount;
        public final int mergeCount;
        public final double averageDensity;
        public final boolean adaptationEnabled;
        
        public AdaptationStats(int subdivisionCount, int mergeCount, 
                             double averageDensity, boolean adaptationEnabled) {
            this.subdivisionCount = subdivisionCount;
            this.mergeCount = mergeCount;
            this.averageDensity = averageDensity;
            this.adaptationEnabled = adaptationEnabled;
        }
        
        @Override
        public String toString() {
            return String.format("AdaptationStats[subdivisions=%d, merges=%d, avgDensity=%.2f, enabled=%s]",
                subdivisionCount, mergeCount, averageDensity, adaptationEnabled);
        }
    }
}