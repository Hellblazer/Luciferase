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
 * Simple collision example with guaranteed collisions.
 *
 * @author hal.hildebrand
 */
public class SimpleCollisionExample {
    
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
        
        // Track collisions
        int[] totalCollisions = {0};
        
        // Add collision listener
        collisionSystem.addCollisionListener((collision, response) -> {
            totalCollisions[0]++;
            System.out.printf("\nCollision #%d detected!%n", totalCollisions[0]);
            System.out.printf("  Between: %s and %s%n",
                             collision.content1().name(),
                             collision.content2().name());
            System.out.printf("  Positions: Entity1=(%.2f,%.2f,%.2f), Entity2=(%.2f,%.2f,%.2f)%n",
                             octree.getEntityPosition(collision.entityId1()).x,
                             octree.getEntityPosition(collision.entityId1()).y,
                             octree.getEntityPosition(collision.entityId1()).z,
                             octree.getEntityPosition(collision.entityId2()).x,
                             octree.getEntityPosition(collision.entityId2()).y,
                             octree.getEntityPosition(collision.entityId2()).z);
            System.out.printf("  Contact point: (%.2f, %.2f, %.2f)%n", 
                             collision.contactPoint().x,
                             collision.contactPoint().y,
                             collision.contactPoint().z);
            System.out.printf("  Penetration depth: %.3f%n", collision.penetrationDepth());
            return true; // Apply the collision response
        });
        
        // Create two balls moving slowly toward each other
        System.out.println("Setting up collision scenario...");
        System.out.println("Ball1: Starting at (-2,0,0) moving right at 0.5 units/sec");
        System.out.println("Ball2: Starting at (2,0,0) moving left at 0.5 units/sec");
        System.out.println("Both balls have radius 1.0, so they should collide when centers are 2.0 apart\n");
        
        LongEntityID ball1 = createBall(octree, collisionSystem, "Ball1", 
                                       new Point3f(-2, 0, 0), 1.0f,
                                       new Vector3f(0.5f, 0, 0), 1.0f);
        
        LongEntityID ball2 = createBall(octree, collisionSystem, "Ball2",
                                       new Point3f(2, 0, 0), 1.0f,
                                       new Vector3f(-0.5f, 0, 0), 1.0f);
        
        // Run simulation
        float deltaTime = 0.1f; // 100ms timestep
        int maxFrames = 50;
        
        System.out.println("Starting simulation...");
        
        for (int frame = 0; frame < maxFrames; frame++) {
            float time = frame * deltaTime;
            
            // Update physics
            collisionSystem.updatePhysics(deltaTime, null); // No gravity for this test
            
            // Update collision shapes based on new positions
            updateCollisionShapes(octree, ball1, 1.0f);
            updateCollisionShapes(octree, ball2, 1.0f);
            
            // Process collisions
            var processed = collisionSystem.processAllCollisions();
            
            // Print status every second
            if (frame % 10 == 0) {
                Point3f pos1 = octree.getEntityPosition(ball1);
                Point3f pos2 = octree.getEntityPosition(ball2);
                float distance = pos1.distance(pos2);
                
                System.out.printf("\nTime %.1fs: Ball1=(%.2f,%.2f,%.2f), Ball2=(%.2f,%.2f,%.2f), Distance=%.2f%n",
                                 time, pos1.x, pos1.y, pos1.z, pos2.x, pos2.y, pos2.z, distance);
                
                if (distance < 2.0f) {
                    System.out.println("  *** BALLS ARE OVERLAPPING! ***");
                }
            }
            
            // Stop if balls have passed each other
            Point3f pos1 = octree.getEntityPosition(ball1);
            Point3f pos2 = octree.getEntityPosition(ball2);
            if (pos1.x > pos2.x + 2.0f) {
                System.out.println("\nBalls have passed each other. Stopping simulation.");
                break;
            }
        }
        
        System.out.printf("\nSimulation complete. Total collisions detected: %d%n", totalCollisions[0]);
    }
    
    private static LongEntityID createBall(Octree<LongEntityID, Ball> octree,
                                          CollisionSystem<LongEntityID, Ball> collisionSystem,
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
        
        // Set physics properties
        PhysicsProperties props = new PhysicsProperties(mass, velocity);
        props.setRestitution(0.8f); // Bouncy
        props.setFriction(0.0f); // No friction for this test
        collisionSystem.setPhysicsProperties(id, props);
        
        return id;
    }
    
    private static void updateCollisionShapes(Octree<LongEntityID, Ball> octree, 
                                            LongEntityID entityId, float radius) {
        Point3f pos = octree.getEntityPosition(entityId);
        if (pos != null) {
            // Update collision shape
            octree.setCollisionShape(entityId, new SphereShape(pos, radius));
            
            // Update bounds
            Ball ball = octree.getEntity(entityId);
            if (ball != null) {
                EntityBounds newBounds = new EntityBounds(pos, radius);
                octree.insert(entityId, pos, (byte) 10, ball, newBounds);
            }
        }
    }
}