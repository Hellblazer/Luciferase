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
     * Factory method to create a MortonKey from coordinates and level. This is a convenience method that delegates to
     * Morton curve calculation.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @param level the hierarchical level
     * @return a new MortonKey
     */
    public static MortonKey fromCoordinates(int x, int y, int z, byte level) {
        // Use the Constants method which properly handles level encoding
        var point = new javax.vecmath.Point3f(x, y, z);
        var mortonCode = Constants.calculateMortonIndex(point, level);
        return new MortonKey(mortonCode);
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
}
