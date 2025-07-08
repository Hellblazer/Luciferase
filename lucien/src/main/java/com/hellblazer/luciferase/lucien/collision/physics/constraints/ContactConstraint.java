/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics.constraints;

import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Contact constraint prevents penetration between rigid bodies.
 * Uses sequential impulses to maintain non-penetration constraint.
 *
 * @author hal.hildebrand
 */
public class ContactConstraint implements Constraint {
    
    private final RigidBody bodyA;
    private final RigidBody bodyB;
    private final Point3f contactPoint;
    private final Vector3f normal;
    private final float penetrationDepth;
    
    // Constraint parameters
    private float normalImpulseSum = 0;
    private float tangentImpulseSum = 0;
    private float bias = 0;
    private float normalMass = 0;
    private float tangentMass = 0;
    
    // Baumgarte stabilization
    private static final float BAUMGARTE_FACTOR = 0.2f;
    private static final float SLOP = 0.01f;
    
    public ContactConstraint(RigidBody bodyA, RigidBody bodyB, 
                           Point3f contactPoint, Vector3f normal, 
                           float penetrationDepth) {
        this.bodyA = bodyA;
        this.bodyB = bodyB;
        this.contactPoint = new Point3f(contactPoint);
        this.normal = new Vector3f(normal);
        this.normal.normalize();
        this.penetrationDepth = penetrationDepth;
    }
    
    @Override
    public void prepare(float deltaTime) {
        // Calculate effective mass for normal direction
        var rA = new Vector3f(contactPoint);
        rA.sub(bodyA.getPosition());
        var rB = new Vector3f(contactPoint);
        rB.sub(bodyB.getPosition());
        
        var rACrossN = new Vector3f();
        rACrossN.cross(rA, normal);
        var rBCrossN = new Vector3f();
        rBCrossN.cross(rB, normal);
        
        var temp1 = new Vector3f();
        bodyA.getInverseInertiaTensor().transform(rACrossN, temp1);
        var temp2 = new Vector3f();
        bodyB.getInverseInertiaTensor().transform(rBCrossN, temp2);
        
        normalMass = bodyA.getInverseMass() + bodyB.getInverseMass();
        normalMass += temp1.dot(rACrossN) + temp2.dot(rBCrossN);
        normalMass = 1.0f / normalMass;
        
        // Calculate tangent mass (for friction)
        var velA = bodyA.getVelocityAtPoint(contactPoint);
        var velB = bodyB.getVelocityAtPoint(contactPoint);
        var relVel = new Vector3f(velA);
        relVel.sub(velB);
        
        var tangentVel = new Vector3f(relVel);
        float vn = relVel.dot(normal);
        var normalVel = new Vector3f(normal);
        normalVel.scale(vn);
        tangentVel.sub(normalVel);
        
        if (tangentVel.lengthSquared() > 0.0001f) {
            var tangent = new Vector3f(tangentVel);
            tangent.normalize();
            
            var rACrossT = new Vector3f();
            rACrossT.cross(rA, tangent);
            var rBCrossT = new Vector3f();
            rBCrossT.cross(rB, tangent);
            
            bodyA.getInverseInertiaTensor().transform(rACrossT, temp1);
            bodyB.getInverseInertiaTensor().transform(rBCrossT, temp2);
            
            tangentMass = bodyA.getInverseMass() + bodyB.getInverseMass();
            tangentMass += temp1.dot(rACrossT) + temp2.dot(rBCrossT);
            tangentMass = 1.0f / tangentMass;
        }
        
        // Baumgarte stabilization bias
        float penetrationError = Math.max(penetrationDepth - SLOP, 0.0f);
        bias = -BAUMGARTE_FACTOR * penetrationError / deltaTime;
        
        // Warm starting
        applyImpulse(normal, normalImpulseSum);
    }
    
    @Override
    public void solve() {
        // Calculate relative velocity
        var velA = bodyA.getVelocityAtPoint(contactPoint);
        var velB = bodyB.getVelocityAtPoint(contactPoint);
        var relVel = new Vector3f(velA);
        relVel.sub(velB);
        
        // Normal impulse
        float vn = relVel.dot(normal);
        float lambda = normalMass * (-vn + bias);
        
        // Clamp accumulated impulse (no pulling)
        float oldSum = normalImpulseSum;
        normalImpulseSum = Math.max(0, normalImpulseSum + lambda);
        lambda = normalImpulseSum - oldSum;
        
        // Apply normal impulse
        applyImpulse(normal, lambda);
        
        // Friction impulse
        if (tangentMass > 0) {
            // Recalculate relative velocity after normal impulse
            velA = bodyA.getVelocityAtPoint(contactPoint);
            velB = bodyB.getVelocityAtPoint(contactPoint);
            relVel.set(velA);
            relVel.sub(velB);
            
            // Calculate tangent
            var tangentVel = new Vector3f(relVel);
            vn = relVel.dot(normal);
            var normalVel = new Vector3f(normal);
            normalVel.scale(vn);
            tangentVel.sub(normalVel);
            
            float tangentSpeed = tangentVel.length();
            if (tangentSpeed > 0.0001f) {
                var tangent = new Vector3f(tangentVel);
                tangent.normalize();
                
                // Friction impulse
                float frictionLambda = -tangentMass * tangentSpeed;
                
                // Coulomb friction cone
                float maxFriction = bodyA.getMaterial().friction() * 
                                   bodyB.getMaterial().friction() * 
                                   normalImpulseSum;
                
                float oldTangentSum = tangentImpulseSum;
                tangentImpulseSum = Math.max(-maxFriction, 
                                            Math.min(maxFriction, 
                                                    tangentImpulseSum + frictionLambda));
                frictionLambda = tangentImpulseSum - oldTangentSum;
                
                // Apply friction impulse
                applyImpulse(tangent, frictionLambda);
            }
        }
    }
    
    private void applyImpulse(Vector3f direction, float magnitude) {
        var impulse = new Vector3f(direction);
        impulse.scale(magnitude);
        
        bodyA.applyImpulse(impulse, contactPoint);
        impulse.negate();
        bodyB.applyImpulse(impulse, contactPoint);
    }
    
    @Override
    public RigidBody[] getBodies() {
        return new RigidBody[] { bodyA, bodyB };
    }
    
    @Override
    public boolean isValid() {
        // Contact is valid if bodies are still close enough
        return penetrationDepth > -0.1f;
    }
    
    @Override
    public float getError() {
        return Math.max(penetrationDepth - SLOP, 0.0f);
    }
}