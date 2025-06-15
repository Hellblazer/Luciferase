/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test for Tetree ray intersection - just one basic test
 *
 * @author hal.hildebrand
 */
public class TetreeRaySimpleTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    @Test
    void testVeryBasicRayIntersection() {
        System.out.println("Starting basic tetree ray test...");
        
        // Insert one entity
        Point3f pos = new Point3f(100, 100, 100);
        byte level = 10;
        
        System.out.println("Inserting entity at: " + pos);
        LongEntityID entityId = tetree.insert(pos, level, "TestEntity");
        System.out.println("Entity ID: " + entityId);
        
        // Create a simple ray that should intersect
        Point3f rayOrigin = new Point3f(50, 100, 100);
        Vector3f rayDirection = new Vector3f(1, 0, 0);
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 200);
        
        System.out.println("Testing ray from " + rayOrigin + " in direction " + rayDirection);
        
        // Test intersection
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);
        System.out.println("Found " + intersections.size() + " intersections");
        
        if (intersections.isEmpty()) {
            System.out.println("No intersections found - this indicates the same issue as Octree had");
        } else {
            System.out.println("✅ Ray intersection working!");
            for (var intersection : intersections) {
                System.out.println("  - Entity: " + intersection.entityId() + ", distance: " + intersection.distance());
            }
        }
        
        // The assertion - we expect to find the intersection
        assertFalse(intersections.isEmpty(), "Ray should intersect with the entity");
    }
}