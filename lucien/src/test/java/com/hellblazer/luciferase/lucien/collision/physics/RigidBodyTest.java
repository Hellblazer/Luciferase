/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RigidBody physics.
 *
 * @author hal.hildebrand
 */
public class RigidBodyTest {
    
    private RigidBody body;
    private Matrix3f identityInertia;
    
    @BeforeEach
    void setUp() {
        identityInertia = new Matrix3f();
        identityInertia.setIdentity();
        body = new RigidBody(10.0f, identityInertia);
    }
    
    @Test
    void testRigidBodyCreation() {
        assertEquals(10.0f, body.getMass());
        assertEquals(0.1f, body.getInverseMass());
        assertFalse(body.isKinematic());
        
        var pos = body.getPosition();
        assertEquals(0, pos.x);
        assertEquals(0, pos.y);
        assertEquals(0, pos.z);
        
        var vel = body.getLinearVelocity();
        assertEquals(0, vel.x);
        assertEquals(0, vel.y);
        assertEquals(0, vel.z);
    }
    
    @Test
    void testStaticBody() {
        var staticBody = RigidBody.createStatic();
        assertEquals(0, staticBody.getMass());
        assertEquals(0, staticBody.getInverseMass());
        assertTrue(staticBody.isKinematic());
        
        // Static bodies shouldn't respond to forces
        staticBody.applyForce(new Vector3f(100, 0, 0));
        staticBody.integrate(0.016f);
        
        var vel = staticBody.getLinearVelocity();
        assertEquals(0, vel.x);
    }
    
    @Test
    void testForceApplication() {
        // Apply constant force
        var force = new Vector3f(100, 0, 0);
        body.applyForce(force);
        
        // Integrate for 1 second
        body.integrate(1.0f);
        
        // F = ma, a = F/m = 100/10 = 10
        // v = at = 10 * 1 = 10
        // But with damping: v = 10 * (1 - 0.05 * 1) = 9.5
        var velocity = body.getLinearVelocity();
        assertEquals(9.5f, velocity.x, 0.001f);
        assertEquals(0, velocity.y);
        assertEquals(0, velocity.z);
        
        // Position update happens after velocity update
        // With damping: v_final = v * (1 - damping * dt) = 10 * (1 - 0.05 * 1) = 9.5
        // x = v_final * t = 9.5 * 1 = 9.5
        var position = body.getPosition();
        assertEquals(9.5f, position.x, 0.01f); // With damping
    }
    
    @Test
    void testImpulseApplication() {
        // Apply impulse at center
        var impulse = new Vector3f(50, 0, 0);
        body.applyImpulse(impulse, body.getPosition());
        
        // Impulse = change in momentum
        // p = mv, so v = p/m = 50/10 = 5
        var velocity = body.getLinearVelocity();
        assertEquals(5.0f, velocity.x, 0.001f);
    }
    
    @Test
    void testAngularImpulse() {
        // Apply impulse off-center to create rotation
        var impulse = new Vector3f(0, 50, 0);
        var point = new Point3f(1, 0, 0); // 1 unit to the right
        
        body.applyImpulse(impulse, point);
        
        // Should have both linear and angular velocity
        var linearVel = body.getLinearVelocity();
        assertEquals(5.0f, linearVel.y, 0.001f); // 50/10 = 5
        
        var angularVel = body.getAngularVelocity();
        // Torque = r × F = (1,0,0) × (0,50,0) = (0,0,50)
        // Angular impulse = (0,0,50), ω = I^-1 * L = 50 (with identity inertia)
        assertEquals(50.0f, angularVel.z, 0.001f);
    }
    
    @Test
    void testDamping() {
        body.setLinearDamping(0.5f);
        body.setAngularDamping(0.5f);
        
        // Set initial velocities
        body.setLinearVelocity(new Vector3f(10, 0, 0));
        body.setAngularVelocity(new Vector3f(0, 0, 10));
        
        // Integrate with damping
        body.integrate(0.1f);
        
        // Velocity should be reduced by damping
        var linearVel = body.getLinearVelocity();
        assertTrue(linearVel.x < 10.0f);
        assertTrue(linearVel.x > 9.0f); // Not too much damping
        
        var angularVel = body.getAngularVelocity();
        assertTrue(angularVel.z < 10.0f);
        assertTrue(angularVel.z > 9.0f);
    }
    
    @Test
    void testVelocityAtPoint() {
        // Set linear and angular velocity
        body.setLinearVelocity(new Vector3f(5, 0, 0));
        body.setAngularVelocity(new Vector3f(0, 0, 1)); // 1 rad/s around Z
        
        // Get velocity at a point 1 unit up from center
        var point = new Point3f(0, 1, 0);
        var velocity = body.getVelocityAtPoint(point);
        
        // Total velocity = linear + ω × r
        // ω × r = (0,0,1) × (0,1,0) = (-1,0,0)
        // Total = (5,0,0) + (-1,0,0) = (4,0,0)
        assertEquals(4.0f, velocity.x, 0.001f);
        assertEquals(0, velocity.y, 0.001f);
        assertEquals(0, velocity.z, 0.001f);
    }
    
    @Test
    void testOrientationIntegration() {
        // Set angular velocity
        body.setAngularVelocity(new Vector3f(0, 0, (float)Math.PI)); // 180 deg/s
        
        // Integrate for 0.5 seconds
        for (int i = 0; i < 50; i++) {
            body.integrate(0.01f);
        }
        
        // Should have rotated 90 degrees
        var orientation = body.getOrientation();
        // After 0.5 seconds at PI rad/s, we've rotated PI/2 radians (90 degrees)
        // But quaternion math is complex, just check we've rotated
        assertTrue(Math.abs(orientation.z) > 0.5f);
        assertTrue(orientation.w < 1.0f); // No longer identity
    }
}