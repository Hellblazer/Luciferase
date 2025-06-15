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
 * Axis-aligned box collision shape for narrow-phase collision detection.
 *
 * @author hal.hildebrand
 */
public class BoxShape extends CollisionShape {
    
    private final Vector3f halfExtents;
    private EntityBounds bounds;
    
    public BoxShape(Point3f center, Vector3f halfExtents) {
        super(center);
        this.halfExtents = new Vector3f(halfExtents);
        this.bounds = new EntityBounds(center, halfExtents.x, halfExtents.y, halfExtents.z);
    }
    
    public BoxShape(Point3f center, float halfWidth, float halfHeight, float halfDepth) {
        this(center, new Vector3f(halfWidth, halfHeight, halfDepth));
    }
    
    public Vector3f getHalfExtents() {
        return new Vector3f(halfExtents);
    }
    
    @Override
    public EntityBounds getAABB() {
        return bounds;
    }
    
    /**
     * Get the closest point on this box to a given point
     */
    public Point3f getClosestPoint(Point3f point) {
        float x = Math.max(bounds.getMinX(), Math.min(point.x, bounds.getMaxX()));
        float y = Math.max(bounds.getMinY(), Math.min(point.y, bounds.getMaxY()));
        float z = Math.max(bounds.getMinZ(), Math.min(point.z, bounds.getMaxZ()));
        return new Point3f(x, y, z);
    }
    
    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
        // Update bounds
        Point3f min = new Point3f(bounds.getMinX() + delta.x, bounds.getMinY() + delta.y, bounds.getMinZ() + delta.z);
        Point3f max = new Point3f(bounds.getMaxX() + delta.x, bounds.getMaxY() + delta.y, bounds.getMaxZ() + delta.z);
        this.bounds = new EntityBounds(min, max);
    }
    
    /**
     * Get the normal of the closest face to a point (assumes point is inside)
     */
    public Vector3f getClosestFaceNormal(Point3f point) {
        // Find which face is closest
        float[] distances = new float[6];
        distances[0] = point.x - bounds.getMinX(); // Left face
        distances[1] = bounds.getMaxX() - point.x; // Right face
        distances[2] = point.y - bounds.getMinY(); // Bottom face
        distances[3] = bounds.getMaxY() - point.y; // Top face
        distances[4] = point.z - bounds.getMinZ(); // Back face
        distances[5] = bounds.getMaxZ() - point.z; // Front face
        
        int minIndex = 0;
        float minDistance = distances[0];
        for (int i = 1; i < 6; i++) {
            if (distances[i] < minDistance) {
                minDistance = distances[i];
                minIndex = i;
            }
        }
        
        return switch (minIndex) {
            case 0 -> new Vector3f(-1, 0, 0);
            case 1 -> new Vector3f(1, 0, 0);
            case 2 -> new Vector3f(0, -1, 0);
            case 3 -> new Vector3f(0, 1, 0);
            case 4 -> new Vector3f(0, 0, -1);
            case 5 -> new Vector3f(0, 0, 1);
            default -> new Vector3f(1, 0, 0);
        };
    }
    
    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return other.collidesWithBox(this);
    }
    
    @Override
    public CollisionResult collidesWithSphere(SphereShape sphere) {
        // Delegate to sphere implementation but flip the normal
        CollisionResult result = sphere.collidesWithBox(this);
        if (result.collides) {
            Vector3f flippedNormal = new Vector3f(result.contactNormal);
            flippedNormal.scale(-1);
            return CollisionResult.collision(result.contactPoint, flippedNormal, result.penetrationDepth);
        }
        return result;
    }
    
    @Override
    public CollisionResult collidesWithBox(BoxShape other) {
        // Check AABB-AABB collision
        if (!boundsIntersect(this.bounds, other.bounds)) {
            return CollisionResult.noCollision();
        }
        
        // Calculate overlap on each axis
        float xOverlap = Math.min(bounds.getMaxX() - other.bounds.getMinX(), 
                                  other.bounds.getMaxX() - bounds.getMinX());
        float yOverlap = Math.min(bounds.getMaxY() - other.bounds.getMinY(), 
                                  other.bounds.getMaxY() - bounds.getMinY());
        float zOverlap = Math.min(bounds.getMaxZ() - other.bounds.getMinZ(), 
                                  other.bounds.getMaxZ() - bounds.getMinZ());
        
        // Find minimum overlap axis (separation axis)
        float minOverlap = xOverlap;
        int axis = 0;
        
        if (yOverlap < minOverlap) {
            minOverlap = yOverlap;
            axis = 1;
        }
        
        if (zOverlap < minOverlap) {
            minOverlap = zOverlap;
            axis = 2;
        }
        
        // Determine contact normal direction
        Vector3f normal = new Vector3f();
        Point3f contactPoint = new Point3f();
        
        switch (axis) {
            case 0 -> { // X-axis
                if (position.x < other.position.x) {
                    normal.set(1, 0, 0);
                    contactPoint.x = bounds.getMaxX();
                } else {
                    normal.set(-1, 0, 0);
                    contactPoint.x = bounds.getMinX();
                }
                contactPoint.y = Math.max(bounds.getMinY(), other.bounds.getMinY()) +
                                 Math.min(bounds.getMaxY() - bounds.getMinY(), 
                                         other.bounds.getMaxY() - other.bounds.getMinY()) / 2;
                contactPoint.z = Math.max(bounds.getMinZ(), other.bounds.getMinZ()) +
                                 Math.min(bounds.getMaxZ() - bounds.getMinZ(), 
                                         other.bounds.getMaxZ() - other.bounds.getMinZ()) / 2;
            }
            case 1 -> { // Y-axis
                if (position.y < other.position.y) {
                    normal.set(0, 1, 0);
                    contactPoint.y = bounds.getMaxY();
                } else {
                    normal.set(0, -1, 0);
                    contactPoint.y = bounds.getMinY();
                }
                contactPoint.x = Math.max(bounds.getMinX(), other.bounds.getMinX()) +
                                 Math.min(bounds.getMaxX() - bounds.getMinX(), 
                                         other.bounds.getMaxX() - other.bounds.getMinX()) / 2;
                contactPoint.z = Math.max(bounds.getMinZ(), other.bounds.getMinZ()) +
                                 Math.min(bounds.getMaxZ() - bounds.getMinZ(), 
                                         other.bounds.getMaxZ() - other.bounds.getMinZ()) / 2;
            }
            case 2 -> { // Z-axis
                if (position.z < other.position.z) {
                    normal.set(0, 0, 1);
                    contactPoint.z = bounds.getMaxZ();
                } else {
                    normal.set(0, 0, -1);
                    contactPoint.z = bounds.getMinZ();
                }
                contactPoint.x = Math.max(bounds.getMinX(), other.bounds.getMinX()) +
                                 Math.min(bounds.getMaxX() - bounds.getMinX(), 
                                         other.bounds.getMaxX() - other.bounds.getMinX()) / 2;
                contactPoint.y = Math.max(bounds.getMinY(), other.bounds.getMinY()) +
                                 Math.min(bounds.getMaxY() - bounds.getMinY(), 
                                         other.bounds.getMaxY() - other.bounds.getMinY()) / 2;
            }
        }
        
        return CollisionResult.collision(contactPoint, normal, minOverlap);
    }
    
    @Override
    public CollisionResult collidesWithOrientedBox(OrientedBoxShape obb) {
        // Use Separating Axis Theorem (SAT)
        // For now, use simple AABB vs AABB test with OBB's AABB
        EntityBounds obbAABB = obb.getAABB();
        if (!boundsIntersect(this.bounds, obbAABB)) {
            return CollisionResult.noCollision();
        }
        
        // Simplified collision - treat OBB as AABB
        // TODO: Implement proper SAT test
        Vector3f delta = new Vector3f();
        delta.sub(obb.getPosition(), this.position);
        
        Vector3f normal = new Vector3f(delta);
        normal.normalize();
        
        Point3f contactPoint = getClosestPoint(obb.getPosition());
        float penetrationDepth = 0.1f; // Placeholder
        
        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }
    
    @Override
    public CollisionResult collidesWithCapsule(CapsuleShape capsule) {
        // Find closest point on box to capsule's line segment
        Point3f p1 = capsule.getEndpoint1();
        Point3f p2 = capsule.getEndpoint2();
        
        // Clamp line segment to box
        Point3f closest1 = getClosestPoint(p1);
        Point3f closest2 = getClosestPoint(p2);
        
        // Find closest point on clamped segment
        Point3f closestOnSegment = capsule.getClosestPointOnSegment(position);
        Point3f closestOnBox = getClosestPoint(closestOnSegment);
        
        // Check distance
        Vector3f delta = new Vector3f();
        delta.sub(closestOnSegment, closestOnBox);
        float distance = delta.length();
        
        if (distance > capsule.getRadius()) {
            return CollisionResult.noCollision();
        }
        
        Vector3f normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            normal = getClosestFaceNormal(closestOnSegment);
        }
        
        float penetrationDepth = capsule.getRadius() - distance;
        
        return CollisionResult.collision(closestOnBox, normal, penetrationDepth);
    }
    
    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        float tmin = 0.0f;
        float tmax = ray.maxDistance();
        
        // For each axis
        for (int i = 0; i < 3; i++) {
            float origin = getComponent(ray.origin(), i);
            float direction = getComponent(ray.direction(), i);
            float min = getComponent(bounds, i, true);
            float max = getComponent(bounds, i, false);
            
            if (Math.abs(direction) < 1e-6f) {
                // Ray is parallel to slab
                if (origin < min || origin > max) {
                    return RayIntersectionResult.noIntersection();
                }
            } else {
                // Compute intersection distances
                float t1 = (min - origin) / direction;
                float t2 = (max - origin) / direction;
                
                if (t1 > t2) {
                    float temp = t1;
                    t1 = t2;
                    t2 = temp;
                }
                
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                
                if (tmin > tmax) {
                    return RayIntersectionResult.noIntersection();
                }
            }
        }
        
        Point3f intersectionPoint = ray.pointAt(tmin);
        Vector3f normal = calculateRayIntersectionNormal(intersectionPoint);
        
        return RayIntersectionResult.intersection(tmin, intersectionPoint, normal);
    }
    
    @Override
    public Point3f getSupport(Vector3f direction) {
        Point3f support = new Point3f(position);
        
        support.x += (direction.x >= 0) ? halfExtents.x : -halfExtents.x;
        support.y += (direction.y >= 0) ? halfExtents.y : -halfExtents.y;
        support.z += (direction.z >= 0) ? halfExtents.z : -halfExtents.z;
        
        return support;
    }
    
    private boolean boundsIntersect(EntityBounds b1, EntityBounds b2) {
        return b1.getMaxX() >= b2.getMinX() && b1.getMinX() <= b2.getMaxX() &&
               b1.getMaxY() >= b2.getMinY() && b1.getMinY() <= b2.getMaxY() &&
               b1.getMaxZ() >= b2.getMinZ() && b1.getMinZ() <= b2.getMaxZ();
    }
    
    private float getComponent(Point3f point, int axis) {
        return switch (axis) {
            case 0 -> point.x;
            case 1 -> point.y;
            case 2 -> point.z;
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }
    
    private float getComponent(Vector3f vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x;
            case 1 -> vector.y;
            case 2 -> vector.z;
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }
    
    private float getComponent(EntityBounds bounds, int axis, boolean min) {
        return switch (axis) {
            case 0 -> min ? bounds.getMinX() : bounds.getMaxX();
            case 1 -> min ? bounds.getMinY() : bounds.getMaxY();
            case 2 -> min ? bounds.getMinZ() : bounds.getMaxZ();
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }
    
    private Vector3f calculateRayIntersectionNormal(Point3f intersectionPoint) {
        // Determine which face was hit based on position
        float epsilon = 0.001f;
        
        if (Math.abs(intersectionPoint.x - bounds.getMinX()) < epsilon) {
            return new Vector3f(-1, 0, 0);
        } else if (Math.abs(intersectionPoint.x - bounds.getMaxX()) < epsilon) {
            return new Vector3f(1, 0, 0);
        } else if (Math.abs(intersectionPoint.y - bounds.getMinY()) < epsilon) {
            return new Vector3f(0, -1, 0);
        } else if (Math.abs(intersectionPoint.y - bounds.getMaxY()) < epsilon) {
            return new Vector3f(0, 1, 0);
        } else if (Math.abs(intersectionPoint.z - bounds.getMinZ()) < epsilon) {
            return new Vector3f(0, 0, -1);
        } else if (Math.abs(intersectionPoint.z - bounds.getMaxZ()) < epsilon) {
            return new Vector3f(0, 0, 1);
        }
        
        // Fallback
        return new Vector3f(1, 0, 0);
    }
}