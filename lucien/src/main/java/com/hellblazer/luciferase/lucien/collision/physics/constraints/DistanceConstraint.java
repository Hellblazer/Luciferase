/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics.constraints;

import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import javax.vecmath.Point3f;
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
        
        // Convert world anchors to local space
        this.localAnchorA = new Vector3f(worldAnchorA);
        this.localAnchorA.sub(bodyA.getPosition());
        
        this.localAnchorB = new Vector3f(worldAnchorB);
        this.localAnchorB.sub(bodyB.getPosition());
        
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
        
        // Calculate effective mass
        var rA = new Vector3f(localAnchorA);
        var rB = new Vector3f(localAnchorB);
        
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
        world.add(localAnchorA);
        return world;
    }
    
    private Point3f getWorldAnchorB() {
        var world = new Point3f(bodyB.getPosition());
        world.add(localAnchorB);
        return world;
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