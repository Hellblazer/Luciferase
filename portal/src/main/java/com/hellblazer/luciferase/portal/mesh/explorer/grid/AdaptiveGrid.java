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

import com.hellblazer.luciferase.portal.mesh.explorer.ScalingStrategy;
import javafx.geometry.BoundingBox;
import javafx.scene.Group;
import javafx.scene.paint.Material;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.HashMap;
import java.util.Map;

/**
 * Adaptive grid that provides level-aware rendering.
 * This implementation creates grid lines directly without extending Grid class.
 *
 * @author hal.hildebrand
 */
public class AdaptiveGrid {
    
    private final ScalingStrategy scalingStrategy;
    private final GridLODManager lodManager;
    private final Map<Integer, Group> gridCache;
    private int currentLevel = 10;
    
    public AdaptiveGrid(ScalingStrategy scalingStrategy) {
        this.scalingStrategy = scalingStrategy;
        this.lodManager = new GridLODManager();
        this.gridCache = new HashMap<>();
    }
    
    /**
     * Construct a grid optimized for the specified viewing level.
     */
    public Group constructForLevel(int level, Material xMat, Material yMat, Material zMat) {
        return constructForLevel(level, xMat, yMat, zMat, null);
    }
    
    /**
     * Construct a grid optimized for the specified viewing level with frustum culling.
     */
    public Group constructForLevel(int level, Material xMat, Material yMat, Material zMat, BoundingBox viewFrustum) {
        // Check cache first
        if (gridCache.containsKey(level) && viewFrustum == null) {
            return gridCache.get(level);
        }
        
        // Get LOD configuration for this level
        GridLODManager.GridConfiguration config = lodManager.getConfigForLevel(level, viewFrustum);
        
        // Create a new group for this level's grid
        Group gridGroup = new Group();
        
        // Build the grid according to the configuration
        constructLevelGrid(gridGroup, level, config, xMat, yMat, zMat);
        
        // Cache if no frustum culling
        if (viewFrustum == null) {
            gridCache.put(level, gridGroup);
        }
        
        this.currentLevel = level;
        return gridGroup;
    }
    
    /**
     * Update the current view frustum for adaptive rendering.
     */
    public void updateViewFrustum(BoundingBox frustum) {
        // This could trigger re-rendering if needed
    }
    
    /**
     * Clear the grid cache to force regeneration.
     */
    public void clearCache() {
        gridCache.clear();
    }
    
    /**
     * Get the current level being displayed.
     */
    public int getCurrentLevel() {
        return currentLevel;
    }
    
    /**
     * Get the LOD manager for external configuration.
     */
    public GridLODManager getLODManager() {
        return lodManager;
    }
    
    private void constructLevelGrid(Group grid, int level, GridLODManager.GridConfiguration config, 
                                  Material xMat, Material yMat, Material zMat) {
        
        // Calculate cell size for this level
        int cellSize = 1 << (21 - level);
        
        // Create grid lines based on configuration
        // Fixed grid size for visibility
        double interval = 50.0; // Fixed interval between grid lines
        int gridCount = Math.min(config.density, 10); // Limit grid lines to 10x10
        double lineRadius = 2.0; // Fixed line thickness
        
        // Apply fading based on distance
        double opacity = config.alpha;
        
        // X-axis lines (red)
        Material fadedXMat = createFadedMaterial(xMat, opacity);
        double gridExtent = gridCount * interval / 2;
        for (int i = -gridCount/2; i <= gridCount/2; i++) {
            double offset = i * interval;
            
            // Line parallel to X at this Y offset in XZ plane
            Box lineX = new Box(gridExtent * 2, lineRadius, lineRadius);
            lineX.setMaterial(fadedXMat);
            lineX.setTranslateY(offset);
            grid.getChildren().add(lineX);
            
            // Line parallel to X at this Z offset in XY plane
            Box lineX2 = new Box(gridExtent * 2, lineRadius, lineRadius);
            lineX2.setMaterial(fadedXMat);
            lineX2.setTranslateZ(offset);
            grid.getChildren().add(lineX2);
        }
        
        // Y-axis lines (green)
        Material fadedYMat = createFadedMaterial(yMat, opacity);
        for (int i = -gridCount/2; i <= gridCount/2; i++) {
            double offset = i * interval;
            
            // Line parallel to Y at this X offset in YZ plane
            Box lineY = new Box(lineRadius, gridExtent * 2, lineRadius);
            lineY.setMaterial(fadedYMat);
            lineY.setTranslateX(offset);
            grid.getChildren().add(lineY);
            
            // Line parallel to Y at this Z offset in XY plane
            Box lineY2 = new Box(lineRadius, gridExtent * 2, lineRadius);
            lineY2.setMaterial(fadedYMat);
            lineY2.setTranslateZ(offset);
            grid.getChildren().add(lineY2);
        }
        
        // Z-axis lines (blue)
        Material fadedZMat = createFadedMaterial(zMat, opacity);
        for (int i = -gridCount/2; i <= gridCount/2; i++) {
            double offset = i * interval;
            
            // Line parallel to Z at this X offset in XZ plane
            Box lineZ = new Box(lineRadius, lineRadius, gridExtent * 2);
            lineZ.setMaterial(fadedZMat);
            lineZ.setTranslateX(offset);
            grid.getChildren().add(lineZ);
            
            // Line parallel to Z at this Y offset in YZ plane
            Box lineZ2 = new Box(lineRadius, lineRadius, gridExtent * 2);
            lineZ2.setMaterial(fadedZMat);
            lineZ2.setTranslateY(offset);
            grid.getChildren().add(lineZ2);
        }
    }
    
    private Cylinder createLine(double length, double radius) {
        return new Cylinder(radius, length * 200);  // Reduce line length for better visibility
    }
    
    private Material createFadedMaterial(Material baseMaterial, double opacity) {
        // For now, return the base material
        // In a full implementation, we would clone and adjust opacity
        return baseMaterial;
    }
}
