/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics.constraints;

import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

/**
 * Distance constraint maintains a fixed distance between two points on rigid bodies.
 * Can be used to implement joints, ropes, and rigid connections.
 *
 * @author hal.hildebrand
 */
public class DistanceConstraint implements Constraint {
    
    private final RigidBody bodyA;
    private final RigidBody bodyB;
    private final Vector3f localAnchorA;
    private final Vector3f localAnchorB;
    private final float targetDistance;
    
    // Solver state
    private float lambda = 0;
    private float effectiveMass = 0;
    private float bias = 0;
    
    // Compliance (softness)
    private float compliance = 0;
    
    // Baumgarte stabilization
    private static final float BAUMGARTE_FACTOR = 0.1f;
    
    public DistanceConstraint(RigidBody bodyA, RigidBody bodyB,
                            Point3f worldAnchorA, Point3f worldAnchorB) {
        this.bodyA = bodyA;
        this.bodyB = bodyB;

        // Store anchors in each body's LOCAL (body-fixed) frame so they rotate with the body (Luciferase-wv1yk).
        // world offset -> local = R^-1 * (worldAnchor - bodyPosition), using the body's orientation at bind time.
        var worldOffsetA = new Vector3f(worldAnchorA);
        worldOffsetA.sub(bodyA.getPosition());
        this.localAnchorA = inverseRotate(bodyA.getOrientation(), worldOffsetA);

        var worldOffsetB = new Vector3f(worldAnchorB);
        worldOffsetB.sub(bodyB.getPosition());
        this.localAnchorB = inverseRotate(bodyB.getOrientation(), worldOffsetB);

        // Calculate target distance
        var delta = new Vector3f(worldAnchorA);
        delta.sub(worldAnchorB);
        this.targetDistance = delta.length();
    }
    
    /**
     * Set compliance (softness) of the constraint.
     * 0 = rigid, higher values = softer
     */
    public void setCompliance(float compliance) {
        this.compliance = Math.max(0, compliance);
    }
    
    @Override
    public void prepare(float deltaTime) {
        // Get world space anchors
        var worldAnchorA = getWorldAnchorA();
        var worldAnchorB = getWorldAnchorB();
        
        // Calculate constraint axis
        var axis = new Vector3f(worldAnchorA);
        axis.sub(worldAnchorB);
        float currentDistance = axis.length();
        
        if (currentDistance < 0.0001f) {
            // Bodies are at same position, skip
            return;
        }
        
        axis.scale(1.0f / currentDistance);
        
        // Calculate effective mass. The lever arms are the CURRENT world-space offsets (rotated by body
        // orientation), not the bind-time local anchors (Luciferase-wv1yk).
        var rA = worldOffsetA();
        var rB = worldOffsetB();

        var rACrossAxis = new Vector3f();
        rACrossAxis.cross(rA, axis);
        var rBCrossAxis = new Vector3f();
        rBCrossAxis.cross(rB, axis);
        
        var temp1 = new Vector3f();
        bodyA.getInverseInertiaTensor().transform(rACrossAxis, temp1);
        var temp2 = new Vector3f();
        bodyB.getInverseInertiaTensor().transform(rBCrossAxis, temp2);
        
        effectiveMass = bodyA.getInverseMass() + bodyB.getInverseMass();
        effectiveMass += temp1.dot(rACrossAxis) + temp2.dot(rBCrossAxis);
        
        // Add compliance
        effectiveMass += compliance / (deltaTime * deltaTime);
        
        effectiveMass = 1.0f / effectiveMass;
        
        // Calculate bias (position error correction)
        float positionError = currentDistance - targetDistance;
        bias = -BAUMGARTE_FACTOR * positionError / deltaTime;
        
        // Warm starting
        applyImpulse(axis, lambda * deltaTime);
    }
    
    @Override
    public void solve() {
        // Get world space anchors
        var worldAnchorA = getWorldAnchorA();
        var worldAnchorB = getWorldAnchorB();
        
        // Calculate constraint axis
        var axis = new Vector3f(worldAnchorA);
        axis.sub(worldAnchorB);
        float currentDistance = axis.length();
        
        if (currentDistance < 0.0001f) {
            return;
        }
        
        axis.scale(1.0f / currentDistance);
        
        // Calculate relative velocity along constraint axis
        var velA = bodyA.getVelocityAtPoint(worldAnchorA);
        var velB = bodyB.getVelocityAtPoint(worldAnchorB);
        var relVel = new Vector3f(velA);
        relVel.sub(velB);
        
        float velocityAlongAxis = relVel.dot(axis);
        
        // Calculate impulse
        float deltaLambda = effectiveMass * (-velocityAlongAxis + bias);
        lambda += deltaLambda;
        
        // Apply impulse
        applyImpulse(axis, deltaLambda);
    }
    
    private void applyImpulse(Vector3f axis, float magnitude) {
        var impulse = new Vector3f(axis);
        impulse.scale(magnitude);
        
        var worldAnchorA = getWorldAnchorA();
        var worldAnchorB = getWorldAnchorB();
        
        bodyA.applyImpulse(impulse, worldAnchorA);
        impulse.negate();
        bodyB.applyImpulse(impulse, worldAnchorB);
    }
    
    private Point3f getWorldAnchorA() {
        var world = new Point3f(bodyA.getPosition());
        world.add(worldOffsetA());
        return world;
    }

    private Point3f getWorldAnchorB() {
        var world = new Point3f(bodyB.getPosition());
        world.add(worldOffsetB());
        return world;
    }

    /** Current world-space lever arm from body A's centre of mass to its anchor (rotated by body orientation). */
    private Vector3f worldOffsetA() {
        return rotate(bodyA.getOrientation(), localAnchorA);
    }

    /** Current world-space lever arm from body B's centre of mass to its anchor (rotated by body orientation). */
    private Vector3f worldOffsetB() {
        return rotate(bodyB.getOrientation(), localAnchorB);
    }

    /** Rotate a vector by a unit quaternion. */
    private static Vector3f rotate(Quat4f q, Vector3f v) {
        var m = new Matrix3f();
        m.set(q);
        var out = new Vector3f();
        m.transform(v, out);
        return out;
    }

    /** Rotate a vector by the inverse of a unit quaternion (transpose of the rotation matrix). */
    private static Vector3f inverseRotate(Quat4f q, Vector3f v) {
        var m = new Matrix3f();
        m.set(q);
        m.transpose();
        var out = new Vector3f();
        m.transform(v, out);
        return out;
    }
    
    @Override
    public RigidBody[] getBodies() {
        return new RigidBody[] { bodyA, bodyB };
    }
    
    @Override
    public boolean isValid() {
        return true; // Distance constraints don't expire
    }
    
    @Override
    public float getError() {
        var worldAnchorA = getWorldAnchorA();
        var worldAnchorB = getWorldAnchorB();
        
        var delta = new Vector3f(worldAnchorA);
        delta.sub(worldAnchorB);
        float currentDistance = delta.length();
        
        return Math.abs(currentDistance - targetDistance);
    }
}