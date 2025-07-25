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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.FrameManager;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntityManager;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Extension of AbstractSpatialIndex that adds Dynamic Scene Occlusion Culling support.
 * This class provides hooks for TBV creation and deferred updates for hidden entities.
 *
 * @param <Key> The spatial key type
 * @param <ID> The entity ID type
 * @param <Content> The content type
 * @author hal.hildebrand
 */
public abstract class DSOCAwareSpatialIndex<Key extends SpatialKey<Key>, ID extends EntityID, Content> 
        extends AbstractSpatialIndex<Key, ID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(DSOCAwareSpatialIndex.class);
    
    // DSOC components
    protected final VisibilityStateManager<ID> visibilityManager;
    protected final DSOCConfiguration dsocConfig;
    protected final FrameManager frameManager;
    
    // Statistics
    private long deferredUpdates = 0;
    private long tbvUpdates = 0;
    
    /**
     * Create a DSOC-aware spatial index
     *
     * @param idGenerator the entity ID generator
     * @param maxEntitiesPerNode maximum entities per node before subdivision
     * @param maxDepth maximum tree depth
     * @param spanningPolicy entity spanning policy
     * @param dsocConfig DSOC configuration
     * @param frameManager frame manager for time tracking
     */
    protected DSOCAwareSpatialIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                                  EntitySpanningPolicy spanningPolicy,
                                  DSOCConfiguration dsocConfig,
                                  FrameManager frameManager) {
        super(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
        this.dsocConfig = dsocConfig;
        this.frameManager = frameManager;
        this.visibilityManager = new VisibilityStateManager<>(dsocConfig);
        
        // Configure entity manager for automatic dynamics
        entityManager.setFrameManager(frameManager);
        entityManager.setAutoDynamicsEnabled(dsocConfig.isAutoDynamicsEnabled());
    }
    
    /**
     * Update entity with DSOC support
     * 
     * @param entityId the entity to update
     * @param newPosition the new position
     * @param level the level in the spatial hierarchy
     */
    @Override
    public void updateEntity(ID entityId, Point3f newPosition, byte level) {
        lock.writeLock().lock();
        try {
            validateSpatialConstraints(newPosition);
            
            var currentFrame = (int) frameManager.getCurrentFrame();
            var visState = visibilityManager.getState(entityId);
            
            // Update dynamics if available
            var dynamics = entityManager.getDynamics(entityId);
            if (dynamics != null) {
                dynamics.updatePosition(newPosition, currentFrame);
            }
            
            // Check if we should defer update
            if (shouldDeferUpdate(entityId, visState, currentFrame)) {
                handleDeferredUpdate(entityId, newPosition, level, currentFrame);
                deferredUpdates++;
                return;
            }
            
            // Perform immediate update
            performImmediateUpdate(entityId, newPosition, level);
            
            // Check if we should create TBV
            if (shouldCreateTBV(entityId, visState, dynamics)) {
                createTBVForEntity(entityId, currentFrame);
            }
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Force update for an entity, regardless of visibility state
     *
     * @param entityId the entity to update
     */
    public void forceEntityUpdate(ID entityId) {
        lock.writeLock().lock();
        try {
            var entity = entityManager.getEntity(entityId);
            if (entity == null) {
                log.warn("Cannot force update for non-existent entity: {}", entityId);
                return;
            }
            
            // Clear any TBVs
            visibilityManager.clearEntity(entityId);
            
            // Perform standard update
            var position = entity.getPosition();
            var level = calculateAppropriateLevel(position);
            performImmediateUpdate(entityId, position, level);
            
            // Mark as visible
            visibilityManager.updateVisibility(entityId, true, frameManager.getCurrentFrame());
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Update visibility state for multiple entities
     *
     * @param visibleEntities set of currently visible entity IDs
     */
    public void updateVisibilityStates(java.util.Set<ID> visibleEntities) {
        lock.readLock().lock();
        try {
            var currentFrame = frameManager.getCurrentFrame();
            
            // Update all known entities
            for (var entityId : entityManager.getAllEntityIds()) {
                var isVisible = visibleEntities.contains(entityId);
                visibilityManager.updateVisibility(entityId, isVisible, currentFrame);
            }
            
            // Prune expired TBVs
            var expired = visibilityManager.pruneExpiredTBVs(currentFrame);
            for (var entityId : expired) {
                log.debug("TBV expired for entity {} at frame {}", entityId, currentFrame);
            }
            
            // Force updates for entities that need them
            var needingUpdate = visibilityManager.getEntitiesNeedingUpdate(currentFrame);
            for (var entityId : needingUpdate) {
                forceEntityUpdate(entityId);
            }
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get visibility statistics
     *
     * @return map of statistics
     */
    public java.util.Map<String, Object> getDSOCStatistics() {
        var stats = visibilityManager.getStatistics();
        stats.put("deferredUpdates", deferredUpdates);
        stats.put("tbvUpdates", tbvUpdates);
        stats.put("dsocEnabled", dsocConfig.isEnabled());
        return stats;
    }
    
    // Protected helper methods
    
    /**
     * Check if update should be deferred
     */
    protected boolean shouldDeferUpdate(ID entityId, VisibilityStateManager.VisibilityState state, 
                                      long currentFrame) {
        if (!dsocConfig.isEnabled()) {
            return false;
        }
        
        // Defer if entity has active TBV
        if (state == VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV) {
            var tbv = visibilityManager.getTBV(entityId);
            return tbv != null && tbv.isValid((int) currentFrame);
        }
        
        return false;
    }
    
    /**
     * Handle deferred update by updating TBV
     */
    protected void handleDeferredUpdate(ID entityId, Point3f newPosition, byte level, long currentFrame) {
        var tbv = visibilityManager.getTBV(entityId);
        if (tbv == null) {
            log.warn("No TBV found for deferred update of entity {}", entityId);
            performImmediateUpdate(entityId, newPosition, level);
            return;
        }
        
        // Update entity position in manager (but not in spatial index)
        entityManager.updateEntityPosition(entityId, newPosition);
        
        // Update TBV if needed based on new position
        var dynamics = entityManager.getDynamics(entityId);
        if (dynamics != null && dsocConfig.isPredictiveUpdates()) {
            // Could recreate TBV with updated velocity
            var newVelocity = dynamics.getVelocity();
            if (newVelocity.lengthSquared() > tbv.getMaxVelocity().lengthSquared() * 1.5f) {
                // Velocity increased significantly, recreate TBV
                createTBVForEntity(entityId, currentFrame);
            }
        }
        
        tbvUpdates++;
        log.debug("Deferred update for entity {} with TBV", entityId);
    }
    
    /**
     * Perform immediate spatial update
     */
    protected void performImmediateUpdate(ID entityId, Point3f newPosition, byte level) {
        // Call parent implementation
        super.updateEntity(entityId, newPosition, level);
    }
    
    /**
     * Check if TBV should be created
     */
    protected boolean shouldCreateTBV(ID entityId, VisibilityStateManager.VisibilityState state,
                                    com.hellblazer.luciferase.lucien.entity.EntityDynamics dynamics) {
        if (!dsocConfig.isEnabled() || dynamics == null) {
            return false;
        }
        
        // Create TBV when transitioning to hidden
        return state == VisibilityStateManager.VisibilityState.VISIBLE &&
               dynamics.getVelocity().length() >= dsocConfig.getVelocityThreshold();
    }
    
    /**
     * Create TBV for entity
     */
    protected void createTBVForEntity(ID entityId, long currentFrame) {
        var dynamics = entityManager.getOrCreateDynamics(entityId);
        var bounds = entityManager.getEntityBounds(entityId);
        
        if (bounds == null) {
            // Create bounds from position if not available
            var position = entityManager.getEntityPosition(entityId);
            bounds = new com.hellblazer.luciferase.lucien.entity.EntityBounds(position, 1.0f);
        }
        
        var tbv = visibilityManager.createTBV(entityId, dynamics, bounds, (int) currentFrame);
        
        if (tbv != null) {
            // Store TBV in spatial nodes if using OcclusionAwareSpatialNode
            storeTBVInNodes(entityId, tbv);
        }
    }
    
    /**
     * Store TBV in appropriate spatial nodes
     */
    protected void storeTBVInNodes(ID entityId, TemporalBoundingVolume<ID> tbv) {
        var locations = entityManager.getEntityLocations(entityId);
        for (var key : locations) {
            var node = getSpatialIndex().get(key);
            if (node instanceof OcclusionAwareSpatialNode) {
                @SuppressWarnings("unchecked")
                var occlusionNode = (OcclusionAwareSpatialNode<ID>) node;
                occlusionNode.addTBV(tbv);
            }
        }
    }
    
    /**
     * Calculate appropriate level for position
     */
    protected abstract byte calculateAppropriateLevel(Point3f position);
    
    /**
     * Get current frame from frame manager
     */
    public long getCurrentFrame() {
        return frameManager.getCurrentFrame();
    }
    
    /**
     * Increment frame
     */
    public void nextFrame() {
        frameManager.incrementFrame();
    }
}