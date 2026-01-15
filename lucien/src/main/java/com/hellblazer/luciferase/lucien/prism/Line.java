/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.prism;

import java.util.Objects;

/**
 * 1D linear element for prism vertical (z) subdivision.
 * 
 * Line elements provide 2-way subdivision along the z-axis, forming the height component
 * of prism spatial keys. The subdivision follows a simple binary subdivision pattern
 * similar to standard octree subdivision along a single axis.
 * 
 * @author hal.hildebrand
 */
public final class Line {
    
    /** Maximum refinement level (same as Octree/Tetree) */
    public static final int MAX_LEVEL = 21;
    
    /** Maximum coordinate value (2^21 - 1) */
    public static final int MAX_COORDINATE = (1 << MAX_LEVEL) - 1;
    
    /** Number of children per line (2-way subdivision) */
    public static final int CHILDREN = 2;
    
    private final byte level;          // Hierarchical level (0-21)
    private final int z;               // Z coordinate
    
    /**
     * Create a Line from world coordinates at a specific level.
     * 
     * @param worldZ Z coordinate in [0,1)
     * @param level The desired level (0-21)
     * @return Line containing the given point at the specified level
     * @throws IllegalArgumentException if coordinates are invalid
     */
    public static Line fromWorldCoordinate(float worldZ, int level) {
        if (worldZ < 0 || worldZ >= 1.0f) {
            throw new IllegalArgumentException(
                String.format("World coordinate must be in [0,1), got: %.3f", worldZ));
        }
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be 0-" + MAX_LEVEL + ", got: " + level);
        }
        
        // For level 0, return root line
        if (level == 0) {
            return new Line(0, 0);
        }
        
        // Quantize to grid
        int scale = 1 << level;
        int z = (int)(worldZ * scale);
        
        // Clamp to valid range
        z = Math.min(z, scale - 1);
        
        return new Line(level, z);
    }
    
    /**
     * Create a new Line element.
     * 
     * @param level the hierarchical level (0-21)
     * @param z the z coordinate
     * @throws IllegalArgumentException if parameters are invalid
     */
    public Line(int level, int z) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be 0-" + MAX_LEVEL + ", got: " + level);
        }
        if (z < 0 || z > MAX_COORDINATE) {
            throw new IllegalArgumentException("Z coordinate must be 0-" + MAX_COORDINATE + ", got: " + z);
        }
        
        // For level 0, coordinate must be 0
        if (level == 0) {
            if (z != 0) {
                throw new IllegalArgumentException("Level 0 coordinate must be 0");
            }
        } else {
            // For other levels, validate coordinate is reasonable
            var maxCoordForLevel = 1 << level;
            if (z >= maxCoordForLevel) {
                throw new IllegalArgumentException(
                    String.format("Coordinate %d exceeds maximum %d for level %d", 
                                z, maxCoordForLevel - 1, level));
            }
        }
        
        this.level = (byte) level;
        this.z = z;
    }
    
    /**
     * Compute the space-filling curve index for this line element.
     * For 1D subdivision, this is simply the z coordinate.
     * 
     * @return the SFC index (consecutive index)
     */
    public long consecutiveIndex() {
        return z;
    }
    
    /**
     * Get the parent line in the hierarchy.
     * 
     * @return the parent line, or null if this is level 0
     */
    public Line parent() {
        if (level == 0) {
            return null;
        }
        
        // Binary subdivision - shift right by 1
        var parentZ = z >>> 1;
        
        return new Line(level - 1, parentZ);
    }
    
    /**
     * Get a child line by child index.
     * 
     * @param childIndex the child index (0-1)
     * @return the child line
     * @throws IllegalArgumentException if childIndex is invalid or line is at max level
     */
    public Line child(int childIndex) {
        if (childIndex < 0 || childIndex >= CHILDREN) {
            throw new IllegalArgumentException("Child index must be 0-1, got: " + childIndex);
        }
        if (level >= MAX_LEVEL) {
            throw new IllegalArgumentException("Cannot get child of line at maximum level " + MAX_LEVEL);
        }
        
        // Binary subdivision
        var childZ = (z << 1) + childIndex;
        
        return new Line(level + 1, childZ);
    }
    
    /**
     * Get the child index of this line relative to its parent.
     * 
     * @return the child index (0-1), or -1 if this is level 0
     */
    public int getChildIndex() {
        if (level == 0) {
            return -1;
        }
        
        // Extract from least significant bit
        return z & 1;
    }
    
    /**
     * Test if this line contains the given world coordinate.
     * 
     * @param worldZ the world z-coordinate [0.0, 1.0)
     * @return true if the coordinate is contained in this line
     */
    public boolean contains(float worldZ) {
        if (worldZ < 0.0f || worldZ >= 1.0f) {
            return false;
        }
        
        var scale = 1 << level;
        var quantZ = Math.min((int) (worldZ * scale), scale - 1);
        
        return quantZ == z;
    }
    
    /**
     * Find the neighbors of this line.
     * 
     * @return array of 2 neighbor lines (may contain nulls for boundary edges)
     */
    public Line[] neighbors() {
        var neighbors = new Line[2];
        
        // Below neighbor
        if (z > 0) {
            neighbors[0] = new Line(level, z - 1);
        }
        
        // Above neighbor
        if (z + 1 < (1 << level)) {
            neighbors[1] = new Line(level, z + 1);
        }
        
        return neighbors;
    }
    
    /**
     * Find the neighbor of this line in a specific direction.
     * 
     * @param direction the direction (-1 for below, 1 for above)
     * @return the neighbor line, or null if at boundary
     * @throws IllegalArgumentException if direction is not -1 or 1
     */
    public Line neighbor(int direction) {
        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException("Direction must be -1 (below) or 1 (above), got: " + direction);
        }
        
        if (direction == -1) {
            // Below neighbor
            if (z > 0) {
                return new Line(level, z - 1);
            }
        } else {
            // Above neighbor
            if (z + 1 < (1 << level)) {
                return new Line(level, z + 1);
            }
        }
        
        return null; // At boundary
    }
    
    /**
     * Get the world coordinate range for this line.
     * 
     * @return array of [minZ, maxZ] coordinates in world space
     */
    public float[] getWorldBounds() {
        var scale = 1.0f / (1 << level);
        var minZ = z * scale;
        return new float[]{minZ, minZ + scale};
    }
    
    /**
     * Get the centroid world coordinate of this line.
     * 
     * @return the center Z coordinate in world space
     */
    public float getCentroidWorldCoordinate() {
        var scale = 1.0f / (1 << level);
        return z * scale + scale * 0.5f;
    }
    
    // Accessors
    
    public byte getLevel() {
        return level;
    }
    
    public int getZ() {
        return z;
    }
    
    // Object methods
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Line other)) return false;
        return level == other.level && z == other.z;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(level, z);
    }
    
    @Override
    public String toString() {
        var center = getCentroidWorldCoordinate();
        return String.format("Line(level=%d, z=%d, center=%.4f)", 
                           level, z, center);
    }
}