/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;

/**
 * Debug test to understand ray intersection behavior in Octree
 *
 * @author hal.hildebrand
 */
public class OctreeRayDebugTest {

    private Octree<LongEntityID, String> octree;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
    }

    @Test
    void debugBasicRayIntersection() {
        // Insert a single entity
        Point3f entityPos = new Point3f(100, 100, 100);
        byte level = 10;
        
        System.out.println("Inserting entity at position: " + entityPos + " at level: " + level);
        LongEntityID entityId = octree.insert(entityPos, level, "TestEntity");
        System.out.println("Entity ID: " + entityId);
        
        // Check if entity exists
        System.out.println("Entity exists: " + octree.containsEntity(entityId));
        System.out.println("Total entities: " + octree.entityCount());
        var stats = octree.getStats();
        System.out.println("Spatial index node count: " + stats.nodeCount());
        
        // Get all nodes
        System.out.println("All spatial nodes:");
        octree.nodes().forEach(node -> 
            System.out.println("  Node index: " + node.mortonIndex() + ", entities: " + node.entityIds().size())
        );
        
        // Try to find entity at position
        var entitiesAtPos = octree.lookup(entityPos, level);
        System.out.println("Entities found at position: " + entitiesAtPos.size());
        
        // Create a simple ray that should intersect
        Point3f rayOrigin = new Point3f(50, 100, 100);  // Same Y and Z, before X
        Vector3f rayDirection = new Vector3f(1, 0, 0);   // Pointing towards entity
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 200);
        
        System.out.println("\nRay details:");
        System.out.println("Ray origin: " + rayOrigin);
        System.out.println("Ray direction: " + rayDirection);
        System.out.println("Ray max distance: " + ray.maxDistance());
        
        // Create a very simple test case to isolate the issue
        System.out.println("\nSimple test: insert entity at origin to create node 0");
        Point3f originPos = new Point3f(0, 0, 0);
        LongEntityID originEntityId = octree.insert(originPos, (byte) 0, "OriginEntity");
        System.out.println("Origin entity ID: " + originEntityId);
        
        // Check nodes again
        System.out.println("All spatial nodes after origin insert:");
        octree.nodes().forEach(node -> 
            System.out.println("  Node index: " + node.mortonIndex() + ", entities: " + node.entityIds().size())
        );
        
        // Debug: Check entity bounds
        System.out.println("\nEntity bounds debugging:");
        System.out.println("Entity " + entityId + " bounds: " + octree.getEntityBounds(entityId));
        System.out.println("Origin entity " + originEntityId + " bounds: " + octree.getEntityBounds(originEntityId));
        
        // Manual test of ray-sphere intersection
        System.out.println("\nManual ray-sphere intersection test:");
        System.out.println("Ray: origin=" + rayOrigin + ", direction=" + rayDirection);
        System.out.println("Entity position: " + entityPos);
        // Direct calculation: the ray goes from (50,100,100) in direction (1,0,0)
        // Entity is at (100,100,100), so it should intersect at distance 50
        float expectedDistance = 50.0f;
        System.out.println("Expected intersection distance: " + expectedDistance);
        
        // Test ray intersection
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);
        System.out.println("\nRay intersections found: " + intersections.size());
        
        if (!intersections.isEmpty()) {
            for (int i = 0; i < intersections.size(); i++) {
                var intersection = intersections.get(i);
                System.out.println("Intersection " + i + ":");
                System.out.println("  Entity ID: " + intersection.entityId());
                System.out.println("  Content: " + intersection.content());
                System.out.println("  Distance: " + intersection.distance());
                System.out.println("  Intersection point: " + intersection.intersectionPoint());
                System.out.println("  Normal: " + intersection.normal());
            }
        } else {
            System.out.println("No intersections found. Possible causes:");
            System.out.println("1. getRayTraversalOrder returns no nodes");
            System.out.println("2. doesRayIntersectNode returns false for all nodes");
            System.out.println("3. getRayEntityDistance returns negative distance");
        }
        
        // Try a different ray - from entity center outward
        Ray3D rayFromCenter = new Ray3D(entityPos, new Vector3f(1, 0, 0), 100);
        System.out.println("\nTesting ray from entity center...");
        List<SpatialIndex.RayIntersection<LongEntityID, String>> centerIntersections = octree.rayIntersectAll(rayFromCenter);
        System.out.println("Intersections from entity center: " + centerIntersections.size());
    }
}