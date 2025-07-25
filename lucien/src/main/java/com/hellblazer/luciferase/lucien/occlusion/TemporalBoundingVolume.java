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

import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Temporal Bounding Volume (TBV) for Dynamic Spatiotemporal Occlusion Culling (DSOC).
 * 
 * A TBV represents an expanded bounding volume that encompasses an entity's potential
 * positions over a time interval. This allows efficient culling of moving entities
 * without requiring per-frame updates to the spatial index.
 * 
 * The expanded bounds account for the entity's velocity and the validity duration,
 * creating a conservative estimate of where the entity might be during the time window.
 *
 * @author hal.hildebrand
 */
public class TemporalBoundingVolume<ID> {
    
    private final ID entityId;
    private final EntityBounds originalBounds;
    private final EntityBounds expandedBounds;
    private final int creationFrame;
    private final int validityDuration;
    private final Vector3f velocity;
    private final float expansionFactor;
    
    /**
     * Create a temporal bounding volume with explicit parameters.
     * 
     * @param entityId The unique identifier of the entity
     * @param originalBounds The original bounds of the entity
     * @param velocity The velocity vector of the entity
     * @param creationFrame The frame when this TBV was created
     * @param validityDuration The number of frames this TBV remains valid
     * @param expansionFactor Additional expansion factor for conservative estimation
     */
    public TemporalBoundingVolume(ID entityId, EntityBounds originalBounds, Vector3f velocity, 
                                  int creationFrame, int validityDuration, float expansionFactor) {
        this.entityId = entityId;
        this.originalBounds = originalBounds;
        this.velocity = new Vector3f(velocity);
        this.creationFrame = creationFrame;
        this.validityDuration = validityDuration;
        this.expansionFactor = expansionFactor;
        this.expandedBounds = calculateExpandedBounds();
    }
    
    /**
     * Create a temporal bounding volume using a strategy.
     * 
     * @param entityId The unique identifier of the entity
     * @param originalBounds The original bounds of the entity  
     * @param velocity The velocity vector of the entity
     * @param creationFrame The frame when this TBV was created
     * @param strategy The strategy to determine validity duration and expansion
     */
    public TemporalBoundingVolume(ID entityId, EntityBounds originalBounds, Vector3f velocity,
                                  int creationFrame, TBVStrategy strategy) {
        this.entityId = entityId;
        this.originalBounds = originalBounds;
        this.velocity = new Vector3f(velocity);
        this.creationFrame = creationFrame;
        
        var params = strategy.calculateTBVParameters(originalBounds, velocity);
        this.validityDuration = params.validityDuration();
        this.expansionFactor = params.expansionFactor();
        this.expandedBounds = calculateExpandedBounds();
    }
    
    /**
     * Calculate the expanded bounds based on velocity and validity duration.
     * The expansion accounts for potential movement in all directions.
     */
    private EntityBounds calculateExpandedBounds() {
        var min = originalBounds.getMin();
        var max = originalBounds.getMax();
        
        // Calculate displacement over validity duration
        var displacement = new Vector3f(velocity);
        displacement.scale(validityDuration);
        
        // Calculate expansion in each direction
        var expansion = new Vector3f(
            Math.abs(displacement.x) + expansionFactor,
            Math.abs(displacement.y) + expansionFactor,
            Math.abs(displacement.z) + expansionFactor
        );
        
        // Expand bounds to account for movement in any direction
        var expandedMin = new Point3f(
            min.x - expansion.x,
            min.y - expansion.y,
            min.z - expansion.z
        );
        
        var expandedMax = new Point3f(
            max.x + expansion.x,
            max.y + expansion.y,
            max.z + expansion.z
        );
        
        return new EntityBounds(expandedMin, expandedMax);
    }
    
    /**
     * Check if this TBV is still valid at the given frame.
     * 
     * @param currentFrame The current frame number
     * @return true if the TBV is still valid, false if it has expired
     */
    public boolean isValid(int currentFrame) {
        return currentFrame >= creationFrame && 
               currentFrame < (creationFrame + validityDuration);
    }
    
    /**
     * Get the remaining validity duration from the current frame.
     * 
     * @param currentFrame The current frame number
     * @return The number of frames remaining, or 0 if expired
     */
    public int getRemainingValidity(int currentFrame) {
        if (currentFrame < creationFrame) {
            return 0; // TBV not yet created
        }
        var endFrame = creationFrame + validityDuration;
        return Math.max(0, endFrame - currentFrame);
    }
    
    /**
     * Get the interpolated bounds at a specific frame within the validity period.
     * This provides a tighter bound than the fully expanded bounds.
     * 
     * @param frame The frame to get bounds for
     * @return The interpolated bounds, or expanded bounds if frame is outside validity
     */
    public EntityBounds getBoundsAtFrame(int frame) {
        if (frame < creationFrame || frame >= creationFrame + validityDuration) {
            return expandedBounds;
        }
        
        var elapsed = frame - creationFrame;
        var displacement = new Vector3f(velocity);
        displacement.scale(elapsed);
        
        var min = originalBounds.getMin();
        var max = originalBounds.getMax();
        
        // Apply displacement to get current position
        var currentMin = new Point3f(
            min.x + displacement.x,
            min.y + displacement.y,
            min.z + displacement.z
        );
        
        var currentMax = new Point3f(
            max.x + displacement.x,
            max.y + displacement.y,
            max.z + displacement.z
        );
        
        return new EntityBounds(currentMin, currentMax);
    }
    
    /**
     * Convert the expanded bounds to a VolumeBounds for spatial operations.
     */
    public VolumeBounds toVolumeBounds() {
        var min = expandedBounds.getMin();
        var max = expandedBounds.getMax();
        return new VolumeBounds(min.x, min.y, min.z, max.x, max.y, max.z);
    }
    
    /**
     * Get the quality score of this TBV at the given frame.
     * Quality decreases as the TBV ages, indicating less precise bounds.
     * 
     * @param currentFrame The current frame number
     * @return Quality score between 0.0 (expired) and 1.0 (fresh)
     */
    public float getQuality(int currentFrame) {
        if (!isValid(currentFrame)) {
            return 0.0f;
        }
        
        var age = currentFrame - creationFrame;
        return 1.0f - ((float) age / validityDuration);
    }
    
    /**
     * Get the maximum velocity used for expansion calculation
     */
    public Vector3f getMaxVelocity() {
        return new Vector3f(velocity);
    }
    
    // Getters
    
    public ID getEntityId() {
        return entityId;
    }
    
    public EntityBounds getOriginalBounds() {
        return originalBounds;
    }
    
    public EntityBounds getExpandedBounds() {
        return expandedBounds;
    }
    
    public int getCreationFrame() {
        return creationFrame;
    }
    
    public int getValidityDuration() {
        return validityDuration;
    }
    
    public Vector3f getVelocity() {
        return new Vector3f(velocity);
    }
    
    public float getExpansionFactor() {
        return expansionFactor;
    }
    
    @Override
    public String toString() {
        return String.format("TBV[entity=%s, frame=%d-%d, velocity=(%.2f,%.2f,%.2f), expansion=%.2f]",
                           entityId, creationFrame, creationFrame + validityDuration,
                           velocity.x, velocity.y, velocity.z, expansionFactor);
    }
}