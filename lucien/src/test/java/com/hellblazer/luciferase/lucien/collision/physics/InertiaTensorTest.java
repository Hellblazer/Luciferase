/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for inertia tensor calculations.
 *
 * @author hal.hildebrand
 */
public class InertiaTensorTest {
    
    @Test
    void testSphereInertia() {
        float mass = 10.0f;
        float radius = 2.0f;
        
        var inertia = InertiaTensor.sphere(mass, radius);
        
        // I = (2/5) * m * r^2 = 0.4 * 10 * 4 = 16
        float expected = 0.4f * mass * radius * radius;
        
        assertEquals(expected, inertia.m00, 0.001f);
        assertEquals(expected, inertia.m11, 0.001f);
        assertEquals(expected, inertia.m22, 0.001f);
        
        // Off-diagonal should be zero
        assertEquals(0, inertia.m01);
        assertEquals(0, inertia.m12);
        assertEquals(0, inertia.m21);
    }
    
    @Test
    void testBoxInertia() {
        float mass = 12.0f;
        var halfExtents = new Vector3f(1, 2, 3); // Full dimensions: 2x4x6
        
        var inertia = InertiaTensor.box(mass, halfExtents);
        
        // I_xx = (1/12) * m * (h^2 + d^2) = (1/12) * 12 * (16 + 36) = 52
        assertEquals(52.0f, inertia.m00, 0.001f);
        
        // I_yy = (1/12) * m * (w^2 + d^2) = (1/12) * 12 * (4 + 36) = 40
        assertEquals(40.0f, inertia.m11, 0.001f);
        
        // I_zz = (1/12) * m * (w^2 + h^2) = (1/12) * 12 * (4 + 16) = 20
        assertEquals(20.0f, inertia.m22, 0.001f);
    }
    
    @Test
    void testCylinderInertia() {
        float mass = 10.0f;
        float radius = 1.0f;
        float height = 4.0f;
        
        var inertia = InertiaTensor.cylinder(mass, radius, height);
        
        // Lateral: I_xx = I_zz = (1/12) * m * (3r^2 + h^2)
        float lateral = (mass / 12.0f) * (3 * radius * radius + height * height);
        assertEquals(lateral, inertia.m00, 0.001f);
        assertEquals(lateral, inertia.m22, 0.001f);
        
        // Axial: I_yy = (1/2) * m * r^2
        float axial = 0.5f * mass * radius * radius;
        assertEquals(axial, inertia.m11, 0.001f);
    }
    
    @Test
    void testCapsuleInertia() {
        float mass = 10.0f;
        float radius = 1.0f;
        float cylinderHeight = 2.0f;
        
        var inertia = InertiaTensor.capsule(mass, radius, cylinderHeight);
        
        // Capsule should have more inertia than a cylinder due to end caps
        var cylinderInertia = InertiaTensor.cylinder(mass, radius, cylinderHeight);
        
        assertTrue(inertia.m00 > cylinderInertia.m00);
        assertTrue(inertia.m22 > cylinderInertia.m22);
        
        // But similar axial inertia
        float diff = Math.abs(inertia.m11 - cylinderInertia.m11);
        assertTrue(diff < 2.0f);
    }
    
    @Test
    void testScaleInertia() {
        var original = InertiaTensor.sphere(1.0f, 1.0f);
        float scale = 2.0f;
        
        var scaled = InertiaTensor.scale(original, scale);
        
        // Inertia scales with 5th power of scale
        float expectedFactor = scale * scale * scale * scale * scale;
        assertEquals(original.m00 * expectedFactor, scaled.m00, 0.001f);
    }
    
    @Test
    void testTransformInertia() {
        // Start with a box aligned to axes
        var localInertia = InertiaTensor.box(10.0f, new Vector3f(2, 1, 3));
        
        // Rotate 45 degrees around Y
        var rotation = new Matrix3f();
        rotation.rotY((float)(Math.PI / 4));
        
        var worldInertia = InertiaTensor.transform(localInertia, rotation);
        
        // Diagonal elements should change due to rotation
        assertNotEquals(localInertia.m00, worldInertia.m00);
        assertNotEquals(localInertia.m22, worldInertia.m22);
        
        // Y-axis rotation preserves Y inertia
        assertEquals(localInertia.m11, worldInertia.m11, 0.001f);
        
        // Should now have off-diagonal elements
        assertNotEquals(0, worldInertia.m02);
        assertNotEquals(0, worldInertia.m20);
    }
}