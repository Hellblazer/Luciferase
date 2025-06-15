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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Debug test for ray traversal order in Octree
 *
 * @author hal.hildebrand
 */
public class OctreeRayTraversalDebugTest {

    // Test class that extends Octree to access protected methods
    private static class TestableOctree extends Octree<LongEntityID, String> {
        public TestableOctree(SequentialLongIDGenerator idGenerator) {
            super(idGenerator);
        }

        // Expose protected methods for testing
        public List<Long> testGetRayTraversalOrder(Ray3D ray) {
            return getRayTraversalOrder(ray).collect(Collectors.toList());
        }

        public boolean testDoesRayIntersectNode(long nodeIndex, Ray3D ray) {
            return doesRayIntersectNode(nodeIndex, ray);
        }

        public float testGetRayNodeIntersectionDistance(long nodeIndex, Ray3D ray) {
            return getRayNodeIntersectionDistance(nodeIndex, ray);
        }
    }

    private TestableOctree octree;

    @BeforeEach
    void setUp() {
        octree = new TestableOctree(new SequentialLongIDGenerator());
    }

    @Test
    void debugRayTraversalPipeline() {
        // Insert entity at the same position as our failing test
        Point3f entityPos = new Point3f(100, 100, 100);
        byte level = 10;
        
        System.out.println("=== Ray Traversal Pipeline Debug ===");
        System.out.println("Inserting entity at position: " + entityPos + " at level: " + level);
        LongEntityID entityId = octree.insert(entityPos, level, "TestEntity");
        System.out.println("Entity ID: " + entityId);
        
        // Create the same ray from our failing test
        Point3f rayOrigin = new Point3f(50, 100, 100);
        Vector3f rayDirection = new Vector3f(1, 0, 0);
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 200);
        
        System.out.println("\nRay details:");
        System.out.println("Ray origin: " + rayOrigin);
        System.out.println("Ray direction: " + rayDirection);
        System.out.println("Ray max distance: " + ray.maxDistance());
        
        // Check spatial index contents
        System.out.println("\nSpatial index contents:");
        octree.nodes().forEach(node -> {
            System.out.println("  Node index: " + node.mortonIndex() + ", entities: " + node.entityIds().size());
            node.entityIds().forEach(id -> {
                Point3f pos = octree.getEntityPosition(id);
                System.out.println("    Entity " + id + " at position: " + pos);
            });
        });
        
        // Step 1: Test getRayTraversalOrder
        System.out.println("\nStep 1: Getting ray traversal order...");
        List<Long> traversalOrder = octree.testGetRayTraversalOrder(ray);
        System.out.println("Traversal order returned " + traversalOrder.size() + " nodes:");
        for (int i = 0; i < Math.min(10, traversalOrder.size()); i++) {
            long nodeIndex = traversalOrder.get(i);
            System.out.println("  [" + i + "] Node index: " + nodeIndex);
        }
        
        if (traversalOrder.isEmpty()) {
            System.out.println("❌ ISSUE FOUND: getRayTraversalOrder returns empty list!");
            System.out.println("This explains why no intersections are found.");
            return;
        }
        
        // Step 2: Test doesRayIntersectNode for each node
        System.out.println("\nStep 2: Testing ray-node intersection for each traversal node...");
        for (int i = 0; i < Math.min(5, traversalOrder.size()); i++) {
            long nodeIndex = traversalOrder.get(i);
            boolean intersects = octree.testDoesRayIntersectNode(nodeIndex, ray);
            float distance = octree.testGetRayNodeIntersectionDistance(nodeIndex, ray);
            System.out.println("  Node " + nodeIndex + ": intersects=" + intersects + ", distance=" + distance);
            
            if (!intersects) {
                System.out.println("    ❌ Ray does not intersect this node");
            }
        }
        
        // Step 3: Test the full ray intersection method
        System.out.println("\nStep 3: Testing full ray intersection...");
        var intersections = octree.rayIntersectAll(ray);
        System.out.println("Full rayIntersectAll result: " + intersections.size() + " intersections");
        
        if (intersections.isEmpty()) {
            System.out.println("❌ Full method still returns no intersections");
        } else {
            intersections.forEach(intersection -> {
                System.out.println("  Found intersection: entity=" + intersection.entityId() + 
                                   ", distance=" + intersection.distance());
            });
        }
    }

    @Test
    void debugSimpleScenario() {
        // Create an even simpler scenario with entity at origin
        System.out.println("\n=== Simple Scenario Debug ===");
        
        Point3f originPos = new Point3f(0, 0, 0);
        LongEntityID originEntityId = octree.insert(originPos, (byte) 0, "OriginEntity");
        System.out.println("Inserted entity at origin, ID: " + originEntityId);
        
        // Ray pointing towards origin from positive X
        Ray3D ray = new Ray3D(new Point3f(10, 0, 0), new Vector3f(-1, 0, 0), 50);
        System.out.println("Ray from (10,0,0) towards (-1,0,0)");
        
        // Test traversal
        List<Long> traversalOrder = octree.testGetRayTraversalOrder(ray);
        System.out.println("Traversal order: " + traversalOrder.size() + " nodes");
        traversalOrder.forEach(nodeIndex -> {
            System.out.println("  Node: " + nodeIndex);
        });
        
        // Test intersection
        var intersections = octree.rayIntersectAll(ray);
        System.out.println("Intersections found: " + intersections.size());
    }
}