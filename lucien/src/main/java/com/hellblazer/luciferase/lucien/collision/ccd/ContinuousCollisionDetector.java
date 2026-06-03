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
    // Fixed scan resolution for the general-shape fallback (Luciferase-fglgp): finds a colliding bracket to bisect.
    private static final int SAMPLE_STEPS   = 32;
    
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
    // Package-private for direct unit testing of the bracket / exhaustion behaviour (Luciferase-fglgp).
    static ContinuousCollisionResult conservativeCCD(MovingShape shape1, MovingShape shape2) {
        // No separation-distance (GJK) query exists for arbitrary shapes, so genuine conservative advancement is
        // not available here. Instead scan [0,1] at a fixed resolution to find the FIRST colliding sample, then
        // bisect within that bracket for a precise time of impact (Luciferase-fglgp). This fixes two defects in the
        // previous blind bisection of [0,1]: (1) a brief mid-interval through-collision whose window excluded the
        // first bisection midpoint was never found (the search only ever narrowed the half containing t=0.5);
        // (2) iteration exhaustion returned noCollision() even when a real hit had already been bracketed.
        //
        // Residual limitation (honest): a collision whose entire window falls strictly between two adjacent scan
        // samples (a shape thinner than its per-step motion) can still be tunnelled. SAMPLE_STEPS bounds that gap;
        // closed-form paths such as rayVsMovingSphereCCD avoid it entirely.

        var atZero = collideAt(shape1, shape2, 0.0f);
        if (atZero.collides) {
            return ContinuousCollisionResult.collision(0.0f, atZero.contactPoint, atZero.contactNormal,
                                                       atZero.penetrationDepth);
        }

        float lastClear = 0.0f;
        for (int i = 1; i <= SAMPLE_STEPS; i++) {
            float t = (float) i / SAMPLE_STEPS;
            var res = collideAt(shape1, shape2, t);
            if (res.collides) {
                return bisectTimeOfImpact(shape1, shape2, lastClear, t); // bracket [lastClear, t]
            }
            lastClear = t;
        }
        return ContinuousCollisionResult.noCollision();
    }

    /** Independent positioned copies at fraction {@code t} (never mutate the live shapes — Luciferase-v2na8). */
    private static CollisionShape.CollisionResult collideAt(MovingShape shape1, MovingShape shape2, float t) {
        return shape1.getShapeAtTime(t).collidesWith(shape2.getShapeAtTime(t));
    }

    /**
     * Bisect within a bracket whose upper end {@code tHit} collides, returning the collision at the converged hit
     * side. Never returns noCollision — a bracketed hit always yields a collision result (Luciferase-fglgp).
     */
    private static ContinuousCollisionResult bisectTimeOfImpact(MovingShape shape1, MovingShape shape2,
                                                                float tClear, float tHit) {
        var hit = collideAt(shape1, shape2, tHit);
        for (int iter = 0; iter < MAX_ITERATIONS && (tHit - tClear) > EPSILON; iter++) {
            float mid = (tClear + tHit) / 2.0f;
            var res = collideAt(shape1, shape2, mid);
            if (res.collides) {
                tHit = mid;
                hit = res;
            } else {
                tClear = mid;
            }
        }
        return ContinuousCollisionResult.collision(tHit, hit.contactPoint, hit.contactNormal, hit.penetrationDepth);
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
     * Ray vs moving sphere collision detection (closed-form, Luciferase-gojpy).
     *
     * <p>Tests proximity of the moving sphere centre to the ray's cylindrical volume (radius = sphere radius,
     * length = {@code maxDistance}). A sphere whose centre is closest to a point beyond {@code maxDistance} but
     * which physically contacts the ray's endpoint cap is not detected — the same cap limitation the previous
     * sampling approach had; the fix removes the between-sample tunnelling, not the cap gap.
     */
    public static ContinuousCollisionResult rayVsMovingSphereCCD(Point3f rayOrigin, Vector3f rayDirection,
                                                               float maxDistance, MovingShape movingSphere) {
        var sphere = (SphereShape) movingSphere.getShape();
        float radius = sphere.getRadius();

        // Closed-form ray-vs-swept-sphere (Luciferase-gojpy): solve for the earliest time t in [0,1] at which the
        // (perpendicular) distance from the moving sphere centre C(t)=C0+t*V to the fixed ray line drops to the
        // radius. The previous 10-sample scan tunnelled whenever the sphere was smaller than motion_length/10.
        var dir = new Vector3f(rayDirection);
        float dirLen = dir.length();
        if (dirLen < EPSILON) {
            return ContinuousCollisionResult.noCollision();
        }
        dir.scale(1.0f / dirLen); // unit ray direction; maxDistance is measured along it

        var c0 = movingSphere.getStartPosition();
        var endPos = movingSphere.getEndPosition();
        var v = new Vector3f();
        v.sub(endPos, c0);                       // sphere centre displacement over the interval

        var a0 = new Vector3f();
        a0.sub(c0, rayOrigin);                    // ray-origin -> sphere centre at t=0

        // Perpendicular-distance-squared to the ray line as a function of t is quadratic: f(t) = A t^2 + B t + C,
        // built from |W(t)|^2 - (W(t)·dir)^2 - radius^2 where W(t) = a0 + t*v.
        float vDotDir = v.dot(dir);
        float aDotDir = a0.dot(dir);
        float A = v.dot(v) - vDotDir * vDotDir;
        float B = 2.0f * (a0.dot(v) - aDotDir * vDotDir);
        float C = a0.dot(a0) - aDotDir * aDotDir - radius * radius;

        float toi = -1.0f;
        if (Math.abs(A) < EPSILON) {
            // Sphere stationary relative to the ray (or moving parallel to it): linear B t + C = 0.
            if (Math.abs(B) >= EPSILON) {
                float t = -C / B;
                if (C <= 0) {
                    toi = 0.0f;                  // already within radius at t=0
                } else if (t >= 0 && t <= 1) {
                    toi = t;
                }
            } else if (C <= 0) {
                toi = 0.0f;
            }
        } else {
            float disc = B * B - 4 * A * C;
            if (disc >= 0) {
                float sqrtDisc = (float) Math.sqrt(disc);
                float t1 = (-B - sqrtDisc) / (2 * A);
                float t2 = (-B + sqrtDisc) / (2 * A);
                if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
                // Earliest entry time within [0,1]; if the interval starts already in contact, t=0.
                if (t1 <= 0 && t2 >= 0) {
                    toi = 0.0f;
                } else if (t1 >= 0 && t1 <= 1) {
                    toi = t1;
                }
            }
        }

        if (toi < 0) {
            return ContinuousCollisionResult.noCollision();
        }

        // Contact point: closest point on the ray to the sphere centre at the time of impact, clamped to the ray.
        var spherePos = movingSphere.getPositionAtTime(toi);
        var toSphere = new Vector3f();
        toSphere.sub(spherePos, rayOrigin);
        float proj = toSphere.dot(dir);
        if (proj < 0 || proj > maxDistance) {
            return ContinuousCollisionResult.noCollision();   // contact lies off the ray's extent
        }
        var projPoint = new Point3f(rayOrigin);
        var along = new Vector3f(dir);
        along.scale(proj);
        projPoint.add(along);

        var normal = new Vector3f();
        normal.sub(spherePos, projPoint);
        if (normal.length() < EPSILON) {
            normal.set(dir);
            normal.scale(-1);
        } else {
            normal.normalize();
        }
        return ContinuousCollisionResult.collision(toi, projPoint, normal, 0.0f);
    }
}