/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests to verify physics components work correctly.
 *
 * @author hal.hildebrand
 */
public class BasicPhysicsTest {
    
    @Test
    void testBasicImpulse() {
        // Simple impulse on a single body
        var inertia = InertiaTensor.sphere(10.0f, 1.0f);
        var body = new RigidBody(10.0f, inertia);
        
        // Apply impulse at center
        body.applyImpulse(new Vector3f(100, 0, 0), body.getPosition());
        
        // p = mv, so v = p/m = 100/10 = 10
        var velocity = body.getLinearVelocity();
        assertEquals(10.0f, velocity.x, 0.001f);
        assertEquals(0, velocity.y);
        assertEquals(0, velocity.z);
    }
    
    @Test
    void testBasicIntegration() {
        var inertia = InertiaTensor.box(10.0f, new Vector3f(1, 1, 1));
        var body = new RigidBody(10.0f, inertia);
        
        // Set velocity directly
        body.setLinearVelocity(new Vector3f(5, 0, 0));
        
        // Integrate for 1 second
        body.integrate(1.0f);
        
        var pos = body.getPosition();
        // With damping, position should be slightly less than 5
        assertTrue(pos.x > 4.5f && pos.x < 5.0f);
    }
    
    @Test
    void testConstraintSystem() {
        // Test that constraint solver can maintain distance
        var inertia = InertiaTensor.sphere(1.0f, 0.5f);
        var bodyA = new RigidBody(1.0f, inertia);
        var bodyB = new RigidBody(1.0f, inertia);
        
        bodyA.setPosition(new Point3f(-1, 0, 0));
        bodyB.setPosition(new Point3f(1, 0, 0));
        
        // Create distance constraint
        var constraint = new com.hellblazer.luciferase.lucien.collision.physics.constraints.DistanceConstraint(
            bodyA, bodyB,
            new Point3f(-1, 0, 0),
            new Point3f(1, 0, 0)
        );
        
        // Initial error should be zero (bodies are at target distance)
        assertEquals(0.0f, constraint.getError(), 0.001f);
        
        // Move bodies apart
        bodyA.setPosition(new Point3f(-2, 0, 0));
        bodyB.setPosition(new Point3f(2, 0, 0));
        
        // Error should be 2.0 (4 units apart - 2 unit target = 2)
        assertEquals(2.0f, constraint.getError(), 0.1f);
        
        // Prepare and solve constraint
        constraint.prepare(0.016f);
        constraint.solve();
        
        // Bodies should have velocities pulling them together
        var velA = bodyA.getLinearVelocity();
        var velB = bodyB.getLinearVelocity();
        
        assertTrue(velA.x > 0); // A moves right
        assertTrue(velB.x < 0); // B moves left
    }
}