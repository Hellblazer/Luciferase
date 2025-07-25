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
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.entity.Entity;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hierarchical occlusion culler that integrates with spatial indices
 * to perform efficient visibility culling using a hierarchical Z-buffer.
 *
 * @author hal.hildebrand
 */
public class HierarchicalOcclusionCuller<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private final HierarchicalZBuffer zBuffer;
    private final DSOCConfiguration config;
    private final OcclusionStatistics statistics;
    // Comparator removed - front-to-back sorting handled in AbstractSpatialIndex
    private final Set<ID> entitiesNeedingUpdate = ConcurrentHashMap.newKeySet();
    
    /**
     * Creates a hierarchical occlusion culler
     * 
     * @param bufferWidth Z-buffer width
     * @param bufferHeight Z-buffer height
     * @param config DSOC configuration
     */
    public HierarchicalOcclusionCuller(int bufferWidth, int bufferHeight, DSOCConfiguration config) {
        this.zBuffer = new HierarchicalZBuffer(bufferWidth, bufferHeight, config.getZPyramidLevels());
        this.config = config;
        this.statistics = new OcclusionStatistics();
        // Front-to-back comparator removed - handled in AbstractSpatialIndex
    }
    
    /**
     * Tests if a node is occluded
     * 
     * @param nodeBounds The bounds of the node
     * @return true if the node is occluded
     */
    public boolean isNodeOccluded(EntityBounds nodeBounds) {
        if (!config.isEnableNodeOcclusion()) {
            return false;
        }
        statistics.nodesTested.incrementAndGet();
        boolean occluded = zBuffer.isOccluded(nodeBounds);
        if (occluded) {
            statistics.nodesOccluded.incrementAndGet();
        }
        return occluded;
    }
    
    /**
     * Tests if an entity is occluded
     * 
     * @param entityBounds The bounds of the entity
     * @return true if the entity is occluded
     */
    public boolean isEntityOccluded(EntityBounds entityBounds) {
        if (!config.isEnableEntityOcclusion()) {
            return false;
        }
        statistics.entitiesTested.incrementAndGet();
        boolean occluded = zBuffer.isOccluded(entityBounds);
        if (occluded) {
            statistics.entitiesOccluded.incrementAndGet();
        }
        return occluded;
    }
    
    /**
     * Renders an occluder
     * 
     * @param bounds The bounds to render as an occluder
     */
    public void renderOccluder(EntityBounds bounds) {
        if (bounds.volume() > config.getMinOccluderVolume()) {
            zBuffer.renderOccluder(bounds);
            statistics.occludersRendered.incrementAndGet();
        }
    }
    
    /**
     * Begin a new frame
     * 
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param frustum The view frustum
     */
    public void beginFrame(float[] viewMatrix, float[] projectionMatrix, Frustum3D frustum) {
        statistics.beginFrame();
        zBuffer.updateCamera(viewMatrix, projectionMatrix, 
                           frustum.getNearPlane(), frustum.getFarPlane());
        zBuffer.clear();
    }
    
    /**
     * End the current frame
     */
    public void endFrame() {
        zBuffer.updateHierarchy();
        statistics.endFrame();
    }
    
    /**
     * Process TBV visibility
     * 
     * @param tbv The temporal bounding volume
     * @param frustum The view frustum
     * @param currentFrame Current frame number
     * @return true if the TBV is visible
     */
    public boolean isTBVVisible(TemporalBoundingVolume<ID> tbv, Frustum3D frustum, long currentFrame) {
        statistics.tbvsTested.incrementAndGet();
        
        EntityBounds tbvBounds = tbv.getBoundsAtFrame((int) currentFrame);
        
        // Frustum test
        if (!frustum.intersects(tbvBounds)) {
            return false;
        }
        
        // Occlusion test
        if (zBuffer.isOccluded(tbvBounds)) {
            statistics.tbvsOccluded.incrementAndGet();
            return false;
        }
        
        statistics.tbvsVisible.incrementAndGet();
        markEntityForUpdate(tbv.getEntityId());
        return true;
    }
    
    /**
     * Increment entity visible count
     */
    public void incrementEntitiesVisible() {
        statistics.entitiesVisible.incrementAndGet();
    }
    
    /**
     * Increment frustum culled count
     */
    public void incrementFrustumCulled() {
        statistics.entitiesFrustumCulled.incrementAndGet();
    }
    
    
    /**
     * Gets occlusion statistics
     */
    public Map<String, Object> getStatistics() {
        return statistics.getSnapshot();
    }
    
    /**
     * Resets statistics
     */
    public void resetStatistics() {
        statistics.reset();
    }
    
    // Removed collectFrustumNodes - now using helper methods from AbstractSpatialIndex
    
    // Methods requiring internal access removed - handled in AbstractSpatialIndex
    
    
    
    
    
    
    /**
     * Marks entity for position update
     */
    private void markEntityForUpdate(ID entityId) {
        entitiesNeedingUpdate.add(entityId);
    }
    
    /**
     * Get entities that need position updates
     * 
     * @return Set of entity IDs needing updates
     */
    public Set<ID> getEntitiesNeedingUpdate() {
        Set<ID> result = new HashSet<>(entitiesNeedingUpdate);
        entitiesNeedingUpdate.clear();
        return result;
    }
    
    
    
    /**
     * Occlusion statistics tracking
     */
    private static class OcclusionStatistics {
        final AtomicLong frameCount = new AtomicLong();
        final AtomicLong nodesTested = new AtomicLong();
        final AtomicLong nodesOccluded = new AtomicLong();
        final AtomicLong entitiesTested = new AtomicLong();
        final AtomicLong entitiesFrustumCulled = new AtomicLong();
        final AtomicLong entitiesOccluded = new AtomicLong();
        final AtomicLong entitiesVisible = new AtomicLong();
        final AtomicLong tbvsTested = new AtomicLong();
        final AtomicLong tbvsOccluded = new AtomicLong();
        final AtomicLong tbvsVisible = new AtomicLong();
        final AtomicLong tbvsExpired = new AtomicLong();
        final AtomicLong occludersRendered = new AtomicLong();
        
        private long frameStartTime;
        private final AtomicLong totalFrameTime = new AtomicLong();
        
        void beginFrame() {
            frameStartTime = System.nanoTime();
        }
        
        void endFrame() {
            long frameTime = System.nanoTime() - frameStartTime;
            totalFrameTime.addAndGet(frameTime);
            frameCount.incrementAndGet();
        }
        
        void reset() {
            frameCount.set(0);
            nodesTested.set(0);
            nodesOccluded.set(0);
            entitiesTested.set(0);
            entitiesFrustumCulled.set(0);
            entitiesOccluded.set(0);
            entitiesVisible.set(0);
            tbvsTested.set(0);
            tbvsOccluded.set(0);
            tbvsVisible.set(0);
            tbvsExpired.set(0);
            occludersRendered.set(0);
            totalFrameTime.set(0);
        }
        
        Map<String, Object> getSnapshot() {
            Map<String, Object> stats = new HashMap<>();
            long frames = frameCount.get();
            
            stats.put("frameCount", frames);
            stats.put("nodesTested", nodesTested.get());
            stats.put("nodesOccluded", nodesOccluded.get());
            stats.put("entitiesTested", entitiesTested.get());
            stats.put("entitiesFrustumCulled", entitiesFrustumCulled.get());
            stats.put("entitiesOccluded", entitiesOccluded.get());
            stats.put("entitiesVisible", entitiesVisible.get());
            stats.put("tbvsTested", tbvsTested.get());
            stats.put("tbvsOccluded", tbvsOccluded.get());
            stats.put("tbvsVisible", tbvsVisible.get());
            stats.put("tbvsExpired", tbvsExpired.get());
            stats.put("occludersRendered", occludersRendered.get());
            
            if (frames > 0) {
                stats.put("avgFrameTimeMs", totalFrameTime.get() / frames / 1_000_000.0);
                stats.put("nodeOcclusionRate", (double) nodesOccluded.get() / nodesTested.get());
                stats.put("entityOcclusionRate", (double) entitiesOccluded.get() / entitiesTested.get());
                stats.put("tbvHitRate", (double) tbvsVisible.get() / tbvsTested.get());
            }
            
            return stats;
        }
    }
}