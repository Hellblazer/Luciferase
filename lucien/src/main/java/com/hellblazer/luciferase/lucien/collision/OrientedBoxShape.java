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
 * Oriented bounding box (OBB) collision shape for narrow-phase collision detection.
 * Represents a box that can be rotated arbitrarily in 3D space.
 *
 * @author hal.hildebrand
 */
public class OrientedBoxShape extends CollisionShape {
    
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
    
    public Vector3f getHalfExtents() {
        return new Vector3f(halfExtents);
    }
    
    public Matrix3f getOrientation() {
        return new Matrix3f(orientation);
    }
    
    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
    }
    
    /**
     * Transform a point from world space to local (box) space
     */
    public Point3f worldToLocal(Point3f worldPoint) {
        Vector3f relative = new Vector3f();
        relative.sub(worldPoint, position);
        
        Vector3f local = new Vector3f();
        inverseOrientation.transform(relative, local);
        
        return new Point3f(local);
    }
    
    /**
     * Transform a point from local (box) space to world space
     */
    public Point3f localToWorld(Point3f localPoint) {
        Vector3f transformed = new Vector3f(localPoint);
        orientation.transform(transformed);
        
        Point3f result = new Point3f(transformed);
        result.add(position);
        
        return result;
    }
    
    /**
     * Get closest point in local space
     */
    public Point3f getClosestPointLocal(Point3f localPoint) {
        float x = Math.max(-halfExtents.x, Math.min(localPoint.x, halfExtents.x));
        float y = Math.max(-halfExtents.y, Math.min(localPoint.y, halfExtents.y));
        float z = Math.max(-halfExtents.z, Math.min(localPoint.z, halfExtents.z));
        return new Point3f(x, y, z);
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
        
        return worldNormal;
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
    
    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return other.collidesWithOrientedBox(this);
    }
    
    @Override
    public CollisionResult collidesWithSphere(SphereShape sphere) {
        // Delegate to sphere but flip normal
        CollisionResult result = sphere.collidesWithOrientedBox(this);
        if (result.collides) {
            Vector3f flippedNormal = new Vector3f(result.contactNormal);
            flippedNormal.scale(-1);
            return CollisionResult.collision(result.contactPoint, flippedNormal, result.penetrationDepth);
        }
        return result;
    }
    
    @Override
    public CollisionResult collidesWithBox(BoxShape box) {
        // Delegate to box implementation (SAT simplified)
        CollisionResult result = box.collidesWithOrientedBox(this);
        if (result.collides) {
            Vector3f flippedNormal = new Vector3f(result.contactNormal);
            flippedNormal.scale(-1);
            return CollisionResult.collision(result.contactPoint, flippedNormal, result.penetrationDepth);
        }
        return result;
    }
    
    @Override
    public CollisionResult collidesWithOrientedBox(OrientedBoxShape other) {
        // Simplified OBB-OBB collision using Separating Axis Theorem (SAT)
        // Check separation along 15 potential axes:
        // 3 from this OBB, 3 from other OBB, 9 from cross products
        
        // For now, use AABB approximation
        EntityBounds thisAABB = getAABB();
        EntityBounds otherAABB = other.getAABB();
        
        if (!boundsIntersect(thisAABB, otherAABB)) {
            return CollisionResult.noCollision();
        }
        
        // Simplified collision response
        Vector3f delta = new Vector3f();
        delta.sub(other.position, this.position);
        
        Vector3f normal = new Vector3f(delta);
        if (normal.length() > 0) {
            normal.normalize();
        } else {
            normal.set(1, 0, 0);
        }
        
        // Approximate contact point
        Point3f contactPoint = new Point3f();
        contactPoint.interpolate(this.position, other.position, 0.5f);
        
        float penetrationDepth = 0.1f; // Placeholder
        
        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }
    
    @Override
    public CollisionResult collidesWithCapsule(CapsuleShape capsule) {
        // Transform capsule endpoints to local space
        Point3f localP1 = worldToLocal(capsule.getEndpoint1());
        Point3f localP2 = worldToLocal(capsule.getEndpoint2());
        
        // Find closest point on box to line segment in local space
        Point3f closestOnSegment = getClosestPointOnSegment(localP1, localP2, new Point3f(0, 0, 0));
        Point3f closestOnBox = getClosestPointLocal(closestOnSegment);
        
        // Transform back to world space
        Point3f worldClosestOnSegment = localToWorld(closestOnSegment);
        Point3f worldClosestOnBox = localToWorld(closestOnBox);
        
        // Check distance
        Vector3f delta = new Vector3f();
        delta.sub(worldClosestOnSegment, worldClosestOnBox);
        float distance = delta.length();
        
        if (distance > capsule.getRadius()) {
            return CollisionResult.noCollision();
        }
        
        Vector3f normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            normal = getClosestFaceNormalWorld(worldClosestOnSegment);
        }
        
        float penetrationDepth = capsule.getRadius() - distance;
        
        return CollisionResult.collision(worldClosestOnBox, normal, penetrationDepth);
    }
    
    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        // Transform ray to local space
        Point3f localOrigin = worldToLocal(ray.origin());
        Vector3f localDirection = new Vector3f(ray.direction());
        inverseOrientation.transform(localDirection);
        
        // Perform AABB ray intersection in local space
        float tmin = 0.0f;
        float tmax = ray.maxDistance();
        
        for (int i = 0; i < 3; i++) {
            float origin = getComponent(localOrigin, i);
            float direction = getComponent(localDirection, i);
            float min = -getComponent(halfExtents, i);
            float max = getComponent(halfExtents, i);
            
            if (Math.abs(direction) < 1e-6f) {
                if (origin < min || origin > max) {
                    return RayIntersectionResult.noIntersection();
                }
            } else {
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
        
        // Transform intersection back to world space
        Point3f localIntersection = new Point3f(localOrigin);
        localIntersection.scaleAdd(tmin, localDirection, localIntersection);
        Point3f worldIntersection = localToWorld(localIntersection);
        
        // Calculate normal
        Vector3f localNormal = calculateLocalNormal(localIntersection);
        Vector3f worldNormal = new Vector3f();
        orientation.transform(localNormal, worldNormal);
        worldNormal.normalize();
        
        return RayIntersectionResult.intersection(tmin, worldIntersection, worldNormal);
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
        
        // Transform to world space
        return localToWorld(localSupport);
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
    
    private Vector3f calculateLocalNormal(Point3f localPoint) {
        // Determine which face was hit based on position
        float epsilon = 0.001f;
        
        if (Math.abs(localPoint.x + halfExtents.x) < epsilon) {
            return new Vector3f(-1, 0, 0);
        } else if (Math.abs(localPoint.x - halfExtents.x) < epsilon) {
            return new Vector3f(1, 0, 0);
        } else if (Math.abs(localPoint.y + halfExtents.y) < epsilon) {
            return new Vector3f(0, -1, 0);
        } else if (Math.abs(localPoint.y - halfExtents.y) < epsilon) {
            return new Vector3f(0, 1, 0);
        } else if (Math.abs(localPoint.z + halfExtents.z) < epsilon) {
            return new Vector3f(0, 0, -1);
        } else if (Math.abs(localPoint.z - halfExtents.z) < epsilon) {
            return new Vector3f(0, 0, 1);
        }
        
        return new Vector3f(1, 0, 0);
    }
    
    private Point3f getClosestPointOnSegment(Point3f p1, Point3f p2, Point3f point) {
        Vector3f v = new Vector3f();
        v.sub(p2, p1);
        
        if (v.lengthSquared() < 1e-6f) {
            return new Point3f(p1);
        }
        
        Vector3f w = new Vector3f();
        w.sub(point, p1);
        
        float t = w.dot(v) / v.dot(v);
        t = Math.max(0, Math.min(1, t));
        
        Point3f result = new Point3f();
        result.scaleAdd(t, v, p1);
        
        return result;
    }
}