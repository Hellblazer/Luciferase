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

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Capsule collision shape for narrow-phase collision detection. A capsule is a cylinder with hemispherical caps on both
 * ends.
 *
 * @author hal.hildebrand
 */
public final class CapsuleShape extends CollisionShape {

    private final Point3f endpoint1;
    private final Point3f endpoint2;
    private final float   radius;
    private final float   height;

    /**
     * Create a capsule from two endpoints and a radius
     */
    public CapsuleShape(Point3f endpoint1, Point3f endpoint2, float radius) {
        super(calculateCenter(endpoint1, endpoint2));
        this.endpoint1 = new Point3f(endpoint1);
        this.endpoint2 = new Point3f(endpoint2);
        this.radius = radius;
        this.height = endpoint1.distance(endpoint2);
    }

    /**
     * Create a vertical capsule centered at position
     */
    public CapsuleShape(Point3f center, float height, float radius) {
        super(center);
        this.radius = radius;
        this.height = height;

        // Create vertical endpoints
        float halfHeight = height / 2;
        this.endpoint1 = new Point3f(center.x, center.y - halfHeight, center.z);
        this.endpoint2 = new Point3f(center.x, center.y + halfHeight, center.z);
    }

    private static Point3f calculateCenter(Point3f p1, Point3f p2) {
        Point3f center = new Point3f();
        center.interpolate(p1, p2, 0.5f);
        return center;
    }

    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return CollisionDetector.detectCollision(this, other);
    }

    @Override
    public EntityBounds getAABB() {
        float minX = Math.min(endpoint1.x, endpoint2.x) - radius;
        float minY = Math.min(endpoint1.y, endpoint2.y) - radius;
        float minZ = Math.min(endpoint1.z, endpoint2.z) - radius;
        float maxX = Math.max(endpoint1.x, endpoint2.x) + radius;
        float maxY = Math.max(endpoint1.y, endpoint2.y) + radius;
        float maxZ = Math.max(endpoint1.z, endpoint2.z) + radius;

        Point3f min = new Point3f(minX, minY, minZ);
        Point3f max = new Point3f(maxX, maxY, maxZ);
        return new EntityBounds(min, max);
    }

    /**
     * Get the closest point on the capsule's line segment to a given point
     */
    public Point3f getClosestPointOnSegment(Point3f point) {
        Vector3f v = new Vector3f();
        v.sub(endpoint2, endpoint1);

        if (v.lengthSquared() < 1e-6f) {
            return new Point3f(endpoint1);
        }

        Vector3f w = new Vector3f();
        w.sub(point, endpoint1);

        float t = w.dot(v) / v.dot(v);
        t = Math.max(0, Math.min(1, t));

        Point3f result = new Point3f();
        result.scaleAdd(t, v, endpoint1);

        return result;
    }

    public Point3f getEndpoint1() {
        return new Point3f(endpoint1);
    }

    public Point3f getEndpoint2() {
        return new Point3f(endpoint2);
    }

    public float getHeight() {
        return height;
    }
    
    public float getHalfHeight() {
        return height / 2;
    }

    /**
     * Get a perpendicular direction to the capsule axis
     */
    public Vector3f getPerpendicularDirection() {
        Vector3f axis = new Vector3f();
        axis.sub(endpoint2, endpoint1);
        axis.normalize();

        // Find a perpendicular vector
        Vector3f perpendicular = new Vector3f();
        if (Math.abs(axis.x) < 0.9) {
            perpendicular.set(1, 0, 0);
        } else {
            perpendicular.set(0, 1, 0);
        }

        Vector3f result = new Vector3f();
        result.cross(axis, perpendicular);
        result.normalize();

        return result;
    }

    public float getRadius() {
        return radius;
    }

    @Override
    public Point3f getSupport(Vector3f direction) {
        // Project endpoints onto direction
        float dot1 = endpoint1.x * direction.x + endpoint1.y * direction.y + endpoint1.z * direction.z;
        float dot2 = endpoint2.x * direction.x + endpoint2.y * direction.y + endpoint2.z * direction.z;

        // Choose endpoint with larger projection
        Point3f basePoint = (dot1 > dot2) ? endpoint1 : endpoint2;

        // Add radius along direction
        Vector3f normalizedDir = new Vector3f(direction);
        normalizedDir.normalize();

        Point3f support = new Point3f(basePoint);
        support.x += normalizedDir.x * radius;
        support.y += normalizedDir.y * radius;
        support.z += normalizedDir.z * radius;

        return support;
    }

    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        // Ray-capsule intersection
        Vector3f ab = new Vector3f();
        ab.sub(endpoint2, endpoint1);
        Vector3f ao = new Vector3f();
        ao.sub(ray.origin(), endpoint1);

        float ab_dot_ab = ab.dot(ab);
        float ab_dot_ao = ab.dot(ao);
        float ab_dot_dir = ab.dot(ray.direction());
        float ao_dot_ao = ao.dot(ao);
        float ao_dot_dir = ao.dot(ray.direction());

        float m = ab_dot_ao / ab_dot_ab;
        float n = ab_dot_dir / ab_dot_ab;

        float a = ray.direction().dot(ray.direction()) - n * n * ab_dot_ab;
        float b = 2 * (ao_dot_dir - n * (ab_dot_ao - m * ab_dot_ab));
        float c = ao_dot_ao - 2 * m * ab_dot_ao + m * m * ab_dot_ab - radius * radius;

        float discriminant = b * b - 4 * a * c;
        if (discriminant < 0) {
            return RayIntersectionResult.noIntersection();
        }

        float sqrtDiscriminant = (float) Math.sqrt(discriminant);
        float t1 = (-b - sqrtDiscriminant) / (2 * a);
        float t2 = (-b + sqrtDiscriminant) / (2 * a);

        // Find valid t
        float t = -1;
        if (t1 >= 0 && t1 <= ray.maxDistance()) {
            float s = m + n * t1;
            if (s >= 0 && s <= 1) {
                t = t1;
            } else {
                // Check sphere caps
                t = checkSphereCap(ray, s < 0.5f ? endpoint1 : endpoint2);
            }
        }

        if (t < 0 && t2 >= 0 && t2 <= ray.maxDistance()) {
            float s = m + n * t2;
            if (s >= 0 && s <= 1) {
                t = t2;
            }
        }

        if (t < 0) {
            return RayIntersectionResult.noIntersection();
        }

        Point3f intersectionPoint = ray.pointAt(t);
        Point3f closestOnSegment = getClosestPointOnSegment(intersectionPoint);
        Vector3f normal = new Vector3f();
        normal.sub(intersectionPoint, closestOnSegment);
        normal.normalize();

        return RayIntersectionResult.intersection(t, intersectionPoint, normal);
    }

    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
        endpoint1.add(delta);
        endpoint2.add(delta);
    }

    private float checkSphereCap(Ray3D ray, Point3f sphereCenter) {
        Vector3f oc = new Vector3f();
        oc.sub(ray.origin(), sphereCenter);

        float a = ray.direction().dot(ray.direction());
        float b = 2.0f * oc.dot(ray.direction());
        float c = oc.dot(oc) - radius * radius;

        float discriminant = b * b - 4 * a * c;
        if (discriminant < 0) {
            return -1;
        }

        float sqrtDiscriminant = (float) Math.sqrt(discriminant);
        float t1 = (-b - sqrtDiscriminant) / (2 * a);
        float t2 = (-b + sqrtDiscriminant) / (2 * a);

        if (t1 >= 0 && t1 <= ray.maxDistance()) {
            return t1;
        } else if (t2 >= 0 && t2 <= ray.maxDistance()) {
            return t2;
        }

        return -1;
    }
}