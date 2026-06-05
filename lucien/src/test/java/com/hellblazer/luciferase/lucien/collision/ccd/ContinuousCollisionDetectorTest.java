/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.ccd;

import com.hellblazer.luciferase.lucien.collision.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

// Note: all assertions using EPSILON=0.001f; contact-point convention is documented
// on ContinuousCollisionDetector.sphereVsSphereCCD (Luciferase-7wzml.95).

/**
 * Tests for continuous collision detection.
 *
 * @author hal.hildebrand
 */
public class ContinuousCollisionDetectorTest {
    
    private static final float EPSILON = 0.001f;
    
    @BeforeEach
    void setUp() {
        // Setup if needed
    }
    
    @Test
    void testMovingSpheresHeadOn() {
        // Two spheres moving directly toward each other
        var sphere1 = new SphereShape(new Point3f(-10, 0, 0), 1.0f);
        var sphere2 = new SphereShape(new Point3f(10, 0, 0), 1.0f);
        
        var moving1 = new MovingShape(sphere1, new Point3f(-10, 0, 0), new Point3f(0, 0, 0), 0, 1);
        var moving2 = new MovingShape(sphere2, new Point3f(10, 0, 0), new Point3f(0, 0, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(moving1, moving2);
        
        assertTrue(result.collides(), "Spheres moving toward each other should collide");
        assertEquals(0.9f, result.timeOfImpact(), EPSILON, "Time of impact when spheres touch");
        assertNotNull(result.contactPoint());
        assertNotNull(result.contactNormal());
    }
    
    @Test
    void testMovingSpheresParallel() {
        // Two spheres moving in parallel
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var sphere2 = new SphereShape(new Point3f(5, 0, 0), 1.0f);
        
        var moving1 = new MovingShape(sphere1, new Point3f(0, 0, 0), new Point3f(0, 10, 0), 0, 1);
        var moving2 = new MovingShape(sphere2, new Point3f(5, 0, 0), new Point3f(5, 10, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(moving1, moving2);
        
        assertFalse(result.collides(), "Parallel moving spheres should not collide");
    }
    
    @Test
    void testMovingSphereCrossing() {
        // Two spheres with crossing paths
        var sphere1 = new SphereShape(new Point3f(-5, 0, 0), 1.0f);
        var sphere2 = new SphereShape(new Point3f(0, -5, 0), 1.0f);
        
        var moving1 = new MovingShape(sphere1, new Point3f(-5, 0, 0), new Point3f(5, 0, 0), 0, 1);
        var moving2 = new MovingShape(sphere2, new Point3f(0, -5, 0), new Point3f(0, 5, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(moving1, moving2);
        
        assertTrue(result.collides(), "Crossing spheres should collide");
        assertEquals(0.3586f, result.timeOfImpact(), 0.001f, "Should collide when distance equals sum of radii");
    }
    
    @Test
    void testStaticCollision() {
        // Two static overlapping spheres
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 2.0f);
        var sphere2 = new SphereShape(new Point3f(2, 0, 0), 2.0f);
        
        var moving1 = new MovingShape(sphere1, new Point3f(0, 0, 0), new Point3f(0, 0, 0), 0, 1);
        var moving2 = new MovingShape(sphere2, new Point3f(2, 0, 0), new Point3f(2, 0, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(moving1, moving2);
        
        assertTrue(result.collides(), "Overlapping static spheres should collide");
        assertEquals(0.0f, result.timeOfImpact(), EPSILON, "Static collision at t=0");
    }
    
    @Test
    void testMovingSphereVsStaticBox() {
        // Sphere moving toward a box
        var sphere = new SphereShape(new Point3f(-5, 0, 0), 1.0f);
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(2, 2, 2));
        
        var movingSphere = new MovingShape(sphere, new Point3f(-5, 0, 0), new Point3f(5, 0, 0), 0, 1);
        var staticBox = new MovingShape(box, new Point3f(0, 0, 0), new Point3f(0, 0, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(movingSphere, staticBox);
        
        assertTrue(result.collides(), "Moving sphere should hit static box");
        assertTrue(result.timeOfImpact() > 0 && result.timeOfImpact() < 1);
    }
    
    @Test
    void testTimeOfImpactOrder() {
        // Test that earlier collisions are detected first
        var movingSphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var target1 = new SphereShape(new Point3f(5, 0, 0), 1.0f);
        var target2 = new SphereShape(new Point3f(10, 0, 0), 1.0f);
        
        var moving = new MovingShape(movingSphere, new Point3f(0, 0, 0), new Point3f(15, 0, 0), 0, 1);
        var static1 = new MovingShape(target1, new Point3f(5, 0, 0), new Point3f(5, 0, 0), 0, 1);
        var static2 = new MovingShape(target2, new Point3f(10, 0, 0), new Point3f(10, 0, 0), 0, 1);
        
        var result1 = ContinuousCollisionDetector.detectCollision(moving, static1);
        var result2 = ContinuousCollisionDetector.detectCollision(moving, static2);
        
        assertTrue(result1.collides());
        assertTrue(result2.collides());
        assertTrue(result1.timeOfImpact() < result2.timeOfImpact(), 
                  "Closer object should have earlier time of impact");
    }
    
    @Test
    void testHighSpeedTunneling() {
        // Test that CCD prevents tunneling
        var bullet = new SphereShape(new Point3f(-100, 0, 0), 0.5f);
        var wall = new BoxShape(new Point3f(0, 0, 0), new Vector3f(1, 10, 10));
        
        // Very fast bullet that would tunnel through wall with discrete collision
        var movingBullet = new MovingShape(bullet, new Point3f(-100, 0, 0), new Point3f(100, 0, 0), 0, 1);
        var staticWall = new MovingShape(wall, new Point3f(0, 0, 0), new Point3f(0, 0, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(movingBullet, staticWall);
        
        assertTrue(result.collides(), "CCD should detect high-speed collision");
        assertEquals(0.5f, result.timeOfImpact(), 0.01f, "Should hit at midpoint");
    }
    
    @Test
    void testMovingShapeInterpolation() {
        var sphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var moving = new MovingShape(sphere, new Point3f(0, 0, 0), new Point3f(10, 0, 0), 0, 1);
        
        // Test position interpolation
        var pos0 = moving.getPositionAtTime(0.0f);
        var pos1 = moving.getPositionAtTime(1.0f);
        var posMid = moving.getPositionAtTime(0.5f);
        
        assertEquals(0.0f, pos0.x, EPSILON);
        assertEquals(10.0f, pos1.x, EPSILON);
        assertEquals(5.0f, posMid.x, EPSILON);
    }
    
    @Test
    void testCapsuleMotion() {
        // Test moving capsule
        var capsule = new CapsuleShape(new Point3f(-5, 0, 0), 3.0f, 1.0f);
        var sphere = new SphereShape(new Point3f(5, 0, 0), 1.0f);
        
        var movingCapsule = new MovingShape(capsule, new Point3f(-5, 0, 0), new Point3f(5, 0, 0), 0, 1);
        var staticSphere = new MovingShape(sphere, new Point3f(5, 0, 0), new Point3f(5, 0, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(movingCapsule, staticSphere);
        
        assertTrue(result.collides(), "Moving capsule should hit static sphere");
    }
    
    @Test
    void testNoCollisionCase() {
        // Spheres moving away from each other
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var sphere2 = new SphereShape(new Point3f(5, 0, 0), 1.0f);
        
        var moving1 = new MovingShape(sphere1, new Point3f(0, 0, 0), new Point3f(-10, 0, 0), 0, 1);
        var moving2 = new MovingShape(sphere2, new Point3f(5, 0, 0), new Point3f(15, 0, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(moving1, moving2);
        
        assertFalse(result.collides(), "Spheres moving apart should not collide");
    }
    
    /**
     * Acceptance test for Luciferase-7wzml.95.
     *
     * At the exact TOI the two spheres are tangent.  The contact point must lie on
     * sphere1's surface (|contact - center1| ≈ r1) AND on sphere2's surface
     * (|contact - center2| ≈ r2), because center1 - n*r1 == center2 + n*r2 at
     * exact contact.  The normal must point from sphere2 toward sphere1.
     * Validated with unequal radii so that any radius-mix-up produces a
     * measurable geometric error.
     */
    @Test
    void testContactPointConventionUnequalRadii() {
        float r1 = 1.0f;
        float r2 = 3.0f;

        // sphere1 at x=-20, sphere2 at x=+20, moving toward each other
        var sphere1 = new SphereShape(new Point3f(-20, 0, 0), r1);
        var sphere2 = new SphereShape(new Point3f(20, 0, 0), r2);

        // both approach origin; they touch when center separation == r1+r2 = 4
        var moving1 = new MovingShape(sphere1, new Point3f(-20, 0, 0), new Point3f(0, 0, 0), 0, 1);
        var moving2 = new MovingShape(sphere2, new Point3f(20, 0, 0), new Point3f(0, 0, 0), 0, 1);

        var result = ContinuousCollisionDetector.detectCollision(moving1, moving2);

        assertTrue(result.collides(), "Spheres moving toward each other should collide");

        float toi = result.timeOfImpact();
        assertTrue(toi > 0 && toi <= 1, "TOI must be in (0,1]");

        // centers at TOI
        var c1 = moving1.getPositionAtTime(toi);
        var c2 = moving2.getPositionAtTime(toi);

        var contact = result.contactPoint();
        var normal  = result.contactNormal();

        // |contact - center1| must equal r1 (contact on sphere1 surface)
        var d1 = new Vector3f(contact.x - c1.x, contact.y - c1.y, contact.z - c1.z);
        assertEquals(r1, d1.length(), EPSILON, "Contact point must lie on sphere1 surface (distance == r1)");

        // |contact - center2| must equal r2 (contact on sphere2 surface — equivalent at exact TOI)
        var d2 = new Vector3f(contact.x - c2.x, contact.y - c2.y, contact.z - c2.z);
        assertEquals(r2, d2.length(), EPSILON, "Contact point must lie on sphere2 surface (distance == r2)");

        // normal must point from sphere2 toward sphere1 (positive x direction here)
        var expectedDir = new Vector3f(c1.x - c2.x, c1.y - c2.y, c1.z - c2.z);
        expectedDir.normalize();
        assertEquals(expectedDir.x, normal.x, EPSILON, "Normal x must point sphere2→sphere1");
        assertEquals(expectedDir.y, normal.y, EPSILON, "Normal y");
        assertEquals(expectedDir.z, normal.z, EPSILON, "Normal z");
    }

    @Test
    void testGrazingCollision() {
        // Sphere just grazing another sphere
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var sphere2 = new SphereShape(new Point3f(0, 2, 0), 1.0f);
        
        var moving1 = new MovingShape(sphere1, new Point3f(-5, 0, 0), new Point3f(5, 0, 0), 0, 1);
        var static2 = new MovingShape(sphere2, new Point3f(0, 2, 0), new Point3f(0, 2, 0), 0, 1);
        
        var result = ContinuousCollisionDetector.detectCollision(moving1, static2);
        
        assertTrue(result.collides(), "Grazing spheres should just touch");
        assertEquals(0.5f, result.timeOfImpact(), 0.1f);
        assertEquals(0.0f, result.penetrationDepth(), 0.1f);
    }
}