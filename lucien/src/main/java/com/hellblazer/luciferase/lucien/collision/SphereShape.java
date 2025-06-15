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
 * Sphere collision shape for narrow-phase collision detection.
 *
 * @author hal.hildebrand
 */
public class SphereShape extends CollisionShape {
    
    private final float radius;
    
    public SphereShape(Point3f center, float radius) {
        super(center);
        if (radius <= 0) {
            throw new IllegalArgumentException("Radius must be positive");
        }
        this.radius = radius;
    }
    
    public float getRadius() {
        return radius;
    }
    
    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
    }
    
    @Override
    public EntityBounds getAABB() {
        return new EntityBounds(position, radius);
    }
    
    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return other.collidesWithSphere(this);
    }
    
    @Override
    public CollisionResult collidesWithSphere(SphereShape other) {
        Vector3f delta = new Vector3f();
        delta.sub(other.position, this.position);
        float distance = delta.length();
        float radiusSum = this.radius + other.radius;
        
        if (distance > radiusSum) {
            return CollisionResult.noCollision();
        }
        
        // Normalize delta for contact normal
        Vector3f normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            // Spheres are at same position, use arbitrary normal
            normal.set(1, 0, 0);
        }
        
        // Contact point is on the surface of this sphere
        Point3f contactPoint = new Point3f(normal);
        contactPoint.scale(this.radius);
        contactPoint.add(this.position);
        
        float penetrationDepth = radiusSum - distance;
        
        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }
    
    @Override
    public CollisionResult collidesWithBox(BoxShape box) {
        // Sphere-Box collision
        Point3f closestPoint = box.getClosestPoint(this.position);
        
        Vector3f delta = new Vector3f();
        delta.sub(this.position, closestPoint);
        float distanceSquared = delta.lengthSquared();
        float radiusSquared = radius * radius;
        
        if (distanceSquared > radiusSquared) {
            return CollisionResult.noCollision();
        }
        
        float distance = (float) Math.sqrt(distanceSquared);
        Vector3f normal = new Vector3f(delta);
        
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            // Center is inside box, find closest face
            normal = box.getClosestFaceNormal(this.position);
        }
        
        Point3f contactPoint = new Point3f(closestPoint);
        float penetrationDepth = radius - distance;
        
        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }
    
    @Override
    public CollisionResult collidesWithOrientedBox(OrientedBoxShape obb) {
        // Transform sphere center to OBB's local space
        Point3f localCenter = obb.worldToLocal(this.position);
        
        // Find closest point in local space
        Point3f localClosest = obb.getClosestPointLocal(localCenter);
        
        // Transform back to world space
        Point3f worldClosest = obb.localToWorld(localClosest);
        
        // Check distance
        Vector3f delta = new Vector3f();
        delta.sub(this.position, worldClosest);
        float distanceSquared = delta.lengthSquared();
        float radiusSquared = radius * radius;
        
        if (distanceSquared > radiusSquared) {
            return CollisionResult.noCollision();
        }
        
        float distance = (float) Math.sqrt(distanceSquared);
        Vector3f normal = new Vector3f(delta);
        
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            // Center is inside OBB, find closest face normal
            normal = obb.getClosestFaceNormalWorld(this.position);
        }
        
        Point3f contactPoint = new Point3f(worldClosest);
        float penetrationDepth = radius - distance;
        
        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }
    
    @Override
    public CollisionResult collidesWithCapsule(CapsuleShape capsule) {
        // Find closest point on capsule's line segment
        Point3f closestOnSegment = capsule.getClosestPointOnSegment(this.position);
        
        // Check sphere-sphere collision
        Vector3f delta = new Vector3f();
        delta.sub(this.position, closestOnSegment);
        float distance = delta.length();
        float radiusSum = this.radius + capsule.getRadius();
        
        if (distance > radiusSum) {
            return CollisionResult.noCollision();
        }
        
        Vector3f normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            // Use perpendicular to capsule axis
            normal = capsule.getPerpendicularDirection();
        }
        
        Point3f contactPoint = new Point3f(normal);
        contactPoint.scale(this.radius);
        contactPoint.add(this.position);
        
        float penetrationDepth = radiusSum - distance;
        
        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }
    
    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        Vector3f oc = new Vector3f();
        oc.sub(ray.origin(), position);
        
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
        normal.sub(intersectionPoint, position);
        normal.normalize();
        
        return RayIntersectionResult.intersection(t, intersectionPoint, normal);
    }
    
    @Override
    public Point3f getSupport(Vector3f direction) {
        Vector3f normalizedDir = new Vector3f(direction);
        normalizedDir.normalize();
        
        Point3f support = new Point3f(normalizedDir);
        support.scale(radius);
        support.add(position);
        
        return support;
    }
}