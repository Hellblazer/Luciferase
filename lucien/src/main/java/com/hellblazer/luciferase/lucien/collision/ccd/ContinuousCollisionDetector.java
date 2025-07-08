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
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Continuous collision detection implementation.
 * Detects collisions between moving shapes over a time interval.
 *
 * @author hal.hildebrand
 */
public class ContinuousCollisionDetector {
    
    private static final float EPSILON = 0.0001f;
    private static final int MAX_ITERATIONS = 20;
    
    /**
     * Detect collision between two moving shapes
     */
    public static ContinuousCollisionResult detectCollision(MovingShape shape1, MovingShape shape2) {
        // If neither shape is moving, use discrete collision detection
        if (!shape1.isMoving() && !shape2.isMoving()) {
            var result = shape1.getShape().collidesWith(shape2.getShape());
            if (result.collides) {
                return ContinuousCollisionResult.collision(0.0f, result.contactPoint, 
                                                         result.contactNormal, result.penetrationDepth);
            }
            return ContinuousCollisionResult.noCollision();
        }
        
        // Use appropriate algorithm based on shape types
        return switch (shape1.getShape()) {
            case SphereShape sphere1 -> switch (shape2.getShape()) {
                case SphereShape sphere2 -> sphereVsSphereCCD(shape1, shape2);
                case BoxShape box -> sphereVsBoxCCD(shape1, shape2);
                case CapsuleShape capsule -> sphereVsCapsuleCCD(shape1, shape2);
                default -> conservativeCCD(shape1, shape2);
            };
            case CapsuleShape capsule1 -> switch (shape2.getShape()) {
                case SphereShape sphere -> flipResult(sphereVsCapsuleCCD(shape2, shape1));
                case CapsuleShape capsule2 -> capsuleVsCapsuleCCD(shape1, shape2);
                default -> conservativeCCD(shape1, shape2);
            };
            default -> conservativeCCD(shape1, shape2);
        };
    }
    
    /**
     * Swept sphere vs sphere collision detection
     */
    private static ContinuousCollisionResult sphereVsSphereCCD(MovingShape movingSphere1, MovingShape movingSphere2) {
        var sphere1 = (SphereShape) movingSphere1.getShape();
        var sphere2 = (SphereShape) movingSphere2.getShape();
        
        // Relative motion
        var relativeVelocity = new Vector3f(movingSphere1.getLinearVelocity());
        relativeVelocity.sub(movingSphere2.getLinearVelocity());
        
        var startRelativePos = new Vector3f();
        startRelativePos.sub(movingSphere1.getStartPosition(), movingSphere2.getStartPosition());
        
        float radiusSum = sphere1.getRadius() + sphere2.getRadius();
        
        // Solve quadratic equation for time of impact
        float a = relativeVelocity.dot(relativeVelocity);
        float b = 2.0f * startRelativePos.dot(relativeVelocity);
        float c = startRelativePos.dot(startRelativePos) - radiusSum * radiusSum;
        
        // No relative motion
        if (Math.abs(a) < EPSILON) {
            if (c <= 0) {
                // Already colliding
                return ContinuousCollisionResult.collision(0.0f, sphere1.getPosition(), 
                                                         new Vector3f(0, 1, 0), -c);
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
        
        // Find first time of impact (earliest positive time)
        float toi = -1;
        if (t1 >= 0 && t1 <= 1 && t2 >= 0 && t2 <= 1) {
            toi = Math.min(t1, t2);
        } else if (t1 >= 0 && t1 <= 1) {
            toi = t1;
        } else if (t2 >= 0 && t2 <= 1) {
            toi = t2;
        }
        
        if (toi < 0) {
            return ContinuousCollisionResult.noCollision();
        }
        
        // Calculate contact point and normal at time of impact
        var pos1 = movingSphere1.getPositionAtTime(toi);
        var pos2 = movingSphere2.getPositionAtTime(toi);
        
        var normal = new Vector3f();
        normal.sub(pos1, pos2);
        normal.normalize();
        
        var contactPoint = new Point3f(pos1);
        var toContact = new Vector3f(normal);
        toContact.scale(-sphere1.getRadius());
        contactPoint.add(toContact);
        
        return ContinuousCollisionResult.collision(toi, contactPoint, normal, 0.0f);
    }
    
    /**
     * Swept sphere vs box collision detection
     */
    private static ContinuousCollisionResult sphereVsBoxCCD(MovingShape movingSphere, MovingShape movingBox) {
        // Use conservative advancement or ray-cast approximation
        return conservativeCCD(movingSphere, movingBox);
    }
    
    /**
     * Swept sphere vs capsule collision detection
     */
    private static ContinuousCollisionResult sphereVsCapsuleCCD(MovingShape movingSphere, MovingShape movingCapsule) {
        // Simplified implementation - treat capsule as thick line segment
        return conservativeCCD(movingSphere, movingCapsule);
    }
    
    /**
     * Swept capsule vs capsule collision detection
     */
    private static ContinuousCollisionResult capsuleVsCapsuleCCD(MovingShape movingCapsule1, MovingShape movingCapsule2) {
        // Simplified implementation
        return conservativeCCD(movingCapsule1, movingCapsule2);
    }
    
    /**
     * Conservative advancement algorithm for general shapes
     */
    private static ContinuousCollisionResult conservativeCCD(MovingShape shape1, MovingShape shape2) {
        float tMin = 0.0f;
        float tMax = 1.0f;
        float currentTime = 0.0f;
        
        // Binary search for time of impact
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            float midTime = (tMin + tMax) / 2.0f;
            
            // Get shapes at mid time
            var pos1 = shape1.getPositionAtTime(midTime);
            var pos2 = shape2.getPositionAtTime(midTime);
            
            // Translate shapes to test positions
            var testShape1 = shape1.getShape();
            var testShape2 = shape2.getShape();
            
            var delta1 = new Vector3f();
            delta1.sub(pos1, shape1.getShape().getPosition());
            testShape1.translate(delta1);
            
            var delta2 = new Vector3f();
            delta2.sub(pos2, shape2.getShape().getPosition());
            testShape2.translate(delta2);
            
            // Test collision
            var result = testShape1.collidesWith(testShape2);
            
            // Restore original positions
            delta1.scale(-1);
            delta2.scale(-1);
            testShape1.translate(delta1);
            testShape2.translate(delta2);
            
            if (result.collides) {
                tMax = midTime;
                if (tMax - tMin < EPSILON) {
                    return ContinuousCollisionResult.collision(midTime, result.contactPoint,
                                                             result.contactNormal, result.penetrationDepth);
                }
            } else {
                tMin = midTime;
            }
        }
        
        return ContinuousCollisionResult.noCollision();
    }
    
    /**
     * Flip the result for symmetric collision detection
     */
    private static ContinuousCollisionResult flipResult(ContinuousCollisionResult result) {
        if (!result.collides()) {
            return result;
        }
        
        var flippedNormal = new Vector3f(result.contactNormal());
        flippedNormal.scale(-1);
        
        return ContinuousCollisionResult.collision(result.timeOfImpact(), result.contactPoint(),
                                                 flippedNormal, result.penetrationDepth());
    }
    
    /**
     * Ray vs moving sphere collision detection
     */
    public static ContinuousCollisionResult rayVsMovingSphereCCD(Point3f rayOrigin, Vector3f rayDirection,
                                                               float maxDistance, MovingShape movingSphere) {
        var sphere = (SphereShape) movingSphere.getShape();
        
        // This is a simplified static approach - for a proper implementation
        // we would need to solve the full ray vs moving sphere equation
        var startPos = movingSphere.getStartPosition();
        var endPos = movingSphere.getEndPosition();
        
        // Test at multiple time steps
        int steps = 10;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float)steps;
            var spherePos = movingSphere.getPositionAtTime(t);
            
            // Ray-sphere intersection test
            var toSphere = new Vector3f();
            toSphere.sub(spherePos, rayOrigin);
            float projLength = toSphere.dot(rayDirection);
            
            if (projLength >= 0 && projLength <= maxDistance) {
                var projPoint = new Point3f(rayOrigin);
                var delta = new Vector3f(rayDirection);
                delta.scale(projLength);
                projPoint.add(delta);
                
                var dist = new Vector3f();
                dist.sub(spherePos, projPoint);
                
                if (dist.length() <= sphere.getRadius()) {
                    var normal = new Vector3f(dist);
                    normal.normalize();
                    return ContinuousCollisionResult.collision(t, projPoint, normal, 0.0f);
                }
            }
        }
        
        return ContinuousCollisionResult.noCollision();
    }
}