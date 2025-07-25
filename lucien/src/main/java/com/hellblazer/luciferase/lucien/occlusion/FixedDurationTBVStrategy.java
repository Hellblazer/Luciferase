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

import javax.vecmath.Vector3f;

/**
 * Fixed duration TBV strategy that uses a constant validity duration for all entities.
 * 
 * This is the simplest strategy and works well when:
 * - Entity velocities are relatively uniform
 * - Predictable update intervals are desired
 * - Simplicity is more important than optimization
 * 
 * The expansion factor can be configured to add additional padding based on
 * expected velocity variations or uncertainty.
 *
 * @author hal.hildebrand
 */
public class FixedDurationTBVStrategy implements TBVStrategy {
    
    private final int validityDuration;
    private final float baseExpansionFactor;
    private final float velocityExpansionMultiplier;
    
    /**
     * Create a fixed duration strategy with specified parameters.
     * 
     * @param validityDuration The fixed validity duration in frames
     * @param baseExpansionFactor Base expansion added to all TBVs
     * @param velocityExpansionMultiplier Multiplier applied to velocity magnitude for additional expansion
     */
    public FixedDurationTBVStrategy(int validityDuration, float baseExpansionFactor, 
                                   float velocityExpansionMultiplier) {
        if (validityDuration <= 0) {
            throw new IllegalArgumentException("Validity duration must be positive");
        }
        if (baseExpansionFactor < 0) {
            throw new IllegalArgumentException("Base expansion factor cannot be negative");
        }
        if (velocityExpansionMultiplier < 0) {
            throw new IllegalArgumentException("Velocity expansion multiplier cannot be negative");
        }
        
        this.validityDuration = validityDuration;
        this.baseExpansionFactor = baseExpansionFactor;
        this.velocityExpansionMultiplier = velocityExpansionMultiplier;
    }
    
    /**
     * Create a simple fixed duration strategy with default expansion.
     * 
     * @param validityDuration The fixed validity duration in frames
     */
    public FixedDurationTBVStrategy(int validityDuration) {
        this(validityDuration, 0.1f, 0.1f);
    }
    
    /**
     * Create a default fixed duration strategy suitable for 60 FPS applications.
     */
    public static FixedDurationTBVStrategy defaultStrategy() {
        return new FixedDurationTBVStrategy(60); // 1 second at 60 FPS
    }
    
    /**
     * Create a conservative strategy with larger expansion factors.
     */
    public static FixedDurationTBVStrategy conservativeStrategy(int validityDuration) {
        return new FixedDurationTBVStrategy(validityDuration, 0.5f, 0.2f);
    }
    
    /**
     * Create an aggressive strategy with minimal expansion.
     */
    public static FixedDurationTBVStrategy aggressiveStrategy(int validityDuration) {
        return new FixedDurationTBVStrategy(validityDuration, 0.05f, 0.05f);
    }
    
    @Override
    public TBVParameters calculateTBVParameters(EntityBounds bounds, Vector3f velocity) {
        // Calculate expansion based on velocity magnitude
        var velocityMagnitude = velocity.length();
        var velocityExpansion = velocityMagnitude * velocityExpansionMultiplier;
        
        // Total expansion is base plus velocity-based expansion
        var totalExpansion = baseExpansionFactor + velocityExpansion;
        
        return TBVParameters.with(validityDuration, totalExpansion);
    }
    
    @Override
    public int getMinValidityDuration() {
        return validityDuration;
    }
    
    @Override
    public int getMaxValidityDuration() {
        return validityDuration;
    }
    
    @Override
    public String getName() {
        return String.format("FixedDuration[%d frames]", validityDuration);
    }
    
    // Getters for configuration inspection
    
    public int getValidityDuration() {
        return validityDuration;
    }
    
    public float getBaseExpansionFactor() {
        return baseExpansionFactor;
    }
    
    public float getVelocityExpansionMultiplier() {
        return velocityExpansionMultiplier;
    }
    
    @Override
    public String toString() {
        return String.format("FixedDurationTBVStrategy[duration=%d, baseExpansion=%.3f, velocityMultiplier=%.3f]",
                           validityDuration, baseExpansionFactor, velocityExpansionMultiplier);
    }
}