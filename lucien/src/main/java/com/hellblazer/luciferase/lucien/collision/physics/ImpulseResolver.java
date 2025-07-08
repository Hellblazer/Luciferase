/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

import com.hellblazer.luciferase.lucien.collision.CollisionShape.CollisionResult;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Resolves collisions using impulse-based methods.
 * Implements conservation of momentum and energy for realistic physics.
 *
 * @author hal.hildebrand
 */
public class ImpulseResolver {
    
    private static final float PENETRATION_SLOP = 0.01f;
    private static final float POSITION_CORRECTION_PERCENT = 0.8f;
    
    /**
     * Resolve a collision between two rigid bodies.
     */
    public static void resolveCollision(RigidBody bodyA, RigidBody bodyB, CollisionResult collision) {
        if (!collision.collides) {
            return;
        }
        
        // Skip if both bodies are static/kinematic
        if ((bodyA.isKinematic() || bodyA.getInverseMass() == 0) && 
            (bodyB.isKinematic() || bodyB.getInverseMass() == 0)) {
            return;
        }
        
        // Calculate relative velocity at contact point
        var contactPoint = collision.contactPoint;
        var velA = bodyA.getVelocityAtPoint(contactPoint);
        var velB = bodyB.getVelocityAtPoint(contactPoint);
        var relativeVelocity = new Vector3f(velA);
        relativeVelocity.sub(velB);
        
        // Calculate velocity along collision normal
        float velocityAlongNormal = relativeVelocity.dot(collision.contactNormal);
        
        // Combine materials
        var combinedMaterial = PhysicsMaterial.combine(bodyA.getMaterial(), bodyB.getMaterial());
        
        // Calculate impulse magnitude
        float impulseMagnitude = calculateImpulseMagnitude(
            bodyA, bodyB, contactPoint, collision.contactNormal, 
            velocityAlongNormal, combinedMaterial.restitution()
        );
        
        // Apply impulse
        var impulse = new Vector3f(collision.contactNormal);
        impulse.scale(impulseMagnitude);
        
        bodyA.applyImpulse(impulse, contactPoint);
        impulse.negate();
        bodyB.applyImpulse(impulse, contactPoint);
        
        // Apply friction
        applyFriction(bodyA, bodyB, contactPoint, collision.contactNormal, 
                     relativeVelocity, impulseMagnitude, combinedMaterial.friction());
        
        // Position correction to resolve penetration
        if (collision.penetrationDepth > PENETRATION_SLOP) {
            applyPositionCorrection(bodyA, bodyB, collision.contactNormal, 
                                   collision.penetrationDepth);
        }
    }
    
    /**
     * Calculate impulse magnitude using physics equations.
     */
    private static float calculateImpulseMagnitude(
            RigidBody bodyA, RigidBody bodyB, Point3f contactPoint,
            Vector3f normal, float velocityAlongNormal, float restitution) {
        
        // Calculate effective mass
        float invMassSum = bodyA.getInverseMass() + bodyB.getInverseMass();
        
        // Include rotational inertia
        var rA = new Vector3f(contactPoint);
        rA.sub(bodyA.getPosition());
        var rB = new Vector3f(contactPoint);
        rB.sub(bodyB.getPosition());
        
        var rACrossN = new Vector3f();
        rACrossN.cross(rA, normal);
        var rBCrossN = new Vector3f();
        rBCrossN.cross(rB, normal);
        
        var angularInfluenceA = new Vector3f();
        bodyA.getInverseInertiaTensor().transform(rACrossN, angularInfluenceA);
        var angularInfluenceB = new Vector3f();
        bodyB.getInverseInertiaTensor().transform(rBCrossN, angularInfluenceB);
        
        var temp1 = new Vector3f();
        temp1.cross(angularInfluenceA, rA);
        var temp2 = new Vector3f();
        temp2.cross(angularInfluenceB, rB);
        
        float angularEffect = temp1.dot(normal) + temp2.dot(normal);
        
        // Impulse = -(1 + e) * relativeVelocity / effectiveMass
        float j = -(1.0f + restitution) * velocityAlongNormal;
        j /= invMassSum + angularEffect;
        
        return j;
    }
    
    /**
     * Apply friction impulse tangent to the collision normal.
     */
    private static void applyFriction(
            RigidBody bodyA, RigidBody bodyB, Point3f contactPoint,
            Vector3f normal, Vector3f relativeVelocity, 
            float normalImpulse, float friction) {
        
        // Calculate tangent vector (perpendicular to normal)
        var tangentVelocity = new Vector3f(relativeVelocity);
        float dotProduct = relativeVelocity.dot(normal);
        var normalComponent = new Vector3f(normal);
        normalComponent.scale(dotProduct);
        tangentVelocity.sub(normalComponent);
        
        float tangentSpeed = tangentVelocity.length();
        if (tangentSpeed < 0.0001f) {
            return; // No tangent velocity
        }
        
        var tangent = new Vector3f(tangentVelocity);
        tangent.normalize();
        
        // Calculate friction impulse magnitude
        float invMassSum = bodyA.getInverseMass() + bodyB.getInverseMass();
        
        // Include rotational effects
        var rA = new Vector3f(contactPoint);
        rA.sub(bodyA.getPosition());
        var rB = new Vector3f(contactPoint);
        rB.sub(bodyB.getPosition());
        
        var rACrossT = new Vector3f();
        rACrossT.cross(rA, tangent);
        var rBCrossT = new Vector3f();
        rBCrossT.cross(rB, tangent);
        
        var angularInfluenceA = new Vector3f();
        bodyA.getInverseInertiaTensor().transform(rACrossT, angularInfluenceA);
        var angularInfluenceB = new Vector3f();
        bodyB.getInverseInertiaTensor().transform(rBCrossT, angularInfluenceB);
        
        var temp1 = new Vector3f();
        temp1.cross(angularInfluenceA, rA);
        var temp2 = new Vector3f();
        temp2.cross(angularInfluenceB, rB);
        
        float angularEffect = temp1.dot(tangent) + temp2.dot(tangent);
        
        float frictionImpulseMagnitude = -tangentSpeed / (invMassSum + angularEffect);
        
        // Coulomb friction: clamp to maximum static friction
        float maxFriction = friction * Math.abs(normalImpulse);
        frictionImpulseMagnitude = Math.max(-maxFriction, 
                                           Math.min(maxFriction, frictionImpulseMagnitude));
        
        // Apply friction impulse
        var frictionImpulse = new Vector3f(tangent);
        frictionImpulse.scale(frictionImpulseMagnitude);
        
        bodyA.applyImpulse(frictionImpulse, contactPoint);
        frictionImpulse.negate();
        bodyB.applyImpulse(frictionImpulse, contactPoint);
    }
    
    /**
     * Correct position to resolve penetration.
     */
    private static void applyPositionCorrection(
            RigidBody bodyA, RigidBody bodyB, 
            Vector3f normal, float penetrationDepth) {
        
        float totalInverseMass = bodyA.getInverseMass() + bodyB.getInverseMass();
        if (totalInverseMass == 0) {
            return;
        }
        
        float correctionMagnitude = Math.max(penetrationDepth - PENETRATION_SLOP, 0.0f) 
                                   * POSITION_CORRECTION_PERCENT / totalInverseMass;
        
        var correction = new Vector3f(normal);
        correction.scale(correctionMagnitude);
        
        var posA = bodyA.getPosition();
        var deltaA = new Vector3f(correction);
        deltaA.scale(-bodyA.getInverseMass()); // Move A backward along normal
        posA.add(deltaA);
        bodyA.setPosition(posA);
        
        var posB = bodyB.getPosition();
        var deltaB = new Vector3f(correction);
        deltaB.scale(bodyB.getInverseMass()); // Move B forward along normal
        posB.add(deltaB);
        bodyB.setPosition(posB);
    }
}