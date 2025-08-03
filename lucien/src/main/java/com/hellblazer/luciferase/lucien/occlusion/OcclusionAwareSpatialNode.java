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

import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Extension of SpatialNodeImpl that adds occlusion awareness and temporal bounding volume support.
 * This node type tracks occlusion metadata and stores TBVs for entities that have moved while hidden.
 *
 * Thread Safety: Uses atomic references and concurrent collections for thread-safe access.
 * Structural modifications still rely on external synchronization from AbstractSpatialIndex.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public class OcclusionAwareSpatialNode<ID extends EntityID> extends SpatialNodeImpl<ID> {
    
    // Occlusion metadata
    private final AtomicReference<Float> occlusionScore = new AtomicReference<>(0.0f);
    private final AtomicLong lastOcclusionFrame = new AtomicLong(-1);
    private final AtomicReference<Boolean> isOccluder = new AtomicReference<>(false);
    
    // TBV storage - maps entity ID to its TBV
    private final Map<ID, TemporalBoundingVolume<ID>> activeTBVs;
    
    // Visibility tracking
    private final AtomicLong lastVisibleFrame = new AtomicLong(-1);
    private final AtomicLong occludedSinceFrame = new AtomicLong(-1);
    
    /**
     * Create an occlusion-aware node with default max entities (10)
     */
    public OcclusionAwareSpatialNode() {
        this(10);
    }
    
    /**
     * Create an occlusion-aware node with specified max entities before split
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    public OcclusionAwareSpatialNode(int maxEntitiesBeforeSplit) {
        super(maxEntitiesBeforeSplit);
        this.activeTBVs = new HashMap<>();
    }
    
    /**
     * Add a temporal bounding volume for an entity
     *
     * @param tbv the temporal bounding volume to add
     */
    public void addTBV(TemporalBoundingVolume<ID> tbv) {
        if (tbv == null) {
            throw new IllegalArgumentException("TBV cannot be null");
        }
        activeTBVs.put(tbv.getEntityId(), tbv);
    }
    
    /**
     * Remove a temporal bounding volume for an entity
     *
     * @param entityId the entity whose TBV should be removed
     * @return the removed TBV, or null if none existed
     */
    public TemporalBoundingVolume<ID> removeTBV(ID entityId) {
        return activeTBVs.remove(entityId);
    }
    
    /**
     * Get the temporal bounding volume for an entity
     *
     * @param entityId the entity ID
     * @return the TBV, or null if none exists
     */
    public TemporalBoundingVolume<ID> getTBV(ID entityId) {
        return activeTBVs.get(entityId);
    }
    
    /**
     * Get all active temporal bounding volumes
     *
     * @return unmodifiable collection of TBVs
     */
    public Collection<TemporalBoundingVolume<ID>> getTBVs() {
        return Collections.unmodifiableCollection(activeTBVs.values());
    }
    
    /**
     * Remove expired TBVs based on the current frame
     *
     * @param currentFrame the current frame number
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
            }
        }
        
        return expired;
    }
    
    /**
     * Check if any TBVs exist in this node
     *
     * @return true if there are active TBVs
     */
    public boolean hasTBVs() {
        return !activeTBVs.isEmpty();
    }
    
    /**
     * Get the number of active TBVs
     *
     * @return count of TBVs
     */
    public int getTBVCount() {
        return activeTBVs.size();
    }
    
    /**
     * Set the occlusion score for this node
     *
     * @param score value between 0.0 (not occluded) and 1.0 (fully occluded)
     */
    public void setOcclusionScore(float score) {
        if (score < 0.0f || score > 1.0f) {
            throw new IllegalArgumentException("Occlusion score must be between 0.0 and 1.0");
        }
        occlusionScore.set(score);
    }
    
    /**
     * Get the occlusion score for this node
     *
     * @return score between 0.0 and 1.0
     */
    public float getOcclusionScore() {
        return occlusionScore.get();
    }
    
    /**
     * Update the last occlusion test frame
     *
     * @param frame the frame when occlusion was last tested
     */
    public void updateLastOcclusionFrame(long frame) {
        lastOcclusionFrame.set(frame);
    }
    
    /**
     * Get the last frame when occlusion was tested
     *
     * @return frame number, or -1 if never tested
     */
    public long getLastOcclusionFrame() {
        return lastOcclusionFrame.get();
    }
    
    /**
     * Set whether this node acts as an occluder
     *
     * @param isOccluder true if this node occludes others
     */
    public void setIsOccluder(boolean isOccluder) {
        this.isOccluder.set(isOccluder);
    }
    
    /**
     * Check if this node acts as an occluder
     *
     * @return true if this node occludes others
     */
    public boolean isOccluder() {
        return isOccluder.get();
    }
    
    /**
     * Mark this node as visible at the current frame
     *
     * @param frame the current frame number
     */
    public void markVisible(long frame) {
        lastVisibleFrame.set(frame);
        occludedSinceFrame.set(-1);
        setOcclusionScore(0.0f);
    }
    
    /**
     * Mark this node as occluded starting at the current frame
     *
     * @param frame the current frame number
     */
    public void markOccluded(long frame) {
        if (occludedSinceFrame.get() == -1) {
            occludedSinceFrame.set(frame);
        }
        setOcclusionScore(1.0f);
    }
    
    /**
     * Get the last frame when this node was visible
     *
     * @return frame number, or -1 if never visible
     */
    public long getLastVisibleFrame() {
        return lastVisibleFrame.get();
    }
    
    /**
     * Get the frame when this node became occluded
     *
     * @return frame number, or -1 if not occluded
     */
    public long getOccludedSinceFrame() {
        return occludedSinceFrame.get();
    }
    
    /**
     * Check if this node is currently occluded
     *
     * @return true if occluded
     */
    public boolean isOccluded() {
        return occludedSinceFrame.get() != -1;
    }
    
    /**
     * Get the duration this node has been occluded
     *
     * @param currentFrame the current frame number
     * @return number of frames occluded, or 0 if not occluded
     */
    public long getOccludedDuration(long currentFrame) {
        var since = occludedSinceFrame.get();
        return since == -1 ? 0 : currentFrame - since;
    }
    
    /**
     * Clear all occlusion-related data
     */
    public void clearOcclusionData() {
        occlusionScore.set(0.0f);
        lastOcclusionFrame.set(-1);
        isOccluder.set(false);
        lastVisibleFrame.set(-1);
        occludedSinceFrame.set(-1);
        activeTBVs.clear();
    }
    
    @Override
    public void clearEntities() {
        super.clearEntities();
        activeTBVs.clear();
    }
    
    /**
     * Get statistics about this node's occlusion state
     *
     * @return map of statistic names to values
     */
    public Map<String, Object> getOcclusionStatistics() {
        var stats = new HashMap<String, Object>();
        stats.put("occlusionScore", getOcclusionScore());
        stats.put("isOccluder", isOccluder());
        stats.put("isOccluded", isOccluded());
        stats.put("lastOcclusionFrame", getLastOcclusionFrame());
        stats.put("lastVisibleFrame", getLastVisibleFrame());
        stats.put("occludedSinceFrame", getOccludedSinceFrame());
        stats.put("activeTBVCount", getTBVCount());
        stats.put("entityCount", getEntityCount());
        return stats;
    }
}