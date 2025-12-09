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

package com.hellblazer.luciferase.portal.esvo;

import com.hellblazer.luciferase.geometry.Point3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural generator for voxel-based geometric shapes.
 * Generates discrete voxel representations of various 3D shapes
 * for use with ESVO octree visualization.
 * 
 * @author hal.hildebrand
 */
public class ProceduralVoxelGenerator {
    
    /**
     * Geometric shape types that can be generated.
     */
    public enum Shape {
        /** Solid sphere */
        SPHERE,
        /** Solid cube */
        CUBE,
        /** Hollow torus (donut) */
        TORUS,
        /** Menger sponge fractal */
        MENGER_SPONGE
    }
    
    /**
     * Generate voxels for the specified shape at given resolution.
     * 
     * @param shape Shape type to generate
     * @param resolution Grid resolution (voxels per dimension)
     * @return List of voxel coordinates
     */
    public List<Point3i> generate(Shape shape, int resolution) {
        return switch (shape) {
            case SPHERE -> generateSphere(resolution);
            case CUBE -> generateCube(resolution);
            case TORUS -> generateTorus(resolution);
            case MENGER_SPONGE -> generateMengerSponge(resolution);
        };
    }
    
    /**
     * Generate a solid sphere centered in the voxel grid.
     * 
     * @param resolution Grid resolution
     * @return Voxel coordinates forming a sphere
     */
    public List<Point3i> generateSphere(int resolution) {
        var voxels = new ArrayList<Point3i>();
        var center = resolution / 2.0f;
        var radius = resolution / 2.0f * 0.8f; // 80% of half-size
        var radiusSquared = radius * radius;
        
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    var dx = x - center;
                    var dy = y - center;
                    var dz = z - center;
                    var distSquared = dx * dx + dy * dy + dz * dz;
                    
                    if (distSquared <= radiusSquared) {
                        voxels.add(new Point3i(x, y, z));
                    }
                }
            }
        }
        
        return voxels;
    }
    
    /**
     * Generate a solid cube filling the voxel grid.
     * 
     * @param resolution Grid resolution
     * @return Voxel coordinates forming a cube
     */
    public List<Point3i> generateCube(int resolution) {
        var voxels = new ArrayList<Point3i>();
        var margin = (int) (resolution * 0.1f); // 10% margin
        
        for (int x = margin; x < resolution - margin; x++) {
            for (int y = margin; y < resolution - margin; y++) {
                for (int z = margin; z < resolution - margin; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        
        return voxels;
    }
    
    /**
     * Generate a hollow torus (donut shape).
     * 
     * @param resolution Grid resolution
     * @return Voxel coordinates forming a torus
     */
    public List<Point3i> generateTorus(int resolution) {
        var voxels = new ArrayList<Point3i>();
        var center = resolution / 2.0f;
        var majorRadius = resolution / 2.0f * 0.6f; // Main ring radius
        var minorRadius = resolution / 2.0f * 0.25f; // Tube radius
        var minorRadiusSquared = minorRadius * minorRadius;
        
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    var dx = x - center;
                    var dy = y - center;
                    var dz = z - center;
                    
                    // Distance from center point to XZ plane circle
                    var distToCenter = Math.sqrt(dx * dx + dz * dz);
                    var distToMajor = distToCenter - majorRadius;
                    
                    // Distance from major radius circle to point
                    var torusDistSquared = distToMajor * distToMajor + dy * dy;
                    
                    if (torusDistSquared <= minorRadiusSquared) {
                        voxels.add(new Point3i(x, y, z));
                    }
                }
            }
        }
        
        return voxels;
    }
    
    /**
     * Generate a Menger sponge fractal.
     * 
     * @param resolution Grid resolution (should be power of 3 for best results)
     * @return Voxel coordinates forming a Menger sponge
     */
    public List<Point3i> generateMengerSponge(int resolution) {
        var voxels = new ArrayList<Point3i>();
        var margin = (int) (resolution * 0.1f);
        var size = resolution - 2 * margin;
        
        for (int x = margin; x < resolution - margin; x++) {
            for (int y = margin; y < resolution - margin; y++) {
                for (int z = margin; z < resolution - margin; z++) {
                    // Normalize coordinates to [0, size)
                    var nx = x - margin;
                    var ny = y - margin;
                    var nz = z - margin;
                    
                    if (isMengerVoxel(nx, ny, nz, size)) {
                        voxels.add(new Point3i(x, y, z));
                    }
                }
            }
        }
        
        return voxels;
    }
    
    /**
     * Check if a voxel should be part of the Menger sponge.
     * Uses recursive subdivision to determine if the voxel is in a removed region.
     * 
     * @param x X coordinate (normalized)
     * @param y Y coordinate (normalized)
     * @param z Z coordinate (normalized)
     * @param size Current subdivision size
     * @return True if voxel is part of the sponge
     */
    private boolean isMengerVoxel(int x, int y, int z, int size) {
        // Base case: at smallest resolution, include the voxel
        if (size <= 1) {
            return true;
        }
        
        // Divide space into 3x3x3 grid
        var third = size / 3;
        if (third == 0) {
            return true; // Can't subdivide further
        }
        
        var cx = x / third;
        var cy = y / third;
        var cz = z / third;
        
        // Check if in removed center region
        // Remove if exactly two coordinates are in center (1)
        var centerCount = 0;
        if (cx == 1) centerCount++;
        if (cy == 1) centerCount++;
        if (cz == 1) centerCount++;
        
        if (centerCount >= 2) {
            return false; // This region is removed
        }
        
        // Recursively check at finer resolution
        return isMengerVoxel(x % third, y % third, z % third, third);
    }
    
    /**
     * Get a user-friendly name for a shape.
     * 
     * @param shape Shape type
     * @return Human-readable name
     */
    public static String getShapeName(Shape shape) {
        return switch (shape) {
            case SPHERE -> "Sphere";
            case CUBE -> "Cube";
            case TORUS -> "Torus";
            case MENGER_SPONGE -> "Menger Sponge";
        };
    }
    
    /**
     * Get approximate voxel count for a shape at given resolution.
     * Used for UI feedback and performance estimates.
     * 
     * @param shape Shape type
     * @param resolution Grid resolution
     * @return Estimated voxel count
     */
    public static int estimateVoxelCount(Shape shape, int resolution) {
        return switch (shape) {
            case SPHERE -> (int) (resolution * resolution * resolution * Math.PI / 6.0 * 0.8 * 0.8 * 0.8);
            case CUBE -> {
                var margin = (int) (resolution * 0.1f);
                var size = resolution - 2 * margin;
                yield size * size * size;
            }
            case TORUS -> {
                // Torus volume = 2 * pi^2 * R * r^2
                // where R = major radius, r = minor radius
                var majorRadius = resolution / 2.0f * 0.6f;
                var minorRadius = resolution / 2.0f * 0.25f;
                yield (int) (2.0 * Math.PI * Math.PI * majorRadius * minorRadius * minorRadius);
            }
            case MENGER_SPONGE -> (int) (Math.pow(resolution * 0.8, Math.log(20) / Math.log(3))); // Fractal dimension
        };
    }
}
