/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

import com.hellblazer.luciferase.lucien.collision.CollisionShape.CollisionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for impulse-based collision resolution.
 *
 * @author hal.hildebrand
 */
public class ImpulseResolverTest {
    
    private RigidBody bodyA;
    private RigidBody bodyB;
    
    @BeforeEach
    void setUp() {
        var inertia = InertiaTensor.sphere(10.0f, 1.0f);
        bodyA = new RigidBody(10.0f, inertia);
        bodyB = new RigidBody(10.0f, inertia);
    }
    
    @Test
    void testHeadOnCollision() {
        // Two bodies moving toward each other
        bodyA.setPosition(new Point3f(-1, 0, 0));
        bodyA.setLinearVelocity(new Vector3f(10, 0, 0));
        
        bodyB.setPosition(new Point3f(1, 0, 0));
        bodyB.setLinearVelocity(new Vector3f(-10, 0, 0));
        
        // Perfect elastic collision (restitution = 1)
        bodyA.setMaterial(new PhysicsMaterial(0.5f, 1.0f, 1000f));
        bodyB.setMaterial(new PhysicsMaterial(0.5f, 1.0f, 1000f));
        
        var collision = new CollisionResult(
            true,
            new Point3f(0, 0, 0),
            new Vector3f(1, 0, 0), // Normal points from A to B
            0.1f
        );
        
        ImpulseResolver.resolveCollision(bodyA, bodyB, collision);
        
        // Bodies should bounce off each other
        var velA = bodyA.getLinearVelocity();
        var velB = bodyB.getLinearVelocity();
        
        // Bodies should have separated (velocities should have changed)
        assertTrue(velA.x < 10.0f); // A was moving right, should slow/reverse
        assertTrue(velB.x > -10.0f); // B was moving left, should slow/reverse
    }
    
    @Test
    void testInelasticCollision() {
        // Two bodies, one moving
        bodyA.setPosition(new Point3f(-1, 0, 0));
        bodyA.setLinearVelocity(new Vector3f(10, 0, 0));
        
        bodyB.setPosition(new Point3f(1, 0, 0));
        bodyB.setLinearVelocity(new Vector3f(0, 0, 0));
        
        // Perfectly inelastic (restitution = 0)
        bodyA.setMaterial(new PhysicsMaterial(0.5f, 0.0f, 1000f));
        bodyB.setMaterial(new PhysicsMaterial(0.5f, 0.0f, 1000f));
        
        var collision = new CollisionResult(
            true,
            new Point3f(0, 0, 0),
            new Vector3f(1, 0, 0),
            0.1f
        );
        
        ImpulseResolver.resolveCollision(bodyA, bodyB, collision);
        
        // Bodies should stick together (same velocity)
        var velA = bodyA.getLinearVelocity();
        var velB = bodyB.getLinearVelocity();
        
        // With zero restitution, relative velocity should be zero
        // Both bodies should be moving in same direction
        float relVel = velA.x - velB.x;
        assertEquals(0.0f, relVel, 0.5f); // Allow some tolerance
    }
    
    @Test
    void testStaticCollision() {
        // One body hitting a static body
        bodyA.setLinearVelocity(new Vector3f(10, 0, 0));
        
        var staticBody = RigidBody.createStatic();
        staticBody.setPosition(new Point3f(2, 0, 0));
        
        var collision = new CollisionResult(
            true,
            new Point3f(1, 0, 0),
            new Vector3f(1, 0, 0),
            0.05f
        );
        
        // Half restitution
        bodyA.setMaterial(new PhysicsMaterial(0.5f, 0.5f, 1000f));
        staticBody.setMaterial(new PhysicsMaterial(0.5f, 0.5f, 1000f));
        
        ImpulseResolver.resolveCollision(bodyA, staticBody, collision);
        
        // Body should bounce back with reduced speed (0.5 restitution)
        var velA = bodyA.getLinearVelocity();
        assertTrue(velA.x < 0); // Should reverse direction
        assertTrue(Math.abs(velA.x) < 10.0f); // Should be slower than initial
        
        // Static body shouldn't move
        var velStatic = staticBody.getLinearVelocity();
        assertEquals(0, velStatic.x);
    }
    
    @Test
    void manifoldDistributesAngularArmsVsSinglePoint() {
        // Luciferase-nm9dj/zz8xp: a 4-point contact manifold distributes the impulse across the true contact polygon
        // so the angular arms reflect the corners, not a single representative point. A body spinning about x and
        // descending onto a kinematic floor sees asymmetric per-corner approach speeds (z=+1 corners approach fast,
        // z=-1 slow), so the manifold induces a change in spin about x that a single centroid contact (zero x-arm)
        // cannot.
        var inertia = InertiaTensor.sphere(10.0f, 1.0f);
        var normal = new Vector3f(0, 1, 0);
        var frictionless = new PhysicsMaterial(0.0f, 0.0f, 1000f);
        var manifold = java.util.List.of(new Point3f(1, 0, 1), new Point3f(1, 0, -1),
                                         new Point3f(-1, 0, -1), new Point3f(-1, 0, 1));

        var aManifold = spinningDescender(inertia, frictionless);
        ImpulseResolver.resolveCollision(aManifold, kinematicFloor(inertia, frictionless),
                                         new CollisionResult(true, new Point3f(0, 0, 0), normal, 0.05f, manifold));

        var aCentroid = spinningDescender(inertia, frictionless);
        ImpulseResolver.resolveCollision(aCentroid, kinematicFloor(inertia, frictionless),
                                         new CollisionResult(true, new Point3f(0, 0, 0), normal, 0.05f));

        float manifoldSpinX = aManifold.getAngularVelocity().x;
        float centroidSpinX = aCentroid.getAngularVelocity().x;

        assertEquals(5.0f, centroidSpinX, 1e-3f, "a single centroid contact has zero x-arm -> spin about x unchanged");
        assertTrue(Math.abs(manifoldSpinX - centroidSpinX) > 1e-2f,
                   "the manifold's offset corners must change the spin about x (distributed arms, Luciferase-nm9dj); "
                   + "manifold=" + manifoldSpinX + " centroid=" + centroidSpinX);
    }

    private static RigidBody spinningDescender(Matrix3f inertia, PhysicsMaterial m) {
        var a = new RigidBody(10.0f, inertia);
        a.setPosition(new Point3f(0, 1, 0));
        a.setLinearVelocity(new Vector3f(0, -5, 0));
        a.setAngularVelocity(new Vector3f(5, 0, 0));
        a.setMaterial(m);
        return a;
    }

    private static RigidBody kinematicFloor(Matrix3f inertia, PhysicsMaterial m) {
        var b = new RigidBody(10.0f, inertia);
        b.setPosition(new Point3f(0, -1, 0));
        b.setKinematic(true);
        b.setMaterial(m);
        return b;
    }

    @Test
    void testFriction() {
        // Body sliding along another
        bodyA.setPosition(new Point3f(0, 1, 0));
        bodyA.setLinearVelocity(new Vector3f(10, -5, 0)); // Moving right and down
        
        bodyB.setPosition(new Point3f(0, -1, 0));
        bodyB.setLinearVelocity(new Vector3f(0, 0, 0)); // Stationary
        
        // High friction materials
        bodyA.setMaterial(PhysicsMaterial.RUBBER);
        bodyB.setMaterial(PhysicsMaterial.RUBBER);
        
        var collision = new CollisionResult(
            true,
            new Point3f(0, 0, 0),
            new Vector3f(0, 1, 0), // Normal pointing up
            0.01f
        );
        
        ImpulseResolver.resolveCollision(bodyA, bodyB, collision);
        
        // Friction should reduce tangential velocity
        var velA = bodyA.getLinearVelocity();
        assertTrue(velA.x < 10.0f); // Friction reduces sliding
        assertTrue(velA.y > -5.0f); // Normal impulse stops downward motion
    }
    
    @Test
    void testRotationalResponse() {
        // Off-center collision should create rotation
        bodyA.setLinearVelocity(new Vector3f(10, 0, 0));
        bodyB.setLinearVelocity(new Vector3f(0, 0, 0));
        
        // Collision happens above center of B
        var collision = new CollisionResult(
            true,
            new Point3f(1, 1, 0), // Contact point
            new Vector3f(1, 0, 0),
            0.05f
        );
        
        ImpulseResolver.resolveCollision(bodyA, bodyB, collision);
        
        // Body B should start rotating (off-center impact)
        var angularVelB = bodyB.getAngularVelocity();
        assertTrue(Math.abs(angularVelB.z) > 0.01f); // Should have some rotation
    }
    
    @Test
    void testPositionCorrection() {
        // Deep penetration
        bodyA.setPosition(new Point3f(0, 0, 0));
        bodyB.setPosition(new Point3f(0.5f, 0, 0)); // Overlapping significantly
        
        var collision = new CollisionResult(
            true,
            new Point3f(0.25f, 0, 0),
            new Vector3f(1, 0, 0),
            0.5f // Deep penetration
        );
        
        var posA_before = bodyA.getPosition();
        var posB_before = bodyB.getPosition();
        
        ImpulseResolver.resolveCollision(bodyA, bodyB, collision);
        
        var posA_after = bodyA.getPosition();
        var posB_after = bodyB.getPosition();
        
        // Bodies should be pushed apart (allow for floating point precision)
        float totalSeparation = (posB_after.x - posA_after.x) - (posB_before.x - posA_before.x);
        assertTrue(totalSeparation > 0.1f); // Some separation occurred
        
        // Separation should be proportional to inverse mass
        float separationA = posA_before.x - posA_after.x;
        float separationB = posB_after.x - posB_before.x;
        assertEquals(separationA, separationB, 0.001f); // Equal masses = equal separation
    }
}