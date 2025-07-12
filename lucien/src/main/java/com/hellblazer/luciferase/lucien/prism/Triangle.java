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
 * 2D triangular element for prism horizontal (x,y) decomposition.
 * 
 * Triangle elements provide 4-way subdivision in the horizontal plane, forming the base component
 * of prism spatial keys. The space-filling curve is complex, adapted from t8code's triangular SFC
 * algorithm that preserves spatial locality through recursive subdivision.
 * 
 * Triangles use a type system (0 or 1) to handle orientation during subdivision, similar to
 * t8code's dtri implementation. Each triangle subdivides into 4 child triangles with appropriate
 * type transitions to maintain geometric consistency.
 * 
 * The coordinate system uses (x, y, n) coordinates where n is an auxiliary coordinate for
 * the triangular space-filling curve computation.
 * 
 * @author hal.hildebrand
 */
public final class Triangle {
    
    /** Maximum refinement level (same as Octree/Tetree) */
    public static final int MAX_LEVEL = 21;
    
    /** Maximum coordinate value (2^21 - 1) */
    public static final int MAX_COORDINATE = (1 << MAX_LEVEL) - 1;
    
    /** Number of children per triangle (4-way subdivision) */
    public static final int CHILDREN = 4;
    
    /** Number of triangle types (0 and 1) */
    public static final int TYPES = 2;
    
    /** Number of edges per triangle */
    public static final int EDGES = 3;
    
    private final byte level;          // Hierarchical level (0-21)
    private final byte type;           // Triangle type (0 or 1)
    private final int x;               // X coordinate
    private final int y;               // Y coordinate  
    private final int n;               // Auxiliary coordinate for SFC
    
    /**
     * Create a Triangle from world coordinates at a specific level.
     * 
     * @param worldX X coordinate in [0,1)
     * @param worldY Y coordinate in [0,1)
     * @param level The desired level (0-21)
     * @return Triangle containing the given point at the specified level
     * @throws IllegalArgumentException if coordinates are invalid
     */
    public static Triangle fromWorldCoordinate(float worldX, float worldY, int level) {
        if (worldX < 0 || worldX >= 1.0f || worldY < 0 || worldY >= 1.0f) {
            throw new IllegalArgumentException(
                String.format("World coordinates must be in [0,1), got: (%.3f, %.3f)", worldX, worldY));
        }
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be 0-" + MAX_LEVEL + ", got: " + level);
        }
        
        // For level 0, return root triangle
        if (level == 0) {
            return new Triangle(0, 0, 0, 0, 1);
        }
        
        // Quantize to grid
        int scale = 1 << level;
        int x = (int)(worldX * scale);
        int y = (int)(worldY * scale);
        
        // Clamp to valid range
        x = Math.min(x, scale - 1);
        y = Math.min(y, scale - 1);
        
        // Ensure x + y < scale (triangular constraint)
        if (x + y >= scale) {
            // Point is outside triangular region, clamp to nearest valid point
            // This maintains x + y = scale - 1
            int total = scale - 1;
            x = x * total / (x + y);
            y = total - x;
        }
        
        // Calculate n coordinate - ensure it's within bounds
        int n = Math.min(scale - x - y, scale - 1);
        
        // Determine type based on parity of coordinates
        // This is a simplified approach - in practice you might need more sophisticated type determination
        int type = ((x + y) % 2 == 0) ? 0 : 1;
        
        return new Triangle(level, type, x, y, n);
    }
    
    /**
     * Create a new Triangle element.
     * 
     * @param level the hierarchical level (0-21)
     * @param type the triangle type (0 or 1)
     * @param x the x coordinate
     * @param y the y coordinate
     * @param n the auxiliary n coordinate
     * @throws IllegalArgumentException if parameters are invalid
     */
    public Triangle(int level, int type, int x, int y, int n) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be 0-" + MAX_LEVEL + ", got: " + level);
        }
        if (type < 0 || type >= TYPES) {
            throw new IllegalArgumentException("Type must be 0 or 1, got: " + type);
        }
        if (x < 0 || x > MAX_COORDINATE) {
            throw new IllegalArgumentException("X coordinate must be 0-" + MAX_COORDINATE + ", got: " + x);
        }
        if (y < 0 || y > MAX_COORDINATE) {
            throw new IllegalArgumentException("Y coordinate must be 0-" + MAX_COORDINATE + ", got: " + y);
        }
        if (n < 0 || n > MAX_COORDINATE) {
            throw new IllegalArgumentException("N coordinate must be 0-" + MAX_COORDINATE + ", got: " + n);
        }
        
        // For level 0, coordinates must be 0
        if (level == 0) {
            if (x != 0 || y != 0 || n != 0) {
                throw new IllegalArgumentException("Level 0 coordinates must all be 0");
            }
        } else {
            // For other levels, validate coordinates are reasonable (relaxed validation)
            var maxCoordForLevel = 1 << level;
            if (x >= maxCoordForLevel || y >= maxCoordForLevel || n >= maxCoordForLevel) {
                throw new IllegalArgumentException(
                    String.format("Coordinates (%d,%d,%d) exceed maximum %d for level %d", 
                                x, y, n, maxCoordForLevel - 1, level));
            }
        }
        
        this.level = (byte) level;
        this.type = (byte) type;
        this.x = x;
        this.y = y;
        this.n = n;
    }
    
    /**
     * Create a triangle from world coordinates at specified level.
     * 
     * @param worldX the world x-coordinate [0.0, 1.0)
     * @param worldY the world y-coordinate [0.0, 1.0)  
     * @param level the target level for quantization
     * @return the triangle element containing this coordinate
     */
    public static Triangle fromWorldCoordinates(float worldX, float worldY, int level) {
        if (worldX < 0.0f || worldX >= 1.0f) {
            throw new IllegalArgumentException("World X coordinate must be [0.0, 1.0), got: " + worldX);
        }
        if (worldY < 0.0f || worldY >= 1.0f) {
            throw new IllegalArgumentException("World Y coordinate must be [0.0, 1.0), got: " + worldY);
        }
        
        var scale = 1 << level;
        var quantX = Math.min((int) (worldX * scale), scale - 1);
        var quantY = Math.min((int) (worldY * scale), scale - 1);
        
        // Determine triangle type and n coordinate based on position
        // This is a simplified version - the full t8code algorithm is more complex
        var type = ((quantX + quantY) % 2);
        var n = Math.min(quantX, quantY);
        
        return new Triangle(level, type, quantX, quantY, n);
    }
    
    /**
     * Compute the space-filling curve index for this triangle element.
     * 
     * This implements a simplified version of t8code's triangular SFC algorithm.
     * The full algorithm involves complex type transitions and cube_id computation
     * that preserves spatial locality through the triangular subdivision hierarchy.
     * 
     * For now, we use a simple linear combination of coordinates.
     * TODO: Implement full t8code triangular SFC algorithm for optimal spatial locality.
     * 
     * @return the SFC index (consecutive index)
     */
    public long consecutiveIndex() {
        // Simplified SFC - linear combination of coordinates
        // This ensures different coordinates produce different indices
        var levelScale = 1L << (level * 2); // Scale factor for this level
        return x + (y * levelScale) + (n * levelScale * levelScale) + (type * levelScale * levelScale * levelScale);
    }
    
    
    /**
     * Get the parent triangle in the hierarchy.
     * 
     * @return the parent triangle, or null if this is level 0
     */
    public Triangle parent() {
        if (level == 0) {
            return null;
        }
        
        // Simplified parent computation - the full t8code algorithm handles type transitions
        var parentX = x >>> 1;
        var parentY = y >>> 1;
        var parentN = n >>> 1;
        var parentType = computeParentType();
        
        return new Triangle(level - 1, parentType, parentX, parentY, parentN);
    }
    
    /**
     * Compute the parent type based on current triangle state.
     * This is a simplified version of t8code's type transition algorithm.
     */
    private int computeParentType() {
        // For simplified implementation, derive parent type from child index
        var childIndex = getChildIndex();
        if (childIndex == -1) return type; // Already at root
        
        // Reverse the child type computation to get parent type
        return (type - (childIndex % 2) + TYPES) % TYPES;
    }
    
    /**
     * Get a child triangle by child index.
     * 
     * @param childIndex the child index (0-3)
     * @return the child triangle
     * @throws IllegalArgumentException if childIndex is invalid or triangle is at max level
     */
    public Triangle child(int childIndex) {
        if (childIndex < 0 || childIndex >= CHILDREN) {
            throw new IllegalArgumentException("Child index must be 0-3, got: " + childIndex);
        }
        if (level >= MAX_LEVEL) {
            throw new IllegalArgumentException("Cannot get child of triangle at maximum level " + MAX_LEVEL);
        }
        
        // Simplified child computation - full t8code algorithm more complex
        var childX = (x << 1) + (childIndex & 1);
        var childY = (y << 1) + ((childIndex >> 1) & 1);
        var childN = n << 1; // Simplified
        var childType = computeChildType(childIndex);
        
        return new Triangle(level + 1, childType, childX, childY, childN);
    }
    
    /**
     * Compute child type based on parent type and child index.
     * This is a simplified version of t8code's type transition algorithm.
     */
    private int computeChildType(int childIndex) {
        // Simplified type transitions - alternate based on parent type and child position
        return (type + (childIndex % 2)) % TYPES;
    }
    
    /**
     * Get the child index of this triangle relative to its parent.
     * 
     * @return the child index (0-3), or -1 if this is level 0
     */
    public int getChildIndex() {
        if (level == 0) {
            return -1;
        }
        
        // Simplified - extract from coordinates
        return (x & 1) + ((y & 1) << 1);
    }
    
    /**
     * Test if this triangle contains the given world coordinates.
     * 
     * @param worldX the world x-coordinate [0.0, 1.0)
     * @param worldY the world y-coordinate [0.0, 1.0)
     * @return true if the coordinates are contained in this triangle
     */
    public boolean contains(float worldX, float worldY) {
        if (worldX < 0.0f || worldX >= 1.0f || worldY < 0.0f || worldY >= 1.0f) {
            return false;
        }
        
        // Simplified containment test - quantize and compare
        var scale = 1 << level;
        var quantX = Math.min((int) (worldX * scale), scale - 1);
        var quantY = Math.min((int) (worldY * scale), scale - 1);
        
        // Basic triangular region test (simplified)
        return quantX == x && quantY == y;
    }
    
    /**
     * Find the neighbors of this triangle on its three edges.
     * 
     * @return array of 3 neighbor triangles (may contain nulls for boundary edges)
     */
    public Triangle[] neighbors() {
        var neighbors = new Triangle[EDGES];
        
        // Simplified neighbor finding - full t8code algorithm is more complex
        // Edge 0: right neighbor
        if (x + 1 < (1 << level)) {
            neighbors[0] = new Triangle(level, type, x + 1, y, n);
        }
        
        // Edge 1: top neighbor  
        if (y + 1 < (1 << level)) {
            neighbors[1] = new Triangle(level, type, x, y + 1, n);
        }
        
        // Edge 2: diagonal neighbor (simplified)
        if (x > 0 && y > 0) {
            neighbors[2] = new Triangle(level, type, x - 1, y - 1, n);
        }
        
        return neighbors;
    }
    
    /**
     * Get the world coordinate range for this triangle.
     * 
     * @return array of [minX, minY, maxX, maxY] coordinates in world space
     */
    public float[] getWorldBounds() {
        var scale = 1.0f / (1 << level);
        var minX = x * scale;
        var minY = y * scale;
        return new float[]{minX, minY, minX + scale, minY + scale};
    }
    
    /**
     * Get the centroid world coordinates of this triangle.
     * 
     * @return array of [centerX, centerY] coordinates in world space
     */
    public float[] getCentroidWorldCoordinates() {
        var scale = 1.0f / (1 << level);
        var centerX = x * scale + scale * 0.5f;
        var centerY = y * scale + scale * 0.5f;
        return new float[]{centerX, centerY};
    }
    
    // Accessors
    
    public byte getLevel() {
        return level;
    }
    
    public byte getType() {
        return type;
    }
    
    public int getX() {
        return x;
    }
    
    public int getY() {
        return y;
    }
    
    public int getN() {
        return n;
    }
    
    // Object methods
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Triangle other)) return false;
        return level == other.level && type == other.type && 
               x == other.x && y == other.y && n == other.n;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(level, type, x, y, n);
    }
    
    @Override
    public String toString() {
        var centroid = getCentroidWorldCoordinates();
        return String.format("Triangle(level=%d, type=%d, coords=(%d,%d,%d), center=(%.4f,%.4f))", 
                           level, type, x, y, n, centroid[0], centroid[1]);
    }
}