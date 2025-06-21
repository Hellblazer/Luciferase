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
package com.hellblazer.luciferase.lucien.entity;

import javax.vecmath.Point3f;

/**
 * Represents the bounding box of an entity for spanning calculations. Entities with bounds can span multiple octree
 * nodes.
 *
 * @author hal.hildebrand
 */
public class EntityBounds {
    private final Point3f min;
    private final Point3f max;

    /**
     * Create bounds from min and max points
     */
    public EntityBounds(Point3f min, Point3f max) {
        this.min = new Point3f(min);
        this.max = new Point3f(max);
    }

    /**
     * Create bounds from center and radius (sphere)
     */
    public EntityBounds(Point3f center, float radius) {
        this.min = new Point3f(center.x - radius, center.y - radius, center.z - radius);
        this.max = new Point3f(center.x + radius, center.y + radius, center.z + radius);
    }

    /**
     * Create bounds from center and half-extents
     */
    public EntityBounds(Point3f center, float halfWidth, float halfHeight, float halfDepth) {
        this.min = new Point3f(center.x - halfWidth, center.y - halfHeight, center.z - halfDepth);
        this.max = new Point3f(center.x + halfWidth, center.y + halfHeight, center.z + halfDepth);
    }

    /**
     * Create point bounds (no extent)
     */
    public static EntityBounds point(Point3f position) {
        return new EntityBounds(position, position);
    }

    /**
     * Check if this bounds is completely contained within a cube
     */
    public boolean containedInCube(float cubeX, float cubeY, float cubeZ, float cubeSize) {
        float cubeMaxX = cubeX + cubeSize;
        float cubeMaxY = cubeY + cubeSize;
        float cubeMaxZ = cubeZ + cubeSize;

        return min.x >= cubeX && max.x <= cubeMaxX && min.y >= cubeY && max.y <= cubeMaxY && min.z >= cubeZ
        && max.z <= cubeMaxZ;
    }

    /**
     * Get the center point of the bounds
     */
    public Point3f getCenter() {
        return new Point3f((min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2);
    }

    public Point3f getMax() {
        return new Point3f(max);
    }

    public float getMaxX() {
        return max.x;
    }

    public float getMaxY() {
        return max.y;
    }

    public float getMaxZ() {
        return max.z;
    }

    public Point3f getMin() {
        return new Point3f(min);
    }

    public float getMinX() {
        return min.x;
    }

    public float getMinY() {
        return min.y;
    }

    public float getMinZ() {
        return min.z;
    }

    /**
     * Check if this bounds intersects with a cube defined by origin and size
     */
    public boolean intersectsCube(float cubeX, float cubeY, float cubeZ, float cubeSize) {
        float cubeMaxX = cubeX + cubeSize;
        float cubeMaxY = cubeY + cubeSize;
        float cubeMaxZ = cubeZ + cubeSize;

        return !(max.x < cubeX || min.x > cubeMaxX || max.y < cubeY || min.y > cubeMaxY || max.z < cubeZ
                 || min.z > cubeMaxZ);
    }

    @Override
    public String toString() {
        return String.format("EntityBounds[min=(%.2f,%.2f,%.2f), max=(%.2f,%.2f,%.2f)]", min.x, min.y, min.z, max.x,
                             max.y, max.z);
    }
}
