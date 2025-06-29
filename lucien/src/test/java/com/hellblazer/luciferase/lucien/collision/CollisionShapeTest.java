/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.Ray3D;
import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for all collision shape types and their interactions.
 * Tests all shape-to-shape collision combinations and ray intersections.
 *
 * @author hal.hildebrand
 */
public class CollisionShapeTest {

    private static final float EPSILON = 0.001f;

    @Test
    void testSphereToSphereCollision() {
        // Test overlapping spheres
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var sphere2 = new SphereShape(new Point3f(8, 0, 0), 5.0f); // 2 units overlap

        var result = sphere1.collidesWith(sphere2);
        assertTrue(result.collides, "Overlapping spheres should collide");
        assertEquals(2.0f, result.penetrationDepth, EPSILON, "Penetration depth should be 2.0");
        assertEquals(-1.0f, result.contactNormal.x, EPSILON, "Contact normal should point along negative X axis");
        assertEquals(0.0f, result.contactNormal.y, EPSILON);
        assertEquals(0.0f, result.contactNormal.z, EPSILON);

        // Test non-overlapping spheres
        var sphere3 = new SphereShape(new Point3f(15, 0, 0), 5.0f);
        var result2 = sphere1.collidesWith(sphere3);
        assertFalse(result2.collides, "Non-overlapping spheres should not collide");
    }

    @Test
    void testSphereToBoxCollision() {
        var sphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var box = new BoxShape(new Point3f(7, 0, 0), new Vector3f(5, 5, 5)); // Half-extents

        var result = sphere.collidesWith(box);
        assertTrue(result.collides, "Sphere should collide with box");
        assertTrue(result.penetrationDepth > 0, "Should have positive penetration depth");
        assertNotNull(result.contactPoint, "Should have contact point");
        assertNotNull(result.contactNormal, "Should have contact normal");

        // Test non-colliding
        var farBox = new BoxShape(new Point3f(20, 0, 0), new Vector3f(5, 5, 5));
        var result2 = sphere.collidesWith(farBox);
        assertFalse(result2.collides, "Distant sphere and box should not collide");
    }

    @Test
    void testSphereToCapsuleCollision() {
        var sphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var capsule = new CapsuleShape(new Point3f(7, 0, 0), 10.0f, 3.0f); // height, radius

        var result = sphere.collidesWith(capsule);
        assertTrue(result.collides, "Sphere should collide with capsule");
        assertTrue(result.penetrationDepth > 0, "Should have positive penetration depth");

        // Test non-colliding
        var farCapsule = new CapsuleShape(new Point3f(20, 0, 0), 10.0f, 3.0f);
        var result2 = sphere.collidesWith(farCapsule);
        assertFalse(result2.collides, "Distant sphere and capsule should not collide");
    }

    @Test
    void testSphereToOrientedBoxCollision() {
        var sphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        
        // Create a rotated box
        var rotation = new Matrix3f();
        rotation.rotY((float) Math.PI / 4); // 45 degree rotation
        var obb = new OrientedBoxShape(new Point3f(8, 0, 0), new Vector3f(4, 4, 4), rotation);

        var result = sphere.collidesWith(obb);
        assertTrue(result.collides, "Sphere should collide with oriented box");
        assertTrue(result.penetrationDepth > 0, "Should have positive penetration depth");
    }

    @Test
    void testBoxToBoxCollision() {
        var box1 = new BoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5));
        var box2 = new BoxShape(new Point3f(8, 0, 0), new Vector3f(5, 5, 5)); // 2 units overlap

        var result = box1.collidesWith(box2);
        assertTrue(result.collides, "Overlapping boxes should collide");
        assertEquals(2.0f, result.penetrationDepth, EPSILON, "Penetration depth should be 2.0");

        // Test non-overlapping
        var box3 = new BoxShape(new Point3f(15, 0, 0), new Vector3f(5, 5, 5));
        var result2 = box1.collidesWith(box3);
        assertFalse(result2.collides, "Non-overlapping boxes should not collide");
    }

    @Test
    void testBoxToCapsuleCollision() {
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5));
        var capsule = new CapsuleShape(new Point3f(7, 0, 0), 10.0f, 3.0f);

        var result = box.collidesWith(capsule);
        assertTrue(result.collides, "Box should collide with capsule");
        assertTrue(result.penetrationDepth > 0, "Should have positive penetration depth");
    }

    @Test
    void testBoxToOrientedBoxCollision() {
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5));
        
        var rotation = new Matrix3f();
        rotation.rotY((float) Math.PI / 4);
        var obb = new OrientedBoxShape(new Point3f(7, 0, 0), new Vector3f(4, 4, 4), rotation);

        var result = box.collidesWith(obb);
        assertTrue(result.collides, "Box should collide with oriented box");
    }

    @Test
    void testCapsuleToCapsuleCollision() {
        var capsule1 = new CapsuleShape(new Point3f(0, 0, 0), 10.0f, 3.0f);
        var capsule2 = new CapsuleShape(new Point3f(5, 0, 0), 10.0f, 3.0f); // Overlapping

        var result = capsule1.collidesWith(capsule2);
        assertTrue(result.collides, "Overlapping capsules should collide");
        assertTrue(result.penetrationDepth > 0, "Should have positive penetration depth");

        // Test different orientations using endpoints
        var capsule3 = new CapsuleShape(new Point3f(5, -5, 0), new Point3f(5, 5, 0), 3.0f);
        var result2 = capsule1.collidesWith(capsule3);
        assertTrue(result2.collides, "Perpendicular capsules should collide when close enough");
    }

    @Test
    void testCapsuleToOrientedBoxCollision() {
        var capsule = new CapsuleShape(new Point3f(0, 0, 0), 10.0f, 3.0f);
        
        var rotation = new Matrix3f();
        rotation.rotZ((float) Math.PI / 6); // 30 degree rotation
        var obb = new OrientedBoxShape(new Point3f(6, 0, 0), new Vector3f(4, 4, 4), rotation);

        var result = capsule.collidesWith(obb);
        assertTrue(result.collides, "Capsule should collide with oriented box");
    }

    @Test
    void testOrientedBoxToOrientedBoxCollision() {
        var rotation1 = new Matrix3f();
        rotation1.rotY((float) Math.PI / 4);
        var obb1 = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5), rotation1);

        var rotation2 = new Matrix3f();
        rotation2.rotY((float) -Math.PI / 4);
        var obb2 = new OrientedBoxShape(new Point3f(7, 0, 0), new Vector3f(5, 5, 5), rotation2);

        var result = obb1.collidesWith(obb2);
        assertTrue(result.collides, "Overlapping oriented boxes should collide");
    }

    @Test
    void testRayIntersectionSphere() {
        var sphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var ray = new Ray3D(new Point3f(-10, 0, 0), new Vector3f(1, 0, 0));

        var result = sphere.intersectRay(ray);
        assertTrue(result.intersects, "Ray should intersect sphere");
        assertEquals(5.0f, result.distance, EPSILON, "Ray should hit sphere at distance 5");
        assertEquals(-5.0f, result.intersectionPoint.x, EPSILON);
        assertEquals(0.0f, result.intersectionPoint.y, EPSILON);
        assertEquals(0.0f, result.intersectionPoint.z, EPSILON);

        // Test ray missing sphere
        var ray2 = new Ray3D(new Point3f(-10, 10, 0), new Vector3f(1, 0, 0));
        var result2 = sphere.intersectRay(ray2);
        assertFalse(result2.intersects, "Ray should miss sphere");
    }

    @Test
    void testRayIntersectionBox() {
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5));
        var ray = new Ray3D(new Point3f(-10, 0, 0), new Vector3f(1, 0, 0));

        var result = box.intersectRay(ray);
        assertTrue(result.intersects, "Ray should intersect box");
        assertEquals(5.0f, result.distance, EPSILON, "Ray should hit box at distance 5");
        assertEquals(-5.0f, result.intersectionPoint.x, EPSILON);

        // Test ray from inside
        var ray2 = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        var result2 = box.intersectRay(ray2);
        assertTrue(result2.intersects, "Ray from inside should intersect box");
        assertEquals(0.0f, result2.distance, EPSILON, "Ray from inside should have zero distance");
    }

    @Test
    void testRayIntersectionCapsule() {
        var capsule = new CapsuleShape(new Point3f(0, 0, 0), 10.0f, 3.0f);
        var ray = new Ray3D(new Point3f(-10, 0, 0), new Vector3f(1, 0, 0));

        var result = capsule.intersectRay(ray);
        assertTrue(result.intersects, "Ray should intersect capsule");
        assertTrue(result.distance > 0, "Should have positive intersection distance");
        assertNotNull(result.intersectionPoint, "Should have intersection point");
    }

    @Test
    void testRayIntersectionOrientedBox() {
        var rotation = new Matrix3f();
        rotation.rotY((float) Math.PI / 4);
        var obb = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5), rotation);
        var ray = new Ray3D(new Point3f(-10, 0, 0), new Vector3f(1, 0, 0));

        var result = obb.intersectRay(ray);
        assertTrue(result.intersects, "Ray should intersect oriented box");
        assertTrue(result.distance > 0, "Should have positive intersection distance");
    }

    @Test
    void testShapeTranslation() {
        // Test sphere translation
        var sphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        sphere.translate(new Vector3f(10, 5, 0));
        assertEquals(10.0f, sphere.getPosition().x, EPSILON);
        assertEquals(5.0f, sphere.getPosition().y, EPSILON);
        assertEquals(0.0f, sphere.getPosition().z, EPSILON);

        // Test box translation
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5));
        box.translate(new Vector3f(-5, 10, 3));
        assertEquals(-5.0f, box.getPosition().x, EPSILON);
        assertEquals(10.0f, box.getPosition().y, EPSILON);
        assertEquals(3.0f, box.getPosition().z, EPSILON);
    }

    @Test
    void testShapeAABB() {
        // Test sphere AABB
        var sphere = new SphereShape(new Point3f(10, 10, 10), 5.0f);
        var aabb = sphere.getAABB();
        assertEquals(5.0f, aabb.getMin().x, EPSILON);
        assertEquals(5.0f, aabb.getMin().y, EPSILON);
        assertEquals(5.0f, aabb.getMin().z, EPSILON);
        assertEquals(15.0f, aabb.getMax().x, EPSILON);
        assertEquals(15.0f, aabb.getMax().y, EPSILON);
        assertEquals(15.0f, aabb.getMax().z, EPSILON);

        // Test rotated box AABB
        var rotation = new Matrix3f();
        rotation.rotY((float) Math.PI / 4); // 45 degree rotation
        var obb = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(5, 5, 5), rotation);
        var obbAABB = obb.getAABB();
        // AABB should be larger than original box due to rotation
        assertTrue(obbAABB.getMax().x > 5.0f, "Rotated box AABB should be larger");
    }

    @Test
    void testEdgeCases() {
        // Test very small radius sphere (zero radius throws exception)
        var zeroSphere = new SphereShape(new Point3f(0, 0, 0), 0.001f);
        var normalSphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var result = zeroSphere.collidesWith(normalSphere);
        assertTrue(result.collides, "Zero-radius sphere at center should collide");

        // Test touching shapes (exactly at boundary)
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var sphere2 = new SphereShape(new Point3f(10, 0, 0), 5.0f); // Exactly touching
        var touchResult = sphere1.collidesWith(sphere2);
        assertTrue(touchResult.collides, "Touching spheres should be considered colliding");
        assertEquals(0.0f, touchResult.penetrationDepth, EPSILON, "Touching spheres should have zero penetration");

        // Test capsule with zero height (essentially a sphere)
        var zeroCapsule = new CapsuleShape(new Point3f(0, 0, 0), 0.0f, 5.0f);
        var sphereResult = zeroCapsule.collidesWith(normalSphere);
        assertTrue(sphereResult.collides, "Zero-height capsule should behave like sphere");
    }

    @Test
    void testSupportPointCalculation() {
        // Test sphere support
        var sphere = new SphereShape(new Point3f(0, 0, 0), 5.0f);
        var direction = new Vector3f(1, 0, 0);
        var support = sphere.getSupport(direction);
        assertEquals(5.0f, support.x, EPSILON, "Sphere support point should be at radius");
        assertEquals(0.0f, support.y, EPSILON);
        assertEquals(0.0f, support.z, EPSILON);

        // Test box support
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(3, 4, 5));
        var boxSupport = box.getSupport(new Vector3f(1, 1, 1));
        assertEquals(3.0f, boxSupport.x, EPSILON);
        assertEquals(4.0f, boxSupport.y, EPSILON);
        assertEquals(5.0f, boxSupport.z, EPSILON);
    }
}