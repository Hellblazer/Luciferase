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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Spatial;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;

/**
 * Utility class for common tetree validation operations.
 * 
 * This class consolidates validation methods that were duplicated across multiple tetree classes.
 * The tetrahedral space-filling curve requires all coordinates to be positive.
 *
 * @author hal.hildebrand
 */
public class TetreeValidationUtils {

    /**
     * Validate that a 3D float point has positive coordinates.
     *
     * @param point the point to validate
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static void validatePositiveCoordinates(Tuple3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Tetree requires positive coordinates. Got: " + point);
        }
    }

    /**
     * Validate that a 3D integer point has positive coordinates.
     *
     * @param point the point to validate
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static void validatePositiveCoordinates(Tuple3i point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(
                "Tetree requires positive coordinates. Got: (" + point.x + ", " + point.y + ", " + point.z + ")");
        }
    }

    /**
     * Validate that individual coordinates are positive.
     *
     * @param x x-coordinate
     * @param y y-coordinate
     * @param z z-coordinate
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static void validatePositiveCoordinates(float x, float y, float z) {
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException(
                "Tetree requires positive coordinates. Got: (" + x + ", " + y + ", " + z + ")");
        }
    }

    /**
     * Validate that a spatial volume has positive origin coordinates.
     *
     * @param volume the spatial volume to validate
     * @throws IllegalArgumentException if origin coordinates are negative
     */
    public static void validatePositiveCoordinates(Spatial volume) {
        // Check origin coordinates based on volume type
        switch (volume) {
            case Spatial.Cube cube -> {
                if (cube.originX() < 0 || cube.originY() < 0 || cube.originZ() < 0) {
                    throw new IllegalArgumentException(
                        "Tetree requires positive coordinates. Cube origin: (" + cube.originX() + ", " + 
                        cube.originY() + ", " + cube.originZ() + ")");
                }
            }
            case Spatial.Sphere sphere -> {
                if (sphere.centerX() < 0 || sphere.centerY() < 0 || sphere.centerZ() < 0) {
                    throw new IllegalArgumentException(
                        "Tetree requires positive coordinates. Sphere center: (" + sphere.centerX() + ", " + 
                        sphere.centerY() + ", " + sphere.centerZ() + ")");
                }
            }
            case Spatial.aabb bounds -> {
                if (bounds.originX() < 0 || bounds.originY() < 0 || bounds.originZ() < 0) {
                    throw new IllegalArgumentException(
                        "Tetree requires positive coordinates. AABB origin: (" + bounds.originX() + ", " + 
                        bounds.originY() + ", " + bounds.originZ() + ")");
                }
            }
            default -> {
                // For other volume types, we can't validate without extracting bounds
                // This is handled by the specific implementation
            }
        }
    }

    // Private constructor to prevent instantiation
    private TetreeValidationUtils() {
        throw new AssertionError("Utility class should not be instantiated");
    }
}