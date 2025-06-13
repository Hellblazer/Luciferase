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

/**
 * Example demonstrating the collision response system.
 *
 * @author hal.hildebrand
 */
public class CollisionExample {
    
    /**
     * Example entity content
     */
    public record Ball(String name, float radius) {}
    
    public static void main(String[] args) {
        // Create spatial index
        Octree<LongEntityID, Ball> octree = new Octree<>(
            new SequentialLongIDGenerator(),
            100,    // max entities per node
            (byte) 10  // max depth
        );
        
        // Create collision system
        CollisionSystem<LongEntityID, Ball> collisionSystem = new CollisionSystem<>(octree);
        
        // Add collision listener
        collisionSystem.addCollisionListener((collision, response) -> {
            System.out.printf("Collision detected between %s and %s%n",
                             collision.content1().name(),
                             collision.content2().name());
            System.out.printf("  Contact point: %s%n", collision.contactPoint());
            System.out.printf("  Contact normal: %s%n", collision.contactNormal());
            System.out.printf("  Penetration depth: %.3f%n", collision.penetrationDepth());
            return true; // Apply the collision response
        });
        
        // Create entities
        createBall(octree, collisionSystem, "Ball1", 
                  new Point3f(0, 0, 0), 1.0f,
                  new Vector3f(5, 0, 0), 1.0f);
        
        createBall(octree, collisionSystem, "Ball2",
                  new Point3f(10, 0, 0), 1.0f,
                  new Vector3f(-5, 0, 0), 1.0f);
        
        createBall(octree, collisionSystem, "Ball3",
                  new Point3f(5, 5, 0), 0.5f,
                  new Vector3f(0, -3, 0), 0.5f);
        
        // Create static floor
        LongEntityID floorId = octree.insert(
            new Point3f(5, -10, 0),
            (byte) 5,
            new Ball("Floor", 100)
        );
        // Note: For bounded entities, we need to use insert without bounds and set bounds separately
        octree.setCollisionShape(floorId, 
            new BoxShape(new Point3f(5, -10, 0), 50, 0.5f, 50));
        
        PhysicsProperties floorProps = PhysicsProperties.createStatic();
        collisionSystem.setPhysicsProperties(floorId, floorProps);
        
        // Simulate physics
        float deltaTime = 0.016f; // 60 FPS
        Vector3f gravity = new Vector3f(0, -9.81f, 0);
        
        System.out.println("Starting simulation...\n");
        
        for (int frame = 0; frame < 120; frame++) { // 2 seconds
            System.out.printf("Frame %d (t=%.2fs):%n", frame, frame * deltaTime);
            
            // Update physics
            collisionSystem.updatePhysics(deltaTime, gravity);
            
            // Process collisions
            var processed = collisionSystem.processAllCollisions();
            
            if (!processed.isEmpty()) {
                System.out.printf("  Processed %d collisions%n", processed.size());
            }
            
            // Print positions every 30 frames
            if (frame % 30 == 0) {
                printPositions(octree);
            }
            
            System.out.println();
        }
        
        // Print final statistics
        var stats = collisionSystem.getLastStats();
        System.out.println("Collision Statistics:");
        System.out.printf("  Broad-phase checks: %d%n", stats.broadPhaseChecks());
        System.out.printf("  Narrow-phase checks: %d%n", stats.narrowPhaseChecks());
        System.out.printf("  Collisions detected: %d%n", stats.collisionsDetected());
        System.out.printf("  Collisions resolved: %d%n", stats.collisionsResolved());
        System.out.printf("  Average processing time: %.3f ms%n", 
                         stats.averageProcessingTime() / 1_000_000.0);
    }
    
    private static void createBall(Octree<LongEntityID, Ball> octree,
                                  CollisionSystem<LongEntityID, Ball> collisionSystem,
                                  String name, Point3f position, float radius,
                                  Vector3f velocity, float mass) {
        
        // Insert entity
        Ball ball = new Ball(name, radius);
        LongEntityID id = octree.insert(position, (byte) 10, ball);
        
        // For entities with bounds, we need to reinsert with bounds
        EntityBounds bounds = new EntityBounds(position, radius);
        octree.insert(id, position, (byte) 10, ball, bounds);
        
        // Set collision shape
        CollisionShape shape = new SphereShape(position, radius);
        octree.setCollisionShape(id, shape);
        
        // Set physics properties
        PhysicsProperties props = new PhysicsProperties(mass, velocity);
        props.setRestitution(0.8f); // Bouncy
        props.setFriction(0.2f);
        collisionSystem.setPhysicsProperties(id, props);
    }
    
    private static void printPositions(Octree<LongEntityID, Ball> octree) {
        System.out.println("  Entity positions:");
        octree.getEntitiesWithPositions().forEach((id, pos) -> {
            Ball ball = octree.getEntity(id);
            if (ball != null && !ball.name().equals("Floor")) {
                System.out.printf("    %s: (%.2f, %.2f, %.2f)%n",
                                 ball.name(), pos.x, pos.y, pos.z);
            }
        });
    }
}