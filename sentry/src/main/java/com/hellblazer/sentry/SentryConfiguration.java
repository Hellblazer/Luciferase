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
    
    // Geometric predicates configuration
    private final GeometricPredicatesFactory.PredicateMode predicateMode;
    private final Double adaptiveEpsilon;
    
    // MutableGrid configuration
    private final boolean enableValidation;
    private final boolean useLandmarkIndex;
    private final boolean useOptimizedFlip;
    
    // SIMD configuration
    private final boolean enableSIMD;
    
    /**
     * Private constructor - use builder.
     */
    private SentryConfiguration(Builder builder) {
        this.predicateMode = builder.predicateMode;
        this.adaptiveEpsilon = builder.adaptiveEpsilon;
        this.enableValidation = builder.enableValidation;
        this.useLandmarkIndex = builder.useLandmarkIndex;
        this.useOptimizedFlip = builder.useOptimizedFlip;
        this.enableSIMD = builder.enableSIMD;
    }
    
    /**
     * Get the default configuration.
     * - Predicate mode: SCALAR
     * - Validation: disabled
     * - Landmark index: enabled
     * - Optimized flip: enabled
     * - SIMD: auto-detect
     */
    public static SentryConfiguration getDefault() {
        return new Builder().build();
    }
    
    /**
     * Get a configuration optimized for performance.
     * - Predicate mode: SCALAR (or SIMD if available)
     * - Validation: disabled
     * - All optimizations enabled
     */
    public static SentryConfiguration getPerformanceOptimized() {
        return new Builder()
            .withPredicateMode(SIMDSupport.isAvailable() ? 
                GeometricPredicatesFactory.PredicateMode.SIMD : 
                GeometricPredicatesFactory.PredicateMode.SCALAR)
            .withValidation(false)
            .withLandmarkIndex(true)
            .withOptimizedFlip(true)
            .withSIMD(true)
            .build();
    }
    
    /**
     * Get a configuration optimized for accuracy.
     * - Predicate mode: EXACT
     * - Validation: enabled
     * - All optimizations enabled
     */
    public static SentryConfiguration getAccuracyOptimized() {
        return new Builder()
            .withPredicateMode(GeometricPredicatesFactory.PredicateMode.EXACT)
            .withValidation(true)
            .withLandmarkIndex(true)
            .withOptimizedFlip(true)
            .build();
    }
    
    // Getters
    public GeometricPredicatesFactory.PredicateMode getPredicateMode() {
        return predicateMode;
    }
    
    public Double getAdaptiveEpsilon() {
        return adaptiveEpsilon;
    }
    
    public boolean isValidationEnabled() {
        return enableValidation;
    }
    
    public boolean isLandmarkIndexEnabled() {
        return useLandmarkIndex;
    }
    
    public boolean isOptimizedFlipEnabled() {
        return useOptimizedFlip;
    }
    
    public boolean isSIMDEnabled() {
        return enableSIMD;
    }
    
    /**
     * Builder for SentryConfiguration.
     */
    public static class Builder {
        private GeometricPredicatesFactory.PredicateMode predicateMode = 
            GeometricPredicatesFactory.PredicateMode.SCALAR;
        private Double adaptiveEpsilon = null;
        private boolean enableValidation = false;
        private boolean useLandmarkIndex = true;
        private boolean useOptimizedFlip = true;
        private boolean enableSIMD = SIMDSupport.isAvailable();
        
        public Builder withPredicateMode(GeometricPredicatesFactory.PredicateMode mode) {
            this.predicateMode = mode;
            return this;
        }
        
        public Builder withAdaptiveEpsilon(double epsilon) {
            this.adaptiveEpsilon = epsilon;
            return this;
        }
        
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
        
        public Builder withSIMD(boolean enable) {
            this.enableSIMD = enable;
            return this;
        }
        
        public SentryConfiguration build() {
            return new SentryConfiguration(this);
        }
    }
}