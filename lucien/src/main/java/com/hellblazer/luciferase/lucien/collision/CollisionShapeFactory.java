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
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Factory for creating collision shapes from entity bounds and metadata.
 *
 * @author hal.hildebrand
 */
public class CollisionShapeFactory {

    /**
     * Create a box shape from a center point and half extents
     */
    public static BoxShape createBox(Point3f center, Vector3f halfExtents) {
        return new BoxShape(center, halfExtents);
    }

    /**
     * Create a capsule shape from endpoints and radius
     */
    public static CapsuleShape createCapsule(Point3f endpoint1, Point3f endpoint2, float radius) {
        return new CapsuleShape(endpoint1, endpoint2, radius);
    }

    /**
     * Try to create an optimal collision shape from bounds. Analyzes the bounds to determine if a sphere might be more
     * appropriate.
     */
    public static CollisionShape createOptimalShape(EntityBounds bounds) {
        if (bounds == null) {
            throw new IllegalArgumentException("Bounds cannot be null");
        }

        float width = bounds.getMaxX() - bounds.getMinX();
        float height = bounds.getMaxY() - bounds.getMinY();
        float depth = bounds.getMaxZ() - bounds.getMinZ();

        // Check if bounds are roughly spherical
        float avg = (width + height + depth) / 3;
        float tolerance = 0.1f; // 10% tolerance

        if (Math.abs(width - avg) / avg < tolerance && Math.abs(height - avg) / avg < tolerance && Math.abs(depth - avg)
        / avg < tolerance) {
            // Create sphere
            float centerX = (bounds.getMinX() + bounds.getMaxX()) / 2;
            float centerY = (bounds.getMinY() + bounds.getMaxY()) / 2;
            float centerZ = (bounds.getMinZ() + bounds.getMaxZ()) / 2;
            Point3f center = new Point3f(centerX, centerY, centerZ);
            float radius = avg / 2;
            return new SphereShape(center, radius);
        }

        // Otherwise create box
        return fromBounds(bounds);
    }

    /**
     * Create an oriented box shape
     */
    public static OrientedBoxShape createOrientedBox(Point3f center, Vector3f halfExtents, Matrix3f orientation) {
        return new OrientedBoxShape(center, halfExtents, orientation);
    }

    /**
     * Create a sphere shape from a center point and radius
     */
    public static SphereShape createSphere(Point3f center, float radius) {
        return new SphereShape(center, radius);
    }

    /**
     * Create a vertical capsule shape
     */
    public static CapsuleShape createVerticalCapsule(Point3f center, float height, float radius) {
        return new CapsuleShape(center, height, radius);
    }

    /**
     * Create a collision shape from entity bounds. By default, creates a BoxShape from the AABB.
     */
    public static CollisionShape fromBounds(EntityBounds bounds) {
        if (bounds == null) {
            throw new IllegalArgumentException("Bounds cannot be null");
        }

        // Calculate center and half extents
        float centerX = (bounds.getMinX() + bounds.getMaxX()) / 2;
        float centerY = (bounds.getMinY() + bounds.getMaxY()) / 2;
        float centerZ = (bounds.getMinZ() + bounds.getMaxZ()) / 2;
        Point3f center = new Point3f(centerX, centerY, centerZ);

        float halfWidth = (bounds.getMaxX() - bounds.getMinX()) / 2;
        float halfHeight = (bounds.getMaxY() - bounds.getMinY()) / 2;
        float halfDepth = (bounds.getMaxZ() - bounds.getMinZ()) / 2;

        return new BoxShape(center, halfWidth, halfHeight, halfDepth);
    }
}
