/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.mesh.explorer.grid;

import javafx.geometry.BoundingBox;

/**
 * Manages Level-of-Detail (LOD) for grid rendering based on viewing conditions.
 * Ensures grid complexity stays within performance bounds while maximizing visual quality.
 *
 * @author hal.hildebrand
 */
public class GridLODManager {
    
    // Maximum number of visible grid lines to maintain performance
    private static final int MAX_VISIBLE_LINES = 10000;
    
    // Base densities for each level range
    private static final int COARSE_BASE_DENSITY = 8;
    private static final int MEDIUM_BASE_DENSITY = 16;
    private static final int FINE_BASE_DENSITY = 32;
    private static final int ULTRA_FINE_BASE_DENSITY = 64;
    
    // Line thickness for each level range
    private static final double COARSE_LINE_THICKNESS = 0.02;
    private static final double MEDIUM_LINE_THICKNESS = 0.01;
    private static final double FINE_LINE_THICKNESS = 0.005;
    private static final double ULTRA_FINE_LINE_THICKNESS = 0.002;
    
    // Alpha values for each level range
    private static final double COARSE_ALPHA = 1.0;
    private static final double MEDIUM_ALPHA = 0.8;
    private static final double FINE_ALPHA = 0.6;
    private static final double ULTRA_FINE_ALPHA = 0.4;
    
    /**
     * Configuration for grid rendering at a specific level.
     */
    public static class GridConfiguration {
        public int density;
        public double lineThickness;
        public double alpha;
        public boolean showMajorLines;
        public boolean showMinorLines;
        public int majorLineInterval;
        
        public GridConfiguration() {
            this.showMajorLines = true;
            this.showMinorLines = true;
            this.majorLineInterval = 4;
        }
    }
    
    /**
     * Get optimal grid configuration for a given level and view frustum.
     */
    public GridConfiguration getConfigForLevel(int level, BoundingBox viewFrustum) {
        GridConfiguration config = new GridConfiguration();
        
        // Get base configuration for level
        setBaseConfiguration(config, level);
        
        // Calculate potential line count
        int potentialLines = calculatePotentialLines(config, viewFrustum);
        
        // Adapt configuration if needed to stay within performance bounds
        if (potentialLines > MAX_VISIBLE_LINES) {
            adaptConfiguration(config, potentialLines);
        }
        
        return config;
    }
    
    /**
     * Set base configuration based on level.
     */
    private void setBaseConfiguration(GridConfiguration config, int level) {
        if (level <= 5) {
            // Coarse levels
            config.density = COARSE_BASE_DENSITY;
            config.lineThickness = COARSE_LINE_THICKNESS;
            config.alpha = COARSE_ALPHA;
            config.showMinorLines = false;
            config.majorLineInterval = 1;
        } else if (level <= 10) {
            // Medium levels
            config.density = MEDIUM_BASE_DENSITY;
            config.lineThickness = MEDIUM_LINE_THICKNESS;
            config.alpha = MEDIUM_ALPHA;
            config.showMinorLines = true;
            config.majorLineInterval = 4;
        } else if (level <= 15) {
            // Fine levels
            config.density = FINE_BASE_DENSITY;
            config.lineThickness = FINE_LINE_THICKNESS;
            config.alpha = FINE_ALPHA;
            config.showMinorLines = true;
            config.majorLineInterval = 8;
        } else {
            // Ultra-fine levels
            config.density = ULTRA_FINE_BASE_DENSITY;
            config.lineThickness = ULTRA_FINE_LINE_THICKNESS;
            config.alpha = ULTRA_FINE_ALPHA;
            config.showMinorLines = true;
            config.majorLineInterval = 16;
        }
    }
    
    /**
     * Calculate potential number of lines that would be rendered.
     */
    private int calculatePotentialLines(GridConfiguration config, BoundingBox frustum) {
        // Estimate based on grid density and view frustum
        // This is a simplified calculation - real implementation would
        // consider actual frustum intersection
        
        int linesPerAxis = config.density + 1;
        int majorLines = linesPerAxis / config.majorLineInterval;
        int minorLines = config.showMinorLines ? (linesPerAxis - majorLines) : 0;
        int totalLinesPerAxis = majorLines + minorLines;
        
        // Each axis has lines in two perpendicular planes
        // X-parallel lines exist in YZ planes, etc.
        int totalLines = totalLinesPerAxis * totalLinesPerAxis * 3;
        
        // Apply frustum culling factor (rough estimate)
        // Assume we see about 50% of the total grid on average
        double frustumFactor = 0.5;
        
        return (int)(totalLines * frustumFactor);
    }
    
    /**
     * Adapt configuration to reduce complexity if needed.
     */
    private void adaptConfiguration(GridConfiguration config, int potentialLines) {
        // Calculate reduction factor
        double reductionFactor = (double)MAX_VISIBLE_LINES / potentialLines;
        
        // Apply square root to get per-axis reduction
        double axisReduction = Math.sqrt(reductionFactor);
        
        // Reduce density
        config.density = Math.max(4, (int)(config.density * axisReduction));
        
        // Adjust other parameters
        if (reductionFactor < 0.5) {
            // Severe reduction needed - disable minor lines
            config.showMinorLines = false;
        }
        
        if (reductionFactor < 0.25) {
            // Extreme reduction - increase major line interval
            config.majorLineInterval *= 2;
        }
        
        // Slightly reduce line thickness for better visibility at lower density
        config.lineThickness *= (0.8 + 0.2 * reductionFactor);
    }
    
    /**
     * Calculate appropriate grid extent based on level and camera position.
     */
    public double getGridExtentForLevel(int level, double cameraDistance) {
        // Base extent increases with coarser levels
        double baseExtent = Math.pow(2, Math.max(0, 10 - level));
        
        // Adjust based on camera distance
        double distanceFactor = Math.max(1.0, cameraDistance / 1000.0);
        
        return baseExtent * distanceFactor;
    }
    
    /**
     * Determine if grid should fade based on distance.
     */
    public double getDistanceFadeFactor(double distanceFromCamera, int level) {
        // Different fade distances for different levels
        double fadeStartDistance = getFadeStartDistance(level);
        double fadeEndDistance = fadeStartDistance * 2.0;
        
        if (distanceFromCamera <= fadeStartDistance) {
            return 1.0;
        } else if (distanceFromCamera >= fadeEndDistance) {
            return 0.0;
        } else {
            // Linear fade between start and end
            double fadeRange = fadeEndDistance - fadeStartDistance;
            double fadeProgress = (distanceFromCamera - fadeStartDistance) / fadeRange;
            return 1.0 - fadeProgress;
        }
    }
    
    /**
     * Get fade start distance based on level.
     */
    private double getFadeStartDistance(int level) {
        if (level <= 5) {
            return 5000.0;
        } else if (level <= 10) {
            return 2000.0;
        } else if (level <= 15) {
            return 1000.0;
        } else {
            return 500.0;
        }
    }
}