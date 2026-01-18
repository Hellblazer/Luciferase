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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SpatialKey;

import javax.vecmath.Point3f;
import java.util.Objects;

/**
 * Spatial key implementation for Octree structures using Morton encoding.
 *
 * Morton codes provide a space-filling curve that maps 3D coordinates to a single dimension while preserving spatial
 * locality. The Morton code inherently encodes both the spatial location and the hierarchical level.
 *
 * This implementation wraps the existing long Morton code to provide type safety and compatibility with the generic
 * SpatialKey interface.
 *
 * @author hal.hildebrand
 */
public final class MortonKey implements SpatialKey<MortonKey> {
    
    /**
     * Represents a range of Morton keys for spatial queries.
     * Used to prune k-NN search using ConcurrentSkipListMap.subMap().
     * 
     * @param lower the lower bound (inclusive) of the Morton key range
     * @param upper the upper bound (exclusive) of the Morton key range
     */
    public record SFCRange(MortonKey lower, MortonKey upper) {
        public SFCRange {
            Objects.requireNonNull(lower, "Lower bound cannot be null");
            Objects.requireNonNull(upper, "Upper bound cannot be null");
        }
    }

    private final long mortonCode;
    private final byte level;  // Cached for performance

    /**
     * Create a new MortonKey from a Morton code.
     *
     * @param mortonCode the Morton-encoded spatial index
     */
    public MortonKey(long mortonCode) {
        this(mortonCode, Constants.toLevel(mortonCode));
    }

    public MortonKey(long mortonCode, byte level) {
        this.mortonCode = mortonCode;
        this.level = level;
    }

    /**
     * Factory method to create a MortonKey from world coordinates and level.
     * The coordinates are raw world coordinates that will be quantized to the cell grid at the given level.
     *
     * @param x     the x coordinate (must be non-negative)
     * @param y     the y coordinate (must be non-negative)
     * @param z     the z coordinate (must be non-negative)
     * @param level the hierarchical level
     * @return a new MortonKey
     * @throws IllegalArgumentException if any coordinate is negative
     */
    public static MortonKey fromCoordinates(int x, int y, int z, byte level) {
        // Validate coordinates are non-negative
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException(
                String.format("Negative coordinates not supported: (%d,%d,%d)", x, y, z)
            );
        }

        // Quantize to cell boundaries at this level
        var cellSize = Constants.lengthAtLevel(level);
        var quantizedX = (x / cellSize) * cellSize;
        var quantizedY = (y / cellSize) * cellSize;
        var quantizedZ = (z / cellSize) * cellSize;

        // Encode to Morton code and preserve the level
        var mortonCode = com.hellblazer.luciferase.geometry.MortonCurve.encode(quantizedX, quantizedY, quantizedZ);
        return new MortonKey(mortonCode, level);
    }

    /**
     * Factory method to create a MortonKey from cell indices at a given level.
     * The coordinates are cell indices (0, 1, 2, ...), not world coordinates.
     *
     * @param cellX the x cell index (must be non-negative)
     * @param cellY the y cell index (must be non-negative)
     * @param cellZ the z cell index (must be non-negative)
     * @param level the hierarchical level
     * @return a new MortonKey
     * @throws IllegalArgumentException if any coordinate is negative or out of bounds
     */
    public static MortonKey fromCellIndices(int cellX, int cellY, int cellZ, byte level) {
        // Validate coordinates are non-negative
        if (cellX < 0 || cellY < 0 || cellZ < 0) {
            throw new IllegalArgumentException(
                String.format("Negative cell indices not supported: (%d,%d,%d)", cellX, cellY, cellZ)
            );
        }

        // Convert cell indices to world coordinates
        var cellSize = Constants.lengthAtLevel(level);
        var worldX = cellX * cellSize;
        var worldY = cellY * cellSize;
        var worldZ = cellZ * cellSize;

        // Validate bounds
        if (worldX > Constants.MAX_COORD || worldY > Constants.MAX_COORD || worldZ > Constants.MAX_COORD) {
            throw new IllegalArgumentException(
                String.format("Cell indices (%d,%d,%d) at level %d exceed maximum bounds", cellX, cellY, cellZ, level)
            );
        }

        // Encode to Morton code and preserve the level
        var mortonCode = com.hellblazer.luciferase.geometry.MortonCurve.encode(worldX, worldY, worldZ);
        return new MortonKey(mortonCode, level);
    }

    public static MortonKey getRoot() {
        return new MortonKey(0, (byte) 0);
    }

    @Override
    public int compareTo(MortonKey other) {
        Objects.requireNonNull(other, "Cannot compare to null MortonKey");
        // Natural ordering of Morton codes preserves spatial locality
        return Long.compare(this.mortonCode, other.mortonCode);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final MortonKey mortonKey)) {
            return false;
        }
        return mortonCode == mortonKey.mortonCode;
    }

    @Override
    public byte getLevel() {
        return level;
    }

    /**
     * Get the underlying Morton code.
     *
     * @return the Morton code value
     */
    public long getMortonCode() {
        return mortonCode;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(mortonCode);
    }

    @Override
    public boolean isValid() {
        // Morton codes with level > max refinement level are invalid
        return level >= 0 && level <= Constants.getMaxRefinementLevel();
    }

    @Override
    public MortonKey parent() {
        if (level == 0) {
            return null; // Root has no parent
        }

        // Calculate parent Morton code by shifting right by 3 bits (removing one octant level)
        var parentLevel = (byte) (level - 1);
        var parentCode = mortonCode >> 3;
        return new MortonKey(parentCode, parentLevel);
    }
    
    /**
     * Get a specific child of this Morton key.
     * 
     * @param childIndex the child index (0-7) representing which octant
     * @return the child MortonKey, or null if at max level
     */
    public MortonKey getChild(int childIndex) {
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("Child index must be between 0 and 7, got: " + childIndex);
        }
        
        if (level >= Constants.getMaxRefinementLevel()) {
            return null; // Cannot subdivide further
        }
        
        // Calculate child Morton code by shifting left by 3 bits and adding the child index
        var childLevel = (byte) (level + 1);
        var childCode = (mortonCode << 3) | childIndex;
        return new MortonKey(childCode, childLevel);
    }
    
    /**
     * Get all 8 children of this Morton key.
     * 
     * @return array of 8 child MortonKeys, or null if at max level
     */
    public MortonKey[] getChildren() {
        if (level >= Constants.getMaxRefinementLevel()) {
            return null; // Cannot subdivide further
        }
        
        MortonKey[] children = new MortonKey[8];
        for (int i = 0; i < 8; i++) {
            children[i] = getChild(i);
        }
        return children;
    }

    @Override
    public MortonKey root() {
        return new MortonKey(0);
    }

    @Override
    public String toString() {
        // Fast toString() that provides essential information
        StringBuilder sb = new StringBuilder("MortonKey[L");
        sb.append(level);
        
        // Add Morton code in base64 for compactness
        sb.append(",m:").append(longToBase64(mortonCode));
        
        // Optionally decode coordinates for debugging
        // This is more expensive but useful for understanding spatial position
        if (mortonCode != 0) {
            int[] coords = com.hellblazer.luciferase.geometry.MortonCurve.decode(mortonCode);
            sb.append(",@(").append(coords[0]).append(",")
              .append(coords[1]).append(",").append(coords[2]).append(")");
        } else {
            sb.append(",@origin");
        }
        
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * Convert a long to a compact base64 string representation.
     * Uses URL-safe base64 encoding without padding for compactness.
     */
    private static String longToBase64(long value) {
        // Convert long to byte array
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        
        // Use URL-safe base64 encoding without padding
        String base64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        
        // Remove leading A's (zeros) for compactness
        int firstNonA = 0;
        while (firstNonA < base64.length() - 1 && base64.charAt(firstNonA) == 'A') {
            firstNonA++;
        }
        return base64.substring(firstNonA);
    }
    
    /**
     * Get the neighbor of this Morton key in a specific direction.
     * This provides O(1) neighbor lookup using pre-computed offset tables and bit manipulation.
     *
     * @param direction the direction to find the neighbor
     * @return the neighbor MortonKey, or null if the neighbor would be out of bounds
     */
    public MortonKey neighbor(Direction direction) {
        // Decode current coordinates
        int[] coords = com.hellblazer.luciferase.geometry.MortonCurve.decode(mortonCode);
        var cellSize = Constants.lengthAtLevel(level);

        // Apply direction offset
        var offset = direction.getOffset();
        var nx = coords[0] + offset[0] * cellSize;
        var ny = coords[1] + offset[1] * cellSize;
        var nz = coords[2] + offset[2] * cellSize;

        // Check bounds - coordinates must be >= 0 and < MAX_COORD
        if (nx < 0 || nx > Constants.MAX_COORD ||
            ny < 0 || ny > Constants.MAX_COORD ||
            nz < 0 || nz > Constants.MAX_COORD) {
            return null; // Out of bounds
        }

        // Encode neighbor Morton code
        var neighborCode = com.hellblazer.luciferase.geometry.MortonCurve.encode(nx, ny, nz);
        return new MortonKey(neighborCode, level);
    }

    /**
     * Direction enum for 26 possible 3D neighbor directions.
     *
     * Layout:
     * - 6 face neighbors (share a face, codimension 1)
     * - 12 edge neighbors (share an edge, codimension 2)
     * - 8 vertex neighbors (share a vertex, codimension 3)
     */
    public enum Direction {
        // === Face neighbors (6) ===
        POSITIVE_X(1, 0, 0),
        NEGATIVE_X(-1, 0, 0),
        POSITIVE_Y(0, 1, 0),
        NEGATIVE_Y(0, -1, 0),
        POSITIVE_Z(0, 0, 1),
        NEGATIVE_Z(0, 0, -1),

        // === Edge neighbors (12) ===
        POS_X_POS_Y(1, 1, 0),
        POS_X_NEG_Y(1, -1, 0),
        NEG_X_POS_Y(-1, 1, 0),
        NEG_X_NEG_Y(-1, -1, 0),

        POS_X_POS_Z(1, 0, 1),
        POS_X_NEG_Z(1, 0, -1),
        NEG_X_POS_Z(-1, 0, 1),
        NEG_X_NEG_Z(-1, 0, -1),

        POS_Y_POS_Z(0, 1, 1),
        POS_Y_NEG_Z(0, 1, -1),
        NEG_Y_POS_Z(0, -1, 1),
        NEG_Y_NEG_Z(0, -1, -1),

        // === Vertex neighbors (8) ===
        POS_X_POS_Y_POS_Z(1, 1, 1),
        POS_X_POS_Y_NEG_Z(1, 1, -1),
        POS_X_NEG_Y_POS_Z(1, -1, 1),
        POS_X_NEG_Y_NEG_Z(1, -1, -1),
        NEG_X_POS_Y_POS_Z(-1, 1, 1),
        NEG_X_POS_Y_NEG_Z(-1, 1, -1),
        NEG_X_NEG_Y_POS_Z(-1, -1, 1),
        NEG_X_NEG_Y_NEG_Z(-1, -1, -1);

        private final int[] offset;

        Direction(int dx, int dy, int dz) {
            this.offset = new int[] { dx, dy, dz };
        }

        public int[] getOffset() {
            return offset;
        }

        /**
         * Get the opposite direction.
         */
        public Direction opposite() {
            return switch (this) {
                case POSITIVE_X -> NEGATIVE_X;
                case NEGATIVE_X -> POSITIVE_X;
                case POSITIVE_Y -> NEGATIVE_Y;
                case NEGATIVE_Y -> POSITIVE_Y;
                case POSITIVE_Z -> NEGATIVE_Z;
                case NEGATIVE_Z -> POSITIVE_Z;

                case POS_X_POS_Y -> NEG_X_NEG_Y;
                case POS_X_NEG_Y -> NEG_X_POS_Y;
                case NEG_X_POS_Y -> POS_X_NEG_Y;
                case NEG_X_NEG_Y -> POS_X_POS_Y;

                case POS_X_POS_Z -> NEG_X_NEG_Z;
                case POS_X_NEG_Z -> NEG_X_POS_Z;
                case NEG_X_POS_Z -> POS_X_NEG_Z;
                case NEG_X_NEG_Z -> POS_X_POS_Z;

                case POS_Y_POS_Z -> NEG_Y_NEG_Z;
                case POS_Y_NEG_Z -> NEG_Y_POS_Z;
                case NEG_Y_POS_Z -> POS_Y_NEG_Z;
                case NEG_Y_NEG_Z -> POS_Y_POS_Z;

                case POS_X_POS_Y_POS_Z -> NEG_X_NEG_Y_NEG_Z;
                case POS_X_POS_Y_NEG_Z -> NEG_X_NEG_Y_POS_Z;
                case POS_X_NEG_Y_POS_Z -> NEG_X_POS_Y_NEG_Z;
                case POS_X_NEG_Y_NEG_Z -> NEG_X_POS_Y_POS_Z;
                case NEG_X_POS_Y_POS_Z -> POS_X_NEG_Y_NEG_Z;
                case NEG_X_POS_Y_NEG_Z -> POS_X_NEG_Y_POS_Z;
                case NEG_X_NEG_Y_POS_Z -> POS_X_POS_Y_NEG_Z;
                case NEG_X_NEG_Y_NEG_Z -> POS_X_POS_Y_POS_Z;
            };
        }
    }

    /**
     * Convert this MortonKey to its protobuf representation.
     */
    public com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey toProto() {
        return com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey.newBuilder()
            .setMortonCode(mortonCode)
            .build();
    }

    /**
     * Create a MortonKey from its protobuf representation.
     */
    public static MortonKey fromProto(com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey proto) {
        return new MortonKey(proto.getMortonCode());
    }
    
    // ===== SFC Range Estimation for k-NN Optimization =====
    
    /**
     * Estimate the appropriate Morton depth for a given search radius.
     * This maps a geometric distance to the corresponding SFC depth where cells
     * are approximately the size of the search radius.
     * 
     * From Paper 4 (Space-Filling Trees for Motion Planning):
     * - Larger radius → coarser level (fewer, larger cells)
     * - Smaller radius → finer level (more, smaller cells)
     * 
     * @param radius the search radius in world coordinates
     * @return the estimated Morton depth (level) appropriate for this radius
     */
    public static byte estimateSFCDepth(float radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("Search radius must be positive, got: " + radius);
        }
        
        // Find the finest level where cell diagonal >= radius
        // Start from finest level (small cells) and work towards coarser levels (large cells)
        // Return the first level where the cell is large enough to cover the radius
        for (byte level = Constants.getMaxRefinementLevel(); level >= 0; level--) {
            float cellSize = Constants.lengthAtLevel(level);
            float cellDiagonal = (float) (cellSize * Math.sqrt(3.0));
            
            // If cell diagonal at this level >= radius, this is our target level
            if (cellDiagonal >= radius) {
                return level;
            }
        }
        
        // Radius is larger than even the root cell (extremely large), use root level
        return 0;
    }
    
    /**
     * Estimate the SFC range (Morton key range) that covers a spherical region.
     * This enables pruned k-NN search using ConcurrentSkipListMap.subMap().
     * 
     * Algorithm from Paper 4:
     * 1. Compute axis-aligned bounding box (AABB) around the sphere
     * 2. Convert AABB corners to Morton keys at the estimated depth
     * 3. Return range [min_morton, max_morton] covering all entities in the sphere
     * 
     * Note: Returns a conservative estimate (may include entities outside the sphere).
     * Caller must filter by actual distance.
     * 
     * @param center the center point of the search sphere (must have non-negative coordinates)
     * @param radius the search radius (must be positive)
     * @return SFCRange covering the spherical region (conservative estimate)
     * @throws IllegalArgumentException if radius is non-positive or center has negative coordinates
     */
    public static SFCRange estimateSFCRange(Point3f center, float radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("Search radius must be positive, got: " + radius);
        }
        
        // Validate center coordinates are non-negative
        if (center.x < 0 || center.y < 0 || center.z < 0) {
            throw new IllegalArgumentException(
                "Negative center coordinates not supported: " + center
            );
        }
        
        // Step 1: Estimate appropriate depth for this radius
        byte level = estimateSFCDepth(radius);
        float cellSize = Constants.lengthAtLevel(level);
        
        // Step 2: Compute AABB around sphere
        // Expand by cell size to ensure complete coverage (conservative)
        float expansion = cellSize;
        
        // Clamp coordinates to valid Morton range [0, MAX_COORD]
        // MAX_COORD = 2^21 - 1 = 2,097,151
        float maxCoord = Constants.MAX_COORD;
        
        Point3f min = new Point3f(
            Math.max(0, center.x - radius - expansion),
            Math.max(0, center.y - radius - expansion),
            Math.max(0, center.z - radius - expansion)
        );
        Point3f max = new Point3f(
            Math.min(maxCoord, center.x + radius + expansion),
            Math.min(maxCoord, center.y + radius + expansion),
            Math.min(maxCoord, center.z + radius + expansion)
        );
        
        // Step 3: Convert AABB corners to Morton keys
        long minMortonCode = Constants.calculateMortonIndex(min, level);
        long maxMortonCode = Constants.calculateMortonIndex(max, level);
        
        // Ensure proper ordering (min <= max)
        if (minMortonCode > maxMortonCode) {
            long tmp = minMortonCode;
            minMortonCode = maxMortonCode;
            maxMortonCode = tmp;
        }
        
        // Step 4: Create inclusive range
        // For subMap(), we need [lower, upper) so increment upper bound
        var lowerBound = new MortonKey(minMortonCode, level);
        
        // Increment upper bound for exclusive upper range in subMap()
        // Handle overflow by using max possible Morton code at this level
        long upperMortonCode;
        if (maxMortonCode == Long.MAX_VALUE) {
            upperMortonCode = Long.MAX_VALUE;
        } else {
            upperMortonCode = maxMortonCode + 1;
        }
        var upperBound = new MortonKey(upperMortonCode, level);
        
        return new SFCRange(lowerBound, upperBound);
    }
}
