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
package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3f;

/**
 * Represents the axis-aligned bounding box of a spatial volume.
 * This is a shared utility record used by multiple spatial index implementations.
 *
 * @author hal.hildebrand
 */
public record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    
    /**
     * Create VolumeBounds from a Spatial volume
     */
    public static VolumeBounds from(Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> new VolumeBounds(
                cube.originX(), cube.originY(), cube.originZ(),
                cube.originX() + cube.extent(),
                cube.originY() + cube.extent(),
                cube.originZ() + cube.extent()
            );
            case Spatial.Sphere sphere -> new VolumeBounds(
                sphere.centerX() - sphere.radius(),
                sphere.centerY() - sphere.radius(),
                sphere.centerZ() - sphere.radius(),
                sphere.centerX() + sphere.radius(),
                sphere.centerY() + sphere.radius(),
                sphere.centerZ() + sphere.radius()
            );
            case Spatial.aabb aabb -> new VolumeBounds(
                aabb.originX(), aabb.originY(), aabb.originZ(),
                aabb.originX() + aabb.extentX(),
                aabb.originY() + aabb.extentY(),
                aabb.originZ() + aabb.extentZ()
            );
            case Spatial.aabt aabt -> new VolumeBounds(
                aabt.originX(), aabt.originY(), aabt.originZ(),
                aabt.originX() + aabt.extentX(),
                aabt.originY() + aabt.extentY(),
                aabt.originZ() + aabt.extentZ()
            );
            case Spatial.Parallelepiped para -> new VolumeBounds(
                para.originX(), para.originY(), para.originZ(),
                para.originX() + para.extentX(),
                para.originY() + para.extentY(),
                para.originZ() + para.extentZ()
            );
            case Spatial.Tetrahedron tet -> {
                var vertices = new Tuple3f[] { tet.a(), tet.b(), tet.c(), tet.d() };
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
                for (var vertex : vertices) {
                    minX = Math.min(minX, vertex.x);
                    minY = Math.min(minY, vertex.y);
                    minZ = Math.min(minZ, vertex.z);
                    maxX = Math.max(maxX, vertex.x);
                    maxY = Math.max(maxY, vertex.y);
                    maxZ = Math.max(maxZ, vertex.z);
                }
                yield new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
            }
            default -> null;
        };
    }
    
    /**
     * Get the width (X extent) of the bounds
     */
    public float width() {
        return maxX - minX;
    }
    
    /**
     * Get the height (Y extent) of the bounds
     */
    public float height() {
        return maxY - minY;
    }
    
    /**
     * Get the depth (Z extent) of the bounds
     */
    public float depth() {
        return maxZ - minZ;
    }
    
    /**
     * Get the maximum extent across all dimensions
     */
    public float maxExtent() {
        return Math.max(Math.max(width(), height()), depth());
    }
    
    /**
     * Check if a point is contained within these bounds
     */
    public boolean contains(float x, float y, float z) {
        return x >= minX && x <= maxX && 
               y >= minY && y <= maxY && 
               z >= minZ && z <= maxZ;
    }
    
    /**
     * Check if these bounds intersect with another set of bounds
     */
    public boolean intersects(VolumeBounds other) {
        return !(maxX < other.minX || minX > other.maxX ||
                 maxY < other.minY || minY > other.maxY ||
                 maxZ < other.minZ || minZ > other.maxZ);
    }
}