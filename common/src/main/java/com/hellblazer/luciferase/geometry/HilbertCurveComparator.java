/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.geometry;

import javax.vecmath.Vector3f;
import java.util.Comparator;

/**
 * A comparator that orders 3D points according to their position along a 3D Hilbert curve.
 * 
 * The Hilbert curve is a space-filling curve that preserves spatial locality - points that
 * are close in 3D space tend to be close along the curve. This makes it useful for spatial
 * indexing, cache-efficient traversal, and spatial clustering.
 * 
 * This implementation uses a fixed-precision integer approach where floating-point coordinates
 * are quantized to a configurable number of bits (default 21 bits per dimension for 63-bit total).
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class HilbertCurveComparator implements Comparator<Vector3f> {
    
    /**
     * Default number of bits per dimension (21 bits * 3 = 63 bits total)
     */
    public static final int DEFAULT_BITS_PER_DIM = 21;
    
    private final int bitsPerDim;
    private final long maxCoord;
    private final float minX, minY, minZ;
    private final float rangeX, rangeY, rangeZ;
    
    /**
     * Create a Hilbert curve comparator for the unit cube [0,1]³ with default precision.
     */
    public HilbertCurveComparator() {
        this(0, 0, 0, 1, 1, 1, DEFAULT_BITS_PER_DIM);
    }
    
    /**
     * Create a Hilbert curve comparator for a specific bounding box with default precision.
     * 
     * @param minX minimum X coordinate
     * @param minY minimum Y coordinate
     * @param minZ minimum Z coordinate
     * @param maxX maximum X coordinate
     * @param maxY maximum Y coordinate
     * @param maxZ maximum Z coordinate
     */
    public HilbertCurveComparator(float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        this(minX, minY, minZ, maxX, maxY, maxZ, DEFAULT_BITS_PER_DIM);
    }
    
    /**
     * Create a Hilbert curve comparator for a specific bounding box and precision.
     * 
     * @param minX minimum X coordinate
     * @param minY minimum Y coordinate
     * @param minZ minimum Z coordinate
     * @param maxX maximum X coordinate
     * @param maxY maximum Y coordinate
     * @param maxZ maximum Z coordinate
     * @param bitsPerDim number of bits per dimension (max 21 for 63-bit total)
     */
    public HilbertCurveComparator(float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ,
                                  int bitsPerDim) {
        if (bitsPerDim < 1 || bitsPerDim > 21) {
            throw new IllegalArgumentException("Bits per dimension must be between 1 and 21");
        }
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            throw new IllegalArgumentException("Invalid bounding box");
        }
        
        this.bitsPerDim = bitsPerDim;
        this.maxCoord = (1L << bitsPerDim) - 1;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.rangeX = maxX - minX;
        this.rangeY = maxY - minY;
        this.rangeZ = maxZ - minZ;
    }
    
    @Override
    public int compare(Vector3f v1, Vector3f v2) {
        long hilbert1 = computeHilbertIndex(v1);
        long hilbert2 = computeHilbertIndex(v2);
        return Long.compare(hilbert1, hilbert2);
    }
    
    /**
     * Compute the Hilbert curve index for a 3D point.
     * 
     * @param point the 3D point
     * @return the Hilbert curve index
     */
    public long computeHilbertIndex(Vector3f point) {
        // Quantize floating-point coordinates to integers
        long x = quantize(point.x, minX, rangeX);
        long y = quantize(point.y, minY, rangeY);
        long z = quantize(point.z, minZ, rangeZ);
        
        // Compute Hilbert index using bit manipulation
        return coordinatesToHilbert(x, y, z);
    }
    
    /**
     * Quantize a floating-point coordinate to an integer in the range [0, maxCoord].
     */
    private long quantize(float value, float min, float range) {
        float normalized = (value - min) / range;
        // Clamp to [0, 1]
        normalized = Math.max(0.0f, Math.min(1.0f, normalized));
        return (long)(normalized * maxCoord);
    }
    
    /**
     * Convert 3D coordinates to Hilbert curve index.
     * Based on the algorithm from "Compact Hilbert Indices" by Chris Hamilton.
     */
    private long coordinatesToHilbert(long x, long y, long z) {
        long hilbert = 0;
        
        // Process each bit level
        for (int i = bitsPerDim - 1; i >= 0; i--) {
            long mask = 1L << i;
            
            // Extract current bits
            long xBit = (x & mask) != 0 ? 1 : 0;
            long yBit = (y & mask) != 0 ? 1 : 0;
            long zBit = (z & mask) != 0 ? 1 : 0;
            
            // Compute the 3-bit index for this level
            long index = (xBit << 2) | (yBit << 1) | zBit;
            
            // Apply Hilbert curve transformation
            long h = hilbertPattern(index);
            hilbert = (hilbert << 3) | h;
            
            // Transform coordinates for next level based on current subcube
            long[] transformed = transformCoordinates(x, y, z, index, mask);
            x = transformed[0];
            y = transformed[1];
            z = transformed[2];
        }
        
        return hilbert;
    }
    
    /**
     * Get the Hilbert pattern for a 3-bit index.
     * This defines the order in which subcubes are visited.
     */
    private long hilbertPattern(long index) {
        // Standard 3D Hilbert curve traversal pattern
        long[] pattern = {0, 1, 3, 2, 7, 6, 4, 5};
        return pattern[(int)index];
    }
    
    /**
     * Transform coordinates based on which subcube we're in.
     * This implements the rotation and reflection operations of the Hilbert curve.
     */
    private long[] transformCoordinates(long x, long y, long z, long index, long mask) {
        switch ((int)index) {
            case 0: // No transformation
                return new long[]{x, y, z};
            case 1: // Swap X and Z
                return new long[]{z, y, x};
            case 2: // Swap X and Z
                return new long[]{z, y, x};
            case 3: // Rotate around Y
                return new long[]{mask - 1 - x, y, mask - 1 - z};
            case 4: // Rotate around Y
                return new long[]{mask - 1 - x, y, mask - 1 - z};
            case 5: // Swap X and Z, invert
                return new long[]{mask - 1 - z, y, mask - 1 - x};
            case 6: // Swap X and Z, invert
                return new long[]{mask - 1 - z, y, mask - 1 - x};
            case 7: // No transformation
                return new long[]{x, y, z};
            default:
                throw new IllegalStateException("Invalid index: " + index);
        }
    }
    
    /**
     * Create a comparator for points in a cube centered at origin.
     * 
     * @param halfSize half the size of the cube
     * @return a Hilbert curve comparator for the cube
     */
    public static HilbertCurveComparator forCube(float halfSize) {
        return new HilbertCurveComparator(-halfSize, -halfSize, -halfSize,
                                          halfSize, halfSize, halfSize);
    }
    
    /**
     * Create a comparator for points in a box.
     * 
     * @param width box width (X dimension)
     * @param height box height (Y dimension)
     * @param depth box depth (Z dimension)
     * @return a Hilbert curve comparator for the box centered at origin
     */
    public static HilbertCurveComparator forBox(float width, float height, float depth) {
        float halfW = width / 2;
        float halfH = height / 2;
        float halfD = depth / 2;
        return new HilbertCurveComparator(-halfW, -halfH, -halfD,
                                          halfW, halfH, halfD);
    }
    
    /**
     * Calculate the Euclidean distance between two 3D points.
     * 
     * @param v1 first point
     * @param v2 second point
     * @return the distance between the points
     */
    public static float distance(Vector3f v1, Vector3f v2) {
        float dx = v2.x - v1.x;
        float dy = v2.y - v1.y;
        float dz = v2.z - v1.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * Calculate the squared Euclidean distance between two 3D points.
     * More efficient when you only need to compare distances.
     * 
     * @param v1 first point
     * @param v2 second point
     * @return the squared distance between the points
     */
    public static float distanceSquared(Vector3f v1, Vector3f v2) {
        float dx = v2.x - v1.x;
        float dy = v2.y - v1.y;
        float dz = v2.z - v1.z;
        return dx * dx + dy * dy + dz * dz;
    }
}