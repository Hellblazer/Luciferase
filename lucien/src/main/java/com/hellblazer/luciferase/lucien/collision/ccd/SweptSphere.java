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
package com.hellblazer.luciferase.lucien.collision.ccd;

import com.hellblazer.luciferase.lucien.collision.*;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Swept sphere collision detection algorithms.
 * Provides precise continuous collision detection for spheres.
 *
 * @author hal.hildebrand
 */
public class SweptSphere {
    
    private static final float EPSILON = 0.0001f;
    
    /**
     * Swept sphere vs static box
     */
    public static ContinuousCollisionResult sweptSphereVsBox(Point3f sphereStart, Vector3f sphereVelocity,
                                                            float sphereRadius, BoxShape box) {
        // Expand box by sphere radius
        var expandedMin = new Point3f(box.getAABB().getMin());
        expandedMin.x -= sphereRadius;
        expandedMin.y -= sphereRadius;
        expandedMin.z -= sphereRadius;
        
        var expandedMax = new Point3f(box.getAABB().getMax());
        expandedMax.x += sphereRadius;
        expandedMax.y += sphereRadius;
        expandedMax.z += sphereRadius;
        
        // Note: expandedBounds variable removed as it's not used
        
        // Ray-AABB intersection with sphere center ray
        float tMin = 0.0f;
        float tMax = 1.0f;
        
        // For each axis
        for (int axis = 0; axis < 3; axis++) {
            float origin = getComponent(sphereStart, axis);
            float velocity = getComponent(sphereVelocity, axis);
            float min = getComponent(expandedMin, axis);
            float max = getComponent(expandedMax, axis);
            
            if (Math.abs(velocity) < EPSILON) {
                // Ray parallel to slab
                if (origin < min || origin > max) {
                    return ContinuousCollisionResult.noCollision();
                }
            } else {
                // Compute intersection times
                float t1 = (min - origin) / velocity;
                float t2 = (max - origin) / velocity;
                
                if (t1 > t2) {
                    float temp = t1;
                    t1 = t2;
                    t2 = temp;
                }
                
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                
                if (tMin > tMax) {
                    return ContinuousCollisionResult.noCollision();
                }
            }
        }
        
        if (tMin > 1.0f || tMax < 0.0f) {
            return ContinuousCollisionResult.noCollision();
        }
        
        // Clamp tMin to valid range
        tMin = Math.max(0.0f, tMin);
        
        // Calculate contact point and normal
        var contactCenter = new Point3f(sphereStart);
        var motion = new Vector3f(sphereVelocity);
        motion.scale(tMin);
        contactCenter.add(motion);
        
        // Find closest point on original box
        var closestPoint = box.getClosestPoint(contactCenter);
        
        // Contact normal points from box to sphere
        var normal = new Vector3f();
        normal.sub(contactCenter, closestPoint);
        if (normal.length() > EPSILON) {
            normal.normalize();
        } else {
            // Sphere center exactly on box surface, use velocity direction
            normal.set(sphereVelocity);
            normal.normalize();
            normal.scale(-1);
        }
        
        // Contact point is on sphere surface
        var contactPoint = new Point3f(contactCenter);
        var toSurface = new Vector3f(normal);
        toSurface.scale(-sphereRadius);
        contactPoint.add(toSurface);
        
        return ContinuousCollisionResult.collision(tMin, contactPoint, normal, 0.0f);
    }
    
    /**
     * Swept sphere vs static triangle
     */
    public static ContinuousCollisionResult sweptSphereVsTriangle(Point3f sphereStart, Vector3f sphereVelocity,
                                                                 float sphereRadius, Point3f v0, Point3f v1, Point3f v2) {
        // Compute triangle normal
        var edge1 = new Vector3f();
        edge1.sub(v1, v0);
        var edge2 = new Vector3f();
        edge2.sub(v2, v0);
        var triangleNormal = new Vector3f();
        triangleNormal.cross(edge1, edge2);
        triangleNormal.normalize();
        
        // First, check collision with triangle plane
        float planeDist = v0.x * triangleNormal.x + v0.y * triangleNormal.y + v0.z * triangleNormal.z;
        
        // Distance from sphere center to plane
        float startDist = sphereStart.x * triangleNormal.x + sphereStart.y * triangleNormal.y + 
                         sphereStart.z * triangleNormal.z - planeDist;
        
        // Velocity component along normal
        float velocityDotNormal = sphereVelocity.dot(triangleNormal);
        
        if (Math.abs(velocityDotNormal) < EPSILON) {
            // Moving parallel to plane
            if (Math.abs(startDist) > sphereRadius) {
                return ContinuousCollisionResult.noCollision();
            }
        }
        
        // Time when sphere touches plane
        float t = (sphereRadius - startDist) / velocityDotNormal;
        if (velocityDotNormal > 0) {
            t = (-sphereRadius - startDist) / velocityDotNormal;
        }
        
        if (t < 0 || t > 1) {
            return ContinuousCollisionResult.noCollision();
        }
        
        // Find intersection point on plane
        var planeIntersection = new Point3f(sphereStart);
        var motion = new Vector3f(sphereVelocity);
        motion.scale(t);
        planeIntersection.add(motion);
        
        // Project onto plane
        var projected = new Point3f(planeIntersection);
        var toPlane = new Vector3f(triangleNormal);
        toPlane.scale(sphereRadius * (velocityDotNormal > 0 ? 1 : -1));
        projected.sub(toPlane);
        
        // Check if projected point is inside triangle
        if (isPointInTriangle(projected, v0, v1, v2)) {
            // Contact point is on sphere surface
            var contactPoint = new Point3f(planeIntersection);
            var toSurface = new Vector3f(triangleNormal);
            toSurface.scale(-sphereRadius);
            contactPoint.add(toSurface);
            return ContinuousCollisionResult.collision(t, contactPoint, triangleNormal, 0.0f);
        }
        
        // Check collision with triangle edges
        ContinuousCollisionResult closestEdge = checkTriangleEdges(sphereStart, sphereVelocity, sphereRadius, v0, v1, v2);
        if (closestEdge.collides()) {
            return closestEdge;
        }
        
        // Check collision with triangle vertices
        return checkTriangleVertices(sphereStart, sphereVelocity, sphereRadius, v0, v1, v2);
    }
    
    /**
     * Swept sphere vs static capsule
     */
    public static ContinuousCollisionResult sweptSphereVsCapsule(Point3f sphereStart, Vector3f sphereVelocity,
                                                                float sphereRadius, CapsuleShape capsule) {
        var p1 = capsule.getEndpoint1();
        var p2 = capsule.getEndpoint2();
        float capsuleRadius = capsule.getRadius();
        float combinedRadius = sphereRadius + capsuleRadius;
        
        // Vector along capsule axis
        var capsuleAxis = new Vector3f();
        capsuleAxis.sub(p2, p1);
        float capsuleLength = capsuleAxis.length();
        
        if (capsuleLength < EPSILON) {
            // Degenerate capsule, treat as sphere
            return sweptSphereVsStaticSphere(sphereStart, sphereVelocity, sphereRadius, 
                                            p1, capsuleRadius);
        }
        
        capsuleAxis.normalize();
        
        // Relative position and velocity
        var relPos = new Vector3f();
        relPos.sub(sphereStart, p1);
        
        // Project onto capsule axis
        float projPos = relPos.dot(capsuleAxis);
        float projVel = sphereVelocity.dot(capsuleAxis);
        
        // Perpendicular component
        var perpPos = new Vector3f(relPos);
        var axisPart = new Vector3f(capsuleAxis);
        axisPart.scale(projPos);
        perpPos.sub(axisPart);
        
        var perpVel = new Vector3f(sphereVelocity);
        axisPart.set(capsuleAxis);
        axisPart.scale(projVel);
        perpVel.sub(axisPart);
        
        // Solve for time of impact
        float a = perpVel.dot(perpVel);
        float b = 2.0f * perpPos.dot(perpVel);
        float c = perpPos.dot(perpPos) - combinedRadius * combinedRadius;
        
        if (Math.abs(a) < EPSILON) {
            // Moving along capsule axis
            if (c > 0) {
                return ContinuousCollisionResult.noCollision();
            }
        } else {
            float discriminant = b * b - 4 * a * c;
            if (discriminant < 0) {
                return ContinuousCollisionResult.noCollision();
            }
            
            float sqrtDisc = (float) Math.sqrt(discriminant);
            float t1 = (-b - sqrtDisc) / (2 * a);
            float t2 = (-b + sqrtDisc) / (2 * a);
            
            float t = -1;
            if (t1 >= 0 && t1 <= 1) {
                t = t1;
            } else if (t2 >= 0 && t2 <= 1) {
                t = t2;
            }
            
            if (t >= 0) {
                // Check if collision point is within capsule segment
                float axialPos = projPos + projVel * t;
                
                if (axialPos >= 0 && axialPos <= capsuleLength) {
                    // Collision with cylinder part
                    var contactPos = new Point3f(sphereStart);
                    var motion = new Vector3f(sphereVelocity);
                    motion.scale(t);
                    contactPos.add(motion);
                    
                    var closestOnCapsule = new Point3f(p1);
                    var axisOffset = new Vector3f(capsuleAxis);
                    axisOffset.scale(axialPos);
                    closestOnCapsule.add(axisOffset);
                    
                    var normal = new Vector3f();
                    normal.sub(contactPos, closestOnCapsule);
                    normal.normalize();
                    
                    var contactPoint = new Point3f(contactPos);
                    var toSurface = new Vector3f(normal);
                    toSurface.scale(-sphereRadius);
                    contactPoint.add(toSurface);
                    
                    return ContinuousCollisionResult.collision(t, contactPoint, normal, 0.0f);
                }
            }
        }
        
        // Check end caps
        var result1 = sweptSphereVsStaticSphere(sphereStart, sphereVelocity, sphereRadius, p1, capsuleRadius);
        var result2 = sweptSphereVsStaticSphere(sphereStart, sphereVelocity, sphereRadius, p2, capsuleRadius);
        
        if (result1.happensBefore(result2)) {
            return result1;
        }
        return result2;
    }
    
    /**
     * Swept sphere vs static sphere
     */
    private static ContinuousCollisionResult sweptSphereVsStaticSphere(Point3f movingCenter, Vector3f velocity,
                                                                      float movingRadius, Point3f staticCenter, 
                                                                      float staticRadius) {
        var relPos = new Vector3f();
        relPos.sub(movingCenter, staticCenter);
        
        float combinedRadius = movingRadius + staticRadius;
        
        // Quadratic equation
        float a = velocity.dot(velocity);
        float b = 2.0f * relPos.dot(velocity);
        float c = relPos.dot(relPos) - combinedRadius * combinedRadius;
        
        if (Math.abs(a) < EPSILON) {
            if (c <= 0) {
                // Already colliding
                var normal = new Vector3f(relPos);
                normal.normalize();
                var contactPoint = new Point3f(movingCenter);
                var toSurface = new Vector3f(normal);
                toSurface.scale(-movingRadius);
                contactPoint.add(toSurface);
                return ContinuousCollisionResult.collision(0.0f, contactPoint, normal, 0.0f);
            }
            return ContinuousCollisionResult.noCollision();
        }
        
        float discriminant = b * b - 4 * a * c;
        if (discriminant < 0) {
            return ContinuousCollisionResult.noCollision();
        }
        
        float sqrtDisc = (float) Math.sqrt(discriminant);
        float t1 = (-b - sqrtDisc) / (2 * a);
        float t2 = (-b + sqrtDisc) / (2 * a);
        
        float t = -1;
        if (t1 >= 0 && t1 <= 1) {
            t = t1;
        } else if (t2 >= 0 && t2 <= 1) {
            t = t2;
        }
        
        if (t < 0) {
            return ContinuousCollisionResult.noCollision();
        }
        
        var contactCenter = new Point3f(movingCenter);
        var motion = new Vector3f(velocity);
        motion.scale(t);
        contactCenter.add(motion);
        
        var normal = new Vector3f();
        normal.sub(contactCenter, staticCenter);
        normal.normalize();
        
        var contactPoint = new Point3f(contactCenter);
        var toSurface = new Vector3f(normal);
        toSurface.scale(-movingRadius);
        contactPoint.add(toSurface);
        
        return ContinuousCollisionResult.collision(t, contactPoint, normal, 0.0f);
    }
    
    // Helper methods
    private static float getComponent(Point3f point, int axis) {
        return switch (axis) {
            case 0 -> point.x;
            case 1 -> point.y;
            case 2 -> point.z;
            default -> 0;
        };
    }
    
    private static float getComponent(Vector3f vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x;
            case 1 -> vector.y;
            case 2 -> vector.z;
            default -> 0;
        };
    }
    
    private static boolean isPointInTriangle(Point3f p, Point3f v0, Point3f v1, Point3f v2) {
        var v0v1 = new Vector3f();
        v0v1.sub(v1, v0);
        var v0v2 = new Vector3f();
        v0v2.sub(v2, v0);
        var v0p = new Vector3f();
        v0p.sub(p, v0);
        
        float dot00 = v0v1.dot(v0v1);
        float dot01 = v0v1.dot(v0v2);
        float dot02 = v0v1.dot(v0p);
        float dot11 = v0v2.dot(v0v2);
        float dot12 = v0v2.dot(v0p);
        
        float invDenom = 1 / (dot00 * dot11 - dot01 * dot01);
        float u = (dot11 * dot02 - dot01 * dot12) * invDenom;
        float v = (dot00 * dot12 - dot01 * dot02) * invDenom;
        
        return (u >= 0) && (v >= 0) && (u + v <= 1);
    }
    
    private static ContinuousCollisionResult checkTriangleEdges(Point3f sphereStart, Vector3f sphereVelocity,
                                                              float sphereRadius, Point3f v0, Point3f v1, Point3f v2) {
        var result1 = sweptSphereVsLineSegment(sphereStart, sphereVelocity, sphereRadius, v0, v1);
        var result2 = sweptSphereVsLineSegment(sphereStart, sphereVelocity, sphereRadius, v1, v2);
        var result3 = sweptSphereVsLineSegment(sphereStart, sphereVelocity, sphereRadius, v2, v0);
        
        ContinuousCollisionResult earliest = result1;
        if (result2.happensBefore(earliest)) earliest = result2;
        if (result3.happensBefore(earliest)) earliest = result3;
        
        return earliest;
    }
    
    private static ContinuousCollisionResult checkTriangleVertices(Point3f sphereStart, Vector3f sphereVelocity,
                                                                 float sphereRadius, Point3f v0, Point3f v1, Point3f v2) {
        var result1 = sweptSphereVsStaticSphere(sphereStart, sphereVelocity, sphereRadius, v0, 0);
        var result2 = sweptSphereVsStaticSphere(sphereStart, sphereVelocity, sphereRadius, v1, 0);
        var result3 = sweptSphereVsStaticSphere(sphereStart, sphereVelocity, sphereRadius, v2, 0);
        
        ContinuousCollisionResult earliest = result1;
        if (result2.happensBefore(earliest)) earliest = result2;
        if (result3.happensBefore(earliest)) earliest = result3;
        
        return earliest;
    }
    
    private static ContinuousCollisionResult sweptSphereVsLineSegment(Point3f sphereStart, Vector3f sphereVelocity,
                                                                     float sphereRadius, Point3f p1, Point3f p2) {
        // This is simplified - a full implementation would handle the swept sphere vs swept line segment case
        var midPoint = new Point3f();
        midPoint.interpolate(p1, p2, 0.5f);
        return sweptSphereVsStaticSphere(sphereStart, sphereVelocity, sphereRadius, midPoint, 0);
    }
}