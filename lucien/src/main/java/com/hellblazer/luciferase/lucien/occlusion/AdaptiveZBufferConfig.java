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

/**
 * Configuration for adaptive Z-buffer sizing based on scene characteristics
 *
 * @author hal.hildebrand
 */
public class AdaptiveZBufferConfig {
    
    /**
     * Z-buffer dimension configuration
     */
    public static class DimensionConfig {
        public final int width;
        public final int height;
        public final int levels;
        public final long memoryFootprint;
        
        public DimensionConfig(int width, int height, int levels) {
            this.width = width;
            this.height = height;
            this.levels = levels;
            this.memoryFootprint = calculateMemoryFootprint(width, height, levels);
        }
        
        private static long calculateMemoryFootprint(int width, int height, int levels) {
            long total = 0;
            int w = width;
            int h = height;
            for (int level = 0; level < levels; level++) {
                total += (long) w * h * 4; // 4 bytes per float
                w = Math.max(1, w / 2);
                h = Math.max(1, h / 2);
            }
            return total;
        }
        
        @Override
        public String toString() {
            return String.format("DimensionConfig{%dx%d, %d levels, %.1f MB}", 
                    width, height, levels, memoryFootprint / (1024.0 * 1024.0));
        }
    }
    
    // Predefined configurations for different scales
    public static final DimensionConfig MINIMAL = new DimensionConfig(128, 128, 3);
    public static final DimensionConfig SMALL = new DimensionConfig(256, 256, 4);
    public static final DimensionConfig MEDIUM = new DimensionConfig(512, 512, 5);
    public static final DimensionConfig LARGE = new DimensionConfig(1024, 1024, 6);
    public static final DimensionConfig MAXIMUM = new DimensionConfig(2048, 2048, 7);
    
    // Thresholds for automatic sizing
    private static final int MINIMAL_ENTITY_THRESHOLD = 100;
    private static final int SMALL_ENTITY_THRESHOLD = 1000;
    private static final int MEDIUM_ENTITY_THRESHOLD = 10000;
    private static final int LARGE_ENTITY_THRESHOLD = 50000;
    
    // Effectiveness thresholds for dynamic scaling
    private static final double LOW_EFFECTIVENESS_THRESHOLD = 0.1;
    private static final double HIGH_EFFECTIVENESS_THRESHOLD = 0.4;
    
    /**
     * Calculate optimal Z-buffer dimensions based on scene characteristics
     * 
     * @param entityCount Total number of entities in the scene
     * @param sceneBounds Size of the scene bounding volume
     * @param occluderDensity Ratio of occluders to total entities
     * @return Recommended dimension configuration
     */
    public static DimensionConfig calculateOptimalDimensions(int entityCount, float sceneBounds, double occluderDensity) {
        // Base sizing on entity count
        DimensionConfig baseConfig;
        if (entityCount < MINIMAL_ENTITY_THRESHOLD) {
            baseConfig = MINIMAL;
        } else if (entityCount < SMALL_ENTITY_THRESHOLD) {
            baseConfig = SMALL;
        } else if (entityCount < MEDIUM_ENTITY_THRESHOLD) {
            baseConfig = MEDIUM;
        } else if (entityCount < LARGE_ENTITY_THRESHOLD) {
            baseConfig = LARGE;
        } else {
            baseConfig = MAXIMUM;
        }
        
        // Adjust based on occluder density
        if (occluderDensity < 0.01) {
            // Very few occluders - downgrade
            baseConfig = downgrade(baseConfig);
        } else if (occluderDensity > 0.2) {
            // Many occluders - upgrade for better precision
            baseConfig = upgrade(baseConfig);
        }
        
        // Adjust based on scene bounds (larger scenes need more precision)
        if (sceneBounds > 10000.0f) {
            baseConfig = upgrade(baseConfig);
        } else if (sceneBounds < 100.0f) {
            baseConfig = downgrade(baseConfig);
        }
        
        return baseConfig;
    }
    
    /**
     * Determine if Z-buffer should be resized based on effectiveness
     * 
     * @param currentConfig Current configuration
     * @param effectiveness Current occlusion effectiveness (0.0 - 1.0)
     * @param memoryPressure Current memory pressure (0.0 - 1.0)
     * @return New configuration or null if no change needed
     */
    public static DimensionConfig adaptForEffectiveness(DimensionConfig currentConfig, 
                                                       double effectiveness, 
                                                       double memoryPressure) {
        // Downscale if low effectiveness and high memory pressure
        if (effectiveness < LOW_EFFECTIVENESS_THRESHOLD && memoryPressure > 0.7) {
            return downgrade(currentConfig);
        }
        
        // Upscale if high effectiveness and low memory pressure
        if (effectiveness > HIGH_EFFECTIVENESS_THRESHOLD && memoryPressure < 0.3) {
            return upgrade(currentConfig);
        }
        
        return null; // No change needed
    }
    
    /**
     * Get the next smaller configuration
     */
    private static DimensionConfig downgrade(DimensionConfig config) {
        if (config == MAXIMUM) return LARGE;
        if (config == LARGE) return MEDIUM;
        if (config == MEDIUM) return SMALL;
        if (config == SMALL) return MINIMAL;
        return MINIMAL; // Already at minimum
    }
    
    /**
     * Get the next larger configuration
     */
    private static DimensionConfig upgrade(DimensionConfig config) {
        if (config == MINIMAL) return SMALL;
        if (config == SMALL) return MEDIUM;
        if (config == MEDIUM) return LARGE;
        if (config == LARGE) return MAXIMUM;
        return MAXIMUM; // Already at maximum
    }
    
    /**
     * Check if a configuration change is worthwhile
     * 
     * @param current Current configuration
     * @param proposed Proposed new configuration
     * @return true if change provides significant benefit
     */
    public static boolean isChangeWorthwhile(DimensionConfig current, DimensionConfig proposed) {
        if (current == null || proposed == null || current == proposed) {
            return false;
        }
        
        // Memory difference threshold (don't change for <25% difference)
        double memoryRatio = (double) proposed.memoryFootprint / current.memoryFootprint;
        return memoryRatio < 0.75 || memoryRatio > 1.25;
    }
}