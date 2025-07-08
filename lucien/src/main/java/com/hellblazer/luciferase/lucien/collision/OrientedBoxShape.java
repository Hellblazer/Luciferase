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

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Oriented bounding box (OBB) collision shape for narrow-phase collision detection. Represents a box that can be
 * rotated arbitrarily in 3D space.
 *
 * @author hal.hildebrand
 */
public final class OrientedBoxShape extends CollisionShape {

    private final Vector3f halfExtents;
    private final Matrix3f orientation;
    private final Matrix3f inverseOrientation;

    public OrientedBoxShape(Point3f center, Vector3f halfExtents, Matrix3f orientation) {
        super(center);
        this.halfExtents = new Vector3f(halfExtents);
        this.orientation = new Matrix3f(orientation);
        this.inverseOrientation = new Matrix3f(orientation);
        this.inverseOrientation.transpose(); // For rotation matrices, transpose = inverse
    }

    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return CollisionDetector.detectCollision(this, other);
    }

    @Override
    public EntityBounds getAABB() {
        // Compute the 8 corners of the OBB
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (int i = 0; i < 8; i++) {
            float localX = ((i & 1) == 0) ? -halfExtents.x : halfExtents.x;
            float localY = ((i & 2) == 0) ? -halfExtents.y : halfExtents.y;
            float localZ = ((i & 4) == 0) ? -halfExtents.z : halfExtents.z;

            Point3f worldCorner = localToWorld(new Point3f(localX, localY, localZ));

            minX = Math.min(minX, worldCorner.x);
            minY = Math.min(minY, worldCorner.y);
            minZ = Math.min(minZ, worldCorner.z);
            maxX = Math.max(maxX, worldCorner.x);
            maxY = Math.max(maxY, worldCorner.y);
            maxZ = Math.max(maxZ, worldCorner.z);
        }

        Point3f min = new Point3f(minX, minY, minZ);
        Point3f max = new Point3f(maxX, maxY, maxZ);
        return new EntityBounds(min, max);
    }

    /**
     * Get closest face normal in world space
     */
    public Vector3f getClosestFaceNormalWorld(Point3f worldPoint) {
        Point3f localPoint = worldToLocal(worldPoint);

        // Find which face is closest in local space
        float[] distances = new float[6];
        distances[0] = localPoint.x + halfExtents.x; // Left face
        distances[1] = halfExtents.x - localPoint.x; // Right face
        distances[2] = localPoint.y + halfExtents.y; // Bottom face
        distances[3] = halfExtents.y - localPoint.y; // Top face
        distances[4] = localPoint.z + halfExtents.z; // Back face
        distances[5] = halfExtents.z - localPoint.z; // Front face

        int minIndex = 0;
        float minDistance = distances[0];
        for (int i = 1; i < 6; i++) {
            if (distances[i] < minDistance) {
                minDistance = distances[i];
                minIndex = i;
            }
        }

        // Get local normal
        Vector3f localNormal = switch (minIndex) {
            case 0 -> new Vector3f(-1, 0, 0);
            case 1 -> new Vector3f(1, 0, 0);
            case 2 -> new Vector3f(0, -1, 0);
            case 3 -> new Vector3f(0, 1, 0);
            case 4 -> new Vector3f(0, 0, -1);
            case 5 -> new Vector3f(0, 0, 1);
            default -> new Vector3f(1, 0, 0);
        };

        // Transform to world space
        Vector3f worldNormal = new Vector3f();
        orientation.transform(localNormal, worldNormal);
        worldNormal.normalize();

        return worldNormal;
    }

    /**
     * Get closest point on OBB in local space
     */
    public Point3f getClosestPointLocal(Point3f localPoint) {
        float x = Math.max(-halfExtents.x, Math.min(localPoint.x, halfExtents.x));
        float y = Math.max(-halfExtents.y, Math.min(localPoint.y, halfExtents.y));
        float z = Math.max(-halfExtents.z, Math.min(localPoint.z, halfExtents.z));
        return new Point3f(x, y, z);
    }

    /**
     * Get closest point on OBB in world space
     */
    public Point3f getClosestPointWorld(Point3f worldPoint) {
        Point3f localPoint = worldToLocal(worldPoint);
        Point3f localClosest = getClosestPointLocal(localPoint);
        return localToWorld(localClosest);
    }

    public Vector3f getHalfExtents() {
        return new Vector3f(halfExtents);
    }

    public Matrix3f getOrientation() {
        return new Matrix3f(orientation);
    }

    @Override
    public Point3f getSupport(Vector3f direction) {
        // Transform direction to local space
        Vector3f localDir = new Vector3f();
        inverseOrientation.transform(direction, localDir);

        // Get support in local space
        Point3f localSupport = new Point3f();
        localSupport.x = (localDir.x >= 0) ? halfExtents.x : -halfExtents.x;
        localSupport.y = (localDir.y >= 0) ? halfExtents.y : -halfExtents.y;
        localSupport.z = (localDir.z >= 0) ? halfExtents.z : -halfExtents.z;

        // Transform back to world space
        return localToWorld(localSupport);
    }

    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        // Transform ray to local space
        Point3f localOrigin = worldToLocal(ray.origin());
        Vector3f localDir = new Vector3f();
        inverseOrientation.transform(ray.direction(), localDir);

        Ray3D localRay = new Ray3D(localOrigin, localDir, ray.maxDistance());

        // Create local AABB
        BoxShape localBox = new BoxShape(new Point3f(0, 0, 0), halfExtents);

        // Intersect with local box
        RayIntersectionResult localResult = localBox.intersectRay(localRay);

        if (!localResult.intersects) {
            return RayIntersectionResult.noIntersection();
        }

        // Transform result back to world space
        Point3f worldIntersection = localToWorld(localResult.intersectionPoint);
        Vector3f worldNormal = new Vector3f();
        orientation.transform(localResult.normal, worldNormal);
        worldNormal.normalize();

        return RayIntersectionResult.intersection(localResult.distance, worldIntersection, worldNormal);
    }

    /**
     * Transform point from local to world space
     */
    public Point3f localToWorld(Point3f localPoint) {
        Point3f worldPoint = new Point3f();
        orientation.transform(localPoint, worldPoint);
        worldPoint.add(position);
        return worldPoint;
    }

    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
    }

    /**
     * Transform point from world to local space
     */
    public Point3f worldToLocal(Point3f worldPoint) {
        Point3f relative = new Point3f(worldPoint);
        relative.sub(position);
        Point3f localPoint = new Point3f();
        inverseOrientation.transform(relative, localPoint);
        return localPoint;
    }
}