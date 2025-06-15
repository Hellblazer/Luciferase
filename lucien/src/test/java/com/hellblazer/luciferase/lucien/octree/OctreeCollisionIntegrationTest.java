/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.collision.*;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Octree collision detection with the full collision system.
 * Tests the complete pipeline including physics, listeners, and filters.
 *
 * @author hal.hildebrand
 */
public class OctreeCollisionIntegrationTest {

    private Octree<LongEntityID, String> octree;
    private CollisionSystem<LongEntityID, String> collisionSystem;
    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        collisionSystem = new CollisionSystem<>(octree);
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testFullCollisionPipeline() {
        // Create two colliding entities with physics properties
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(105, 100, 100);
        
        EntityBounds bounds1 = new EntityBounds(
            new Point3f(95, 95, 95),
            new Point3f(105, 105, 105)
        );
        EntityBounds bounds2 = new EntityBounds(
            new Point3f(100, 95, 95),
            new Point3f(110, 105, 105)
        );
        
        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();
        
        octree.insert(id1, pos1, (byte) 10, "Entity1", bounds1);
        octree.insert(id2, pos2, (byte) 10, "Entity2", bounds2);
        
        // Set physics properties
        PhysicsProperties props1 = new PhysicsProperties(1.0f, new Vector3f(5, 0, 0));  // Moving right
        PhysicsProperties props2 = new PhysicsProperties(1.0f, new Vector3f(-3, 0, 0)); // Moving left
        
        collisionSystem.setPhysicsProperties(id1, props1);
        collisionSystem.setPhysicsProperties(id2, props2);
        
        // Process collisions
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = collisionSystem.processAllCollisions();
        
        assertEquals(1, processed.size(), "Should process one collision");
        
        // Verify physics response
        CollisionResponse response = processed.get(0).response();
        assertTrue(response.hasResponse(), "Should have collision response");
        assertNotNull(response.impulse1(), "Should have impulse for entity 1");
        assertNotNull(response.impulse2(), "Should have impulse for entity 2");
        
        // Verify velocities changed
        Vector3f newVel1 = props1.getVelocity();
        Vector3f newVel2 = props2.getVelocity();
        
        assertNotEquals(5.0f, newVel1.x, "Entity 1 velocity should change");
        assertNotEquals(-3.0f, newVel2.x, "Entity 2 velocity should change");
    }

    @Test
    void testCollisionListenerIntegration() {
        // Create collision scenario
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100, 100);
        
        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");
        
        // Add collision listener
        AtomicInteger collisionCount = new AtomicInteger(0);
        List<SpatialIndex.CollisionPair<LongEntityID, String>> detectedCollisions = new ArrayList<>();
        
        CollisionListener<LongEntityID, String> listener = new CollisionListener<>() {
            @Override
            public boolean onCollision(SpatialIndex.CollisionPair<LongEntityID, String> collision, 
                                     CollisionResponse response) {
                collisionCount.incrementAndGet();
                detectedCollisions.add(collision);
                return true; // Allow collision processing
            }
        };
        
        collisionSystem.addCollisionListener(listener);
        
        // Process collisions
        collisionSystem.processAllCollisions();
        
        assertEquals(1, collisionCount.get(), "Listener should be notified of collision");
        assertEquals(1, detectedCollisions.size(), "Should detect one collision");
        assertTrue(detectedCollisions.get(0).involves(id1), "Collision should involve entity 1");
        assertTrue(detectedCollisions.get(0).involves(id2), "Collision should involve entity 2");
    }

    @Test
    void testCollisionFilterIntegration() {
        // Create multiple entities
        Point3f[] positions = {
            new Point3f(100, 100, 100),
            new Point3f(100.05f, 100, 100),
            new Point3f(200, 200, 200),
            new Point3f(200.05f, 200, 200)
        };
        
        LongEntityID[] ids = new LongEntityID[4];
        for (int i = 0; i < 4; i++) {
            ids[i] = octree.insert(positions[i], (byte) 10, "Entity" + i);
        }
        
        // Create filter that only allows collisions involving entity 0
        CollisionFilter<LongEntityID, String> filter = collision -> 
            collision.involves(ids[0]);
        
        CollisionSystem<LongEntityID, String> filteredSystem = 
            new CollisionSystem<>(octree, new CollisionResolver(), filter);
        
        // Process collisions
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = 
            filteredSystem.processAllCollisions();
        
        assertEquals(1, processed.size(), "Should only process collisions involving entity 0");
        assertTrue(processed.get(0).collision().involves(ids[0]), 
                  "Processed collision should involve entity 0");
    }

    @Test
    void testPhysicsUpdateIntegration() {
        // Create entity with physics
        Point3f initialPos = new Point3f(100, 100, 100);
        LongEntityID id = octree.insert(initialPos, (byte) 10, "PhysicsEntity");
        
        PhysicsProperties props = new PhysicsProperties(1.0f, new Vector3f(10, 0, 0)); // Moving at 10 units/sec
        collisionSystem.setPhysicsProperties(id, props);
        
        // Update physics
        float deltaTime = 0.1f; // 100ms
        Vector3f gravity = new Vector3f(0, -9.8f, 0);
        collisionSystem.updatePhysics(deltaTime, gravity);
        
        // Verify position updated
        Point3f newPos = octree.getEntityPosition(id);
        assertNotNull(newPos);
        assertEquals(101.0f, newPos.x, 0.001f, "X position should advance by velocity * time");
        assertTrue(newPos.y < 100.0f, "Y position should decrease due to gravity");
        assertEquals(100.0f, newPos.z, 0.001f, "Z position should remain unchanged");
    }

    @Test
    void testStaticVsDynamicCollisions() {
        // Create static and dynamic entities
        Point3f staticPos = new Point3f(100, 100, 100);
        Point3f dynamicPos = new Point3f(95, 100, 100);
        
        EntityBounds staticBounds = new EntityBounds(
            new Point3f(95, 95, 95),
            new Point3f(105, 105, 105)
        );
        
        LongEntityID staticId = idGenerator.generateID();
        LongEntityID dynamicId = idGenerator.generateID();
        
        octree.insert(staticId, staticPos, (byte) 10, "Static", staticBounds);
        octree.insert(dynamicId, dynamicPos, (byte) 10, "Dynamic");
        
        // Set physics properties
        PhysicsProperties staticProps = PhysicsProperties.createStatic();
        PhysicsProperties dynamicProps = new PhysicsProperties(1.0f, new Vector3f(10, 0, 0)); // Moving toward static
        
        collisionSystem.setPhysicsProperties(staticId, staticProps);
        collisionSystem.setPhysicsProperties(dynamicId, dynamicProps);
        
        // Update physics to move dynamic entity into static
        collisionSystem.updatePhysics(0.1f, null);
        
        // Process collisions
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = 
            collisionSystem.processAllCollisions();
        
        assertTrue(processed.size() > 0, "Should detect collision after movement");
        
        // Verify only dynamic entity receives impulse
        CollisionResponse response = processed.get(0).response();
        if (processed.get(0).collision().entityId1().equals(staticId)) {
            assertEquals(0, response.impulse1().length(), 0.001f, 
                        "Static entity should receive no impulse");
            assertTrue(response.impulse2().length() > 0, 
                      "Dynamic entity should receive impulse");
        } else {
            assertTrue(response.impulse1().length() > 0, 
                      "Dynamic entity should receive impulse");
            assertEquals(0, response.impulse2().length(), 0.001f, 
                        "Static entity should receive no impulse");
        }
    }

    @Test
    void testCollisionRegionIntegration() {
        // Create entities in different regions
        Point3f[] positions = {
            new Point3f(50, 50, 50),
            new Point3f(50.05f, 50, 50),
            new Point3f(150, 150, 150),
            new Point3f(150.05f, 150, 150),
            new Point3f(250, 250, 250),
            new Point3f(250.05f, 250, 250)
        };
        
        LongEntityID[] ids = new LongEntityID[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = octree.insert(positions[i], (byte) 10, "Entity" + i);
        }
        
        // Test collision detection in specific region
        Spatial region = new Spatial.Cube(0, 0, 0, 100); // Region covering first pair
        List<SpatialIndex.CollisionPair<LongEntityID, String>> regionCollisions = 
            octree.findCollisionsInRegion(region);
        
        assertEquals(1, regionCollisions.size(), "Should find one collision in region");
        assertTrue(regionCollisions.get(0).involves(ids[0]) && regionCollisions.get(0).involves(ids[1]),
                  "Collision should be between entities 0 and 1");
        
        // Test with different region
        Spatial region2 = new Spatial.Sphere(150, 150, 150, 50);
        List<SpatialIndex.CollisionPair<LongEntityID, String>> regionCollisions2 = 
            octree.findCollisionsInRegion(region2);
        
        assertEquals(1, regionCollisions2.size(), "Should find one collision in second region");
        assertTrue(regionCollisions2.get(0).involves(ids[2]) && regionCollisions2.get(0).involves(ids[3]),
                  "Collision should be between entities 2 and 3");
    }

    @Test
    void testContinuousCollisionDetection() {
        // Test collision detection with fast-moving entities
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(200, 100, 100);
        
        EntityBounds bounds1 = new EntityBounds(
            new Point3f(95, 95, 95),
            new Point3f(105, 105, 105)
        );
        EntityBounds bounds2 = new EntityBounds(
            new Point3f(195, 95, 95),
            new Point3f(205, 105, 105)
        );
        
        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();
        
        octree.insert(id1, pos1, (byte) 10, "Fast1", bounds1);
        octree.insert(id2, pos2, (byte) 10, "Fast2", bounds2);
        
        // Set high velocities
        PhysicsProperties props1 = new PhysicsProperties(1.0f, new Vector3f(500, 0, 0));  // Very fast right
        PhysicsProperties props2 = new PhysicsProperties(1.0f, new Vector3f(-500, 0, 0)); // Very fast left
        
        collisionSystem.setPhysicsProperties(id1, props1);
        collisionSystem.setPhysicsProperties(id2, props2);
        
        // Small time steps to catch collision
        float dt = 0.01f;
        boolean collisionDetected = false;
        
        for (int i = 0; i < 20 && !collisionDetected; i++) {
            collisionSystem.updatePhysics(dt, null);
            List<CollisionSystem.ProcessedCollision<LongEntityID>> collisions = 
                collisionSystem.processAllCollisions();
            if (!collisions.isEmpty()) {
                collisionDetected = true;
            }
        }
        
        assertTrue(collisionDetected, "Should detect collision between fast-moving entities");
    }

    @Test
    void testCollisionDiagnostics() {
        // Create collision scenario
        Point3f[] positions = new Point3f[10];
        LongEntityID[] ids = new LongEntityID[10];
        
        // Create cluster of colliding entities
        for (int i = 0; i < 10; i++) {
            positions[i] = new Point3f(100 + i * 0.05f, 100, 100);
            ids[i] = octree.insert(positions[i], (byte) 10, "Entity" + i);
        }
        
        // Process collisions and check diagnostics
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = 
            collisionSystem.processAllCollisions();
        
        CollisionSystem.CollisionStats stats = collisionSystem.getLastStats();
        
        assertTrue(stats.broadPhaseChecks() > 0, "Should have broad phase checks");
        assertTrue(stats.narrowPhaseChecks() > 0, "Should have narrow phase checks");
        assertTrue(stats.collisionsDetected() > 0, "Should detect collisions");
        assertTrue(stats.totalProcessingTime() > 0, "Should have processing time");
        
        // Verify collision count makes sense
        assertTrue(stats.collisionsDetected() >= 9, 
                  "Should detect at least 9 collisions in chain of 10 entities");
    }

    @Test
    void testKinematicEntityCollisions() {
        // Test kinematic entities (script-controlled movement)
        Point3f kinematicPos = new Point3f(100, 100, 100);
        Point3f dynamicPos = new Point3f(95, 100, 100);
        
        EntityBounds kinematicBounds = new EntityBounds(
            new Point3f(95, 95, 95),
            new Point3f(105, 105, 105)
        );
        
        LongEntityID kinematicId = idGenerator.generateID();
        LongEntityID dynamicId = idGenerator.generateID();
        
        octree.insert(kinematicId, kinematicPos, (byte) 10, "Kinematic", kinematicBounds);
        octree.insert(dynamicId, dynamicPos, (byte) 10, "Dynamic");
        
        // Set physics properties
        PhysicsProperties kinematicProps = PhysicsProperties.createKinematic(new Vector3f(-5, 0, 0)); // Moving left
        PhysicsProperties dynamicProps = new PhysicsProperties(1.0f, new Vector3f(5, 0, 0));   // Moving right
        
        collisionSystem.setPhysicsProperties(kinematicId, kinematicProps);
        collisionSystem.setPhysicsProperties(dynamicId, dynamicProps);
        
        // Update and process
        collisionSystem.updatePhysics(0.1f, null);
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = 
            collisionSystem.processAllCollisions();
        
        // Kinematic entities should push dynamic entities but not be affected
        if (!processed.isEmpty()) {
            CollisionResponse response = processed.get(0).response();
            
            // Identify which is kinematic
            boolean kinematicIsFirst = processed.get(0).collision().entityId1().equals(kinematicId);
            
            if (kinematicIsFirst) {
                assertEquals(0, response.impulse1().length(), 0.001f,
                            "Kinematic entity should receive no impulse");
                assertTrue(response.impulse2().length() > 0,
                          "Dynamic entity should receive impulse from kinematic");
            } else {
                assertTrue(response.impulse1().length() > 0,
                          "Dynamic entity should receive impulse from kinematic");
                assertEquals(0, response.impulse2().length(), 0.001f,
                            "Kinematic entity should receive no impulse");
            }
        }
    }

    @Test
    void testMultiListenerPriority() {
        // Test multiple listeners with different behaviors
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100, 100);
        
        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");
        
        AtomicInteger listener1Count = new AtomicInteger(0);
        AtomicInteger listener2Count = new AtomicInteger(0);
        
        // First listener allows processing
        CollisionListener<LongEntityID, String> listener1 = 
            (collision, response) -> {
                listener1Count.incrementAndGet();
                return true;
            };
        
        // Second listener prevents processing
        CollisionListener<LongEntityID, String> listener2 = 
            (collision, response) -> {
                listener2Count.incrementAndGet();
                return false;
            };
        
        collisionSystem.addCollisionListener(listener1);
        collisionSystem.addCollisionListener(listener2);
        
        // Set physics properties
        collisionSystem.setPhysicsProperties(id1, new PhysicsProperties());
        collisionSystem.setPhysicsProperties(id2, new PhysicsProperties());
        
        // Process collisions
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = 
            collisionSystem.processAllCollisions();
        
        assertEquals(1, listener1Count.get(), "First listener should be called");
        assertEquals(1, listener2Count.get(), "Second listener should be called");
        assertEquals(0, processed.size(), 
                    "Collision should not be processed due to listener veto");
    }
}