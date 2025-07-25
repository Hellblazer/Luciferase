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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityDynamics;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages visibility state transitions for entities in the spatial index.
 * Handles the creation of temporal bounding volumes when entities become hidden
 * and tracks state changes for efficient occlusion culling.
 *
 * State Transitions:
 * - UNKNOWN -> VISIBLE: Entity first appears in view
 * - VISIBLE -> HIDDEN_WITH_TBV: Entity becomes occluded, TBV created
 * - HIDDEN_WITH_TBV -> VISIBLE: Entity reappears, TBV removed
 * - HIDDEN_WITH_TBV -> HIDDEN_EXPIRED: TBV expires
 * - HIDDEN_EXPIRED -> VISIBLE: Entity forced update on next visibility
 *
 * Thread Safety: Uses concurrent collections for thread-safe access.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public class VisibilityStateManager<ID extends EntityID> {
    
    private static final Logger log = LoggerFactory.getLogger(VisibilityStateManager.class);
    
    /**
     * Visibility states for entities
     */
    public enum VisibilityState {
        VISIBLE,           // Entity is currently visible
        HIDDEN_WITH_TBV,   // Entity is hidden but has active TBV
        HIDDEN_EXPIRED,    // Entity is hidden and TBV has expired
        UNKNOWN           // Entity visibility is unknown (initial state)
    }
    
    /**
     * Entity visibility information
     */
    public static class VisibilityInfo<ID> {
        private final VisibilityState state;
        private final long lastVisibleFrame;
        private final long hiddenSinceFrame;
        private final long tbvCreatedFrame;
        private final ID entityId;
        
        public VisibilityInfo(ID entityId, VisibilityState state, 
                            long lastVisibleFrame, long hiddenSinceFrame, 
                            long tbvCreatedFrame) {
            this.entityId = entityId;
            this.state = state;
            this.lastVisibleFrame = lastVisibleFrame;
            this.hiddenSinceFrame = hiddenSinceFrame;
            this.tbvCreatedFrame = tbvCreatedFrame;
        }
        
        public VisibilityState getState() { return state; }
        public long getLastVisibleFrame() { return lastVisibleFrame; }
        public long getHiddenSinceFrame() { return hiddenSinceFrame; }
        public long getTbvCreatedFrame() { return tbvCreatedFrame; }
        public ID getEntityId() { return entityId; }
        
        public long getHiddenDuration(long currentFrame) {
            return hiddenSinceFrame > 0 ? currentFrame - hiddenSinceFrame : 0;
        }
    }
    
    // State storage
    private final Map<ID, VisibilityInfo<ID>> entityStates;
    private final Map<ID, TemporalBoundingVolume<ID>> activeTBVs;
    
    // Configuration
    private final DSOCConfiguration config;
    private final TBVStrategy tbvStrategy;
    
    // Statistics
    private final AtomicLong totalTBVsCreated = new AtomicLong(0);
    private final AtomicLong totalTBVsExpired = new AtomicLong(0);
    private final AtomicLong totalStateTransitions = new AtomicLong(0);
    
    /**
     * Create a visibility state manager with configuration
     *
     * @param config DSOC configuration
     */
    public VisibilityStateManager(DSOCConfiguration config) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        this.tbvStrategy = config.getTbvStrategy();
        this.entityStates = new ConcurrentHashMap<>();
        this.activeTBVs = new ConcurrentHashMap<>();
    }
    
    /**
     * Update visibility state for an entity
     *
     * @param entityId the entity ID
     * @param isVisible whether the entity is currently visible
     * @param currentFrame current frame number
     * @return the new visibility state
     */
    public VisibilityState updateVisibility(ID entityId, boolean isVisible, long currentFrame) {
        var currentInfo = entityStates.get(entityId);
        var currentState = currentInfo != null ? currentInfo.state : VisibilityState.UNKNOWN;
        var newState = currentState;
        
        // State machine logic
        switch (currentState) {
            case UNKNOWN:
                newState = isVisible ? VisibilityState.VISIBLE : VisibilityState.UNKNOWN;
                break;
                
            case VISIBLE:
                if (!isVisible) {
                    newState = VisibilityState.HIDDEN_WITH_TBV;
                }
                break;
                
            case HIDDEN_WITH_TBV:
                if (isVisible) {
                    newState = VisibilityState.VISIBLE;
                    removeTBV(entityId);
                } else {
                    // Check if TBV expired
                    var tbv = activeTBVs.get(entityId);
                    if (tbv != null && !tbv.isValid((int) currentFrame)) {
                        newState = VisibilityState.HIDDEN_EXPIRED;
                        removeTBV(entityId);
                        totalTBVsExpired.incrementAndGet();
                    }
                }
                break;
                
            case HIDDEN_EXPIRED:
                if (isVisible) {
                    newState = VisibilityState.VISIBLE;
                }
                break;
        }
        
        // Update state if changed
        if (newState != currentState) {
            updateStateInfo(entityId, currentInfo, newState, currentFrame);
            totalStateTransitions.incrementAndGet();
            
            if (log.isDebugEnabled()) {
                log.debug("Entity {} transitioned from {} to {} at frame {}", 
                         entityId, currentState, newState, currentFrame);
            }
        }
        
        return newState;
    }
    
    /**
     * Create a temporal bounding volume for an entity
     *
     * @param entityId the entity ID
     * @param dynamics entity dynamics for velocity
     * @param bounds entity bounds
     * @param currentFrame current frame number
     * @return the created TBV, or null if creation failed
     */
    public TemporalBoundingVolume<ID> createTBV(ID entityId, EntityDynamics dynamics, 
                                               EntityBounds bounds, long currentFrame) {
        if (dynamics == null || bounds == null) {
            log.warn("Cannot create TBV for entity {} - missing dynamics or bounds", entityId);
            return null;
        }
        
        // Check if we should create TBV based on velocity
        var velocity = dynamics.getVelocity();
        if (velocity.length() < config.getVelocityThreshold() && !config.isAlwaysCreateTbv()) {
            log.debug("Entity {} velocity {} below threshold {}, skipping TBV creation",
                     entityId, velocity.length(), config.getVelocityThreshold());
            return null;
        }
        
        // Create TBV using configured strategy
        var tbv = new TemporalBoundingVolume<>(entityId, bounds, velocity, (int) currentFrame, tbvStrategy);
        
        // Store TBV
        activeTBVs.put(entityId, tbv);
        totalTBVsCreated.incrementAndGet();
        
        // Update state to HIDDEN_WITH_TBV
        updateVisibility(entityId, false, currentFrame);
        
        log.debug("Created TBV for entity {} with validity {} frames", 
                 entityId, tbv.getValidityDuration());
        
        return tbv;
    }
    
    /**
     * Get the current visibility state for an entity
     *
     * @param entityId the entity ID
     * @return the visibility state
     */
    public VisibilityState getState(ID entityId) {
        var info = entityStates.get(entityId);
        return info != null ? info.state : VisibilityState.UNKNOWN;
    }
    
    /**
     * Get visibility information for an entity
     *
     * @param entityId the entity ID
     * @return visibility info, or null if unknown
     */
    public VisibilityInfo<ID> getVisibilityInfo(ID entityId) {
        return entityStates.get(entityId);
    }
    
    /**
     * Get the active TBV for an entity
     *
     * @param entityId the entity ID
     * @return the TBV, or null if none exists
     */
    public TemporalBoundingVolume<ID> getTBV(ID entityId) {
        return activeTBVs.get(entityId);
    }
    
    /**
     * Check if an entity has an active TBV
     *
     * @param entityId the entity ID
     * @return true if TBV exists
     */
    public boolean hasTBV(ID entityId) {
        return activeTBVs.containsKey(entityId);
    }
    
    /**
     * Get all entities with active TBVs
     *
     * @return set of entity IDs
     */
    public Set<ID> getEntitiesWithTBVs() {
        return new HashSet<>(activeTBVs.keySet());
    }
    
    /**
     * Remove expired TBVs
     *
     * @param currentFrame current frame number
     * @return list of entity IDs whose TBVs expired
     */
    public List<ID> pruneExpiredTBVs(long currentFrame) {
        var expired = new ArrayList<ID>();
        var iterator = activeTBVs.entrySet().iterator();
        
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().isValid((int) currentFrame)) {
                expired.add(entry.getKey());
                iterator.remove();
                totalTBVsExpired.incrementAndGet();
                
                // Update state to HIDDEN_EXPIRED
                updateVisibility(entry.getKey(), false, currentFrame);
            }
        }
        
        return expired;
    }
    
    /**
     * Force visibility update for entities that need it
     *
     * @param currentFrame current frame number
     * @return list of entity IDs that need position updates
     */
    public List<ID> getEntitiesNeedingUpdate(long currentFrame) {
        return entityStates.entrySet().stream()
            .filter(entry -> {
                var info = entry.getValue();
                // Entities that have been hidden too long
                if (info.state == VisibilityState.HIDDEN_EXPIRED) {
                    return true;
                }
                // Entities with low quality TBVs
                if (info.state == VisibilityState.HIDDEN_WITH_TBV) {
                    var tbv = activeTBVs.get(entry.getKey());
                    return tbv != null && tbv.getQuality((int) currentFrame) < config.getTbvRefreshThreshold();
                }
                return false;
            })
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    /**
     * Clear visibility data for an entity
     *
     * @param entityId the entity ID
     */
    public void clearEntity(ID entityId) {
        entityStates.remove(entityId);
        removeTBV(entityId);
    }
    
    /**
     * Clear all visibility data
     */
    public void clear() {
        entityStates.clear();
        activeTBVs.clear();
    }
    
    /**
     * Get statistics about visibility management
     *
     * @return map of statistic names to values
     */
    public Map<String, Object> getStatistics() {
        var stats = new HashMap<String, Object>();
        
        // Count states
        var stateCounts = new EnumMap<VisibilityState, Long>(VisibilityState.class);
        for (VisibilityState state : VisibilityState.values()) {
            stateCounts.put(state, 0L);
        }
        entityStates.values().forEach(info -> 
            stateCounts.merge(info.state, 1L, Long::sum));
        
        stats.put("totalEntities", entityStates.size());
        stats.put("visibleEntities", stateCounts.get(VisibilityState.VISIBLE));
        stats.put("hiddenWithTBV", stateCounts.get(VisibilityState.HIDDEN_WITH_TBV));
        stats.put("hiddenExpired", stateCounts.get(VisibilityState.HIDDEN_EXPIRED));
        stats.put("unknownEntities", stateCounts.get(VisibilityState.UNKNOWN));
        stats.put("activeTBVs", activeTBVs.size());
        stats.put("totalTBVsCreated", totalTBVsCreated.get());
        stats.put("totalTBVsExpired", totalTBVsExpired.get());
        stats.put("totalStateTransitions", totalStateTransitions.get());
        
        return stats;
    }
    
    // Private helper methods
    
    private void updateStateInfo(ID entityId, VisibilityInfo<ID> oldInfo, 
                               VisibilityState newState, long currentFrame) {
        long lastVisible = oldInfo != null ? oldInfo.lastVisibleFrame : -1;
        long hiddenSince = oldInfo != null ? oldInfo.hiddenSinceFrame : -1;
        long tbvCreated = oldInfo != null ? oldInfo.tbvCreatedFrame : -1;
        
        // Update frame tracking based on state transition
        if (newState == VisibilityState.VISIBLE) {
            lastVisible = currentFrame;
            hiddenSince = -1;
        } else if (oldInfo == null || oldInfo.state == VisibilityState.VISIBLE) {
            // Just became hidden
            hiddenSince = currentFrame;
        }
        
        if (newState == VisibilityState.HIDDEN_WITH_TBV && 
            (oldInfo == null || oldInfo.state != VisibilityState.HIDDEN_WITH_TBV)) {
            tbvCreated = currentFrame;
        }
        
        entityStates.put(entityId, new VisibilityInfo<>(entityId, newState, 
                                                     lastVisible, hiddenSince, tbvCreated));
    }
    
    private void removeTBV(ID entityId) {
        activeTBVs.remove(entityId);
    }
}