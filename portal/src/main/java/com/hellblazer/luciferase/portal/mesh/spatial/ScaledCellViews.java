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
package com.hellblazer.luciferase.portal.mesh.spatial;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.portal.mesh.explorer.ScalingStrategy;
import javafx.scene.paint.Material;
import javafx.scene.transform.Affine;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.HashMap;
import java.util.Map;

/**
 * An enhanced version of CellViews that uses ScalingStrategy to properly
 * handle the massive coordinate range of spatial indices (0 to 2^21).
 * This solves the visualization scaling issues by normalizing coordinates
 * before applying transforms.
 *
 * @author hal.hildebrand
 */
public class ScaledCellViews extends CellViews {
    
    private final ScalingStrategy scalingStrategy;
    private final Map<TetreeKey<?>, Affine> scaledTransformCache = new HashMap<>();
    
    /**
     * Initialize with default edge thickness, material, and scaling strategy.
     */
    public ScaledCellViews() {
        super();
        this.scalingStrategy = new ScalingStrategy();
    }
    
    /**
     * Initialize with specified edge thickness, material, and scaling strategy.
     *
     * @param edgeThickness The thickness of the wireframe edges
     * @param edgeMaterial The material to use for the edges
     */
    public ScaledCellViews(double edgeThickness, Material edgeMaterial) {
        super(edgeThickness, edgeMaterial);
        this.scalingStrategy = new ScalingStrategy();
    }
    
    /**
     * Initialize with custom scaling strategy for specialized use cases.
     *
     * @param edgeThickness The thickness of the wireframe edges
     * @param edgeMaterial The material to use for the edges
     * @param scalingStrategy Custom scaling strategy
     */
    public ScaledCellViews(double edgeThickness, Material edgeMaterial, ScalingStrategy scalingStrategy) {
        super(edgeThickness, edgeMaterial);
        this.scalingStrategy = scalingStrategy;
    }
    
    /**
     * Clear both the original and scaled transform caches.
     */
    @Override
    public void clearTransformCache() {
        super.clearTransformCache();
        scaledTransformCache.clear();
    }
    
    /**
     * Get the scaling strategy for external configuration if needed.
     *
     * @return The scaling strategy instance
     */
    public ScalingStrategy getScalingStrategy() {
        return scalingStrategy;
    }
    
    /**
     * Calculate the transform needed to position and scale a tetrahedron
     * using the scaling strategy to handle the coordinate range properly.
     *
     * @param tet The tetrahedron
     * @return The affine transform with proper scaling
     */
    @Override
    protected Affine calculateTransform(Tet tet) {
        // Check scaled cache first
        var cacheKey = tet.tmIndex();
        Affine cached = scaledTransformCache.get(cacheKey);
        if (cached != null) {
            return new Affine(cached); // Return copy
        }
        
        // Get the anchor position and normalize it
        Point3i anchor = tet.anchor();
        Point3f normalized = scalingStrategy.normalize(anchor.x, anchor.y, anchor.z);
        
        // Get the level-based transform from scaling strategy
        int level = tet.l();
        Affine transform = scalingStrategy.createTransform(level, normalized);
        
        // Cache the scaled transform
        scaledTransformCache.put(cacheKey, new Affine(transform));
        
        return transform;
    }
}