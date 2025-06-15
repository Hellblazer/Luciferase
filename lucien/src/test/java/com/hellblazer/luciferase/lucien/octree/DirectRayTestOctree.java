/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Direct test of the actual raySphereIntersection method in Octree
 *
 * @author hal.hildebrand
 */
public class DirectRayTestOctree {

    // Test class that extends Octree to access protected methods
    private static class TestableOctree extends Octree<LongEntityID, String> {
        public TestableOctree(SequentialLongIDGenerator idGenerator) {
            super(idGenerator);
        }

        // Expose the protected raySphereIntersection method for testing
        public float testRaySphereIntersection(Ray3D ray, Point3f center, float radius) {
            return raySphereIntersection(ray, center, radius);
        }
    }

    private TestableOctree octree;

    @BeforeEach
    void setUp() {
        octree = new TestableOctree(new SequentialLongIDGenerator());
    }

    @Test
    void testDirectRaySphereIntersection() {
        // Test case from our debug scenario
        Point3f rayOrigin = new Point3f(50, 100, 100);
        Vector3f rayDirection = new Vector3f(1, 0, 0);
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 200);
        
        Point3f entityPos = new Point3f(100, 100, 100);
        float radius = 0.1f;
        
        System.out.println("=== Direct test of actual raySphereIntersection implementation ===");
        System.out.println("Ray origin: " + rayOrigin);
        System.out.println("Ray direction: " + rayDirection);
        System.out.println("Entity position: " + entityPos);
        System.out.println("Sphere radius: " + radius);
        
        float distance = octree.testRaySphereIntersection(ray, entityPos, radius);
        System.out.println("Actual implementation result: " + distance);
        System.out.println("Expected result: ~49.9");
        
        if (distance < 0) {
            System.out.println("❌ ISSUE CONFIRMED: The actual implementation returns -1 (no intersection)");
            System.out.println("This explains why the ray intersection tests are failing");
        } else {
            System.out.println("✅ Intersection found at distance: " + distance);
        }
    }

    @Test
    void testVariousScenarios() {
        System.out.println("\n=== Testing various ray-sphere scenarios ===");
        
        // Scenario 1: Ray from (0,0,0) direction (1,0,0) hitting sphere at (50,0,0)
        Ray3D ray1 = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        Point3f center1 = new Point3f(50, 0, 0);
        float result1 = octree.testRaySphereIntersection(ray1, center1, 0.1f);
        System.out.println("Scenario 1 - Ray (0,0,0)->(1,0,0), sphere at (50,0,0): " + result1);
        
        // Scenario 2: Ray missing sphere
        Ray3D ray2 = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        Point3f center2 = new Point3f(50, 10, 0); // 10 units off path
        float result2 = octree.testRaySphereIntersection(ray2, center2, 0.1f);
        System.out.println("Scenario 2 - Ray missing sphere: " + result2);
        
        // Scenario 3: Ray starting inside sphere
        Ray3D ray3 = new Ray3D(new Point3f(50, 0, 0), new Vector3f(1, 0, 0));
        Point3f center3 = new Point3f(50, 0, 0); // Same position
        float result3 = octree.testRaySphereIntersection(ray3, center3, 1.0f);
        System.out.println("Scenario 3 - Ray inside sphere: " + result3);
    }
}