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

import com.hellblazer.luciferase.lucien.SpatialKey;

import java.util.Objects;

/**
 * Composite spatial key for prism elements combining triangular base and linear height.
 * 
 * PrismKey implements the SpatialKey interface for prism-based spatial indexing, following
 * t8code's design of prisms as Cartesian products of triangles and lines. This provides
 * anisotropic spatial decomposition with fine horizontal granularity and coarse vertical
 * granularity, ideal for layered data.
 * 
 * The key maintains strict level synchronization between triangle and line components,
 * ensuring geometric consistency. The space-filling curve combines both components to
 * preserve spatial locality while enabling efficient range operations.
 * 
 * Morton-order subdivision produces 8 children per prism:
 * - Children 0-3: Lower plane (line child 0) + triangle children 0-3
 * - Children 4-7: Upper plane (line child 1) + triangle children 0-3
 * 
 * @author hal.hildebrand
 */
public final class PrismKey implements SpatialKey<PrismKey> {
    
    /** Number of children per prism (8-way subdivision) */
    public static final int CHILDREN = 8;
    
    private final Triangle triangle;    // Horizontal (x,y) component
    private final Line line;            // Vertical (z) component
    
    /**
     * Create a new PrismKey from triangle and line components.
     * 
     * @param triangle the horizontal triangular component
     * @param line the vertical linear component
     * @throws IllegalArgumentException if components have different levels
     */
    public PrismKey(Triangle triangle, Line line) {
        if (triangle == null) {
            throw new IllegalArgumentException("Triangle component cannot be null");
        }
        if (line == null) {
            throw new IllegalArgumentException("Line component cannot be null");
        }
        if (triangle.getLevel() != line.getLevel()) {
            throw new IllegalArgumentException(
                String.format("Triangle and line levels must match: triangle=%d, line=%d", 
                            triangle.getLevel(), line.getLevel()));
        }
        
        this.triangle = triangle;
        this.line = line;
    }
    
    /**
     * Create a prism key from world coordinates at specified level.
     * 
     * @param worldX the world x-coordinate [0.0, 1.0)
     * @param worldY the world y-coordinate [0.0, 1.0)
     * @param worldZ the world z-coordinate [0.0, 1.0)
     * @param level the target level for quantization
     * @return the prism key containing this coordinate
     */
    public static PrismKey fromWorldCoordinates(float worldX, float worldY, float worldZ, int level) {
        var triangle = Triangle.fromWorldCoordinates(worldX, worldY, level);
        var line = Line.fromWorldCoordinate(worldZ, level);
        return new PrismKey(triangle, line);
    }
    
    /**
     * Create the root prism key (level 0).
     * 
     * @return the root prism key
     */
    public static PrismKey createRoot() {
        return new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
    }
    
    /**
     * Compute the composite space-filling curve index.
     * 
     * This combines the triangular and linear SFC indices using a simple
     * interleaving approach. The triangle contributes the lower bits and 
     * the line contributes the higher bits to ensure uniqueness.
     * 
     * @return the composite SFC index
     */
    public long consecutiveIndex() {
        var triangleId = triangle.consecutiveIndex();
        var lineId = line.consecutiveIndex();
        
        // Simple combination: line ID in high bits, triangle ID in low bits
        // This ensures all combinations are unique
        var level = getLevel();
        var triangleBits = level * 2 + 2; // Allow enough bits for triangle
        
        return (lineId << triangleBits) | triangleId;
    }
    
    @Override
    public byte getLevel() {
        return triangle.getLevel(); // Both components have same level
    }
    
    @Override
    public boolean isValid() {
        return triangle.getLevel() == line.getLevel() && 
               triangle.getLevel() >= 0 && triangle.getLevel() <= Triangle.MAX_LEVEL;
    }
    
    @Override
    public PrismKey parent() {
        if (getLevel() == 0) {
            return null;
        }
        
        var parentTriangle = triangle.parent();
        var parentLine = line.parent();
        
        if (parentTriangle == null || parentLine == null) {
            return null;
        }
        
        return new PrismKey(parentTriangle, parentLine);
    }
    
    @Override
    public PrismKey root() {
        return createRoot();
    }
    
    /**
     * Get a child prism key by child index.
     * 
     * Uses Morton-order subdivision where child index maps to:
     * - triangleChildIndex = childIndex % 4
     * - lineChildIndex = childIndex / 4
     * 
     * @param childIndex the child index (0-7)
     * @return the child prism key
     * @throws IllegalArgumentException if childIndex is invalid or key is at max level
     */
    public PrismKey child(int childIndex) {
        if (childIndex < 0 || childIndex >= CHILDREN) {
            throw new IllegalArgumentException("Child index must be 0-7, got: " + childIndex);
        }
        if (getLevel() >= Triangle.MAX_LEVEL) {
            throw new IllegalArgumentException("Cannot get child of prism at maximum level");
        }
        
        var triangleChildIndex = childIndex % Triangle.CHILDREN;  // 0-3
        var lineChildIndex = childIndex / Triangle.CHILDREN;      // 0-1
        
        var childTriangle = triangle.child(triangleChildIndex);
        var childLine = line.child(lineChildIndex);
        
        return new PrismKey(childTriangle, childLine);
    }
    
    /**
     * Get the child index of this prism relative to its parent.
     * 
     * @return the child index (0-7), or -1 if this is level 0
     */
    public int getChildIndex() {
        if (getLevel() == 0) {
            return -1;
        }
        
        var triangleChildIndex = triangle.getChildIndex();
        var lineChildIndex = line.getChildIndex();
        
        if (triangleChildIndex == -1 || lineChildIndex == -1) {
            return -1;
        }
        
        return triangleChildIndex + (lineChildIndex * Triangle.CHILDREN);
    }
    
    /**
     * Test if this prism contains the given world coordinates.
     * 
     * @param worldX the world x-coordinate [0.0, 1.0)
     * @param worldY the world y-coordinate [0.0, 1.0)
     * @param worldZ the world z-coordinate [0.0, 1.0)
     * @return true if the coordinates are contained in this prism
     */
    public boolean contains(float worldX, float worldY, float worldZ) {
        return triangle.contains(worldX, worldY) && line.contains(worldZ);
    }
    
    /**
     * Get the centroid world coordinates of this prism.
     * 
     * @return array of [centerX, centerY, centerZ] coordinates in world space
     */
    public float[] getCentroid() {
        var triangleCentroid = triangle.getCentroidWorldCoordinates();
        var lineCentroid = line.getCentroidWorldCoordinate();
        return new float[]{triangleCentroid[0], triangleCentroid[1], lineCentroid};
    }
    
    /**
     * Get the volume of this prism in world coordinates.
     * 
     * @return the prism volume
     */
    public float getVolume() {
        // Simplified volume calculation
        var level = getLevel();
        var baseArea = 1.0f / (1 << (level * 2));  // Triangle area approximation
        var height = 1.0f / (1 << level);          // Line segment height
        return baseArea * height;
    }
    
    /**
     * Get the world coordinate bounds for this prism.
     * 
     * @return array of [minX, minY, minZ, maxX, maxY, maxZ] coordinates
     */
    public float[] getWorldBounds() {
        var triangleBounds = triangle.getWorldBounds();
        var lineRange = line.getWorldBounds();
        return new float[]{
            triangleBounds[0], triangleBounds[1], lineRange[0],  // min x,y,z
            triangleBounds[2], triangleBounds[3], lineRange[1]   // max x,y,z
        };
    }
    
    /**
     * Estimate the distance from this prism to a world coordinate point.
     * 
     * @param worldX the world x-coordinate
     * @param worldY the world y-coordinate  
     * @param worldZ the world z-coordinate
     * @return the estimated distance
     */
    public float estimateDistanceTo(float worldX, float worldY, float worldZ) {
        var centroid = getCentroid();
        var dx = worldX - centroid[0];
        var dy = worldY - centroid[1];
        var dz = worldZ - centroid[2];
        return (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
    }
    
    // Component accessors
    
    public Triangle getTriangle() {
        return triangle;
    }
    
    public Line getLine() {
        return line;
    }
    
    // Comparable implementation
    
    @Override
    public int compareTo(PrismKey other) {
        if (other == null) {
            return 1;
        }
        
        // Compare by consecutive index for spatial ordering
        return Long.compare(this.consecutiveIndex(), other.consecutiveIndex());
    }
    
    // Object methods
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PrismKey other)) return false;
        return Objects.equals(triangle, other.triangle) && Objects.equals(line, other.line);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(triangle, line);
    }
    
    @Override
    public String toString() {
        var centroid = getCentroid();
        return String.format("PrismKey(level=%d, center=(%.4f,%.4f,%.4f), sfc=%d)", 
                           getLevel(), centroid[0], centroid[1], centroid[2], consecutiveIndex());
    }
}