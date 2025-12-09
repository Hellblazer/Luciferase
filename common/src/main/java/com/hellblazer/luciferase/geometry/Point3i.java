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

package com.hellblazer.luciferase.geometry;

import java.util.Objects;

/**
 * Immutable 3D point with integer coordinates.
 * Used for voxel positions and discrete spatial coordinates.
 * 
 * @author hal.hildebrand
 */
public final class Point3i {
    
    /** X coordinate */
    public final int x;
    
    /** Y coordinate */
    public final int y;
    
    /** Z coordinate */
    public final int z;
    
    /**
     * Create a new 3D integer point.
     * 
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public Point3i(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
    
    /**
     * Create a point at the origin (0, 0, 0).
     * 
     * @return Point at origin
     */
    public static Point3i origin() {
        return new Point3i(0, 0, 0);
    }
    
    /**
     * Create a point with all coordinates set to the same value.
     * 
     * @param value Value for all coordinates
     * @return Point with uniform coordinates
     */
    public static Point3i uniform(int value) {
        return new Point3i(value, value, value);
    }
    
    /**
     * Add another point to this point.
     * 
     * @param other Point to add
     * @return New point with summed coordinates
     */
    public Point3i add(Point3i other) {
        return new Point3i(x + other.x, y + other.y, z + other.z);
    }
    
    /**
     * Subtract another point from this point.
     * 
     * @param other Point to subtract
     * @return New point with subtracted coordinates
     */
    public Point3i subtract(Point3i other) {
        return new Point3i(x - other.x, y - other.y, z - other.z);
    }
    
    /**
     * Multiply this point by a scalar.
     * 
     * @param scalar Scalar multiplier
     * @return New point with scaled coordinates
     */
    public Point3i multiply(int scalar) {
        return new Point3i(x * scalar, y * scalar, z * scalar);
    }
    
    /**
     * Calculate Manhattan distance to another point.
     * 
     * @param other Other point
     * @return Manhattan distance (|dx| + |dy| + |dz|)
     */
    public int manhattanDistance(Point3i other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }
    
    /**
     * Calculate squared Euclidean distance to another point.
     * Avoids sqrt() for performance.
     * 
     * @param other Other point
     * @return Squared distance
     */
    public long distanceSquared(Point3i other) {
        long dx = x - other.x;
        long dy = y - other.y;
        long dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
    
    /**
     * Check if this point is within a cubic bounds.
     * 
     * @param min Minimum corner (inclusive)
     * @param max Maximum corner (exclusive)
     * @return True if point is within bounds
     */
    public boolean isWithinBounds(Point3i min, Point3i max) {
        return x >= min.x && x < max.x &&
               y >= min.y && y < max.y &&
               z >= min.z && z < max.z;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Point3i other)) return false;
        return x == other.x && y == other.y && z == other.z;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }
    
    @Override
    public String toString() {
        return String.format("Point3i(%d, %d, %d)", x, y, z);
    }
    
    /**
     * Convert to array [x, y, z].
     * 
     * @return Array representation
     */
    public int[] toArray() {
        return new int[] { x, y, z };
    }
    
    /**
     * Create point from array [x, y, z].
     * 
     * @param array Array with at least 3 elements
     * @return Point from array
     * @throws IllegalArgumentException if array length < 3
     */
    public static Point3i fromArray(int[] array) {
        if (array.length < 3) {
            throw new IllegalArgumentException("Array must have at least 3 elements");
        }
        return new Point3i(array[0], array[1], array[2]);
    }
}
