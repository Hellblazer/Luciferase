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
 * Adaptive TBV strategy that adjusts validity duration based on entity velocity.
 * 
 * This strategy provides:
 * - Longer validity for slow-moving or stationary entities
 * - Shorter validity for fast-moving entities
 * - Dynamic expansion based on velocity and entity size
 * 
 * The adaptive approach optimizes the trade-off between update frequency and
 * bounding volume tightness, improving both performance and culling accuracy.
 *
 * @author hal.hildebrand
 */
public class AdaptiveTBVStrategy implements TBVStrategy {
    
    private final int minValidityDuration;
    private final int maxValidityDuration;
    private final float velocityThreshold;
    private final float sizeInfluenceFactor;
    private final float baseExpansionFactor;
    private final float adaptiveExpansionRate;
    
    /**
     * Create an adaptive strategy with full configuration.
     * 
     * @param minValidityDuration Minimum validity duration (for fast entities)
     * @param maxValidityDuration Maximum validity duration (for slow/stationary entities)
     * @param velocityThreshold Velocity above which minimum duration is used
     * @param sizeInfluenceFactor How much entity size affects duration (0.0 = no effect, 1.0 = full effect)
     * @param baseExpansionFactor Base expansion added to all TBVs
     * @param adaptiveExpansionRate Rate at which expansion increases with velocity
     */
    public AdaptiveTBVStrategy(int minValidityDuration, int maxValidityDuration,
                              float velocityThreshold, float sizeInfluenceFactor,
                              float baseExpansionFactor, float adaptiveExpansionRate) {
        if (minValidityDuration <= 0 || maxValidityDuration <= 0) {
            throw new IllegalArgumentException("Validity durations must be positive");
        }
        if (minValidityDuration > maxValidityDuration) {
            throw new IllegalArgumentException("Min duration cannot exceed max duration");
        }
        if (velocityThreshold < 0) {
            throw new IllegalArgumentException("Velocity threshold cannot be negative");
        }
        if (sizeInfluenceFactor < 0 || sizeInfluenceFactor > 1) {
            throw new IllegalArgumentException("Size influence factor must be between 0 and 1");
        }
        if (baseExpansionFactor < 0 || adaptiveExpansionRate < 0) {
            throw new IllegalArgumentException("Expansion factors cannot be negative");
        }
        
        this.minValidityDuration = minValidityDuration;
        this.maxValidityDuration = maxValidityDuration;
        this.velocityThreshold = velocityThreshold;
        this.sizeInfluenceFactor = sizeInfluenceFactor;
        this.baseExpansionFactor = baseExpansionFactor;
        this.adaptiveExpansionRate = adaptiveExpansionRate;
    }
    
    /**
     * Create an adaptive strategy with sensible defaults.
     */
    public static AdaptiveTBVStrategy defaultStrategy() {
        return new AdaptiveTBVStrategy(
            30,     // 0.5 seconds at 60 FPS
            300,    // 5 seconds at 60 FPS
            10.0f,  // 10 units/frame velocity threshold
            0.3f,   // 30% size influence
            0.1f,   // 0.1 unit base expansion
            0.15f   // 15% adaptive expansion rate
        );
    }
    
    /**
     * Create a strategy optimized for mostly static scenes.
     */
    public static AdaptiveTBVStrategy staticSceneStrategy() {
        return new AdaptiveTBVStrategy(
            60,     // 1 second minimum
            600,    // 10 seconds maximum
            5.0f,   // Lower velocity threshold
            0.5f,   // Higher size influence
            0.05f,  // Minimal base expansion
            0.1f    // Lower adaptive rate
        );
    }
    
    /**
     * Create a strategy optimized for highly dynamic scenes.
     */
    public static AdaptiveTBVStrategy dynamicSceneStrategy() {
        return new AdaptiveTBVStrategy(
            15,     // 0.25 seconds minimum
            120,    // 2 seconds maximum
            20.0f,  // Higher velocity threshold
            0.1f,   // Lower size influence
            0.2f,   // Higher base expansion
            0.25f   // Higher adaptive rate
        );
    }
    
    @Override
    public TBVParameters calculateTBVParameters(EntityBounds bounds, Vector3f velocity) {
        var velocityMagnitude = velocity.length();
        
        // Calculate validity duration based on velocity
        var velocityFactor = Math.min(1.0f, velocityMagnitude / velocityThreshold);
        var baseValidity = minValidityDuration + 
                          (1.0f - velocityFactor) * (maxValidityDuration - minValidityDuration);
        
        // Adjust based on entity size (larger entities can have longer validity)
        var size = Math.max(bounds.getMax().x - bounds.getMin().x,
                           Math.max(bounds.getMax().y - bounds.getMin().y,
                                   bounds.getMax().z - bounds.getMin().z));
        var sizeFactor = 1.0f + (size / 10.0f) * sizeInfluenceFactor;
        
        var validityDuration = (int) Math.max(minValidityDuration, 
                                              Math.min(maxValidityDuration, baseValidity * sizeFactor));
        
        // Calculate adaptive expansion
        var velocityExpansion = velocityMagnitude * adaptiveExpansionRate;
        var sizeExpansion = size * 0.05f; // 5% of size as additional expansion
        var totalExpansion = baseExpansionFactor + velocityExpansion + sizeExpansion;
        
        return TBVParameters.with(validityDuration, totalExpansion);
    }
    
    @Override
    public int getMinValidityDuration() {
        return minValidityDuration;
    }
    
    @Override
    public int getMaxValidityDuration() {
        return maxValidityDuration;
    }
    
    @Override
    public String getName() {
        return String.format("Adaptive[%d-%d frames]", minValidityDuration, maxValidityDuration);
    }
    
    // Getters for configuration inspection
    
    public float getVelocityThreshold() {
        return velocityThreshold;
    }
    
    public float getSizeInfluenceFactor() {
        return sizeInfluenceFactor;
    }
    
    public float getBaseExpansionFactor() {
        return baseExpansionFactor;
    }
    
    public float getAdaptiveExpansionRate() {
        return adaptiveExpansionRate;
    }
    
    @Override
    public String toString() {
        return String.format("AdaptiveTBVStrategy[duration=%d-%d, velocityThreshold=%.2f, " +
                           "sizeInfluence=%.2f, baseExpansion=%.3f, adaptiveRate=%.3f]",
                           minValidityDuration, maxValidityDuration, velocityThreshold,
                           sizeInfluenceFactor, baseExpansionFactor, adaptiveExpansionRate);
    }
}