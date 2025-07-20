/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
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
package com.hellblazer.sentry;

/**
 * Configuration for Sentry module components.
 * Replaces system property-based configuration with explicit parameters.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class SentryConfiguration {
    
    // MutableGrid configuration
    private final boolean enableValidation;
    private final boolean useLandmarkIndex;
    private final boolean useOptimizedFlip;
    
    /**
     * Private constructor - use builder.
     */
    private SentryConfiguration(Builder builder) {
        this.enableValidation = builder.enableValidation;
        this.useLandmarkIndex = builder.useLandmarkIndex;
        this.useOptimizedFlip = builder.useOptimizedFlip;
    }
    
    /**
     * Get the default configuration.
     * - Validation: disabled
     * - Landmark index: enabled
     * - Optimized flip: enabled
     */
    public static SentryConfiguration getDefault() {
        return new Builder().build();
    }
    
    /**
     * Get a configuration optimized for performance.
     * - Validation: disabled
     * - All optimizations enabled
     */
    public static SentryConfiguration getPerformanceOptimized() {
        return new Builder()
            .withValidation(false)
            .withLandmarkIndex(true)
            .withOptimizedFlip(true)
            .build();
    }
    
    /**
     * Get a configuration optimized for accuracy.
     * - Validation: enabled
     * - All optimizations enabled
     */
    public static SentryConfiguration getAccuracyOptimized() {
        return new Builder()
            .withValidation(true)
            .withLandmarkIndex(true)
            .withOptimizedFlip(true)
            .build();
    }
    
    // Getters
    public boolean isValidationEnabled() {
        return enableValidation;
    }
    
    public boolean isLandmarkIndexEnabled() {
        return useLandmarkIndex;
    }
    
    public boolean isOptimizedFlipEnabled() {
        return useOptimizedFlip;
    }
    
    /**
     * Builder for SentryConfiguration.
     */
    public static class Builder {
        private boolean enableValidation = false;
        private boolean useLandmarkIndex = true;
        private boolean useOptimizedFlip = true;
        
        public Builder withValidation(boolean enable) {
            this.enableValidation = enable;
            return this;
        }
        
        public Builder withLandmarkIndex(boolean enable) {
            this.useLandmarkIndex = enable;
            return this;
        }
        
        public Builder withOptimizedFlip(boolean enable) {
            this.useOptimizedFlip = enable;
            return this;
        }
        
        public SentryConfiguration build() {
            return new SentryConfiguration(this);
        }
    }
}