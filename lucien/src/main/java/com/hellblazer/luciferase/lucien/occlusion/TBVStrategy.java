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
 * Strategy interface for determining Temporal Bounding Volume (TBV) parameters.
 * 
 * Different strategies can be implemented to adapt TBV creation based on:
 * - Entity velocity and acceleration
 * - Historical movement patterns
 * - Entity type and behavior
 * - Performance requirements
 * 
 * The strategy determines both the validity duration (how long the TBV remains valid)
 * and the expansion factor (additional padding for conservative estimation).
 *
 * @author hal.hildebrand
 */
public interface TBVStrategy {
    
    /**
     * Parameters calculated by a TBV strategy.
     * 
     * @param validityDuration Number of frames this TBV should remain valid
     * @param expansionFactor Additional expansion in world units for conservative bounds
     */
    record TBVParameters(int validityDuration, float expansionFactor) {
        
        public TBVParameters {
            if (validityDuration <= 0) {
                throw new IllegalArgumentException("Validity duration must be positive");
            }
            if (expansionFactor < 0) {
                throw new IllegalArgumentException("Expansion factor cannot be negative");
            }
        }
        
        /**
         * Create parameters with default expansion factor.
         */
        public static TBVParameters withDuration(int duration) {
            return new TBVParameters(duration, 0.0f);
        }
        
        /**
         * Create parameters with specific duration and expansion.
         */
        public static TBVParameters with(int duration, float expansion) {
            return new TBVParameters(duration, expansion);
        }
    }
    
    /**
     * Calculate TBV parameters based on entity properties.
     * 
     * @param bounds The current bounds of the entity
     * @param velocity The current velocity of the entity
     * @return The calculated TBV parameters
     */
    TBVParameters calculateTBVParameters(EntityBounds bounds, Vector3f velocity);
    
    /**
     * Get the minimum validity duration this strategy will produce.
     * Used for configuration validation and performance estimation.
     * 
     * @return The minimum validity duration in frames
     */
    int getMinValidityDuration();
    
    /**
     * Get the maximum validity duration this strategy will produce.
     * Used for configuration validation and performance estimation.
     * 
     * @return The maximum validity duration in frames
     */
    int getMaxValidityDuration();
    
    /**
     * Get a descriptive name for this strategy.
     * 
     * @return The strategy name
     */
    String getName();
}