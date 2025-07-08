/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.ccd;

import com.hellblazer.luciferase.lucien.collision.*;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for swept sphere collision algorithms.
 *
 * @author hal.hildebrand
 */
public class SweptSphereTest {
    
    private static final float EPSILON = 0.001f;
    
    @Test
    void testSweptSphereVsBox() {
        // Sphere moving into box from left
        var sphereStart = new Point3f(-5, 0, 0);
        var velocity = new Vector3f(10, 0, 0);
        float radius = 1.0f;
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(2, 2, 2));
        
        var result = SweptSphere.sweptSphereVsBox(sphereStart, velocity, radius, box);
        
        assertTrue(result.collides());
        assertEquals(0.2f, result.timeOfImpact(), EPSILON);
        assertEquals(-2.0f, result.contactPoint().x, EPSILON); // Contact at x=-2
        assertEquals(-1.0f, result.contactNormal().x, EPSILON); // Normal points left
    }
    
    @Test
    void testSweptSphereVsBoxMiss() {
        // Sphere moving past box
        var sphereStart = new Point3f(-5, 5, 0);
        var velocity = new Vector3f(10, 0, 0);
        float radius = 1.0f;
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(2, 2, 2));
        
        var result = SweptSphere.sweptSphereVsBox(sphereStart, velocity, radius, box);
        
        assertFalse(result.collides());
    }
    
    @Test
    void testSweptSphereVsBoxCorner() {
        // Sphere hitting box corner
        var sphereStart = new Point3f(-5, -5, 0);
        var velocity = new Vector3f(10, 10, 0);
        float radius = 1.0f;
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(2, 2, 2));
        
        var result = SweptSphere.sweptSphereVsBox(sphereStart, velocity, radius, box);
        
        assertTrue(result.collides());
        assertTrue(result.timeOfImpact() > 0 && result.timeOfImpact() < 1);
    }
    
    @Test
    void testSweptSphereVsTriangle() {
        // Sphere hitting triangle face
        var sphereStart = new Point3f(0, 5, 0);
        var velocity = new Vector3f(0, -10, 0);
        float radius = 1.0f;
        
        var v0 = new Point3f(-2, 0, -2);
        var v1 = new Point3f(2, 0, -2);
        var v2 = new Point3f(0, 0, 2);
        
        var result = SweptSphere.sweptSphereVsTriangle(sphereStart, velocity, radius, v0, v1, v2);
        
        assertTrue(result.collides());
        assertEquals(0.4f, result.timeOfImpact(), EPSILON);
        assertEquals(2.0f, result.contactPoint().y, EPSILON); // Contact point on sphere surface  
        assertEquals(1.0f, Math.abs(result.contactNormal().y), EPSILON); // Normal along Y axis
    }
    
    @Test
    void testSweptSphereVsTriangleEdge() {
        // Sphere grazing triangle edge
        var sphereStart = new Point3f(-5, 1, 0);
        var velocity = new Vector3f(10, 0, 0);
        float radius = 1.0f;
        
        var v0 = new Point3f(0, 0, -2);
        var v1 = new Point3f(0, 0, 2);
        var v2 = new Point3f(0, 2, 0);
        
        var result = SweptSphere.sweptSphereVsTriangle(sphereStart, velocity, radius, v0, v1, v2);
        
        assertTrue(result.collides());
        assertEquals(0.5f, result.timeOfImpact(), 0.1f);
    }
    
    @Test
    void testSweptSphereVsCapsule() {
        // Sphere hitting vertical capsule
        var sphereStart = new Point3f(-5, 0, 0);
        var velocity = new Vector3f(10, 0, 0);
        float radius = 1.0f;
        var capsule = new CapsuleShape(new Point3f(0, 0, 0), 3.0f, 1.0f);
        
        var result = SweptSphere.sweptSphereVsCapsule(sphereStart, velocity, radius, capsule);
        
        assertTrue(result.collides());
        assertEquals(0.3f, result.timeOfImpact(), EPSILON);
        assertEquals(-1.0f, result.contactPoint().x, EPSILON); // Contact point on sphere surface
    }
    
    @Test
    void testSweptSphereVsCapsuleEndCap() {
        // Sphere hitting capsule end cap
        var sphereStart = new Point3f(0, -10, 0);
        var velocity = new Vector3f(0, 20, 0);
        float radius = 1.0f;
        var capsule = new CapsuleShape(new Point3f(0, 0, 0), 3.0f, 1.0f);
        
        var result = SweptSphere.sweptSphereVsCapsule(sphereStart, velocity, radius, capsule);
        
        assertTrue(result.collides());
        assertTrue(result.timeOfImpact() > 0 && result.timeOfImpact() < 1);
        assertTrue(result.contactPoint().y < -2.0f); // Below capsule bottom
    }
    
    @Test
    void testSweptSphereVsHorizontalCapsule() {
        // Sphere hitting horizontal capsule
        var sphereStart = new Point3f(0, -5, 0);
        var velocity = new Vector3f(0, 10, 0);
        float radius = 1.0f;
        var capsule = new CapsuleShape(new Point3f(-3, 0, 0), new Point3f(3, 0, 0), 1.0f);
        
        var result = SweptSphere.sweptSphereVsCapsule(sphereStart, velocity, radius, capsule);
        
        assertTrue(result.collides());
        assertEquals(0.3f, result.timeOfImpact(), EPSILON);
        assertEquals(-1.0f, result.contactPoint().y, EPSILON);
    }
    
    @Test
    void testSweptSphereParallelToCapsule() {
        // Sphere moving parallel to capsule axis
        var sphereStart = new Point3f(3, -5, 0);
        var velocity = new Vector3f(0, 10, 0);
        float radius = 1.0f;
        var capsule = new CapsuleShape(new Point3f(0, 0, 0), 5.0f, 1.0f);
        
        var result = SweptSphere.sweptSphereVsCapsule(sphereStart, velocity, radius, capsule);
        
        assertFalse(result.collides(), "Sphere too far from capsule axis");
    }
    
    @Test
    void testSweptSphereAlreadyColliding() {
        // Sphere starting inside box
        var sphereStart = new Point3f(0, 0, 0);
        var velocity = new Vector3f(5, 0, 0);
        float radius = 1.0f;
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(2, 2, 2));
        
        var result = SweptSphere.sweptSphereVsBox(sphereStart, velocity, radius, box);
        
        assertTrue(result.collides());
        assertEquals(0.0f, result.timeOfImpact(), EPSILON, "Already colliding at t=0");
    }
    
    @Test
    void testRayVsMovingSphere() {
        // Ray shooting at moving sphere
        var rayOrigin = new Point3f(0, 0, 0);
        var rayDirection = new Vector3f(1, 0, 0);
        float maxDistance = 20.0f;
        
        var sphere = new SphereShape(new Point3f(10, -5, 0), 2.0f);
        var movingSphere = new MovingShape(sphere, new Point3f(10, -5, 0), new Point3f(10, 5, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.rayVsMovingSphereCCD(rayOrigin, rayDirection, 
                                                                     maxDistance, movingSphere);
        
        assertTrue(result.collides());
        assertTrue(result.timeOfImpact() >= 0 && result.timeOfImpact() <= 1);
        assertEquals(10.0f, result.contactPoint().x, 1.0f);
    }
}