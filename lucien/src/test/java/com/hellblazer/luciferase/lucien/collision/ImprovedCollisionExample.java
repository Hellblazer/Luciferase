/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.HashMap;
import java.util.Map;

/**
 * Improved example demonstrating the collision response system with proper updates.
 *
 * @author hal.hildebrand
 */
public class ImprovedCollisionExample {
    
    /**
     * Example entity content
     */
    public record Ball(String name, float radius) {}
    
    /**
     * Extended collision system that updates collision shapes
     */
    static class ExtendedCollisionSystem<ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content> 
            extends CollisionSystem<ID, Content> {
        
        private final Map<ID, Float> entityRadii = new HashMap<>();
        
        public ExtendedCollisionSystem(com.hellblazer.luciferase.lucien.SpatialIndex<ID, Content> spatialIndex) {
            super(spatialIndex);
        }
        
        public void setEntityRadius(ID entityId, float radius) {
            entityRadii.put(entityId, radius);
        }
        
        @Override
        public void updatePhysics(float deltaTime, Vector3f gravity) {
            // First do normal physics update
            super.updatePhysics(deltaTime, gravity);
            
            // Then update collision shapes and bounds for all moved entities
            for (Map.Entry<ID, PhysicsProperties> entry : super.physicsProperties.entrySet()) {
                ID entityId = entry.getKey();
                PhysicsProperties props = entry.getValue();
                
                if (!props.isStatic()) {
                    Point3f newPos = super.spatialIndex.getEntityPosition(entityId);
                    if (newPos != null) {
                        Float radius = entityRadii.get(entityId);
                        if (radius != null) {
                            // Update collision shape
                            CollisionShape newShape = new SphereShape(newPos, radius);
                            super.spatialIndex.setCollisionShape(entityId, newShape);
                            
                            // Update bounds by reinserting with new position
                            Content content = super.spatialIndex.getEntity(entityId);
                            if (content != null) {
                                EntityBounds newBounds = new EntityBounds(newPos, radius);
                                super.spatialIndex.insert(entityId, newPos, (byte) 10, content, newBounds);
                            }
                        }
                    }
                }
            }
        }
    }
    
    public static void main(String[] args) {
        // Create spatial index
        Octree<LongEntityID, Ball> octree = new Octree<>(
            new SequentialLongIDGenerator(),
            100,    // max entities per node
            (byte) 10  // max depth
        );
        
        // Create extended collision system
        ExtendedCollisionSystem<LongEntityID, Ball> collisionSystem = new ExtendedCollisionSystem<>(octree);
        
        // Track collision count
        int[] collisionCount = {0};
        
        // Add collision listener
        collisionSystem.addCollisionListener((collision, response) -> {
            collisionCount[0]++;
            System.out.printf("Collision %d detected between %s and %s%n",
                             collisionCount[0],
                             collision.content1().name(),
                             collision.content2().name());
            System.out.printf("  Contact point: (%.2f, %.2f, %.2f)%n", 
                             collision.contactPoint().x,
                             collision.contactPoint().y,
                             collision.contactPoint().z);
            System.out.printf("  Contact normal: (%.2f, %.2f, %.2f)%n",
                             collision.contactNormal().x,
                             collision.contactNormal().y,
                             collision.contactNormal().z);
            System.out.printf("  Penetration depth: %.3f%n", collision.penetrationDepth());
            return true; // Apply the collision response
        });
        
        // Create entities with more obvious collision setup
        // Two balls moving toward each other
        createBall(octree, collisionSystem, "Ball1", 
                  new Point3f(-5, 0, 0), 1.0f,
                  new Vector3f(2, 0, 0), 1.0f);
        
        createBall(octree, collisionSystem, "Ball2",
                  new Point3f(5, 0, 0), 1.0f,
                  new Vector3f(-2, 0, 0), 1.0f);
        
        // Ball falling onto floor
        createBall(octree, collisionSystem, "Ball3",
                  new Point3f(0, 5, 0), 0.5f,
                  new Vector3f(0, 0, 0), 0.5f);
        
        // Create static floor
        Ball floor = new Ball("Floor", 100);
        Point3f floorPos = new Point3f(0, -2, 0);
        LongEntityID floorId = octree.insert(floorPos, (byte) 5, floor);
        
        // Set floor bounds and collision shape
        EntityBounds floorBounds = new EntityBounds(
            new Point3f(-10, -2.5f, -10), 
            new Point3f(10, -1.5f, 10)
        );
        octree.insert(floorId, floorPos, (byte) 5, floor, floorBounds);
        octree.setCollisionShape(floorId, 
            new BoxShape(floorPos, 10f, 0.5f, 10f));
        
        PhysicsProperties floorProps = PhysicsProperties.createStatic();
        collisionSystem.setPhysicsProperties(floorId, floorProps);
        
        // Simulate physics
        float deltaTime = 0.016f; // 60 FPS
        Vector3f gravity = new Vector3f(0, -9.81f, 0);
        
        System.out.println("Starting simulation...\n");
        
        for (int frame = 0; frame < 300; frame++) { // 5 seconds
            if (frame % 60 == 0) {
                System.out.printf("\n=== Time: %.1fs ===%n", frame * deltaTime);
                printPositions(octree);
            }
            
            // Update physics (includes collision shape updates)
            collisionSystem.updatePhysics(deltaTime, gravity);
            
            // Process collisions
            var processed = collisionSystem.processAllCollisions();
            
            if (!processed.isEmpty() && frame % 10 == 0) {
                System.out.printf("Frame %d: Processed %d collisions%n", frame, processed.size());
            }
        }
        
        // Print final statistics
        System.out.println("\n=== Final Statistics ===");
        var stats = collisionSystem.getLastStats();
        System.out.println("Collision Statistics:");
        System.out.printf("  Total collisions detected: %d%n", collisionCount[0]);
        System.out.printf("  Broad-phase checks: %d%n", stats.broadPhaseChecks());
        System.out.printf("  Narrow-phase checks: %d%n", stats.narrowPhaseChecks());
        System.out.printf("  Collisions detected: %d%n", stats.collisionsDetected());
        System.out.printf("  Collisions resolved: %d%n", stats.collisionsResolved());
        System.out.printf("  Average processing time: %.3f ms%n", 
                         stats.averageProcessingTime() / 1_000_000.0);
        
        System.out.println("\nFinal positions:");
        printPositions(octree);
    }
    
    private static void createBall(Octree<LongEntityID, Ball> octree,
                                  ExtendedCollisionSystem<LongEntityID, Ball> collisionSystem,
                                  String name, Point3f position, float radius,
                                  Vector3f velocity, float mass) {
        
        // Insert entity
        Ball ball = new Ball(name, radius);
        LongEntityID id = octree.insert(position, (byte) 10, ball);
        
        // Set bounds
        EntityBounds bounds = new EntityBounds(position, radius);
        octree.insert(id, position, (byte) 10, ball, bounds);
        
        // Set collision shape
        CollisionShape shape = new SphereShape(position, radius);
        octree.setCollisionShape(id, shape);
        
        // Store radius for updates
        collisionSystem.setEntityRadius(id, radius);
        
        // Set physics properties
        PhysicsProperties props = new PhysicsProperties(mass, velocity);
        props.setRestitution(0.8f); // Bouncy
        props.setFriction(0.2f);
        collisionSystem.setPhysicsProperties(id, props);
    }
    
    private static void printPositions(Octree<LongEntityID, Ball> octree) {
        octree.getEntitiesWithPositions().forEach((id, pos) -> {
            Ball ball = octree.getEntity(id);
            if (ball != null) {
                System.out.printf("  %s: (%.2f, %.2f, %.2f)%n",
                                 ball.name(), pos.x, pos.y, pos.z);
            }
        });
    }
}