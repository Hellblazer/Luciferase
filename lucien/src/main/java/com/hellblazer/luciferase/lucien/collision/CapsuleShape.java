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
public class CapsuleShape extends CollisionShape {

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
        return other.collidesWithCapsule(this);
    }

    @Override
    public CollisionResult collidesWithBox(BoxShape box) {
        // Delegate to box but flip normal
        CollisionResult result = box.collidesWithCapsule(this);
        if (result.collides) {
            Vector3f flippedNormal = new Vector3f(result.contactNormal);
            flippedNormal.scale(-1);
            return CollisionResult.collision(result.contactPoint, flippedNormal, result.penetrationDepth);
        }
        return result;
    }

    @Override
    public CollisionResult collidesWithCapsule(CapsuleShape other) {
        // Find closest points on both line segments
        Point3f closestThis = findClosestPointsBetweenSegments(this.endpoint1, this.endpoint2, other.endpoint1,
                                                               other.endpoint2);

        Point3f closestOther = other.getClosestPointOnSegment(closestThis);
        closestThis = this.getClosestPointOnSegment(closestOther);

        // Check sphere-sphere collision
        Vector3f delta = new Vector3f();
        delta.sub(closestOther, closestThis);
        float distance = delta.length();
        float radiusSum = this.radius + other.radius;

        if (distance > radiusSum) {
            return CollisionResult.noCollision();
        }

        Vector3f normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            // Use perpendicular to one of the axes
            normal = this.getPerpendicularDirection();
        }

        // Contact point between the two capsules
        Point3f contactPoint = new Point3f();
        contactPoint.interpolate(closestThis, closestOther, this.radius / radiusSum);

        float penetrationDepth = radiusSum - distance;

        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }

    @Override
    public CollisionResult collidesWithOrientedBox(OrientedBoxShape obb) {
        // Delegate to OBB but flip normal
        CollisionResult result = obb.collidesWithCapsule(this);
        if (result.collides) {
            Vector3f flippedNormal = new Vector3f(result.contactNormal);
            flippedNormal.scale(-1);
            return CollisionResult.collision(result.contactPoint, flippedNormal, result.penetrationDepth);
        }
        return result;
    }

    @Override
    public CollisionResult collidesWithSphere(SphereShape sphere) {
        // Delegate to sphere but flip normal
        CollisionResult result = sphere.collidesWithCapsule(this);
        if (result.collides) {
            Vector3f flippedNormal = new Vector3f(result.contactNormal);
            flippedNormal.scale(-1);
            return CollisionResult.collision(result.contactPoint, flippedNormal, result.penetrationDepth);
        }
        return result;
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

    /**
     * Get a perpendicular direction to the capsule axis
     */
    public Vector3f getPerpendicularDirection() {
        Vector3f axis = new Vector3f();
        axis.sub(endpoint2, endpoint1);
        axis.normalize();

        // Find a perpendicular vector
        Vector3f perpendicular = new Vector3f();
        if (Math.abs(axis.x) < 0.9f) {
            perpendicular.set(1, 0, 0);
        } else {
            perpendicular.set(0, 1, 0);
        }

        // Make it truly perpendicular
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
        // Find which endpoint gives better support
        float dot1 = direction.dot(new Vector3f(endpoint1));
        float dot2 = direction.dot(new Vector3f(endpoint2));

        Point3f basePoint = (dot1 > dot2) ? endpoint1 : endpoint2;

        // Add radius in the direction
        Vector3f normalizedDir = new Vector3f(direction);
        normalizedDir.normalize();

        Point3f support = new Point3f(normalizedDir);
        support.scale(radius);
        support.add(basePoint);

        return support;
    }

    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        // Ray-capsule intersection is ray-cylinder + ray-sphere at endpoints

        // First, check ray-cylinder intersection
        Vector3f axis = new Vector3f();
        axis.sub(endpoint2, endpoint1);
        axis.normalize();

        // Project ray onto cylinder axis
        Vector3f toOrigin = new Vector3f();
        toOrigin.sub(ray.origin(), endpoint1);

        Vector3f perpOrigin = new Vector3f(toOrigin);
        float dotAxis = toOrigin.dot(axis);
        perpOrigin.scaleAdd(-dotAxis, axis, perpOrigin);

        Vector3f perpDir = new Vector3f(ray.direction());
        float dirDotAxis = ray.direction().dot(axis);
        perpDir.scaleAdd(-dirDotAxis, axis, perpDir);

        // Solve quadratic for cylinder intersection
        float a = perpDir.dot(perpDir);
        float b = 2 * perpOrigin.dot(perpDir);
        float c = perpOrigin.dot(perpOrigin) - radius * radius;

        float discriminant = b * b - 4 * a * c;

        float tMin = Float.MAX_VALUE;

        if (discriminant >= 0 && Math.abs(a) > 1e-6f) {
            float sqrtDiscriminant = (float) Math.sqrt(discriminant);
            float t1 = (-b - sqrtDiscriminant) / (2 * a);
            float t2 = (-b + sqrtDiscriminant) / (2 * a);

            // Check if intersections are within cylinder height
            for (float t : new float[] { t1, t2 }) {
                if (t >= 0 && t <= ray.maxDistance()) {
                    Point3f hitPoint = ray.pointAt(t);
                    Point3f closestOnSegment = getClosestPointOnSegment(hitPoint);

                    if (hitPoint.distance(closestOnSegment) <= radius + 1e-6f) {
                        tMin = Math.min(tMin, t);
                    }
                }
            }
        }

        // Check sphere intersections at endpoints
        for (Point3f endpoint : new Point3f[] { endpoint1, endpoint2 }) {
            RayIntersectionResult sphereResult = raySphereIntersection(ray, endpoint, radius);
            if (sphereResult.intersects && sphereResult.distance < tMin) {
                tMin = sphereResult.distance;
            }
        }

        if (tMin == Float.MAX_VALUE) {
            return RayIntersectionResult.noIntersection();
        }

        Point3f intersectionPoint = ray.pointAt(tMin);

        // Calculate normal
        Point3f closestOnSegment = getClosestPointOnSegment(intersectionPoint);
        Vector3f normal = new Vector3f();
        normal.sub(intersectionPoint, closestOnSegment);

        if (normal.length() > 1e-6f) {
            normal.normalize();
        } else {
            // Hit exactly on the axis, use perpendicular
            normal = getPerpendicularDirection();
        }

        return RayIntersectionResult.intersection(tMin, intersectionPoint, normal);
    }

    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
        endpoint1.add(delta);
        endpoint2.add(delta);
    }

    private Point3f findClosestPointsBetweenSegments(Point3f p1, Point3f p2, Point3f p3, Point3f p4) {
        Vector3f d1 = new Vector3f();
        d1.sub(p2, p1);

        Vector3f d2 = new Vector3f();
        d2.sub(p4, p3);

        Vector3f r = new Vector3f();
        r.sub(p1, p3);

        float a = d1.dot(d1);
        float e = d2.dot(d2);
        float f = d2.dot(r);

        float s, t;

        if (a <= 1e-6f && e <= 1e-6f) {
            // Both segments are points
            s = 0;
            t = 0;
        } else if (a <= 1e-6f) {
            // First segment is a point
            s = 0;
            t = Math.max(0, Math.min(1, f / e));
        } else if (e <= 1e-6f) {
            // Second segment is a point
            t = 0;
            s = Math.max(0, Math.min(1, -d1.dot(r) / a));
        } else {
            // General case
            float b = d1.dot(d2);
            float c = d1.dot(r);
            float denom = a * e - b * b;

            if (Math.abs(denom) > 1e-6f) {
                s = Math.max(0, Math.min(1, (b * f - c * e) / denom));
            } else {
                s = 0;
            }

            t = (b * s + f) / e;
            t = Math.max(0, Math.min(1, t));

            // Recompute s for this t
            s = (t * b - c) / a;
            s = Math.max(0, Math.min(1, s));
        }

        Point3f result = new Point3f();
        result.scaleAdd(s, d1, p1);

        return result;
    }

    private RayIntersectionResult raySphereIntersection(Ray3D ray, Point3f center, float radius) {
        Vector3f oc = new Vector3f();
        oc.sub(ray.origin(), center);

        float a = ray.direction().dot(ray.direction());
        float b = 2.0f * oc.dot(ray.direction());
        float c = oc.dot(oc) - radius * radius;

        float discriminant = b * b - 4 * a * c;
        if (discriminant < 0) {
            return RayIntersectionResult.noIntersection();
        }

        float sqrtDiscriminant = (float) Math.sqrt(discriminant);
        float t1 = (-b - sqrtDiscriminant) / (2 * a);
        float t2 = (-b + sqrtDiscriminant) / (2 * a);

        float t = -1;
        if (t1 >= 0 && t1 <= ray.maxDistance()) {
            t = t1;
        } else if (t2 >= 0 && t2 <= ray.maxDistance()) {
            t = t2;
        }

        if (t < 0) {
            return RayIntersectionResult.noIntersection();
        }

        Point3f intersectionPoint = ray.pointAt(t);
        Vector3f normal = new Vector3f();
        normal.sub(intersectionPoint, center);
        normal.normalize();

        return RayIntersectionResult.intersection(t, intersectionPoint, normal);
    }
}
