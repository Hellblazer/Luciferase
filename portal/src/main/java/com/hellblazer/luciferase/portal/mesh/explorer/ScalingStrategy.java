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
package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.lucien.Constants;
import javafx.geometry.Point3D;
import javafx.scene.transform.Affine;

import javax.vecmath.Point3f;

/**
 * Handles coordinate scaling between spatial index coordinates (0 to 2^21)
 * and JavaFX 3D view coordinates. This provides a centralized solution to
 * the scaling challenges when visualizing hierarchical spatial indices.
 *
 * @author hal.hildebrand
 */
public class ScalingStrategy {
    
    // Maximum coordinate value in spatial index (2^21)
    private static final double MAX_COORDINATE = Constants.MAX_EXTENT;
    
    // Target size for visualization in JavaFX coordinates
    private static final double VIEW_SCALE = 1000.0;
    
    /**
     * Normalize spatial index coordinates to [0,1] range.
     * This is the foundation of our scaling approach.
     *
     * @param point The point in spatial index coordinates (0 to 2^21)
     * @return The normalized point (0.0 to 1.0 in each dimension)
     */
    public Point3f normalize(Point3f point) {
        return new Point3f(
            (float)(point.x / MAX_COORDINATE),
            (float)(point.y / MAX_COORDINATE),
            (float)(point.z / MAX_COORDINATE)
        );
    }
    
    /**
     * Normalize spatial index coordinates to [0,1] range.
     * Integer version for direct use with spatial index anchors.
     *
     * @param x The x coordinate in spatial index space
     * @param y The y coordinate in spatial index space
     * @param z The z coordinate in spatial index space
     * @return The normalized point (0.0 to 1.0 in each dimension)
     */
    public Point3f normalize(int x, int y, int z) {
        return new Point3f(
            (float)(x / MAX_COORDINATE),
            (float)(y / MAX_COORDINATE),
            (float)(z / MAX_COORDINATE)
        );
    }
    
    /**
     * Convert normalized coordinates to view coordinates.
     * Centers the coordinate system around origin.
     *
     * @param normalized The normalized point (0.0 to 1.0)
     * @return The view coordinates (-VIEW_SCALE/2 to VIEW_SCALE/2)
     */
    public Point3D toViewCoordinates(Point3f normalized) {
        return new Point3D(
            (normalized.x - 0.5) * VIEW_SCALE,
            (normalized.y - 0.5) * VIEW_SCALE,
            (normalized.z - 0.5) * VIEW_SCALE
        );
    }
    
    /**
     * Get the cell size at a given level in spatial index coordinates.
     *
     * @param level The level (0 to 20)
     * @return The cell edge length at that level
     */
    public double getCellSizeAtLevel(int level) {
        return Math.pow(2, Constants.getMaxRefinementLevel() - level);
    }
    
    /**
     * Get the scale factor for converting from spatial index to view coordinates
     * at a specific level.
     *
     * @param level The level (0 to 20)
     * @return The scale factor to apply
     */
    public double getScaleFactorForLevel(int level) {
        double cellSize = getCellSizeAtLevel(level);
        return VIEW_SCALE / cellSize;
    }
    
    /**
     * Create a transform for positioning and scaling an object at the given level.
     * This is a simple version that just handles uniform scaling.
     *
     * @param level The level of the object
     * @param normalizedPosition The normalized position (0.0 to 1.0)
     * @return An Affine transform ready to apply to JavaFX nodes
     */
    public Affine createTransform(int level, Point3f normalizedPosition) {
        Affine transform = new Affine();
        
        // Convert to view coordinates
        Point3D viewPos = toViewCoordinates(normalizedPosition);
        
        // Get scale for this level
        double scaleFactor = getScaleFactorForLevel(level);
        
        // Apply scale first, then translation
        transform.appendScale(scaleFactor, scaleFactor, scaleFactor);
        transform.appendTranslation(viewPos.getX(), viewPos.getY(), viewPos.getZ());
        
        return transform;
    }
    
    /**
     * Get the view scale constant for external use.
     *
     * @return The view scale (size of normalized cube in view coordinates)
     */
    public double getViewScale() {
        return VIEW_SCALE;
    }
    
    /**
     * Get the maximum coordinate value for external use.
     *
     * @return The maximum spatial index coordinate (2^21)
     */
    public double getMaxCoordinate() {
        return MAX_COORDINATE;
    }
}